package org.bruce.aday.voice.llm

import java.util.concurrent.atomic.AtomicBoolean

/**
 * [LocalVoiceRecognizer] may finish the offline speech model before [ListHabitsActivity] exists
 * (app prefetch). We remember that the offline AI model should be offered next time the habits
 * screen is shown.
 */
object PendingTinyLlamaAutoShow {
    private val pending = AtomicBoolean(false)

    fun markPending() {
        pending.set(true)
    }

    fun consumePending(): Boolean = pending.getAndSet(false)
}
