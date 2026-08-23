package app.witbound.net

import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import java.net.InetAddress

/**
 * Discovers phones advertising _witbound._tcp on the LAN (jmDNS). The desktop is
 * a sender: it browses only, never advertises (or it would find itself).
 */
class PhoneBrowser(private val onChange: (List<Phone>) -> Unit) {
    data class Phone(val name: String, val host: String, val port: Int) {
        val id get() = name
        val baseUrl get() = "http://$host:$port"
    }

    private var jmdns: JmDNS? = null
    private val found = LinkedHashMap<String, Phone>()

    fun start() {
        if (jmdns != null) return
        Thread {
            runCatching {
                // Bind jmDNS to the real LAN interface, not getLocalHost()
                // (which can resolve to loopback and miss the phone's Bonjour).
                val lanIp = Lan.localIPv4().firstOrNull()
                val bind = if (lanIp != null) InetAddress.getByName(lanIp) else InetAddress.getLocalHost()
                val dns = JmDNS.create(bind)
                jmdns = dns
                dns.addServiceListener("${WitboundLink.SERVICE_TYPE}.local.", object : ServiceListener {
                    override fun serviceAdded(e: ServiceEvent) { e.dns.requestServiceInfo(e.type, e.name, 1500) }
                    override fun serviceRemoved(e: ServiceEvent) { found.remove(e.info?.name ?: e.name); publish() }
                    override fun serviceResolved(e: ServiceEvent) { add(e.info) }
                })
            }
        }.apply { isDaemon = true }.start()
    }

    private fun add(info: ServiceInfo?) {
        info ?: return
        val host = info.inet4Addresses.firstOrNull()?.hostAddress
            ?: info.inetAddresses.firstOrNull()?.hostAddress ?: return
        val name = info.getPropertyString("name")?.takeIf { it.isNotBlank() } ?: info.name
        found[info.name] = Phone(name, host, info.port)
        publish()
    }

    private fun publish() = onChange(found.values.sortedBy { it.name.lowercase() })

    fun stop() { runCatching { jmdns?.close() }; jmdns = null; found.clear() }
}

/** Fire-and-forget helpers to reach a phone's control listener + local IPs. */
object Lan {
    fun localIPv4(): List<String> {
        val out = ArrayList<String>()
        java.net.NetworkInterface.getNetworkInterfaces().toList().forEach { ni ->
            if (!ni.isUp || ni.isLoopback) return@forEach
            ni.inetAddresses.toList().forEach { a ->
                if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                    val ip = a.hostAddress
                    if (!ip.startsWith("169.254") && ip !in out) out += ip
                }
            }
        }
        return out
    }

    /** POST json to a phone; returns (status, body) or null on failure. */
    fun postJson(baseUrl: String, path: String, json: String, timeoutMs: Int = 15000): Pair<Int, String>? = runCatching {
        val conn = java.net.URL("$baseUrl$path").openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"; conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
        conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        code to body
    }.getOrNull()
}
