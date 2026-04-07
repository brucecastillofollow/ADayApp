package org.bruce.aday.voice

sealed interface VoiceHabitCommand {
    data class AddHabit(val name: String) : VoiceHabitCommand
    data class MarkDone(val habitName: String) : VoiceHabitCommand
}

object VoiceHabitCommandParser {
    fun parse(text: String): VoiceHabitCommand? {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return null

        extractAddHabitName(normalized)?.let { return VoiceHabitCommand.AddHabit(it) }
        extractDoneHabitName(normalized)?.let { return VoiceHabitCommand.MarkDone(it) }
        return null
    }

    private fun extractAddHabitName(text: String): String? {
        val patterns = listOf(
            """^(add|create)\s+(a\s+)?habit\s+(called\s+)?(.+)$""".toRegex(),
            """^new\s+habit\s+(.+)$""".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues.last().trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    private fun extractDoneHabitName(text: String): String? {
        val patterns = listOf(
            """^(mark\s+)?(.+)\s+(as\s+)?done$""".toRegex(),
            """^i\s+(did|completed)\s+(.+)$""".toRegex(),
            """^complete\s+(.+)$""".toRegex()
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues.last().trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }
}

