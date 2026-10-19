package org.imci.transcriber

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Records 16 kHz mono PCM straight from the microphone, keeps it in memory
 * (readable while recording, for live mode) and saves it as a WAV file on stop.
 */
class Recorder {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val lock = Any()
    private var pcm = ShortArray(AudioDecoder.SAMPLE_RATE * 60)
    private var count = 0

    val isRecording get() = running.get()
    val sampleCount get() = synchronized(lock) { count }
    val seconds get() = sampleCount / AudioDecoder.SAMPLE_RATE.toFloat()

    @SuppressLint("MissingPermission")  // checked by the UI before start()
    fun start() {
        if (running.getAndSet(true)) return
        synchronized(lock) { count = 0 }
        val minBuf = AudioRecord.getMinBufferSize(
            AudioDecoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioDecoder.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        worker = thread(name = "recorder", priority = Thread.MAX_PRIORITY) {
            val buf = ShortArray(minBuf / 2)
            rec.startRecording()
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(lock) {
                    if (count + n > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, count + n))
                    System.arraycopy(buf, 0, pcm, count, n)
                    count += n
                }
            }
            rec.stop(); rec.release()
        }
    }

    /** Samples [from, to) as floats - safe to call while recording. */
    fun samples(from: Int = 0, to: Int = sampleCount): FloatArray = synchronized(lock) {
        val a = from.coerceIn(0, count)
        val b = to.coerceIn(a, count)
        FloatArray(b - a) { pcm[a + it] / 32768f }
    }

    /** Stops recording, writes [wavFile] (if given) and returns all samples as floats. */
    fun stop(wavFile: File?): FloatArray {
        running.set(false)
        worker?.join()
        if (wavFile != null) synchronized(lock) { writeWav(wavFile) }
        return samples()
    }

    private fun writeWav(file: File) {
        file.parentFile?.mkdirs()
        val bytes = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm, 0, count)
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(AudioDecoder.SAMPLE_RATE).putInt(AudioDecoder.SAMPLE_RATE * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(count * 2)
            f.write(h.array())
            f.write(bytes.array())
        }
    }
}
