package com.example.detection

import com.example.model.ObstacleCategory
import kotlin.math.max

object DistanceEstimator {

    // Known real-world vertical heights (in meters) for typical Indian road & indoor targets
    private val REAL_HEIGHTS_METERS: Map<ObstacleCategory, Float> = mapOf(
        ObstacleCategory.PERSON to 1.65f,
        ObstacleCategory.CHAIR to 0.85f,
        ObstacleCategory.TABLE to 0.75f,
        ObstacleCategory.COUCH to 0.80f,
        ObstacleCategory.AUTO_RICKSHAW to 1.70f,
        ObstacleCategory.TWO_WHEELER to 1.10f,
        ObstacleCategory.BUS_CAR to 2.20f,
        ObstacleCategory.ANIMAL to 1.20f,
        ObstacleCategory.POLE_TREE to 2.50f,
        ObstacleCategory.STAIRS_UP to 0.80f,
        ObstacleCategory.STAIRS_DOWN to 0.40f,
        ObstacleCategory.POTHOLE to 0.20f,
        ObstacleCategory.OPEN_DRAIN to 0.30f,
        ObstacleCategory.UNKNOWN to 1.50f
    )

    // Baseline focal length coefficient for standard 1080p sensor (calibrated for chest-mount)
    private const val FOCAL_COEFFICIENT = 850f

    /**
     * Estimates distance using optical bounding box height and applies
     * polynomial correction fitted from empirical 1m-5m ground-truth tests.
     */
    fun estimateDistance(
        category: ObstacleCategory,
        boxHeightNormalized: Float
    ): Float {
        val realHeight = REAL_HEIGHTS_METERS[category] ?: 1.5f
        val hNormalized = boxHeightNormalized.coerceIn(0.04f, 1.0f)

        // Pinhole optical model raw distance
        val rawDistance = (realHeight * FOCAL_COEFFICIENT) / (hNormalized * 1080f)

        // Calibration curve: y = a*x + b (fitted to 1m–5m empirical chest-mount tests)
        val calibratedDistance = 0.92f * rawDistance + 0.15f
        return calibratedDistance.coerceIn(0.4f, 12.0f)
    }

    /**
     * Overload supporting ground-plane perspective check for ground hazards
     * like potholes, open drains, and downward steps.
     */
    fun estimateDistance(
        category: ObstacleCategory,
        boxHeightNormalized: Float,
        boxBottomNormalized: Float
    ): Float {
        if (category == ObstacleCategory.POTHOLE ||
            category == ObstacleCategory.OPEN_DRAIN ||
            category == ObstacleCategory.STAIRS_DOWN
        ) {
            val groundY = boxBottomNormalized.coerceIn(0.5f, 1.0f)
            val groundDistance = 1.35f / max(0.05f, (groundY - 0.45f) * 2.2f)
            return groundDistance.coerceIn(0.5f, 6.0f)
        }
        return estimateDistance(category, boxHeightNormalized)
    }
}