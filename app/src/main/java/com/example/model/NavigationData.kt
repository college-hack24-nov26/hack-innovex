package com.example.model

enum class ManeuverType(val iconName: String, val spokenPrefix: String) {
    DEPART("straight", "Start walking"),
    STRAIGHT("straight", "Continue straight"),
    TURN_LEFT("turn_left", "Turn left"),
    TURN_RIGHT("turn_right", "Turn right"),
    SLIGHT_LEFT("slight_left", "Bear slightly left"),
    SLIGHT_RIGHT("slight_right", "Bear slightly right"),
    ARRIVE("flag", "Arriving at destination")
}

data class NavStep(
    val stepIndex: Int,
    val instruction: String,
    val distanceMeters: Int,
    val maneuver: ManeuverType,
    val streetName: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class NavigationData(
    val destination: String = "",
    val isNavigating: Boolean = false,
    val totalDistanceMeters: Int = 0,
    val remainingDistanceMeters: Int = 0,
    val estimatedDurationMinutes: Int = 0,
    val currentStepIndex: Int = 0,
    val steps: List<NavStep> = emptyList(),
    val isOffRoute: Boolean = false,
    val currentStreet: String = "Locating GPS address...",
    val currentSpeedKmH: Float = 0f,
    val currentHeading: Float = 0f,
    val userLatitude: Double = 0.0,
    val userLongitude: Double = 0.0,
    val destLatitude: Double = 0.0,
    val destLongitude: Double = 0.0,
    val googleMapsUrl: String = "",
    val isArrived: Boolean = false
) {
    val currentStep: NavStep?
        get() = steps.getOrNull(currentStepIndex)

    val nextStep: NavStep?
        get() = steps.getOrNull(currentStepIndex + 1)
}