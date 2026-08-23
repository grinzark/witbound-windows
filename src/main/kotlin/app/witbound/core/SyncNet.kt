package app.witbound.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The sync-map network client (pure JVM — no platform Context). The fingerprint
 * MUST stay byte-identical to iOS SeedNet.pairId and Android SyncNet.pairId:
 * sha256(sha256(epub) + sha256(audio)), first 32 hex chars.
 */
object SyncNet {
    const val PROD = "https://witbound-syncmap.syncmap-worker.workers.dev"

    fun pairId(epub: File, audio: File): String = pairIdFromShas(fileSha(epub), fileSha(audio))

    fun pairIdFromShas(epubSha: ByteArray, audioSha: ByteArray): String {
        val outer = MessageDigest.getInstance("SHA-256")
        outer.update(epubSha); outer.update(audioSha)
        return outer.digest().joinToString("") { "%02x".format(it) }.take(32)
    }

    fun fileSha(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun fileShaHex(f: File): String = hex(fileSha(f))

    /** A finished map for this pair, or null (miss/offline — transcribe locally). */
    fun fetchMap(baseUrl: String, pairId: String): ByteArray? = runCatching {
        val conn = URL("$baseUrl/v1/map/$pairId").openConnection() as HttpURLConnection
        conn.connectTimeout = 4_000; conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size < 16 || bytes.size > 32 shl 20) return null
            val map = SyncMap.parse(bytes)
            if (map.count <= 0) return null
            bytes
        } finally { conn.disconnect() }
    }.getOrNull()

    /** Contribute a finished map. Fire-and-forget; failures are silent. */
    fun uploadMap(baseUrl: String, pairId: String, mapBytes: ByteArray): Boolean = runCatching {
        val conn = URL("$baseUrl/v1/map/$pairId").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4_000; conn.readTimeout = 30_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.outputStream.use { it.write(mapBytes) }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    }.getOrDefault(false)
}
