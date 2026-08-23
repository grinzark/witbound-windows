package app.witbound

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.swing.JFileChooser

private val Ink = Color(0xFF14110E)
private val Parchment = Color(0xFFF2ECE1)
private val Gold = Color(0xFFA97C2E)
private val Paper = Color(0xFFFAF8F2)
private val Muted = Color(0xFF6B645A)

fun main() = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val model = remember { CompanionModel(scope) }
    LaunchedEffect(Unit) {
        model.startBackground()
        // Dev/demo hook: WB_AUTOADD="path1|path2" adds files on launch.
        System.getenv("WB_AUTOADD")?.split("|")?.filter { it.isNotBlank() }?.map(::File)
            ?.takeIf { it.isNotEmpty() }?.let { model.add(it) }
    }
    Window(
        onCloseRequest = { model.shutdown(); exitApplication() },
        title = "Witbound for Windows",
        state = rememberWindowState(width = 860.dp, height = 640.dp),
    ) { App(model) }
}

@Composable
private fun App(model: CompanionModel) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Gold, background = Paper, surface = Color.White)) {
        Column(Modifier.fillMaxSize().background(Paper)) {
            Header()
            Divider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp)) {
                SendBar(model)
                Spacer(Modifier.height(12.dp))
                if (model.items.isEmpty() && model.looseBooks.isEmpty() && model.looseAudio.isEmpty()) {
                    DropZone(model)
                } else {
                    model.items.forEach { BookRow(model, it) }
                    model.looseBooks.forEach { LooseRow(it, true) }
                    model.looseAudio.forEach { LooseRow(it, false) }
                    Spacer(Modifier.height(8.dp)); AddMore(model)
                }
            }
            Divider()
            Footer(model)
        }
    }
}

@Composable private fun Header() {
    Row(Modifier.fillMaxWidth().background(Parchment).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Witbound for Windows", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("Sync here, once. Your phone receives a book that's already synced.", fontSize = 12.sp, color = Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Your books never leave this computer.", fontSize = 11.sp, color = Muted)
            Text("Only the timing map — no text, no audio — is shared.", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable private fun SendBar(model: CompanionModel) {
    var expanded by remember { mutableStateOf(false) }
    val chosen = model.phones.firstOrNull { it.id == model.targetPhoneId.value }
    Column(Modifier.fillMaxWidth().background(Parchment, RoundedCornerShape(12.dp))
        .border(1.dp, Gold.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Send to ", color = Ink, fontSize = 13.sp)
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(if (model.targetPhoneId.value == null) "Choose a phone" else model.targetPhoneName(),
                        color = Gold, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("No phone") }, onClick = { model.chooseTarget(null); expanded = false })
                    model.phones.forEach { p ->
                        DropdownMenuItem(text = { Text(p.name) }, onClick = { model.chooseTarget(p.id); expanded = false })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${model.phones.size} phone(s) on Wi-Fi", fontSize = 11.sp, color = Muted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = model.sendWhenFinished.value, onCheckedChange = { model.sendWhenFinished.value = it },
                enabled = model.targetPhoneId.value != null)
            Text(if (model.targetPhoneId.value == null) "Send each book to my phone automatically when it finishes"
                 else "Send each book to ${model.targetPhoneName()} automatically when it finishes",
                fontSize = 12.5.sp, color = if (model.targetPhoneId.value == null) Muted else Ink)
        }
        val sub = when {
            chosen != null && model.sendWhenFinished.value -> "Books fly to ${chosen.name} the moment they finish — no touch on the phone."
            model.phones.isEmpty() -> "Open Witbound on your phone (same Wi-Fi) and it appears here."
            else -> "Pick a phone and books arrive already synced."
        }
        Text(sub, fontSize = 11.sp, color = Muted)
    }
}

@Composable private fun DropZone(model: CompanionModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Add an ebook and its audiobook", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("An .epub or .pdf plus the .m4b / .m4a / .mp3. Add a whole folder to sync many at once —",
            fontSize = 12.5.sp, color = Muted)
        Text("Witbound matches each ebook to its audiobook by name, in any order.", fontSize = 12.5.sp, color = Muted)
        Spacer(Modifier.height(14.dp))
        Button(onClick = { pickFiles(model) }, colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
            Text("Choose files…", color = Color.White)
        }
    }
}

