package org.bruce.aday.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandPreParserTest {

    @Test
    fun delete_habit() {
        val cmd = VoiceCommandPreParser.tryParse("delete habit morning run", listOf("Morning run")) as VoiceHabitCommand.DeleteHabit
        assertEquals("Morning run", cmd.habitName)
    }

    @Test
    fun archive_habit() {
        val cmd = VoiceCommandPreParser.tryParse("archive learn piano", listOf()) as VoiceHabitCommand.ArchiveHabit
        assertEquals("learn piano", cmd.habitName)
    }

    @Test
    fun mark_done_with_amount() {
        val cmd = VoiceCommandPreParser.tryParse(
            "log 2 liters for water",
            listOf("Water"),
        ) as VoiceHabitCommand.MarkDone
        assertEquals("Water", cmd.habitName)
        assertEquals(2.0, cmd.amount!!, 0.001)
    }
}
