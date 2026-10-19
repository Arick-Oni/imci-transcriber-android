package org.imci.transcriber

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                // keep the CPU running at full speed: transcription stalls if the screen turns off
                LaunchedEffect(vm.busy) {
                    if (vm.busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                App(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Transcript?>(null) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.error) { vm.error?.let { snack.showSnackbar(it); vm.error = null } }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("IMCI Transcriber") })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Transcribe") })
                Tab(tab == 1, { tab = 1 }, text = { Text("History (${vm.history.size})") })
            }
            when (tab) {
                0 -> TranscribeScreen(vm)
                else -> HistoryScreen(vm) { viewing = it }
            }
        }
    }
    viewing?.let { t -> TranscriptDialog(vm, t) { viewing = null } }
}

@Composable
private fun TranscribeScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.transcribeFile(it) }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.toggleRecording() else vm.error = "Microphone permission is needed to record"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        val status = vm.status
        val recording = status is Status.Recording
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    if (recording || ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED) vm.toggleRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = vm.ready && (recording || !vm.busy),
                colors = if (recording) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                         else ButtonDefaults.buttonColors(),
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Icon(if (recording) Icons.Filled.Stop else Icons.Filled.Mic, null)
                Spacer(Modifier.width(8.dp))
                Text(if (recording) "Stop & transcribe" else "Record", fontSize = 16.sp)
            }
            OutlinedButton(
                onClick = { pickAudio.launch(arrayOf("audio/*", "video/*")) },
                enabled = vm.ready && !vm.busy,
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Icon(Icons.Filled.AudioFile, null)
                Spacer(Modifier.width(8.dp))
                Text("Audio file", fontSize = 16.sp)
            }
        }

        Row(Modifier.fillMaxWidth().clickable(enabled = !vm.busy) { vm.setLive(!vm.liveMode) },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live transcription", fontWeight = FontWeight.Medium)
                Text(if (vm.liveMode) "Text appears while you record (a little behind the speaker)"
                     else "Transcribes after you press Stop",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Switch(checked = vm.liveMode, onCheckedChange = { vm.setLive(it) }, enabled = !vm.busy)
        }

        StatusCard(vm)

        if (vm.liveChunks.isNotEmpty() || vm.lastResult != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(vm.liveTitle, fontWeight = FontWeight.Bold)
                    vm.lastResult?.let { t ->
                        Text("Finished · ${Transcript.fmt(t.audioSeconds)} recording · ${t.timeLabel} · saved to History",
                            style = MaterialTheme.typography.bodySmall)
                        ShareRow(vm, t)
                    }
                    HorizontalDivider()
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            vm.liveChunks.forEach { ChunkView(it) }
                        }
                    }
                }
            }
        } else if (!vm.busy) {
            Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.RecordVoiceOver, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                Text("Record a consultation or choose an audio file.", textAlign = TextAlign.Center)
                Text("Everything is processed on this phone - no internet needed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun StatusCard(vm: MainViewModel) {
    val s = vm.status
    if (s is Status.Idle) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                Status.Preparing -> { Text("Getting ready…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is Status.Recording -> {
                    Text("● Recording  ${Transcript.fmt(s.seconds)}" + if (s.live) "  ·  live" else "",
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    if (s.live) Text(
                        if (s.waiting > 0) "Transcribing as you go · ${s.waiting} part${if (s.waiting > 1) "s" else ""} waiting"
                        else "Transcribing as you go · keep the app open",
                        style = MaterialTheme.typography.bodySmall)
                }
                is Status.Finishing -> {
                    Text(if (s.waiting > 0) "Finishing… ${s.waiting} part${if (s.waiting > 1) "s" else ""} left" else "Finishing…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is Status.Decoding -> { Text("Opening audio… ${(s.progress * 100).toInt()}%"); LinearProgressIndicator({ s.progress }, Modifier.fillMaxWidth()) }
                is Status.Transcribing -> {
                    val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
                    Text(if (s.total == 0) "Listening for speech…" else "Transcribing… ${(frac * 100).toInt()}%")
                    LinearProgressIndicator({ frac }, Modifier.fillMaxWidth())
                    Text("Recording ${Transcript.fmt(s.audioSeconds)} · ${Transcript.fmt(s.elapsed)} so far · keep the app open",
                        style = MaterialTheme.typography.bodySmall)
                }
                Status.Idle -> {}
            }
            if (s !is Status.Preparing && s !is Status.Recording) {
                TextButton(onClick = { vm.cancel() }) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ChunkView(c: Chunk) {
    Column {
        Text("${Transcript.fmt(c.start)} – ${Transcript.fmt(c.end)}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(c.text, fontSize = 17.sp, lineHeight = 26.sp)
    }
}

@Composable
private fun ShareRow(vm: MainViewModel, t: Transcript) {
    val ctx = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("transcript", t.plainText))
            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        }, label = { Text("Copy") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)) })
        AssistChip(onClick = {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", vm.textFile(t))
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, t.title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Share transcript"))
        }, label = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)) })
    }
}

@Composable
private fun HistoryScreen(vm: MainViewModel, onOpen: (Transcript) -> Unit) {
    if (vm.history.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Transcripts you make appear here.", color = MaterialTheme.colorScheme.outline)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.history, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(t) }) {
                Column(Modifier.padding(14.dp)) {
                    Text(t.title, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.createdMs))} · " +
                        "${Transcript.fmt(t.audioSeconds)} recording · ${t.timeLabel}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(t.plainText.take(120), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun TranscriptDialog(vm: MainViewModel, t: Transcript, onClose: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(t.title, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${Transcript.fmt(t.audioSeconds)} recording · ${t.timeLabel}", style = MaterialTheme.typography.bodySmall)
                ShareRow(vm, t)
                SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { t.chunks.forEach { ChunkView(it) } } }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                if (confirmDelete) { vm.delete(t); onClose() } else confirmDelete = true
            }) { Text(if (confirmDelete) "Tap again to delete" else "Delete", color = MaterialTheme.colorScheme.error) }
        },
    )
}
