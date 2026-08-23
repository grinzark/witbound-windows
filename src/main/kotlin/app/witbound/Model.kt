package app.witbound

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import app.witbound.core.SyncNet
import app.witbound.engine.SyncPipeline
import app.witbound.engine.Transcriber
import app.witbound.net.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.prefs.Preferences

private val PREFS = Preferences.userRoot().node("app/witbound/desktop")

/** Prefs-backed setting. */
class Setting<T>(private val key: String, private val def: T, private val enc: (T)->String, private val dec: (String)->T) {
    private val _v = mutableStateOf(dec(PREFS.get(key, enc(def))))
    var value: T
        get() = _v.value
        set(x) { _v.value = x; PREFS.put(key, enc(x)) }
}

enum class Stage { QUEUED, PREPARING, TRANSCRIBING, ALIGNING, UPLOADING, READY, FAILED }
enum class SendState { NONE, WAITING, SENDING, SENT, SEND_FAILED }

class BookItem(val book: File, val audio: File, title: String) {
    val id = java.util.UUID.randomUUID().toString()
    val title = mutableStateOf(title)
    val author = mutableStateOf("")
    val stage = mutableStateOf(Stage.QUEUED)
    val statusLine = mutableStateOf("Waiting its turn")
    val fraction = mutableStateOf(0.0)
    val etaSec = mutableStateOf<Double?>(null)
    val durationSec = mutableStateOf(0.0)
    val send = mutableStateOf(SendState.NONE)
    var outcome: SyncPipeline.Outcome? = null
    val canQueue get() = stage.value == Stage.QUEUED || stage.value == Stage.FAILED
    val isReady get() = stage.value == Stage.READY
}

class CompanionModel(private val scope: CoroutineScope) {
    val items = mutableStateListOf<BookItem>()
    val looseBooks = mutableStateListOf<File>()
    val looseAudio = mutableStateListOf<File>()
    val phones = mutableStateListOf<PhoneBrowser.Phone>()
    val running = mutableStateOf(false)
    val serverAddr = mutableStateOf<String?>(null)

    val communitySync = Setting("communitySync", true, { it.toString() }, { it.toBoolean() })
    val sendWhenFinished = Setting("sendWhenFinished", false, { it.toString() }, { it.toBoolean() })
    val targetPhoneId = Setting<String?>("targetPhoneId", null, { it ?: "" }, { it.ifBlank { null } })
    private var targetName = Setting<String>("targetPhoneName", "your phone", { it }, { it })

    val computerName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("PC")
        .removeSuffix(".local").removeSuffix(".lan")
    val appSupport = File(System.getProperty("user.home"), ".witbound-desktop").apply { mkdirs() }
    val mapsDir = File(appSupport, "maps").apply { mkdirs() }
    val workDir = File(appSupport, "work").apply { mkdirs() }

    private val server = LanServer(computerName) { pid -> onDelivered(pid) }
    private val browser = PhoneBrowser { list -> scope.launch(Dispatchers.Main) { phones.clear(); phones.addAll(list); retryWaiting() } }
    private var worker: Job? = null

    fun startBackground() {
        runCatching { server.start(8765); serverAddr.value = Lan.localIPv4().firstOrNull()?.let { "http://$it:${server.port}" } }
        browser.start()
    }
    fun shutdown() { worker?.cancel(); server.stop(); browser.stop() }

    fun targetPhoneName(): String = phones.firstOrNull { it.id == targetPhoneId.value }?.name ?: targetName.value
    fun chooseTarget(id: String?) {
        targetPhoneId.value = id
        if (id != null) { phones.firstOrNull { it.id == id }?.let { targetName.value = it.name }; sendWhenFinished.value = true }
    }

