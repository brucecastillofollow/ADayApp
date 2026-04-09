package org.bruce.aday.voice

object VoiceHabitCommandParser {
    fun parse(text: String): VoiceHabitCommand? = VoiceCommandPreParser.tryParse(text, emptyList())
}
