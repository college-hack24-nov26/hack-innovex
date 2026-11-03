package com.example.detection

import android.graphics.RectF
import com.example.model.Direction
import com.example.model.Obstacle
import com.example.model.ObstacleCategory
import com.example.model.ObstacleSeverity
import java.util.Locale
import kotlin.math.abs
import kotlin.math.tan

data class GeometricHazard(
    val type: HazardType,
    val distanceMeters: Float,
    val direction: Direction,
    val severity: ObstacleSeverity
)

enum class HazardType {
    POSITIVE_OBSTACLE, // Wall, barrier, solid object rising from floor
    NEGATIVE_DROP_OFF  // Open drain, stairs down, curb drop-off
}

class GeometricReasoner(
    private val cameraHeightMeters: Float = 1.35f, // Chest-mount height
    private val verticalFovRad: Float = 1.05f       // ~60 degrees
) {
    /**
     * NADAKA GEOMETRIC REASONING:
     * Compares the measured Depth Anything V2 depth grid against the
     * theoretical mathematical ground plane derived from the phone's IMU pitch angle.
     */
    fun analyzeGeometry(
        depthMap: DepthMap,
        phonePitchRad: Float
    ): List<GeometricHazard> {
        val hazards = mutableListOf<GeometricHazard>()
        val gridH = depthMap.gridHeight
        val gridW = depthMap.gridWidth

        // Inspect the center walking corridor (middle 30% of camera width)
        val centerColStart = (gridW * 0.35f).toInt()
        val centerColEnd = (gridW * 0.65f).toInt()

        // 1. Scan rows from bottom (near feet) upward (into the distance)
        for (row in (gridH - 1) downTo (gridH * 0.35f).toInt() step 4) {
            val normY = row.toFloat() / gridH
            val pixelAngleY = (normY - 0.5f) * verticalFovRad
            val totalAngle = phonePitchRad + pixelAngleY

            // Theoretical distance to flat floor at this row according to IMU
            val expectedGroundDist = if (totalAngle > 0.08f) {
                cameraHeightMeters / tan(totalAngle)
            } else {
                continue
            }

            // Sample measured depth across the center walking corridor
            var measuredSum = 0f
            var sampleCount = 0
            for (col in centerColStart until centerColEnd step 4) {
                val rawDepth = depthMap.depthGrid[row][col]
                // Convert model disparity to physical meters
                val measuredMeters = (1.0f / (rawDepth * 0.85f + 0.15f)) * 3.8f
                measuredSum += measuredMeters
                sampleCount++
            }

            if (sampleCount == 0) continue
            val actualMeasuredDist = measuredSum / sampleCount

            // -------------------------------------------------------------
            // GEOMETRIC TEST A: NEGATIVE DROP-OFF (Stairs Down / Open Drain)
            // If actual distance is significantly GREATER than expected floor,
            // the ground has vanished beneath the user!
            // -------------------------------------------------------------
            if (actualMeasuredDist > expectedGroundDist + 0.65f && expectedGroundDist <= 2.2f) {
                hazards.add(
                    GeometricHazard(
                        type = HazardType.NEGATIVE_DROP_OFF,
                        distanceMeters = String.format(Locale.US, "%.1f", expectedGroundDist).toFloat(),
                        direction = Direction.CENTER,
                        severity = ObstacleSeverity.DANGER // Immediate stop!
                    )
                )
                break // Report closest drop-off immediately
            }

            // -------------------------------------------------------------
            // GEOMETRIC TEST B: POSITIVE BARRIER (Wall / Large Obstacle)
            // If actual distance is significantly CLOSER than expected floor,
            // a solid surface is rising vertically from the ground!
            // -------------------------------------------------------------
            if (actualMeasuredDist < expectedGroundDist - 0.50f && actualMeasuredDist <= 2.0f) {
                val isDanger = actualMeasuredDist <= 1.0f
                hazards.add(
                    GeometricHazard(
                        type = HazardType.POSITIVE_OBSTACLE,
                        distanceMeters = String.format(Locale.US, "%.1f", actualMeasuredDist).toFloat(),
                        direction = Direction.CENTER,
                        severity = if (isDanger) ObstacleSeverity.DANGER else ObstacleSeverity.WARNING
                    )
                )
                break
            }
        }

        return hazards
    }

    /**
     * Fuses YOLOX 2D Bounding Boxes with Depth Anything V2 3D Depth Map.
     * Takes the true statistical median depth inside the box.
     */
    fun fuseObjectWithDepth(
        bbox: RectF,
        category: ObstacleCategory,
        label: String,
        depthMap: DepthMap
    ): Obstacle {
        val trueDistance = depthMap.getMedianDistanceForBox(bbox)
        val lateralOffset = (bbox.centerX() - 0.5f) * trueDistance * 1.30f
        val inCorridor = abs(lateralOffset) <= 0.65f

        val direction = when {
            lateralOffset < -0.65f -> Direction.LEFT
            lateralOffset > 0.65f -> Direction.RIGHT
            else -> Direction.CENTER
        }

        val severity = when {
            inCorridor && trueDistance <= 1.2f -> ObstacleSeverity.DANGER
            inCorridor && trueDistance <= 2.5f -> ObstacleSeverity.WARNING
            else -> ObstacleSeverity.INFO
        }

        return Obstacle(
            category = category,
            label = "Obstacle", // User preference: generic alert
            distanceMeters = String.format(Locale.US, "%.1f", trueDistance).toFloat(),
            direction = direction,
            severity = severity,
            boundingBox = bbox,
            confidence = 0.95f
        )
    }
}