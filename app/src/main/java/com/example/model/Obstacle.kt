package com.example.model

import android.graphics.RectF
import java.util.Locale
import java.util.UUID

enum class AppLanguage(val code: String, val displayName: String, val locale: Locale) {
    ENGLISH("en", "English", Locale.US),
    HINDI("hi", "हिन्दी (Hindi)", Locale("hi", "IN")),
    MARATHI("mr", "मराठी (Marathi)", Locale("mr", "IN"))
}

enum class Direction(val spoken: String) {
    LEFT("on your left"),
    CENTER("ahead"),
    RIGHT("on your right");

    fun getSpoken(lang: AppLanguage): String = when (lang) {
        AppLanguage.MARATHI -> when (this) {
            LEFT -> "तुमच्या डाव्या बाजूला"
            CENTER -> "समोर"
            RIGHT -> "तुमच्या उजव्या बाजूला"
        }
        AppLanguage.HINDI -> when (this) {
            LEFT -> "आपके बाईं ओर"
            CENTER -> "सामने"
            RIGHT -> "आपके दाईं ओर"
        }
        AppLanguage.ENGLISH -> this.spoken
    }
}

enum class ObstacleSeverity {
    DANGER,  // < 1.2m
    WARNING, // 1.2m - 2.5m
    INFO     // > 2.5m
}

