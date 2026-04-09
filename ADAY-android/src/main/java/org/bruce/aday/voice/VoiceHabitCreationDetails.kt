package org.bruce.aday.voice

import org.bruce.aday.core.models.Frequency
import org.bruce.aday.core.models.HabitType
import org.bruce.aday.core.models.NumericalHabitType
import org.bruce.aday.core.models.PaletteColor
import org.bruce.aday.core.models.Reminder

/**
 * Fields for creating a habit from voice (rule-based LLM JSON or future pre-parser).
 * Mirrors the edit screen: yes/no vs measurable, frequency, reminder, notes, etc.
 */
data class VoiceHabitCreationDetails(
    val name: String,
    val habitType: HabitType = HabitType.YES_NO,
    val question: String? = null,
    val notes: String? = null,
    val color: PaletteColor? = null,
    val frequency: Frequency = Frequency.DAILY,
    val reminder: Reminder? = null,
    val unit: String = "",
    val targetValue: Double = 0.0,
    val targetType: NumericalHabitType = NumericalHabitType.AT_LEAST,
)
