package org.bruce.aday.voice

object HabitNameResolver {

    fun resolve(mentioned: String, habitNames: List<String>): String {
        val m = mentioned.trim()
        if (m.isEmpty()) return m
        habitNames.firstOrNull { it.equals(m, ignoreCase = true) }?.let { return it }
        val ml = m.lowercase()
        habitNames.firstOrNull { it.lowercase() == ml }?.let { return it }
        habitNames.firstOrNull { it.lowercase().contains(ml) || ml.contains(it.lowercase()) }?.let { return it }
        return m.trim()
    }
}
