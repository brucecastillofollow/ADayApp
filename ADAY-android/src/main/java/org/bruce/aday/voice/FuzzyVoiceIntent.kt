package org.bruce.aday.voice

import kotlin.math.max

/**
 * Last-resort interpreter when the local LLM is disabled: fuzzy phrase and habit-name matching
 * on the speech transcript.
 */
object FuzzyVoiceIntent {

    private const val VERB_MATCH = 0.78
    private const val CUE_MATCH = 0.72
    private const val HABIT_MATCH = 0.62
    private const val HABIT_RESOLVE = 0.55

    private val DELETE_PHRASES = listOf("delete", "remove", "erase", "trash", "get rid of")
    private val ARCHIVE_PHRASES = listOf("archive", "achieved", "archived")
    private val DONE_CUES = listOf(
        "done", "did", "finished", "completed", "checked", "check", "complete",
    )
    private val ADD_PHRASES = listOf(
        "add habit", "add a habit", "new habit", "create habit", "create a habit",
        "start habit", "start a habit", "add", "create", "start",
    )

    private val BANNED_DELETE_TAIL = setOf("it", "this", "that", "one")
    private val BANNED_ADD_VERBS = setOf("it", "one", "a", "the")

    fun tryMatch(transcript: String, habitNames: List<String>): VoiceHabitCommand? {
        val t = transcript.trim().lowercase()
        if (t.isEmpty()) return null
        val words = tokenize(t)
        if (words.isEmpty()) return null

        tryDelete(words, habitNames)?.let { return it }
        tryArchive(words, habitNames)?.let { return it }
        tryMarkDone(t, words, habitNames)?.let { return it }
        tryAdd(words)?.let { return it }
        return null
    }

    private fun tokenize(s: String): List<String> =
        Regex("\\w+").findAll(s).map { it.value.lowercase() }.toList()

    private fun tryDelete(
        words: List<String>,
        habitNames: List<String>,
    ): VoiceHabitCommand? {
        if (habitNames.isEmpty()) return null
        val (endIdx, _) = matchPhraseAnywhere(words, DELETE_PHRASES) ?: return null
        val tail = words.drop(endIdx).joinToString(" ").trim()
        if (tail.length < 2 || tail in BANNED_DELETE_TAIL) return null
        val habit = fuzzyResolveHabit(tail, habitNames) ?: return null
        return VoiceHabitCommand.DeleteHabit(habit)
    }

    private fun tryArchive(
        words: List<String>,
        habitNames: List<String>,
    ): VoiceHabitCommand? {
        if (habitNames.isEmpty()) return null
        val (endIdx, _) = matchPhraseAnywhere(words, ARCHIVE_PHRASES) ?: return null
        val tail = words.drop(endIdx).joinToString(" ").trim()
        if (tail.length < 2) return null
        val habit = fuzzyResolveHabit(tail, habitNames) ?: return null
        return VoiceHabitCommand.ArchiveHabit(habit)
    }

    private fun tryMarkDone(
        full: String,
        words: List<String>,
        habitNames: List<String>,
    ): VoiceHabitCommand? {
        if (habitNames.isEmpty()) return null
        if (!hasDoneCue(words, full)) return null
        val (habit, score) = bestHabitInTranscript(full, words, habitNames) ?: return null
        if (score < HABIT_MATCH) return null
        return VoiceHabitCommand.MarkDone(habit)
    }

    private fun tryAdd(words: List<String>): VoiceHabitCommand? {
        val (endIdx, phrase) = matchPhraseAnywhere(words, ADD_PHRASES) ?: return null
        var tail = words.drop(endIdx).joinToString(" ").trim()
        if (tail.startsWith("called ")) tail = tail.removePrefix("called ").trim()
        if (tail.startsWith("habit ")) tail = tail.removePrefix("habit ").trim()
        if (tail.length < 2 || tail in BANNED_ADD_VERBS) return null
        // Avoid treating "add" alone as habit name when phrase was single word "add"
        if (phrase == "add" && tail == "habit") return null
        val titled = tail.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
        return VoiceHabitCommand.AddHabit(titled)
    }

