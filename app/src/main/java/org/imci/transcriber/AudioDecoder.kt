package org.imci.transcriber

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/** Decodes any audio Android can read (m4a, mp3, wav, ogg, mp4 ...) to 16 kHz mono floats. */
object AudioDecoder {
    const val SAMPLE_RATE = 16000

    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in this file")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        if (durationUs > 0) onProgress(min(1f, extractor.sampleTime.toFloat() / durationUs))
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    pcm.write(bytes)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }
            }
        }
        codec.stop(); codec.release(); extractor.release()

        val mono = toMono(pcm.toByteArray(), channels, pcmFloat)
        return resample(mono, srcRate, SAMPLE_RATE)
    }

    private fun toMono(bytes: ByteArray, channels: Int, isFloat: Boolean): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return if (isFloat) {
            val fb = bb.asFloatBuffer()
            FloatArray(fb.limit() / channels) { i ->
                var s = 0f
                for (c in 0 until channels) s += fb.get(i * channels + c)
                s / channels
            }
        } else {
            val sb = bb.asShortBuffer()
            FloatArray(sb.limit() / channels) { i ->
                var s = 0f
                for (c in 0 until channels) s += sb.get(i * channels + c)
                s / channels / 32768f
            }
        }
    }

    /** Box-filter + linear-interpolation resampler; plenty for speech recognition. */
    fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate
        val src = if (ratio > 1.0) {
            // crude low-pass: moving average over ~one output sample period
            val w = floor(ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(input.size)
            var acc = 0f
            for (i in input.indices) {
                acc += input[i]
                if (i >= w) acc -= input[i - w]
                out[i] = acc / min(i + 1, w)
            }
            out
        } else input
        val n = (src.size / ratio).toInt()
        return FloatArray(n) { i ->
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = min(i0 + 1, src.size - 1)
            val frac = (pos - i0).toFloat()
            src[i0] * (1 - frac) + src[i1] * frac
        }
    }
}