@Composable private fun AddMore(model: CompanionModel) {
    TextButton(onClick = { pickFiles(model) }) { Text("+ Add more books…", color = Gold) }
}

@Composable private fun LooseRow(f: File, isBook: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Parchment.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(12.dp)) {
        Column {
            Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink)
            Text(if (isBook) "Waiting for its audiobook" else "Waiting for its ebook", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable private fun BookRow(model: CompanionModel, item: BookItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).background(Color.White, RoundedCornerShape(12.dp))
        .border(1.dp, Ink.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.title.value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ink, maxLines = 1)
            val meta = buildList { if (item.author.value.isNotBlank()) add(item.author.value); if (item.durationSec.value > 0) add(minutes(item.durationSec.value)); add(item.book.name) }.joinToString(" · ")
            Text(meta, fontSize = 11.sp, color = Ink.copy(alpha = 0.4f), maxLines = 1)
            Text(item.statusLine.value, fontSize = 11.5.sp, color = statusColor(item), maxLines = 2)
            if (item.stage.value == Stage.TRANSCRIBING) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { item.fraction.value.toFloat() }, color = Gold, modifier = Modifier.fillMaxWidth().height(4.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        RowTrailing(model, item)
    }
}

@Composable private fun RowTrailing(model: CompanionModel, item: BookItem) {
    when (item.stage.value) {
        Stage.PREPARING, Stage.ALIGNING, Stage.UPLOADING -> CircularProgressIndicator(Modifier.size(20.dp), color = Gold, strokeWidth = 2.dp)
        Stage.TRANSCRIBING -> item.etaSec.value?.let { Text(minutes(it), color = Gold, fontSize = 12.sp) } ?: CircularProgressIndicator(Modifier.size(20.dp), color = Gold, strokeWidth = 2.dp)
        Stage.READY -> when (item.send.value) {
            SendState.SENDING -> CircularProgressIndicator(Modifier.size(20.dp), color = Gold, strokeWidth = 2.dp)
            SendState.SENT -> Text("✓ Sent", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            SendState.WAITING -> TextButton(onClick = { model.send(item) }) { Text("Send now", color = Gold) }
            SendState.SEND_FAILED -> TextButton(onClick = { model.send(item) }) { Text("Try again", color = Color.Red) }
            SendState.NONE -> TextButton(onClick = { model.send(item) },
                enabled = model.targetPhoneId.value != null || model.phones.isNotEmpty()) {
                Text(if (model.targetPhoneId.value != null) "Send to ${model.targetPhoneName()}" else "Send to phone", color = Gold)
            }
        }
        Stage.FAILED -> TextButton(onClick = { model.start() }) { Text("Retry", color = Gold) }
        Stage.QUEUED -> TextButton(onClick = { model.remove(item) }) { Text("✕", color = Muted) }
    }
}

private fun statusColor(item: BookItem): Color = when (item.stage.value) {
    Stage.FAILED -> Color.Red
    Stage.READY -> if (item.send.value == SendState.SEND_FAILED) Color.Red else Gold
    else -> Muted
}

@Composable private fun Footer(model: CompanionModel) {
    Row(Modifier.fillMaxWidth().background(Parchment).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { pickFiles(model) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) { Text("Add books…") }
        Spacer(Modifier.width(14.dp))
        Checkbox(checked = model.communitySync.value, onCheckedChange = { model.communitySync.value = it })
        Text("Community sync", fontSize = 12.sp, color = Ink)
        Spacer(Modifier.weight(1f))
        Text(model.summary(), fontSize = 11.sp, color = Muted)
        Spacer(Modifier.width(14.dp))
        if (model.running.value) OutlinedButton(onClick = { model.stop() }) { Text("Stop") }
        else Button(onClick = { model.start() }, enabled = model.items.any { it.canQueue }, colors = ButtonDefaults.buttonColors(containerColor = Gold)) { Text("Start", color = Color.White) }
    }
}

private fun pickFiles(model: CompanionModel) {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
        isMultiSelectionEnabled = true
        dialogTitle = "Choose ebooks + audiobooks (or a folder)"
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        model.add(chooser.selectedFiles.toList())
    }
}
