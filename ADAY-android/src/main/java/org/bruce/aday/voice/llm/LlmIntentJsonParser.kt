package org.bruce.aday.voice.llm

import org.bruce.aday.voice.VoiceHabitCommand
import org.json.JSONObject

object LlmIntentJsonParser {

    fun parseToCommand(raw: String, habitNames: List<String>): VoiceHabitCommand? {
        val jsonStr = extractJsonObject(raw) ?: return null
        return try {
            val o = JSONObject(jsonStr)
            val legacyIntent = o.optString("intent").trim().lowercase()
            val action = o.optString("action").trim().lowercase()
            val intent = if (action.isNotBlank()) action else legacyIntent
            val name = o.optString("habit").trim().ifBlank { o.optString("habitName").trim() }
            when (intent) {
                "add" -> {
                    if (name.length < 2) null else VoiceHabitCommand.AddHabit(name)
                }
                "complete" -> {
                    if (name.isEmpty()) null else VoiceHabitCommand.MarkDone(resolveHabitName(name, habitNames))
                }
                "delete" -> null
                "add_habit" -> {
                    if (name.length < 2) null else VoiceHabitCommand.AddHabit(name)
                }
                "mark_done" -> {
                    if (name.isEmpty()) null else VoiceHabitCommand.MarkDone(resolveHabitName(name, habitNames))
                }
                "none", "" -> null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveHabitName(mentioned: String, habitNames: List<String>): String {
        val m = mentioned.trim().lowercase()
        habitNames.firstOrNull { it.equals(mentioned, ignoreCase = true) }?.let { return it }
        habitNames.firstOrNull { it.lowercase() == m }?.let { return it }
        habitNames.firstOrNull { it.lowercase().contains(m) || m.contains(it.lowercase()) }?.let { return it }
        return mentioned.trim()
    }

    fun extractJsonObject(raw: String): String? {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl >= 0) s = s.substring(firstNl + 1)
            val fence = s.lastIndexOf("```")
            if (fence >= 0) s = s.substring(0, fence)
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return s.substring(start, end + 1)
    }
}
