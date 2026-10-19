package org.imci.transcriber

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Status {
    data object Idle : Status
    data object Preparing : Status
    data class Recording(val seconds: Float, val live: Boolean = false, val waiting: Int = 0) : Status
    data class Finishing(val waiting: Int) : Status
    data class Decoding(val progress: Float) : Status
    data class Transcribing(val done: Int, val total: Int, val elapsed: Float, val audioSeconds: Float) : Status
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val transcriber = Transcriber(app)
    private val store = TranscriptStore(app)
    private val recorder = Recorder()
    private val threads = transcriber.defaultThreads()
    private var job: Job? = null
    private var live: LiveSession? = null
    private var liveJobs: Pair<Job, Job>? = null
    @Volatile private var liveWaiting = 0
    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    var status by mutableStateOf<Status>(Status.Idle); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var liveTitle by mutableStateOf("")
    val liveChunks = mutableStateListOf<Chunk>()
    var lastResult by mutableStateOf<Transcript?>(null); private set
    val history = mutableStateListOf<Transcript>()
    /** Live mode: transcribe while recording (remembered between launches). */
    var liveMode by mutableStateOf(prefs.getBoolean("live", true))
        private set

    fun setLive(on: Boolean) {
        liveMode = on
        prefs.edit().putBoolean("live", on).apply()
    }

    val busy get() = status !is Status.Idle

    init {
        refreshHistory()
        prepare()
    }

    /** Loads the built-in engine (a few seconds on first start). */
    private fun prepare() {
        viewModelScope.launch {
            status = Status.Preparing
            runCatching { withContext(Dispatchers.Default) { transcriber.load() } }
                .onSuccess { ready = true }
                .onFailure { error = it.message }
            status = Status.Idle
        }
    }

    fun transcribeFile(uri: Uri) {
        val app = getApplication<Application>()
        val title = displayName(uri)
        job = viewModelScope.launch {
            runCatching {
                status = Status.Decoding(0f)
                val samples = withContext(Dispatchers.Default) {
                    AudioDecoder.decode(app, uri) { status = Status.Decoding(it) }
                }
                run(title, samples)
            }.onFailure { if (isActive) error = "Could not transcribe: ${it.message}" }
            status = Status.Idle
        }
    }

    fun toggleRecording() {
        if (recorder.isRecording) {
            val id = store.newId()
            val wav = File(store.recordingsDir, "recording_$id.wav")
            val session = live
            job = viewModelScope.launch {
                if (session != null) finishLive(session, wav)
                else {
                    val samples = withContext(Dispatchers.IO) { recorder.stop(wav) }
                    runCatching { run(wav.name, samples) }.onFailure { error = "Could not transcribe: ${it.message}" }
                }
                status = Status.Idle
            }
        } else {
            val useLive = liveMode && transcriber.isLoaded
            if (useLive) {
                liveTitle = "Live recording"
                liveChunks.clear()
                lastResult = null
                transcriber.resetCancel()
                liveWaiting = 0
                val session = LiveSession(transcriber, recorder, threads,
                    onChunk = { c -> viewModelScope.launch { liveChunks.add(c) } },
                    onProgress = { n -> liveWaiting = n })
                live = session
                recorder.start()
                liveJobs = session.launch(viewModelScope)
            } else {
                recorder.start()
            }
            job = viewModelScope.launch {
                while (recorder.isRecording) {
                    status = Status.Recording(recorder.seconds, useLive, liveWaiting)
                    delay(250)
                }
            }
        }
    }

    /** Stop pressed in live mode: send the last part, wait for the queue, save. */
    private suspend fun finishLive(session: LiveSession, wav: File) {
        val audioSeconds = withContext(Dispatchers.IO) { recorder.stop(wav) }.size / AudioDecoder.SAMPLE_RATE.toFloat()
        val t0 = SystemClock.elapsedRealtime()
        session.finish()
        val waiter = viewModelScope.launch {
            while (true) { status = Status.Finishing(liveWaiting); delay(250) }
        }
        liveJobs?.let { (chunker, worker) -> chunker.join(); worker.join() }
        waiter.cancel()
        live = null
        liveJobs = null
        if (transcriber.cancelled) return
        val chunks = session.results.sortedBy { it.start }
        val t = Transcript(store.newId(), wav.name, System.currentTimeMillis(), audioSeconds,
            (SystemClock.elapsedRealtime() - t0) / 1000f, chunks, live = true)
        withContext(Dispatchers.IO) { store.save(t) }
        liveTitle = wav.name
        lastResult = t
        refreshHistory()
    }

    private suspend fun run(title: String, samples: FloatArray) {
        check(transcriber.isLoaded) { "The transcription engine is not ready yet" }
        val audioSeconds = samples.size / AudioDecoder.SAMPLE_RATE.toFloat()
        liveTitle = title
        liveChunks.clear()
        lastResult = null
        val t0 = SystemClock.elapsedRealtime()
        val elapsed = { (SystemClock.elapsedRealtime() - t0) / 1000f }
        status = Status.Transcribing(0, 0, 0f, audioSeconds)
        val chunks = withContext(Dispatchers.Default) {
            transcriber.transcribe(samples, threads) { done, total, chunk ->
                val e = elapsed()
                viewModelScope.launch {  // UI state is touched on the main thread only
                    chunk?.let { liveChunks.add(it) }
                    if (busy) status = Status.Transcribing(done, total, e, audioSeconds)
                }
            }
        }
        if (!busy) return  // cancelled
        val t = Transcript(store.newId(), title, System.currentTimeMillis(), audioSeconds, elapsed(), chunks)
        withContext(Dispatchers.IO) { store.save(t) }
        lastResult = t
        refreshHistory()
    }

    fun cancel() {
        transcriber.cancel()
        job?.cancel()
        liveJobs?.let { (a, b) -> a.cancel(); b.cancel() }
        liveJobs = null
        live = null
        if (recorder.isRecording) recorder.stop(null)
        status = Status.Idle
    }

    fun delete(t: Transcript) {
        store.delete(t)
        refreshHistory()
    }

    fun textFile(t: Transcript) = File(store.dir, "${t.id}.txt")

    private fun refreshHistory() {
        history.clear()
        history.addAll(store.list())
    }

    private fun displayName(uri: Uri): String =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment ?: "audio"

    override fun onCleared() {
        transcriber.release()
    }
}
