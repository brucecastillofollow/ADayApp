package org.bruce.aday.voice.llm

object VoiceLlmPrompts {

    /**
     * TinyLlama-1.1B-Chat-v1.0 style (see HF model card): `<|system|>`, `<|user|>`, `<|assistant|>` blocks.
     */
    fun tinyLlamaChatPrompt(system: String, user: String): String =
        buildString {
            append("<|system|>\n")
            append(system.trim())
            append("\n<|user|>\n")
            append(user.trim())
            append("\n<|assistant|>\n")
        }

    fun systemPrompt(habitNames: List<String>): String {
        val list = habitNames.joinToString(", ").ifBlank { "(none yet)" }
        return buildString {
            append("You map spoken habit commands to ONE JSON object. Output JSON only — no markdown, no text before or after.\n\n")
            append("Schema (all keys required; use null where not applicable):\n")
            append("{\n")
            append("  \"action\": \"none|create_habit|mark_done|delete_habit|archive_habit\",\n")
            append("  \"habit\": \"string\",\n")
            append("  \"amount\": null,\n")
            append("  \"habit_type\": \"yes_no|numerical|null\",\n")
            append("  \"question\": \"\",\n")
            append("  \"notes\": \"\",\n")
            append("  \"color_index\": null,\n")
            append("  \"frequency_kind\": \"daily|every_week|every_other_day|times_per_week|times_per_month|every_n_days|custom|null\",\n")
            append("  \"frequency_num\": null,\n")
            append("  \"frequency_den\": null,\n")
            append("  \"every_n_days\": null,\n")
            append("  \"reminder_on\": false,\n")
            append("  \"reminder_hour\": null,\n")
            append("  \"reminder_minute\": null,\n")
            append("  \"unit\": \"\",\n")
            append("  \"target_value\": null,\n")
            append("  \"target_type\": \"at_least|at_most|null\"\n")
            append("}\n\n")
            append("Rules:\n")
            append("- action=\"none\" if the utterance is not a habit command.\n")
            append("- For mark_done on a measurable habit, set \"amount\" to a positive number (same units as the habit).\n")
            append("- For mark_done on a yes/no habit, set amount null.\n")
            append("- create_habit: set habit_type yes_no or numerical; for numerical set unit, target_value, target_type.\n")
            append("- frequency_kind: daily = every day; every_other_day = every 2 days; times_per_week uses frequency_num; every_n_days uses every_n_days.\n")
            append("- delete_habit removes permanently; archive_habit means the goal is reached / stop tracking.\n")
            append("- Prefer matching habit names from this list when the user refers to an existing habit: ")
            append(list)
            append(".")
        }
    }

    fun userPrompt(transcript: String): String {
        return "User said:\n\"$transcript\"\n\nJSON:"
    }
}
