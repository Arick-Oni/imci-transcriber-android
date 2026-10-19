package org.imci.transcriber

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Transcript(
    val id: String,
    val title: String,
    val createdMs: Long,
    val audioSeconds: Float,
    val processingSeconds: Float,
    val chunks: List<Chunk>,
    /** live mode: most of the work happened while recording; processingSeconds is only the wait after Stop.
     *  null = saved by v1.1, which did not record the mode. */
    val live: Boolean? = false,
) {
    val plainText: String get() = chunks.joinToString("\n") { it.text }

    /** How long transcription took, e.g. "transcribed in 3:41 (1.1x recording length)". */
    val timeLabel: String get() = when (live) {
        true -> "text ready ${fmt(processingSeconds)} after Stop"
        false -> "transcribed in ${fmt(processingSeconds)}" +
            if (audioSeconds > 0) " (%.1fx recording length)".format(processingSeconds / audioSeconds) else ""
        null -> "processing ${fmt(processingSeconds)}"
    }

    fun asText(): String = buildString {
        appendLine(title)
        appendLine("Recording length ${fmt(audioSeconds)} · $timeLabel")
        appendLine()
        chunks.forEach { c ->
            appendLine("[${fmt(c.start)} → ${fmt(c.end)}]")
            appendLine(c.text)
            appendLine()
        }
    }

    companion object {
        fun fmt(sec: Float): String {
            val s = sec.toInt()
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/** Saves transcripts as JSON + TXT under Android/data/org.imci.transcriber/files/transcripts/. */
class TranscriptStore(context: Context) {
    val dir: File = File(context.getExternalFilesDir(null), "transcripts").apply { mkdirs() }
    val recordingsDir: File = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    fun newId(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun save(t: Transcript) {
        val chunks = JSONArray()
        t.chunks.forEach {
            chunks.put(JSONObject().put("start", it.start.toDouble()).put("end", it.end.toDouble())
                .put("text", it.text).put("loop_collapsed", it.loopCollapsed))
        }
        val json = JSONObject()
            .put("id", t.id).put("title", t.title).put("created_ms", t.createdMs)
            .put("audio_seconds", t.audioSeconds.toDouble()).put("processing_seconds", t.processingSeconds.toDouble())
            .put("live", t.live)
            .put("chunks", chunks)
        File(dir, "${t.id}.json").writeText(json.toString(2))
        File(dir, "${t.id}.txt").writeText(t.asText())
    }

    fun list(): List<Transcript> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { load(it) }.getOrNull() }
            .sortedByDescending { it.createdMs }

    private fun load(f: File): Transcript {
        val j = JSONObject(f.readText())
        val arr = j.getJSONArray("chunks")
        return Transcript(
            id = j.getString("id"), title = j.getString("title"), createdMs = j.getLong("created_ms"),
            audioSeconds = j.getDouble("audio_seconds").toFloat(),
            processingSeconds = j.getDouble("processing_seconds").toFloat(),
            live = if (j.has("live") && !j.isNull("live")) j.getBoolean("live") else null,
            chunks = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Chunk(c.getDouble("start").toFloat(), c.getDouble("end").toFloat(),
                    c.getString("text"), c.optBoolean("loop_collapsed"))
            },
        )
    }

    fun delete(t: Transcript) {
        File(dir, "${t.id}.json").delete()
        File(dir, "${t.id}.txt").delete()
    }
}
