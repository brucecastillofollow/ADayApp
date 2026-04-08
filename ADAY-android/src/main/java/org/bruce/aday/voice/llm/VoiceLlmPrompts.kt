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
            append("You are a command parser for a habit tracking app.\n\n")
            append("Convert the user input into JSON with this format:\n")
            append("{\n")
            append("  \"action\": \"add | delete | complete\",\n")
            append("  \"habit\": \"string\",\n")
            append("  \"value\": \"optional\"\n")
            append("}\n\n")
            append("Rules:\n")
            append("- Output one JSON object only.\n")
            append("- No markdown, no explanation.\n")
            append("- If unsure, use action=\"complete\" with best habit guess or action=\"add\".\n")
            append("- Known habits: $list")
        }
    }

    fun userPrompt(transcript: String): String {
        return "User input:\n\"$transcript\"\n\nOutput:"
    }
}
