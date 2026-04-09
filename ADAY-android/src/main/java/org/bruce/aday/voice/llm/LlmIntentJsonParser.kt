package org.bruce.aday.voice.llm

import org.bruce.aday.core.models.Frequency
import org.bruce.aday.core.models.HabitType
import org.bruce.aday.core.models.NumericalHabitType
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.models.Reminder
import org.bruce.aday.core.models.WeekdayList
import org.bruce.aday.voice.HabitNameResolver
import org.bruce.aday.voice.VoiceHabitCommand
import org.bruce.aday.voice.VoiceHabitCreationDetails
import org.json.JSONObject

object LlmIntentJsonParser {

    fun parseToCommand(raw: String, habitNames: List<String>): VoiceHabitCommand? {
        val jsonStr = extractJsonObject(raw) ?: return null
        return try {
            val o = JSONObject(jsonStr)
            val legacyIntent = o.optString("intent").trim().lowercase()
            val action = o.optString("action").trim().lowercase()
            val intent = action.ifBlank { legacyIntent }
            when (intent) {
                "none", "" -> null
                "create_habit", "add", "add_habit" -> parseCreateHabit(o)
                "mark_done", "complete" -> parseMarkDone(o, habitNames)
                "delete_habit", "delete" -> parseDelete(o, habitNames)
                "archive_habit", "archive" -> parseArchive(o, habitNames)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCreateHabit(o: JSONObject): VoiceHabitCommand? {
        val details = parseCreationDetails(o) ?: return null
        return VoiceHabitCommand.AddHabitDetailed(details)
    }

    private fun parseCreationDetails(o: JSONObject): VoiceHabitCreationDetails? {
        val name = o.optString("habit").trim().ifBlank { o.optString("habit_name").trim() }
        if (name.length < 2) return null
        val habitTypeStr = o.optString("habit_type").trim().lowercase()
        val habitType = when (habitTypeStr) {
            "numerical", "measurable", "number" -> HabitType.NUMERICAL
            else -> HabitType.YES_NO
        }
        val question = o.optString("question").trim().ifBlank { null }
        val notes = o.optString("notes").trim().ifBlank { null }
        val colorIdx = o.optInt("color_index", -1)
        val color = if (colorIdx in 0..15) PaletteColor(colorIdx) else null
        val frequency = frequencyFromJson(o)
        val reminder = reminderFromJson(o)
        val unit = o.optString("unit").trim()
        val targetValue = o.optDouble("target_value", 0.0)
        val targetType = when (o.optString("target_type").trim().lowercase()) {
            "at_most" -> NumericalHabitType.AT_MOST
            else -> NumericalHabitType.AT_LEAST
        }
        return VoiceHabitCreationDetails(
            name = name,
            habitType = habitType,
            question = question,
            notes = notes,
            color = color,
            frequency = frequency,
            reminder = reminder,
            unit = if (habitType == HabitType.NUMERICAL) unit else "",
            targetValue = if (habitType == HabitType.NUMERICAL) targetValue else 0.0,
            targetType = targetType,
        )
    }

    private fun frequencyFromJson(o: JSONObject): Frequency {
        val kind = o.optString("frequency_kind").trim().lowercase()
        val num = o.optInt("frequency_num", 1).coerceAtLeast(1)
        val den = o.optInt("frequency_den", 1).coerceAtLeast(1)
        return when (kind) {
            "daily" -> Frequency.DAILY
            "every_week" -> Frequency(1, 7)
            "every_other_day" -> Frequency(1, 2)
            "times_per_week" -> Frequency(num.coerceIn(1, 7), 7)
            "times_per_month" -> Frequency(num.coerceAtLeast(1), 30)
            "every_n_days" -> Frequency(1, o.optInt("every_n_days", 1).coerceAtLeast(1))
            "custom" -> Frequency(num, den)
            else -> Frequency.DAILY
        }
    }

    private fun reminderFromJson(o: JSONObject): Reminder? {
        if (!o.optBoolean("reminder_on", false)) return null
        val hour = o.optInt("reminder_hour", -1)
        val minute = o.optInt("reminder_minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return null
        return Reminder(hour, minute, WeekdayList.EVERY_DAY)
    }

    private fun parseMarkDone(o: JSONObject, habitNames: List<String>): VoiceHabitCommand? {
        val name = o.optString("habit").trim().ifBlank { o.optString("habit_name").trim() }
        if (name.isEmpty()) return null
        val resolved = HabitNameResolver.resolve(name, habitNames)
        val amount = parseOptionalPositiveAmount(o)
        return VoiceHabitCommand.MarkDone(resolved, amount)
    }

    private fun parseOptionalPositiveAmount(o: JSONObject): Double? {
        if (!o.has("amount") || o.isNull("amount")) return null
        val raw = o.get("amount")
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: return null
            else -> return null
        }
        return value.takeIf { it > 0 }
    }

    private fun parseDelete(o: JSONObject, habitNames: List<String>): VoiceHabitCommand? {
        val name = o.optString("habit").trim().ifBlank { o.optString("habit_name").trim() }
        if (name.length < 2) return null
        return VoiceHabitCommand.DeleteHabit(HabitNameResolver.resolve(name, habitNames))
    }

    private fun parseArchive(o: JSONObject, habitNames: List<String>): VoiceHabitCommand? {
        val name = o.optString("habit").trim().ifBlank { o.optString("habit_name").trim() }
        if (name.length < 2) return null
        return VoiceHabitCommand.ArchiveHabit(HabitNameResolver.resolve(name, habitNames))
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
