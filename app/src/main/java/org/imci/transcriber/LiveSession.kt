package org.imci.transcriber

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Live transcription while recording ("option 1": VAD-triggered chunks).
 * Policy validated on the PC in mobile/experiments/exp2_live.py:
 *   every 0.5 s the VAD looks at the not-yet-transcribed audio and a chunk is sent when
 *     (a) the speaker paused >= 0.4 s and the buffer holds >= MIN_CHUNK_S, or
 *     (b) a pause >= LONG_PAUSE_S ends an utterance of >= 1.5 s, or
 *     (c) MAX_CHUNK_S of continuous speech -> cut at the quietest point of its last 4 s.
 * One worker transcribes chunks in order; on stop() the rest is flushed and drained.
 *
 * Threading: the VAD context is only used by the chunker loop, the Whisper context only
 * by the worker loop, so the two can run at the same time.
 */
class LiveSession(
    private val transcriber: Transcriber,
    private val recorder: Recorder,
    private val threads: Int,
    private val onChunk: (Chunk) -> Unit,
    private val onProgress: (queued: Int) -> Unit,
) {
    companion object {
        const val STEP_MS = 500L
        const val MIN_CHUNK_S = 8f
        const val LONG_PAUSE_S = 1.0f
        const val MAX_CHUNK_S = 20f
        private const val SR = AudioDecoder.SAMPLE_RATE
    }

    private class Pending(val from: Int, val to: Int)

    private val queue = Channel<Pending>(Channel.UNLIMITED)
    private val queued = AtomicInteger(0)
    @Volatile private var stopping = false
    private var start = 0          // first sample not yet sent for transcription
    val results = ArrayList<Chunk>()

    fun launch(scope: CoroutineScope) = Pair(
        scope.launch(Dispatchers.Default) { chunkerLoop() },
        scope.launch(Dispatchers.Default) { workerLoop() },
    )

    /** Called after the recorder stopped: send whatever is left. */
    fun finish() {
        stopping = true
    }

    private fun send(from: Int, to: Int) {
        if (to - from < SR / 4) return
        onProgress(queued.incrementAndGet())
        queue.trySend(Pending(from, to))
        start = to
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.chunkerLoop() {
        while (isActive) {
            delay(STEP_MS)
            val now = recorder.sampleCount
            val done = stopping
            if (done) {
                // flush: everything after `start` that contains speech
                val rest = recorder.samples(start, now)
                val regions = transcriber.speechRegions(rest)
                if (regions.isNotEmpty()) {
                    send(start + (regions.first().first * SR).toInt(), start + (regions.last().second * SR).toInt())
                }
                queue.close()
                return
            }
            val bufSec = (now - start) / SR.toFloat()
            if (bufSec < 1f) continue
            val buf = recorder.samples(start, now)
            val regions = transcriber.speechRegions(buf)
            if (regions.isEmpty()) {
                if (bufSec > 5f) start = now - SR      // long silence: keep the buffer small
                continue
            }
            val firstSpeech = start + (regions.first().first * SR).toInt()
            // regions followed by an observed pause (>= 400 ms + 200 ms padding)
            val closed = regions.filter { it.second <= bufSec - 0.6f }
            var cut: Int? = null
            if (closed.isNotEmpty()) {
                val lastEnd = closed.last().second
                if (lastEnd >= MIN_CHUNK_S ||
                    (bufSec - lastEnd >= LONG_PAUSE_S && lastEnd - regions.first().first >= 1.5f)) {
                    cut = start + (lastEnd * SR).toInt()
                }
            }
            if (cut == null && bufSec >= MAX_CHUNK_S) {
                cut = start + quietPoint(buf, MAX_CHUNK_S - 4f, MAX_CHUNK_S)
            }
            if (cut != null) {
                start = maxOf(start, firstSpeech - SR / 10)
                send(start, cut)
            }
        }
    }

    private suspend fun workerLoop() {
        for (p in queue) {
            if (transcriber.cancelled) break
            val audio = recorder.samples(p.from, p.to)
            val chunk = transcriber.transcribeChunk(audio, p.from / SR.toFloat(), p.to / SR.toFloat(), threads)
            onProgress(queued.decrementAndGet())
            chunk?.let { results.add(it); onChunk(it) }
        }
    }

    /** Sample index (relative to [buf]) of the quietest ~60 ms between lo and hi seconds. */
    private fun quietPoint(buf: FloatArray, lo: Float, hi: Float): Int {
        val win = SR / 50            // 20 ms frames
        val hop = SR / 100           // 10 ms hop
        val a = (lo * SR).toInt().coerceIn(0, buf.size)
        val b = (hi * SR).toInt().coerceIn(a, buf.size)
        if (b - a < win * 5) return b
        val rms = ArrayList<Float>()
        var i = a
        while (i + win <= b) {
            var s = 0f
            for (k in i until i + win) s += buf[k] * buf[k]
            rms.add(sqrt(s / win)); i += hop
        }
        var best = 0; var bestVal = Float.MAX_VALUE
        for (j in 2 until rms.size - 2) {
            val v = (rms[j - 2] + rms[j - 1] + rms[j] + rms[j + 1] + rms[j + 2]) / 5
            if (v < bestVal) { bestVal = v; best = j }
        }
        return a + best * hop + win / 2
    }
}
