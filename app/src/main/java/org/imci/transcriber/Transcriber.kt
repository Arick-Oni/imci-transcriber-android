package org.imci.transcriber

import android.content.Context
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

data class Chunk(val start: Float, val end: Float, val text: String, val loopCollapsed: Boolean)

/**
 * On-device version of imci_v3 (without speaker labels):
 *   Silero VAD -> speech regions merged into <= MAX_CHUNK_S chunks
 *   -> regional Bengali Whisper (greedy, no fallback, no timestamps)
 *      with the encoder window (audio_ctx) sized to each chunk
 *   -> same text cleanup as the PC pipeline.
 */
class Transcriber(private val context: Context) {
    companion object {
        const val MAX_CHUNK_S = 20f
        // Both ship inside the APK (stored uncompressed, read in place - no copy to storage):
        // speech_model.bin = regional Bengali Whisper medium, q8_0; vad_model.bin = Silero VAD v6.2
        const val MODEL_ASSET = "speech_model.bin"
        const val VAD_ASSET = "vad_model.bin"

        @Volatile private var backendsLoaded = false
    }

    private var ctx = 0L
    private var vctx = 0L
    val isLoaded get() = ctx != 0L && vctx != 0L

    /** Number of "big" cores: cores whose max frequency is above the slowest cluster's. */
    fun defaultThreads(): Int = runCatching {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
            File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
        }
        val big = freqs.count { it > (freqs.minOrNull() ?: 0) }
        if (big in 2..8) big else 4
    }.getOrDefault(4)

    fun load() {
        if (!backendsLoaded) {
            WhisperNative.loadBackends(context.applicationInfo.nativeLibraryDir)
            backendsLoaded = true
        }
        if (vctx == 0L) {
            vctx = WhisperNative.initVadFromAsset(context.assets, VAD_ASSET, 2)
            check(vctx != 0L) { "Could not start the speech detector" }
        }
        if (ctx == 0L) {
            ctx = WhisperNative.initModelFromAsset(context.assets, MODEL_ASSET)
            check(ctx != 0L) { "Could not start the transcription engine" }
        }
    }

    /** Speech regions merged into chunks of at most MAX_CHUNK_S seconds. */
    fun chunks(samples: FloatArray): List<Pair<Float, Float>> {
        val seg = WhisperNative.vadSegments(vctx, samples, MAX_CHUNK_S)
        val out = ArrayList<Pair<Float, Float>>()
        var i = 0
        while (i + 1 < seg.size) {
            val s = seg[i]; val e = seg[i + 1]
            val last = out.lastOrNull()
            if (last != null && e - last.first <= MAX_CHUNK_S) out[out.size - 1] = last.first to e
            else out.add(s to e)
            i += 2
        }
        return out
    }

    /** Speech regions in [samples] (seconds, relative to its start) without forced splitting - for live mode. */
    fun speechRegions(samples: FloatArray): List<Pair<Float, Float>> {
        val seg = WhisperNative.vadSegments(vctx, samples, 1000f)
        return (0 until seg.size / 2).map { seg[2 * it] to seg[2 * it + 1] }
    }

    /** Encoder window: 50 frames/s, rounded up to 64, +64 margin (validated on the Poco F5). */
    private fun audioCtx(seconds: Float) = min(1500, ceil(seconds * 50 / 64).toInt() * 64 + 64)

    /** Transcribes one chunk of audio; [start]/[end] are only stored on the result. Null if no text. */
    fun transcribeChunk(audio: FloatArray, start: Float, end: Float, threads: Int): Chunk? {
        if (audio.isEmpty()) return null
        val bytes = WhisperNative.transcribe(ctx, audio, threads, audioCtx(audio.size / AudioDecoder.SAMPLE_RATE.toFloat()))
        val (text, removed) = TextCleanup.clean(TextCleanup.decode(bytes))
        return if (text.isNotEmpty() && !cancelled) Chunk(start, end, text, removed > 0) else null
    }

    fun transcribe(
        samples: FloatArray,
        threads: Int,
        onChunk: (index: Int, total: Int, chunk: Chunk?) -> Unit,
    ): List<Chunk> {
        cancelled = false
        val regions = chunks(samples)
        val result = ArrayList<Chunk>()
        onChunk(0, regions.size, null)
        for ((idx, region) in regions.withIndex()) {
            if (cancelled) break
            val (st, en) = region
            val a = (st * AudioDecoder.SAMPLE_RATE).toInt().coerceIn(0, samples.size)
            val b = (en * AudioDecoder.SAMPLE_RATE).toInt().coerceIn(a, samples.size)
            val chunk = transcribeChunk(samples.copyOfRange(a, b), st, en, threads)
            if (cancelled) break
            chunk?.let { result.add(it) }
            onChunk(idx + 1, regions.size, chunk)
        }
        return result
    }

    fun resetCancel() { cancelled = false }

    @Volatile var cancelled = false
        private set

    fun cancel() {
        cancelled = true
        WhisperNative.cancel()
    }

    fun release() {
        if (ctx != 0L) WhisperNative.freeModel(ctx)
        if (vctx != 0L) WhisperNative.freeVad(vctx)
        ctx = 0L; vctx = 0L
    }
}
