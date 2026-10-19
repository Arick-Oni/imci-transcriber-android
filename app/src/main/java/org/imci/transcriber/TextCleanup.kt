package org.imci.transcriber

/** Same cleanup as imci_v3/run_pipeline_v3.py, ported to Kotlin. */
object TextCleanup {

    private val hallucinationPatterns = listOf(
        "thank(s| you) for watching", "subscribe", "like and share",
        "ভিডিও(টি)? দেখার জন্য ধন্যবাদ", "দেখার জন্য ধন্যবাদ", "সাবস্ক্রাইব",
        "amara\\.org", "subtitles? by", "end of (this|the) video",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    // whisper.cpp prints some special/partial tokens as "<>" or "[_BEG_]"-style markers;
    // U+FFFD comes from split multi-byte characters
    private val tokenJunk = Regex("<>|\\[_[A-Z_]+_?\\d*]|\\uFFFD")

    fun decode(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8)   // invalid sequences become U+FFFD, removed below

    fun clean(raw: String): Pair<String, Int> {
        var text = tokenJunk.replace(raw, " ").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || hallucinationPatterns.any { it.containsMatchIn(text) }) return "" to 0
        val (collapsed, removed) = collapseLoops(text)
        text = collapsed
        return text to removed
    }

    /**
     * Collapse runs of a repeated word / 2-3 word phrase (>= 3 in a row) to one copy.
     * Token-based on purpose: gzip compression ratio misfires on Bengali.
     */
    fun collapseLoops(text: String): Pair<String, Int> {
        var toks = text.split(" ").filter { it.isNotEmpty() }
        var removed = 0
        for (n in 1..3) {
            val out = ArrayList<String>(toks.size)
            var i = 0
            while (i < toks.size) {
                if (i + n > toks.size) { out.add(toks[i]); i++; continue }
                val unit = toks.subList(i, i + n)
                var j = i + n
                var reps = 1
                while (j + n <= toks.size && toks.subList(j, j + n) == unit) { reps++; j += n }
                if (reps >= 3) {
                    out.addAll(unit)
                    removed += (reps - 1) * n
                    i = j
                } else {
                    out.add(toks[i]); i++
                }
            }
            toks = out
        }
        return toks.joinToString(" ") to removed
    }
}
