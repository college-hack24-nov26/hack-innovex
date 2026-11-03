package com.example.model

enum class VibrationPattern {
    NONE,
    RAPID_DANGER,   // Urgent sharp pulsing for immediate danger
    CAUTION_PULSE,  // Double vibration for obstacle warning
    CONFIRM_SHORT,  // Single light haptic click
    ARRIVAL_SUCCESS // Distinctive 3-pulse melody
}

enum class ToneType {
    NONE,
    HIGH_DANGER,      // Urgent high frequency beep
    WARNING_DOUBLE,   // Double warning beep
    NAV_CHIME,        // Pleasant turn chime
    SUCCESS_CHIME     // Arrival or scan success chime
}

data class AlertItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val priority: Int, // 1 = DANGER, 2 = NAVIGATION, 3 = ASSISTANT, 4 = READER
    val message: String,
    val interrupts: Boolean = (priority == PRIORITY_DANGER),
    val vibration: VibrationPattern = VibrationPattern.NONE,
    val tone: ToneType = ToneType.NONE,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val PRIORITY_DANGER = 1
        const val PRIORITY_NAVIGATION = 2
        const val PRIORITY_ASSISTANT = 3
        const val PRIORITY_READER = 4
    }
}
