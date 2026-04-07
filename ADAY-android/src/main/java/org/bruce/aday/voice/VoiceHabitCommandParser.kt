package org.bruce.aday.voice

sealed interface VoiceHabitCommand {
    data class AddHabit(val name: String) : VoiceHabitCommand
    data class MarkDone(val habitName: String) : VoiceHabitCommand
}

object VoiceHabitCommandParser {
    fun parse(text: String): VoiceHabitCommand? {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return null

        // Prefer completion when utterance ends with "done" so "add exercise done" is not a habit name.
        if (normalized.endsWith(" done") || normalized.endsWith(" as done")) {
            extractDoneHabitName(normalized)?.let { return VoiceHabitCommand.MarkDone(it) }
        }

        extractAddHabitName(normalized)?.let { return VoiceHabitCommand.AddHabit(it) }
        extractDoneHabitName(normalized)?.let { return VoiceHabitCommand.MarkDone(it) }
        return null
    }

    private fun extractAddHabitName(text: String): String? {
        val patterns = listOf(
            """^(add|create)\s+(a\s+)?habit\s+(called\s+)?(.+)$""".toRegex(),
            """^new\s+habit\s+(.+)$""".toRegex(),
            // Casual phrasing (offline ASR often misses the word "habit")
            """^(add|create|start)\s+(.+)$""".toRegex(),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues.last().trim()
            if (name.length >= 2 && name !in BANNED_ADD_VERBS) return name
        }
        return null
    }

    /** Avoid treating ultra-short commands like "add it" as habit names when ASR is noisy. */
    private val BANNED_ADD_VERBS = setOf("it", "one", "a", "the")

    private fun extractDoneHabitName(text: String): String? {
        val patterns = listOf(
            """^(mark\s+)?(.+)\s+(as\s+)?done$""".toRegex(),
            """^i\s+(did|completed)\s+(.+)$""".toRegex(),
            """^complete\s+(.+)$""".toRegex(),
            """^finished\s+(.+)$""".toRegex(),
            """^(.+)\s+finished$""".toRegex(),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues.last().trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }
}