    private fun hasDoneCue(words: List<String>, full: String): Boolean {
        for (w in words) {
            for (cue in DONE_CUES) {
                if (normalizedLevenshteinRatio(w, cue) >= CUE_MATCH) return true
            }
        }
        for (cue in DONE_CUES) {
            if (full.contains(cue)) return true
        }
        if (full.contains("mark ") && full.contains(" done")) return true
        return false
    }

    /**
     * Returns end index (exclusive) in [words] after matched phrase, and the matched phrase string.
     * Prefers longer phrases when scores tie (phrases sorted descending by length).
     */
    private fun matchPhraseAnywhere(words: List<String>, phrases: List<String>): Pair<Int, String>? {
        var bestEnd = 0
        var bestPhrase = ""
        var bestScore = 0.0
        var bestPhraseLen = 0
        var found = false
        for (phrase in phrases.sortedByDescending { it.length }) {
            val parts = phrase.split(' ').filter { it.isNotEmpty() }
            if (parts.isEmpty() || parts.size > words.size) continue
            val lastStart = words.size - parts.size
            for (i in 0..lastStart) {
                val joined = (0 until parts.size).joinToString(" ") { words[i + it] }
                val r = normalizedLevenshteinRatio(joined, phrase)
                if (r < VERB_MATCH) continue
                val better = !found ||
                    r > bestScore + 1e-6 ||
                    (kotlin.math.abs(r - bestScore) < 1e-6 && phrase.length > bestPhraseLen)
                if (better) {
                    found = true
                    bestScore = r
                    bestPhraseLen = phrase.length
                    bestEnd = i + parts.size
                    bestPhrase = phrase
                }
            }
        }
        return if (found) bestEnd to bestPhrase else null
    }

    private fun bestHabitInTranscript(
        full: String,
        words: List<String>,
        habitNames: List<String>,
    ): Pair<String, Double>? {
        val fl = full.lowercase()
        var bestHabit: String? = null
        var bestScore = 0.0
        for (h in habitNames) {
            val hl = h.lowercase()
            if (hl.isEmpty()) continue
            if (fl.contains(hl)) {
                return h to 1.0
            }
            val score = bestBlockRatio(hl, words)
            if (score > bestScore) {
                bestScore = score
                bestHabit = h
            }
        }
        return if (bestHabit != null) bestHabit to bestScore else null
    }

    /** Best normalized Levenshtein ratio between [habit] and any consecutive word block (up to 8 words). */
    private fun bestBlockRatio(habit: String, words: List<String>): Double {
        var best = 0.0
        val maxLen = minOf(8, words.size)
        for (i in words.indices) {
            for (len in 1..maxLen) {
                if (i + len > words.size) break
                val block = words.subList(i, i + len).joinToString(" ")
                val r = normalizedLevenshteinRatio(habit, block)
                if (r > best) best = r
            }
        }
        return best
    }

    private fun fuzzyResolveHabit(raw: String, habitNames: List<String>): String? {
        val r = raw.trim().lowercase()
        if (r.isEmpty()) return null
        habitNames.firstOrNull { it.equals(r, ignoreCase = true) }?.let { return it }
        val viaResolver = HabitNameResolver.resolve(r, habitNames)
        habitNames.firstOrNull { it.equals(viaResolver, ignoreCase = true) }?.let { return it }
        if (flContainsHabit(r, habitNames)) return viaResolver

        var best: String? = null
        var bestScore = HABIT_RESOLVE
        for (h in habitNames) {
            val sc = normalizedLevenshteinRatio(h.lowercase(), r)
            if (sc > bestScore) {
                bestScore = sc
                best = h
            }
        }
        return best
    }

    private fun flContainsHabit(r: String, habitNames: List<String>): Boolean =
        habitNames.any { r.contains(it.lowercase()) }

    private fun normalizedLevenshteinRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = levenshtein(a, b).toDouble()
        return 1.0 - d / max(a.length, b.length).toDouble()
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        var prev = IntArray(n + 1) { it }
        for (i in 1..m) {
            val cur = IntArray(n + 1)
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[n]
    }
}
