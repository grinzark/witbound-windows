package app.witbound.net

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The LAN file server the phone pulls from: catalogue, byte-exact files,
 *  Range requests (resumable), and the /delivered ack. */
class ServerTest {
    private fun get(url: String, range: String? = null): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream).readBytes()
        c.disconnect(); return code to body
    }

    @Test fun servesCatalogueFilesAndRanges() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wb-srv-${System.nanoTime()}").apply { mkdirs() }
        val book = File(dir, "book.epub").apply { writeText("EPUB-CONTENT-0123456789") }
        val audio = File(dir, "audio.m4b").apply { writeBytes(ByteArray(5000) { (it % 256).toByte() }) }
        val map = File(dir, "map.rasm").apply { writeBytes(byteArrayOf(1,2,3,4,5)) }
        val bookSha = app.witbound.core.SyncNet.fileShaHex(book)
        val audioSha = app.witbound.core.SyncNet.fileShaHex(audio)
        val mapSha = app.witbound.core.SyncNet.fileShaHex(map)

        var deliveredPid: String? = null
        val server = LanServer("TestPC") { deliveredPid = it }
        val port = server.start(0)
        try {
            server.update(listOf(LanServer.Served("abc123", "T", "A", 10.0,
                book, bookSha, audio, audioSha, map, mapSha, 0.99, "pc")))
            val base = "http://127.0.0.1:$port"
            assertEquals(200, get("$base/healthz").first)
            val (pc, pb) = get("$base/pairs")
            assertEquals(200, pc)
            assertEquals("abc123", org.json.JSONObject(String(pb)).getJSONArray("pairs").getJSONObject(0).getString("pairId"))
            val (fc, fb) = get("$base/file/$bookSha")
            assertEquals(200, fc); assertEquals("EPUB-CONTENT-0123456789", String(fb))
            val (rc, rb) = get("$base/file/$audioSha", "bytes=10-19")
            assertEquals(206, rc); assertEquals(10, rb.size)
            assertTrue(rb.toList() == audio.readBytes().slice(10..19))
            val (mc, mb) = get("$base/map/abc123")
            assertEquals(200, mc); assertTrue(mb.contentEquals(byteArrayOf(1,2,3,4,5)))
            val d = URL("$base/delivered/abc123").openConnection() as HttpURLConnection
            d.requestMethod = "POST"; d.doOutput = true; d.outputStream.use { it.write(ByteArray(0)) }
            assertEquals(200, d.responseCode); d.disconnect()
            assertEquals("abc123", deliveredPid)
            assertEquals(404, get("$base/file/deadbeef").first)
        } finally { server.stop() }
    }
}
