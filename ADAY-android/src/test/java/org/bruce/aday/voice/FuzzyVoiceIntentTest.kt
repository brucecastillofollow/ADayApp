package org.bruce.aday.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyVoiceIntentTest {

    @Test
    fun mark_done_fuzzy_habit() {
        val cmd = FuzzyVoiceIntent.tryMatch(
            "i finished mornig run today",
            listOf("Morning run"),
        ) as VoiceHabitCommand.MarkDone
        assertEquals("Morning run", cmd.habitName)
    }

    @Test
    fun delete_fuzzy_verb() {
        val cmd = FuzzyVoiceIntent.tryMatch(
            "pleese remove warter",
            listOf("Water"),
        ) as VoiceHabitCommand.DeleteHabit
        assertEquals("Water", cmd.habitName)
    }

    @Test
    fun no_match_without_cues() {
        assertNull(FuzzyVoiceIntent.tryMatch("random words", listOf("Water")))
    }

    @Test
    fun add_after_phrase() {
        val cmd = FuzzyVoiceIntent.tryMatch(
            "new habit drink tea",
            emptyList(),
        ) as VoiceHabitCommand.AddHabit
        assertTrue(cmd.name.contains("tea", ignoreCase = true))
    }
}
