package app.witbound.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The file server the phone pulls from: GET /pairs, GET /file/<sha256> (Range),
 * GET /map/<pairId>, POST /delivered/<pairId>, GET /healthz, GET / (browser
 * fallback). Uses the JDK's built-in HTTP server — no extra dependency.
 */
class LanServer(
    private val computerName: String,
    private val onDelivered: (String) -> Unit,
) {
    /** One synced pairing served on the LAN. */
    data class Served(
        val pairId: String, val title: String, val author: String, val durationSec: Double,
        val book: File, val bookSha: String, val audio: File, val audioSha: String,
        val map: File, val mapSha: String, val matchRate: Double, val source: String,
    )

    @Volatile private var pairs: List<Served> = emptyList()
    fun update(list: List<Served>) { pairs = list }

    private var server: HttpServer? = null
    var port: Int = 0; private set

    fun start(preferredPort: Int = 8765): Int {
        if (server != null) return port
        val srv = try { HttpServer.create(InetSocketAddress(preferredPort), 0) }
                  catch (e: Exception) { HttpServer.create(InetSocketAddress(0), 0) }
        srv.executor = Executors.newFixedThreadPool(4)
        srv.createContext("/") { ex -> handle(ex) }
        srv.start()
        server = srv
        port = srv.address.port
        return port
    }

    fun stop() { server?.stop(0); server = null }

    private fun handle(ex: HttpExchange) {
        try {
            val path = ex.requestURI.path
            val method = ex.requestMethod
            val comps = path.split("/").filter { it.isNotEmpty() }
            if (method == "POST" && comps.size == 2 && comps[0] == "delivered") {
                val pid = comps[1].lowercase()
                if (pairs.any { it.pairId == pid }) onDelivered(pid)
                sendString(ex, 200, "application/json", "{\"ok\":true}"); return
            }
            if (method != "GET" && method != "HEAD") { sendString(ex, 405, "text/plain", "no"); return }
            when {
                comps.isEmpty() || comps[0] == "index.html" -> sendString(ex, 200, "text/html; charset=utf-8", html())
                comps[0] == "healthz" -> sendString(ex, 200, "text/plain", "ok")
                comps[0] == "pairs" -> sendString(ex, 200, "application/json; charset=utf-8", pairsJson())
                comps[0] == "file" && comps.size >= 2 -> {
                    val sha = comps[1].lowercase()
                    val f = pairs.firstNotNullOfOrNull { p ->
                        when (sha) { p.bookSha -> p.book; p.audioSha -> p.audio; p.mapSha -> p.map; else -> null }
                    }
                    if (f == null) sendString(ex, 404, "text/plain", "not found") else sendFile(ex, f)
                }
                comps[0] == "map" && comps.size == 2 -> {
                    val p = pairs.firstOrNull { it.pairId == comps[1].lowercase() }
                    if (p == null) sendString(ex, 404, "text/plain", "not found") else sendFile(ex, p.map)
                }
                else -> sendString(ex, 404, "text/plain", "not found")
            }
        } catch (e: Exception) {
            runCatching { sendString(ex, 500, "text/plain", "error") }
        } finally { ex.close() }
    }

    private fun pairsJson(): String {
        val arr = org.json.JSONArray()
        for (p in pairs) arr.put(org.json.JSONObject()
            .put("pairId", p.pairId).put("title", p.title).put("author", p.author)
            .put("durationSec", p.durationSec)
            .put("book", org.json.JSONObject().put("name", p.book.name).put("sha256", p.bookSha).put("bytes", p.book.length()))
            .put("audio", org.json.JSONObject().put("name", p.audio.name).put("sha256", p.audioSha).put("bytes", p.audio.length()))
            .put("map", org.json.JSONObject().put("sha256", p.mapSha).put("bytes", p.map.length()).put("format", "rasm1").put("matchRate", p.matchRate)))
        return org.json.JSONObject().put("version", 1).put("computer", computerName).put("pairs", arr).toString(2)
    }

    private fun contentType(f: File): String = when (f.extension.lowercase()) {
        "epub" -> "application/epub+zip"; "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"; "m4a","m4b","mp4","aac" -> "audio/mp4"
        "flac" -> "audio/flac"; "wav" -> "audio/wav"; "ogg","oga","opus" -> "audio/ogg"
        "rasm" -> "application/octet-stream"; else -> "application/octet-stream"
    }

    private fun sendFile(ex: HttpExchange, f: File) {
        val total = f.length()
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Content-Type", contentType(f))
        ex.responseHeaders.add("Content-Disposition", "attachment; filename=\"${f.name}\"")
        val range = ex.requestHeaders.getFirst("Range")
        var start = 0L; var end = total - 1; var status = 200
        if (range != null && range.startsWith("bytes=")) {
            val bits = range.removePrefix("bytes=").split("-", limit = 2)
            start = bits.getOrNull(0)?.toLongOrNull() ?: 0
            end = bits.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: (total - 1)
            if (start > end || start >= total) { start = 0; end = total - 1 } else {
                status = 206
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
            }
        }
        val len = end - start + 1
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(status, -1); return }
        ex.sendResponseHeaders(status, len)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(256 * 1024)
            var remaining = len
            ex.responseBody.use { out ->
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n); remaining -= n
                }
            }
        }
    }

    private fun sendString(ex: HttpExchange, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        if (ex.requestMethod == "HEAD") { ex.sendResponseHeaders(code, -1); return }
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun html(): String {
        val esc = { s: String -> s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;") }
        val rows = pairs.joinToString("\n") { p ->
            "<li><b>${esc(p.title)}</b><br><a href=\"/file/${p.bookSha}\">ebook</a> · <a href=\"/file/${p.audioSha}\">audiobook</a></li>"
        }
        return "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
            "<h2>Witbound on ${esc(computerName)}</h2><p>${pairs.size} book(s). Download both files, then add them in Witbound.</p><ul>$rows</ul>"
    }
}
