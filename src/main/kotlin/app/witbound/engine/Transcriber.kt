package app.witbound.engine

import app.witbound.core.TimedWord
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * On-device transcription via a bundled whisper.cpp binary. The GPU speedup
 * comes from WHICH binary ships: build whisper.cpp with CUDA (NVIDIA), Vulkan
 * (any DX12 GPU) or Metal (Apple), and it offloads to the GPU automatically —
 * this wrapper just drives it. Word-level timestamps via --max-len 1
 * --split-on-word; audio is normalised to 16 kHz mono WAV with ffmpeg first.
 *
 * Locations are resolved from (1) explicit config, (2) env vars
 * WITBOUND_WHISPER / WITBOUND_FFMPEG / WITBOUND_MODEL, (3) a bundled bin/ next
 * to the app, (4) PATH — so the same code runs in dev on a Mac and shipped on
 * Windows.
 */
class Transcriber(private val cfg: WhisperConfig = WhisperConfig.resolve()) {

    data class WhisperConfig(
        val whisper: File,
        val ffmpeg: File,
        val model: File,
        val threads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(8),
    ) {
        companion object {
            fun resolve(modelName: String = "ggml-base.en.bin"): WhisperConfig {
                val whisper = locate("WITBOUND_WHISPER", listOf("whisper-cli", "main", "whisper"))
                    ?: error("whisper binary not found (set WITBOUND_WHISPER or bundle bin/whisper-cli)")
                val ffmpeg = locate("WITBOUND_FFMPEG", listOf("ffmpeg"))
                    ?: error("ffmpeg not found (set WITBOUND_FFMPEG or bundle bin/ffmpeg)")
                val model = System.getenv("WITBOUND_MODEL")?.let(::File)?.takeIf { it.exists() }
                    ?: bundled("models/$modelName") ?: bundled(modelName)
                    ?: error("whisper model not found (set WITBOUND_MODEL)")
                return WhisperConfig(whisper, ffmpeg, model)
            }
            private fun appDir(): File =
                File(WhisperConfig::class.java.protectionDomain.codeSource?.location?.toURI()
                    ?: File(".").toURI()).let { if (it.isFile) it.parentFile else it }
            private fun bundled(rel: String): File? {
                val candidates = listOf(File(appDir(), rel), File(appDir(), "bin/$rel"),
                    File(appDir().parentFile ?: appDir(), rel))
                return candidates.firstOrNull { it.exists() }
            }
            private fun locate(env: String, names: List<String>): File? {
                System.getenv(env)?.let { val f = File(it); if (f.exists()) return f }
                for (n in names) {
                    bundled(n)?.let { return it }
                    bundled("bin/$n")?.let { return it }
                    val onPath = which(n); if (onPath != null) return onPath
                }
                return null
            }
            private fun which(name: String): File? = runCatching {
                val cmd = if (System.getProperty("os.name").lowercase().contains("win")) "where" else "which"
                val p = ProcessBuilder(cmd, name).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().lineSequence().firstOrNull()?.trim()
                p.waitFor(5, TimeUnit.SECONDS)
                out?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
            }.getOrNull()
        }
    }

    /**
     * Transcribes the whole audio file to absolute-timestamped words.
     * [onProgress] receives 0..1. Runs whisper.cpp once (it chunks internally);
     * the GPU backend, if the binary has one, is used automatically.
     */
    /** Directory holding ffmpeg (ffprobe sits alongside). */
    fun ffmpegDir(): File = cfg.ffmpeg.parentFile ?: File(".")

    fun transcribe(audio: File, workDir: File, onProgress: (Double) -> Unit = {}): List<TimedWord> {
        workDir.mkdirs()
        val wav = File(workDir, "audio-16k.wav")
        if (!wav.exists() || wav.length() == 0L) {
            onProgress(0.0)
            runProcess(listOf(cfg.ffmpeg.absolutePath, "-y", "-i", audio.absolutePath,
                "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", wav.absolutePath), null)
            require(wav.exists() && wav.length() > 0) { "ffmpeg failed to produce 16k wav" }
        }
        val outBase = File(workDir, "transcript")
        val json = File(workDir, "transcript.json")
        if (!json.exists()) {
            val cmd = listOf(cfg.whisper.absolutePath,
                "-m", cfg.model.absolutePath, "-f", wav.absolutePath,
                "-t", cfg.threads.toString(),
                "--max-len", "1", "--split-on-word",
                "--output-json", "--output-file", outBase.absolutePath,
                "--print-progress")
            runProcess(cmd) { line ->
                // whisper.cpp prints: "whisper_print_progress_callback: progress = 42%"
                Regex("progress\\s*=\\s*(\\d+)%").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    onProgress((it.coerceIn(0, 100)) / 100.0)
                }
            }
            require(json.exists()) { "whisper produced no JSON (${outBase}.json)" }
        }
        onProgress(1.0)
        return parseWhisperJson(json)
    }

    private fun parseWhisperJson(json: File): List<TimedWord> {
        val root = JSONObject(json.readText())
        val arr = root.optJSONArray("transcription") ?: return emptyList()
        val words = ArrayList<TimedWord>(arr.length())
        for (i in 0 until arr.length()) {
            val seg = arr.getJSONObject(i)
            val text = seg.optString("text").trim()
            if (text.isEmpty()) continue
            val off = seg.optJSONObject("offsets") ?: continue
            val from = off.optLong("from") / 1000.0   // ms -> s
            val to = off.optLong("to") / 1000.0
            if (to < from) continue
            words += TimedWord(text, from, to)
        }
        return words
    }

    private fun runProcess(cmd: List<String>, onLine: ((String) -> Unit)?) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { onLine?.invoke(it) }
        }
        val code = p.waitFor()
        require(code == 0) { "process exited $code: ${cmd.first()}" }
    }
}