    // ---- adding books (fuzzy name matching + folders) ----
    fun add(paths: List<File>) {
        val books = ArrayList<File>(); val audios = ArrayList<File>()
        for (f in paths) {
            if (f.isDirectory) addFolder(f)
            else when (f.extension.lowercase()) {
                "epub","pdf" -> books += f
                "m4b","m4a","mp3","flac","ogg","oga","opus","wav","aac","mp4","aiff","aif" -> audios += f
            }
        }
        pairUp(books + looseBooks, audios + looseAudio, keepLoose = true)
        startIfIdle()
    }
    private fun addFolder(dir: File) {
        val kids = dir.listFiles()?.toList() ?: return
        val books = kids.filter { it.extension.lowercase() in setOf("epub","pdf") }
        val audios = kids.filter { it.extension.lowercase() in setOf("m4b","m4a","mp3","flac","ogg","oga","opus","wav","aac","mp4","aiff","aif") }
        if (books.size == 1 && audios.size == 1) append(books[0], audios[0])
        else pairUp(books, audios, keepLoose = false)
        kids.filter { it.isDirectory }.forEach { addFolder(it) }
    }
    private val noise = setOf("the","a","an","of","and","unabridged","abridged","audiobook","audio","book",
        "ebook","epub","pdf","m4b","m4a","mp3","part","pt","vol","volume","complete","full","narrated","by","read","novel")
    private fun tokens(f: File) = f.nameWithoutExtension.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(" ")
        .filter { it.length >= 2 && it !in noise }.toSet()
    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }
    private fun pairUp(books: List<File>, audios: List<File>, keepLoose: Boolean) {
        val remaining = audios.toMutableList(); val unmatched = ArrayList<File>()
        for (b in books) {
            val bt = tokens(b)
            val best = remaining.map { it to similarity(bt, tokens(it)) }.maxByOrNull { it.second }
            if (best != null && best.second >= 0.34) { append(b, best.first); remaining.remove(best.first) }
            else unmatched += b
        }
        if (unmatched.size == 1 && remaining.size == 1) { append(unmatched[0], remaining[0]); unmatched.clear(); remaining.clear() }
        if (keepLoose) { looseBooks.clear(); looseBooks.addAll(unmatched); looseAudio.clear(); looseAudio.addAll(remaining) }
    }
    private fun append(book: File, audio: File) {
        if (items.any { it.book == book && it.audio == audio }) return
        val item = BookItem(book, audio, book.nameWithoutExtension)
        items.add(item)
        scope.launch(Dispatchers.IO) {
            runCatching { app.witbound.core.EpubParser.parse(book) }.getOrNull()?.let {
                withContext(Dispatchers.Main) { item.title.value = it.book.title.ifBlank { item.title.value }; item.author.value = it.book.author }
            }
        }
    }
    fun remove(item: BookItem) { if (!item.isReadyOrIdle()) return; items.remove(item); publish() }
    private fun BookItem.isReadyOrIdle() = stage.value in setOf(Stage.READY, Stage.QUEUED, Stage.FAILED)

    // ---- queue ----
    fun startIfIdle() { if (!running.value && items.any { it.stage.value == Stage.QUEUED }) start() }
    fun start() {
        if (running.value) return
        items.filter { it.stage.value == Stage.FAILED }.forEach { it.stage.value = Stage.QUEUED }
        if (items.none { it.stage.value == Stage.QUEUED }) return
        running.value = true
        worker = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val next = withContext(Dispatchers.Main) { items.firstOrNull { it.stage.value == Stage.QUEUED } } ?: break
                process(next)
            }
            withContext(Dispatchers.Main) { running.value = false }
        }
    }
    fun stop() { worker?.cancel(); running.value = false }

    private suspend fun process(item: BookItem) {
        val opts = SyncPipeline.Options(
            server = if (communitySync.value) SyncNet.PROD else null, workRoot = workDir, mapsDir = mapsDir)
        var clockStart = 0L; var doneAtStart = 0.0
        try {
            val outcome = SyncPipeline.sync(item.book, item.audio, opts) { ev ->
                scope.launch(Dispatchers.Main) {
                    when (ev) {
                        is SyncPipeline.Ev.Hashing -> { item.stage.value = Stage.PREPARING; item.statusLine.value = "Fingerprinting the files…" }
                        is SyncPipeline.Ev.CheckingNetwork -> item.statusLine.value = "Checking if someone already synced this…"
                        is SyncPipeline.Ev.Aligning -> { item.stage.value = Stage.ALIGNING; item.statusLine.value = "Matching every word to the text…" }
                        is SyncPipeline.Ev.Uploading -> { item.stage.value = Stage.UPLOADING; item.statusLine.value = "Sharing the timing map…" }
                        is SyncPipeline.Ev.Transcribing -> {
                            item.stage.value = Stage.TRANSCRIBING; item.fraction.value = ev.fraction
                            val now = System.currentTimeMillis()
                            if (clockStart == 0L) { clockStart = now; doneAtStart = ev.fraction }
                            val elapsed = (now - clockStart) / 1000.0; val prog = ev.fraction - doneAtStart
                            item.etaSec.value = if (elapsed > 5 && prog > 0.02) elapsed / prog * (1 - ev.fraction) else null
                            val pct = (ev.fraction * 100).toInt()
                            item.statusLine.value = "Transcribing · $pct%" + (item.etaSec.value?.let { " · ~${minutes(it)} left" } ?: "")
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { finish(item, outcome) }
        } catch (e: CancellationException) {
            withContext(Dispatchers.Main) { item.stage.value = Stage.QUEUED; item.statusLine.value = "Stopped — progress kept" }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { item.stage.value = Stage.FAILED; item.statusLine.value = "Couldn't sync — ${e.message}" }
        }
    }
    private fun finish(item: BookItem, o: SyncPipeline.Outcome) {
        item.outcome = o; item.durationSec.value = o.durationSec
        if (o.title.isNotBlank()) item.title.value = o.title
        if (o.author.isNotBlank()) item.author.value = o.author
        item.stage.value = Stage.READY
        item.statusLine.value = readyLine(o)
        publish()
        if (sendWhenFinished.value && targetPhoneId.value != null) send(item)
    }
    private fun readyLine(o: SyncPipeline.Outcome): String {
        val pct = (o.matchRate * 100).toInt()
        return when (o.source) {
            "network" -> "Synced instantly — found on the network · $pct% of words matched"
            else -> if (o.onNetwork) "Synced on this PC · $pct% of words matched" else "Synced on this PC · $pct% matched" +
                (o.uploadError?.let { " · $it" } ?: " · kept private")
        }
    }

    // ---- send ----
    fun send(item: BookItem, overrideId: String? = null) {
        val o = item.outcome ?: return
        if (item.send.value == SendState.SENDING) return
        val id = overrideId ?: targetPhoneId.value
        val phone = phones.firstOrNull { it.id == id }
        val src = serverAddr.value
        if (src == null) { item.send.value = SendState.SEND_FAILED; item.statusLine.value = "Sharing not started — check Wi-Fi"; return }
        if (phone == null) { item.send.value = SendState.WAITING; item.statusLine.value = "Waiting for ${targetPhoneName()} to open Witbound"; return }
        item.send.value = SendState.SENDING; item.statusLine.value = "Sending to ${phone.name}…"
        val offer = LinkOffer(WitboundLink.VERSION, o.pairId, item.title.value, item.author.value, o.durationSec,
            src, src, computerName,
            LinkFileRef(item.book.name, o.bookSha, item.book.length()),
            LinkFileRef(item.audio.name, o.audioSha, item.audio.length()),
            LinkMapRef(o.mapSha, o.rasm.length(), "rasm1", o.matchRate))
        scope.launch(Dispatchers.IO) {
            val resp = Lan.postJson(phone.baseUrl, "/offer", offer.json().toString())
            withContext(Dispatchers.Main) {
                if (resp == null) { item.send.value = SendState.WAITING; item.statusLine.value = "Waiting for ${phone.name}…"; return@withContext }
                val reply = runCatching { LinkOfferReply.parse(resp.second) }.getOrNull()
                when (reply?.decision) {
                    "accepted" -> item.statusLine.value = "Sending to ${phone.name}…"   // /delivered flips to SENT
                    "duplicate" -> { item.send.value = SendState.SENT; item.statusLine.value = "Already on ${phone.name}" }
                    "locked" -> { item.send.value = SendState.SEND_FAILED; item.statusLine.value = "Couldn't send — ${reply.message ?: "phone needs Plus"}" }
                    else -> { item.send.value = SendState.SEND_FAILED; item.statusLine.value = "Couldn't send — ${reply?.message ?: "phone declined"}" }
                }
            }
        }
    }
    private fun onDelivered(pid: String) = scope.launch(Dispatchers.Main) {
        items.firstOrNull { it.outcome?.pairId == pid }?.let {
            it.send.value = SendState.SENT; it.statusLine.value = "Delivered to ${targetPhoneName()} — it opened already synced"
        }
    }
    private fun retryWaiting() {
        if (targetPhoneId.value == null || phones.none { it.id == targetPhoneId.value }) return
        items.filter { it.isReady && it.send.value == SendState.WAITING }.forEach { send(it) }
    }

    private fun publish() {
        val served = items.mapNotNull { item ->
            val o = item.outcome ?: return@mapNotNull null
            if (!item.book.exists() || !item.audio.exists() || !o.rasm.exists()) return@mapNotNull null
            LanServer.Served(o.pairId, item.title.value, item.author.value, o.durationSec,
                item.book, o.bookSha, item.audio, o.audioSha, o.rasm, o.mapSha, o.matchRate, o.source)
        }
        server.update(served)
    }

    fun summary(): String {
        val ready = items.count { it.isReady }; val work = items.count { it.stage.value in setOf(Stage.PREPARING,Stage.TRANSCRIBING,Stage.ALIGNING,Stage.UPLOADING) }
        val wait = items.count { it.stage.value == Stage.QUEUED }
        return buildList { add("${items.size} book${if (items.size==1) "" else "s"}"); if (ready>0) add("$ready synced"); if (work>0) add("$work syncing"); if (wait>0) add("$wait waiting") }.joinToString(" · ")
    }
}

fun minutes(sec: Double): String {
    val m = (sec / 60).toInt()
    if (m < 1) return "under a minute"; if (m < 60) return "$m min"
    val h = m / 60; val r = m % 60; return if (r == 0) "$h h" else "$h h $r min"
}
