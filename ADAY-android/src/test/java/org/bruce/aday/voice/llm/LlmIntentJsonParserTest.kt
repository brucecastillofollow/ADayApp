package org.bruce.aday.voice.llm

import org.bruce.aday.voice.VoiceHabitCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmIntentJsonParserTest {

    @Test
    fun extractJsonObject_stripsFence() {
        val raw = "```json\n" + "{\"intent\":\"none\",\"habitName\":\"\"}\n" + "```"
        assertEquals(
            "{\"intent\":\"none\",\"habitName\":\"\"}",
            LlmIntentJsonParser.extractJsonObject(raw),
        )
    }

    @Test
    fun parseToCommand_markDone() {
        val json = """{"action":"mark_done","habit":"Drink water"}"""
        val cmd = LlmIntentJsonParser.parseToCommand(json, listOf("Drink water", "Run"))
        val done = cmd as? VoiceHabitCommand.MarkDone
        assertTrue(done != null)
        assertEquals("Drink water", done!!.habitName)
    }

    @Test
    fun parseToCommand_deleteHabit() {
        val json = """{"action":"delete_habit","habit":"Run"}"""
        val cmd = LlmIntentJsonParser.parseToCommand(json, listOf("Drink water", "Run"))
        val del = cmd as? VoiceHabitCommand.DeleteHabit
        assertTrue(del != null)
        assertEquals("Run", del!!.habitName)
    }
}