enum class ObstacleCategory(val displayLabel: String) {
    PERSON("Person"),
    CHAIR("Chair"),
    TABLE("Table"),
    COUCH("Couch"),
    ANIMAL("Animal"),
    AUTO_RICKSHAW("Auto Rickshaw"),
    TWO_WHEELER("Two Wheeler"),
    BUS_CAR("Vehicle"),
    POLE_TREE("Pole"),
    STAIRS_UP("Stairs up"),
    STAIRS_DOWN("Drop-off"),
    POTHOLE("Pothole"),
    OPEN_DRAIN("Open drain"),
    WALL("Wall"),
    UNKNOWN("Obstacle")
}
data class Obstacle(
    val id: String = UUID.randomUUID().toString(),
    val trackId: Int = 0,
    val category: ObstacleCategory = ObstacleCategory.UNKNOWN,
    val label: String = "Obstacle",
    val distanceMeters: Float,
    val approachSpeedMps: Float = 0.0f,
    val direction: Direction,
    val severity: ObstacleSeverity,
    val boundingBox: RectF = RectF(0.25f, 0.25f, 0.75f, 0.85f),
    val confidence: Float = 0.90f,
    val consecutiveHits: Int = 1,
    val isDistanceUncertain: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {

    fun getSpokenObjectName(lang: AppLanguage): String = when (lang) {
        AppLanguage.MARATHI -> when (category) {
            ObstacleCategory.PERSON -> "व्यक्ती"
            ObstacleCategory.CHAIR -> "खुर्ची"
            ObstacleCategory.TABLE -> "टेबल"
            ObstacleCategory.COUCH -> "सोफा"
            ObstacleCategory.ANIMAL -> "प्राणी"
            ObstacleCategory.AUTO_RICKSHAW -> "ऑटो रिक्षा"
            ObstacleCategory.TWO_WHEELER -> "दुचाकी गाडी"
            ObstacleCategory.BUS_CAR -> "वाहन"
            ObstacleCategory.POLE_TREE -> "खांब किंवा झाड"
            ObstacleCategory.STAIRS_UP -> "वर जाणाऱ्या पायऱ्या"
            ObstacleCategory.STAIRS_DOWN -> "पायऱ्या किंवा खड्डा"
            ObstacleCategory.POTHOLE -> "खड्डा"
            ObstacleCategory.OPEN_DRAIN -> "उघडी गटार"
            ObstacleCategory.WALL -> "भिंत"
            ObstacleCategory.UNKNOWN -> if (label.isNotBlank() && !label.equals("Obstacle", true)) label else "अडथळा"
        }
        AppLanguage.HINDI -> when (category) {
            ObstacleCategory.PERSON -> "व्यक्ति"
            ObstacleCategory.CHAIR -> "कुर्सी"
            ObstacleCategory.TABLE -> "टेबल"
            ObstacleCategory.COUCH -> "सोफा"
            ObstacleCategory.ANIMAL -> "जानवर"
            ObstacleCategory.AUTO_RICKSHAW -> "ऑटो रिक्शा"
            ObstacleCategory.TWO_WHEELER -> "दोपहिया वाहन"
            ObstacleCategory.BUS_CAR -> "गाड़ी"
            ObstacleCategory.POLE_TREE -> "खंभा या पेड़"
            ObstacleCategory.STAIRS_UP -> "ऊपर जाने की सीढ़ियां"
            ObstacleCategory.STAIRS_DOWN -> "सीढ़ियां या गड्ढा"
            ObstacleCategory.POTHOLE -> "गड्ढा"
            ObstacleCategory.OPEN_DRAIN -> "खुला नाला"
            ObstacleCategory.WALL -> "दीवार"
            ObstacleCategory.UNKNOWN -> if (label.isNotBlank() && !label.equals("Obstacle", true)) label else "रुकावट"
        }
        AppLanguage.ENGLISH -> {
            if (label.isNotBlank() && !label.equals("Obstacle", ignoreCase = true)) {
                label
            } else {
                category.displayLabel
            }
        }
    }

    fun toSpokenAlert(lang: AppLanguage = AppLanguage.ENGLISH): String {
        val distFormatted = String.format(Locale.US, "%.1f", distanceMeters)
        val dirSpoken = direction.getSpoken(lang)
        val objectName = getSpokenObjectName(lang)

        // 1. Camera lens covered/blocked check
        if (label.equals("Camera blocked", ignoreCase = true)) {
            return when (lang) {
                AppLanguage.MARATHI -> "कॅमेरा लेन्स झाकलेली आहे. दृश्य मार्गदर्शन उपलब्ध नाही."
                AppLanguage.HINDI -> "कैमरा लेंस ढका हुआ है। दृश्य मार्गदर्शन उपलब्ध नहीं है।"
                AppLanguage.ENGLISH -> "Camera lens is covered or blocked. Visual guidance unavailable."
            }
        }

        // 2. Downward stairs & drop-off
        if (category == ObstacleCategory.STAIRS_DOWN || label.contains("Drop-off", ignoreCase = true)) {
            return when (lang) {
                AppLanguage.MARATHI -> "सावधान! पुढे पायऱ्या किंवा खड्डा आहे, थांबा!"
                AppLanguage.HINDI -> "सावधान! आगे सीढ़ियां या गड्ढा है, रुकिए!"
                AppLanguage.ENGLISH -> "Warning! Drop-off or stairs down ahead. Stop!"
            }
        }

        // 3. Open drain
        if (category == ObstacleCategory.OPEN_DRAIN || label.contains("drain", ignoreCase = true)) {
            return when (lang) {
                AppLanguage.MARATHI -> "सावधान! पुढे $distFormatted मीटरवर उघडी गटार आहे, थांबा!"
                AppLanguage.HINDI -> "सावधान! आगे $distFormatted मीटर पर खुला नाला है, रुकिए!"
                AppLanguage.ENGLISH -> "Warning! Open drain ahead, $distFormatted meters. Stop!"
            }
        }

        // 4. Fast-approaching emergency
        if (approachSpeedMps > 1.2f && distanceMeters <= 3.5f) {
            return when (lang) {
                AppLanguage.MARATHI -> "धोका! वेगाने येणारे $objectName $dirSpoken $distFormatted मीटरवर आहे, थांबा!"
                AppLanguage.HINDI -> "खतरा! तेजी से आता $objectName $dirSpoken $distFormatted मीटर पर है, रुकिए!"
                AppLanguage.ENGLISH -> "Danger! Fast approaching $objectName, $distFormatted meters ${direction.spoken}. Stop!"
            }
        }

        // 5. Speaks the actual detected object name (Person, Chair, Vehicle, Two Wheeler, etc.)
        return when (lang) {
            AppLanguage.MARATHI -> when (severity) {
                ObstacleSeverity.DANGER -> "थांबा! $dirSpoken $objectName, $distFormatted मीटर."
                ObstacleSeverity.WARNING -> "सावधान, $dirSpoken $objectName, $distFormatted मीटर."
                ObstacleSeverity.INFO -> "$dirSpoken $objectName, $distFormatted मीटर."
            }
            AppLanguage.HINDI -> when (severity) {
                ObstacleSeverity.DANGER -> "रुकिए! $dirSpoken $objectName, $distFormatted मीटर।"
                ObstacleSeverity.WARNING -> "सावधान, $dirSpoken $objectName, $distFormatted मीटर।"
                ObstacleSeverity.INFO -> "$dirSpoken $objectName, $distFormatted मीटर।"
            }
            AppLanguage.ENGLISH -> when (severity) {
                ObstacleSeverity.DANGER -> {
                    if (direction == Direction.CENTER) {
                        "Stop! $objectName directly ahead, $distFormatted meters."
                    } else {
                        "Stop! $objectName $distFormatted meters ${direction.spoken}."
                    }
                }
                ObstacleSeverity.WARNING -> {
                    if (direction == Direction.CENTER) {
                        "Caution, $objectName ahead, $distFormatted meters."
                    } else {
                        "$objectName ${direction.spoken}, $distFormatted meters."
                    }
                }
                ObstacleSeverity.INFO -> {
                    "$objectName ${direction.spoken}, $distFormatted meters."
                }
            }
        }
    }
}