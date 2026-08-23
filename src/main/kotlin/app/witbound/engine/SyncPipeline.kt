package app.witbound.engine

import app.witbound.core.*
import java.io.File

/**
 * One (ebook, audio) pairing → a finished RASM map, mirroring the macOS
 * SeedPipeline. Always yields a local map; the sync-map network is optional.
 */
object SyncPipeline {
    data class Options(
        val server: String? = SyncNet.PROD,   // null = never touch the network
        val workRoot: File,
        val mapsDir: File,
        val acceptNetworkAtOrAbove: Double = 0.75,
    )
    data class Outcome(
        val pairId: String, val bookSha: String, val audioSha: String,
        val rasm: File, val mapSha: String, val matchRate: Double,
        val source: String, var onNetwork: Boolean, var uploadError: String?,
        val title: String, val author: String, val durationSec: Double,
    )
    sealed class Ev {
        object Hashing : Ev(); object CheckingNetwork : Ev()
        data class Transcribing(val fraction: Double) : Ev()
        object Aligning : Ev(); object Uploading : Ev()
    }

    fun sync(epub: File, audio: File, opts: Options,
             transcriber: Transcriber = Transcriber(),
             progress: (Ev) -> Unit = {}): Outcome {
        progress(Ev.Hashing)
        val bookShaBytes = SyncNet.fileSha(epub); val audioShaBytes = SyncNet.fileSha(audio)
        val bookSha = SyncNet.hex(bookShaBytes); val audioSha = SyncNet.hex(audioShaBytes)
        val pairId = SyncNet.pairIdFromShas(bookShaBytes, audioShaBytes)

        val parsed = EpubParser.parse(epub)
        val book = parsed.book
        val bookTokens = Tokenizer.tokenize(book)
        val duration = audioDurationSec(audio, transcriber)
        opts.mapsDir.mkdirs()
        val rasmFile = File(opts.mapsDir, "$pairId.rasm")

        fun outcome(rate: Double, source: String, onNet: Boolean, upErr: String?): Outcome {
            val mapSha = SyncNet.hex(SyncNet.fileSha(rasmFile))
            return Outcome(pairId, bookSha, audioSha, rasmFile, mapSha, rate, source, onNet, upErr,
                book.title, book.author, duration)
        }

        // network first (optional)
        var onNetwork = false
        if (opts.server != null) {
            progress(Ev.CheckingNetwork)
            val data = SyncNet.fetchMap(opts.server, pairId)
            if (data != null) {
                onNetwork = true
                val map = runCatching { SyncMap.parse(data) }.getOrNull()
                if (map != null && map.count == bookTokens.size && map.sectionNarrated.size == book.sections.size) {
                    val rate = map.narratedMatchRate()
                    if (rate >= opts.acceptNetworkAtOrAbove) {
                        rasmFile.writeBytes(data)
                        return outcome(rate, "network", true, null)
                    }
                }
            }
        }

        // transcribe -> align -> build
        val work = File(opts.workRoot, audioSha.take(32) + "-work")
        val words = transcriber.transcribe(audio, work) { f -> progress(Ev.Transcribing(f)) }
        progress(Ev.Aligning)
        val (alignment, _) = Aligner.align(bookTokens.map { it.normalized }, words.map { Tokenizer.normalize(it.text) })
        val map = SyncMapBuilder.build(bookTokens, words, alignment, book.sections.size)
        val rasm = map.toBinary()
        rasmFile.writeBytes(rasm)
        val rate = map.narratedMatchRate()

        var upErr: String? = null
        if (opts.server != null && !onNetwork) {
            progress(Ev.Uploading)
            if (!SyncNet.uploadMap(opts.server, pairId, rasm)) upErr = "couldn't reach the network"
            else onNetwork = true
        }
        return outcome(rate, "pc", onNetwork, upErr)
    }

    private fun audioDurationSec(audio: File, t: Transcriber): Double = runCatching {
        // ffprobe sits next to ffmpeg
        val ffprobe = File(t.ffmpegDir(), if (isWin()) "ffprobe.exe" else "ffprobe")
        val bin = if (ffprobe.exists()) ffprobe.absolutePath else "ffprobe"
        val p = ProcessBuilder(bin, "-v", "error", "-show_entries", "format=duration",
            "-of", "default=nw=1:nk=1", audio.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.toDoubleOrNull() ?: 0.0
    }.getOrDefault(0.0)

    private fun isWin() = System.getProperty("os.name").lowercase().contains("win")
}
