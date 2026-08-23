package app.witbound.core

import app.witbound.engine.SyncPipeline
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real transcribe -> align -> RASM through whisper.cpp, exactly the code path
 * the Windows build runs. Gated on WITBOUND_WHISPER so plain `test` skips it.
 * Run: WITBOUND_WHISPER=... WITBOUND_FFMPEG=... WITBOUND_MODEL=... \
 *      WB_EPUB=... WB_AUDIO=... ./gradlew test --tests "*IntegrationTest*"
 */
class IntegrationTest {
    @Test fun realPipeline() {
        assumeTrue(System.getenv("WITBOUND_WHISPER") != null, "no whisper binary; skipping")
        val epub = File(System.getenv("WB_EPUB") ?: return)
        val audio = File(System.getenv("WB_AUDIO") ?: return)
        assumeTrue(epub.exists() && audio.exists(), "sample files missing")
        val tmp = File(System.getProperty("java.io.tmpdir"), "wb-itest").apply { mkdirs() }
        val opts = SyncPipeline.Options(server = null,   // force local transcription
            workRoot = File(tmp, "work"), mapsDir = File(tmp, "maps"))
        val t0 = System.currentTimeMillis()
        val o = SyncPipeline.sync(epub, audio, opts) { ev ->
            if (ev is SyncPipeline.Ev.Transcribing) print("\r  transcribe ${(ev.fraction*100).toInt()}%   ")
        }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        println("\npairId=${o.pairId} source=${o.source} matchRate=${"%.3f".format(o.matchRate)} " +
                "rasm=${o.rasm.length()}B duration=${o.durationSec}s wall=${secs}s")
        // pairId must equal the cross-platform value for this exact pair.
        System.getenv("WB_EXPECT_PAIRID")?.let { assertEquals(it, o.pairId, "pairId mismatch across platforms") }
        assertTrue(o.rasm.exists() && o.rasm.length() > 100, "no RASM written")
        // the RASM must parse back (what the phone reads)
        val map = SyncMap.parse(o.rasm.readBytes())
        assertTrue(map.count > 0)
        assertTrue(o.matchRate > 0.70, "match rate too low: ${o.matchRate}")
    }
}
