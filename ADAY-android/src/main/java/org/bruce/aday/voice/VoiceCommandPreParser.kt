package org.bruce.aday.voice

/**
 * High-confidence rule-based parse. Returns a command only when intent + required parameters
 * are clear without LLM. If this returns null, the pipeline may try heuristics, then local LLM.
 */
object VoiceCommandPreParser {

    fun tryParse(transcript: String, habitNames: List<String>): VoiceHabitCommand? {
        val normalized = transcript.trim().lowercase()
        if (normalized.isEmpty()) return null

        tryDelete(normalized, habitNames)?.let { return it }
        tryArchive(normalized, habitNames)?.let { return it }
        tryMarkDoneWithAmount(normalized, habitNames)?.let { return it }

        if (normalized.endsWith(" done") || normalized.endsWith(" as done")) {
            extractDoneHabitName(normalized)?.let { name ->
                return VoiceHabitCommand.MarkDone(resolveName(name, habitNames))
            }
        }

        extractAddHabitName(normalized)?.let { return VoiceHabitCommand.AddHabit(it) }
        extractDoneHabitName(normalized)?.let { name ->
            return VoiceHabitCommand.MarkDone(resolveName(name, habitNames))
        }
        return null
    }

    private fun resolveName(raw: String, habitNames: List<String>): String =
        if (habitNames.isEmpty()) raw.trim() else HabitNameResolver.resolve(raw, habitNames)

    private fun tryDelete(text: String, habitNames: List<String>): VoiceHabitCommand? {
        val patterns = listOf(
            """^(delete|remove|erase|trash)\s+(the\s+)?(habit\s+)?(.+)$""".toRegex(),
            """^(get\s+rid\s+of)\s+(the\s+)?(habit\s+)?(.+)$""".toRegex(),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val raw = match.groupValues.last().trim()
            if (raw.length < 2) continue
            if (raw in BANNED_DELETE_OBJECTS) continue
            return VoiceHabitCommand.DeleteHabit(resolveName(raw, habitNames))
        }
        return null
    }

    private val BANNED_DELETE_OBJECTS = setOf("it", "this", "that", "one")

    private fun tryArchive(text: String, habitNames: List<String>): VoiceHabitCommand? {
        val p1 = """^archive\s+(the\s+)?(habit\s+)?(.+)$""".toRegex()
        p1.find(text)?.let {
            val raw = it.groupValues[3].trim()
            if (raw.length >= 2) return VoiceHabitCommand.ArchiveHabit(resolveName(raw, habitNames))
        }
        val p2 = """^(?:i\s+)?(?:have\s+)?achieved\s+(.+)$""".toRegex()
        p2.find(text)?.let {
            val raw = it.groupValues[1].trim()
            if (raw.length >= 2) return VoiceHabitCommand.ArchiveHabit(resolveName(raw, habitNames))
        }
        val p3 = """^(.+?)\s+is\s+achieved$""".toRegex()
        p3.find(text)?.let {
            val raw = it.groupValues[1].trim()
            if (raw.length >= 2) return VoiceHabitCommand.ArchiveHabit(resolveName(raw, habitNames))
        }
        val p4 = """^mark\s+(.+?)\s+as\s+archived$""".toRegex()
        p4.find(text)?.let {
            val raw = it.groupValues[1].trim()
            if (raw.length >= 2) return VoiceHabitCommand.ArchiveHabit(resolveName(raw, habitNames))
        }
        return null
    }

    private fun tryMarkDoneWithAmount(text: String, habitNames: List<String>): VoiceHabitCommand? {
        val unitPattern = """(?:hours?|hrs?|minutes?|mins?|min|hour|km|kilometers?|kilometres?|miles?|mi|liters?|litres?|l)"""
        val patterns = listOf(
            """^(?:log|logged|record|recorded)\s+(\d+(?:\.\d+)?)\s*(?:$unitPattern)?\s+(?:for|on|with)\s+(.+)$""".toRegex(),
            """^(\d+(?:\.\d+)?)\s+$unitPattern\s+(?:for|of|on|with)\s+(.+)$""".toRegex(),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val amount = match.groupValues[1].toDoubleOrNull() ?: continue
            val habitPart = match.groupValues[2].trim()
            if (habitPart.length < 2) continue
            if (amount <= 0.0) continue
            return VoiceHabitCommand.MarkDone(resolveName(habitPart, habitNames), amount)
        }
        return null
    }

    private fun extractAddHabitName(text: String): String? {
        val patterns = listOf(
            """^(add|create)\s+(a\s+)?habit\s+(called\s+)?(.+)$""".toRegex(),
            """^new\s+habit\s+(.+)$""".toRegex(),
            """^(add|create|start)\s+(.+)$""".toRegex(),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val name = match.groupValues.last().trim()
            if (name.length >= 2 && name !in BANNED_ADD_VERBS) return name
        }
        return null
    }

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
