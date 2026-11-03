package com.example.detection

import android.graphics.RectF
import com.example.model.Direction
import com.example.model.ObstacleCategory
import java.util.Locale
import kotlin.math.tan

data class SpatialMeasurement(
    val forwardDistanceMeters: Float, // Z axis (ahead)
    val lateralOffsetMeters: Float,   // X axis (- left, + right)
    val isInCollisionCorridor: Boolean,
    val direction: Direction
)

object PrecisionDistanceEstimator {

    // Standard phone camera Field of View (FOV)
    private const val VERTICAL_FOV_RAD = 1.05f    // ~60 degrees
    private const val HORIZONTAL_FOV_RAD = 1.30f  // ~74 degrees

    // Assumed height of phone held at chest level (meters)
    private const val CAMERA_HEIGHT_METERS = 1.35f

    // Total width of human walking clearance (shoulders + white cane sweep = 1.2m)
    private const val CORRIDOR_HALF_WIDTH_METERS = 0.55f

    /**
     * Calculates metric distance by intersecting camera ray through object bottom edge with the ground plane.
     */
    fun computeSpatialPosition(
        boundingBox: RectF,
        category: ObstacleCategory,
        phonePitchRadians: Float
    ): SpatialMeasurement {
        val bottomY = boundingBox.bottom.coerceIn(0.1f, 1.0f)
        val centerX = boundingBox.centerX().coerceIn(0f, 1f)

        // 1. Calculate ray angle from camera center to bottom contact point
        // Pixel angle relative to optical center (-0.5 to +0.5)
        val pixelAngleY = (bottomY - 0.5f) * VERTICAL_FOV_RAD
        val totalGroundAngle = phonePitchRadians + pixelAngleY

        // 2. Ground raycast distance (Z axis)
        var forwardDistance = if (totalGroundAngle > 0.08f) {
            CAMERA_HEIGHT_METERS / tan(totalGroundAngle)
        } else {
            // Optical height fallback if bottom edge is near the horizon
            calculateHeightBasedFallback(category, boundingBox.height())
        }

        // Clamp to sensible physical walking bounds
        forwardDistance = forwardDistance.coerceIn(0.4f, 12.0f)

        // 3. Lateral offset calculation (X axis)
        val pixelAngleX = (centerX - 0.5f) * HORIZONTAL_FOV_RAD
        val lateralOffset = forwardDistance * tan(pixelAngleX)

        // 4. Collision corridor evaluation:
        // Only objects within +/- 0.55m of your center trajectory will cause a physical collision
        val inCorridor = (lateralOffset >= -CORRIDOR_HALF_WIDTH_METERS && lateralOffset <= CORRIDOR_HALF_WIDTH_METERS)

        val direction = when {
            lateralOffset < -CORRIDOR_HALF_WIDTH_METERS -> Direction.LEFT
            lateralOffset > CORRIDOR_HALF_WIDTH_METERS -> Direction.RIGHT
            else -> Direction.CENTER
        }

        return SpatialMeasurement(
            forwardDistanceMeters = String.format(Locale.US, "%.1f", forwardDistance).toFloat(),
            lateralOffsetMeters = String.format(Locale.US, "%.2f", lateralOffset).toFloat(),
            isInCollisionCorridor = inCorridor,
            direction = direction
        )
    }

    private fun calculateHeightBasedFallback(category: ObstacleCategory, boxHeight: Float): Float {
        val realH = when (category) {
            ObstacleCategory.PERSON -> 1.65f
            ObstacleCategory.CHAIR -> 0.85f
            ObstacleCategory.TABLE -> 0.75f
            ObstacleCategory.AUTO_RICKSHAW -> 1.70f
            ObstacleCategory.TWO_WHEELER -> 1.10f
            else -> 1.90f
        }
        val safeHeight = boxHeight.coerceIn(0.05f, 1.0f)
        return ((realH * 850f) / (safeHeight * 1080f)).coerceIn(1.0f, 12.0f)
    }
}