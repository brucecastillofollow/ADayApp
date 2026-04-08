package org.bruce.aday.voice.llm

import org.bruce.aday.voice.VoiceHabitCommand

object HeuristicVoiceIntent {

    fun match(transcript: String, habitNames: List<String>): VoiceHabitCommand? {
        val t = transcript.trim().lowercase()
        if (t.isEmpty() || habitNames.isEmpty()) return null

        val doneCue = listOf("done", "did", "finished", "completed", "check", "checked").any { w ->
            t.contains(" $w") || t.startsWith(w) || t.endsWith(" $w") || t.contains("$w ")
        }
        if (doneCue) {
            for (h in habitNames) {
                val hl = h.lowercase()
                if (hl.length >= 2 && t.contains(hl)) {
                    return VoiceHabitCommand.MarkDone(h)
                }
            }
        }

        val addCue = t.contains("add ") || t.contains("new habit") || t.contains("start ")
        if (addCue) {
            var rest = t
            listOf("add a habit", "add habit", "add an habit", "add", "new habit", "start").forEach { prefix ->
                if (rest.startsWith(prefix)) {
                    rest = rest.removePrefix(prefix).trim()
                }
            }
            if (rest.length >= 2) {
                val titled = rest.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                }
                return VoiceHabitCommand.AddHabit(titled)
            }
        }
        return null
    }
}
