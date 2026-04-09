package org.bruce.aday.voice

sealed interface VoiceHabitCommand {
    /** Simple yes/no habit with defaults (name + generated question). */
    data class AddHabit(val name: String) : VoiceHabitCommand

    /** Full habit definition from structured parsing / LLM. */
    data class AddHabitDetailed(val details: VoiceHabitCreationDetails) : VoiceHabitCommand

    /**
     * [amount] set for measurable habits (total for today); null toggles yes/no or defers to UI rules.
     */
    data class MarkDone(val habitName: String, val amount: Double? = null) : VoiceHabitCommand

    data class DeleteHabit(val habitName: String) : VoiceHabitCommand
    data class ArchiveHabit(val habitName: String) : VoiceHabitCommand
}
