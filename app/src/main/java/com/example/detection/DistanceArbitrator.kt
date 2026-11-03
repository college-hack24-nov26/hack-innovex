package com.example.detection

import android.graphics.RectF
import android.media.Image
import com.example.model.ObstacleCategory
import java.nio.ByteOrder
import kotlin.math.abs

enum class DistanceConfidence {
    VERIFIED_HIGH,    // ARCore depth validated by ground raycast
    ESTIMATED_MEDIUM, // Consistent fallback estimates
    CONSERVATIVE_LOW, // Ambiguity detected; safer closer reading used
    UNCERTAIN         // Disagreement/sensor holes; do not speak exact number
}

data class ArbitratedDistance(
    val distanceMeters: Float,
    val confidence: DistanceConfidence,
    val isUncertain: Boolean,
    val sourceUsed: String
)

object DistanceArbitrator {

    /**
     * Decision Matrix: Arbitrates between ARCore depth buffer, ground-contact raycast, and bounding box formula.
     * NEVER blindly averages conflicting signals.
     */
    fun arbitrate(
        arcoreDepthMeters: Float?,
        groundRaycastMeters: Float,
        boxFormulaMeters: Float,
        category: ObstacleCategory
    ): ArbitratedDistance {

        // CASE 1: Valid ARCore Depth Buffer measurement available (0.3m to 6.0m)
        if (arcoreDepthMeters != null && arcoreDepthMeters in 0.30f..6.0f) {
            val discrepancy = abs(arcoreDepthMeters - groundRaycastMeters)

            return when {
                // High agreement: Both independent depth systems match within 65cm
                discrepancy <= 0.65f -> {
                    ArbitratedDistance(
                        distanceMeters = String.format("%.1f", arcoreDepthMeters).toFloat(),
                        confidence = DistanceConfidence.VERIFIED_HIGH,
                        isUncertain = false,
                        sourceUsed = "ARCore Depth (Verified)"
                    )
                }
                // Moderate discrepancy: Pedestrian safety rule -> Pick the closer distance!
                discrepancy <= 1.30f -> {
                    val saferDistance = minOf(arcoreDepthMeters, groundRaycastMeters)
                    ArbitratedDistance(
                        distanceMeters = String.format("%.1f", saferDistance).toFloat(),
                        confidence = DistanceConfidence.CONSERVATIVE_LOW,
                        isUncertain = false,
                        sourceUsed = "Conservative (Closer of AR/Raycast)"
                    )
                }
                // Severe disagreement: Depth sensor and geometry do not match
                else -> {
                    val emergencyDist = minOf(arcoreDepthMeters, groundRaycastMeters)
                    ArbitratedDistance(
                        distanceMeters = String.format("%.1f", emergencyDist).toFloat(),
                        confidence = DistanceConfidence.UNCERTAIN,
                        isUncertain = true,
                        sourceUsed = "Discrepancy Rejection"
                    )
                }
            }
        }

        // CASE 2: ARCore unavailable (unsupported device or textureless surface)
        // Compare ground-contact raycast against optical box formula
        val formulaDiff = abs(groundRaycastMeters - boxFormulaMeters)
        return if (formulaDiff <= 0.85f) {
            ArbitratedDistance(
                distanceMeters = String.format("%.1f", groundRaycastMeters).toFloat(),
                confidence = DistanceConfidence.ESTIMATED_MEDIUM,
                isUncertain = false,
                sourceUsed = "Ground Raycast + IMU"
            )
        } else {
            // Optical dimensions and ground raycast contradict each other (e.g. partially hidden obstacle)
            val saferDist = minOf(groundRaycastMeters, boxFormulaMeters)
            ArbitratedDistance(
                distanceMeters = String.format("%.1f", saferDist).toFloat(),
                confidence = DistanceConfidence.UNCERTAIN,
                isUncertain = true,
                sourceUsed = "Geometry Contradiction"
            )
        }
    }

    /**
     * Extracts statistical MEDIAN depth from the inner 50% core of a bounding box.
     * Operates on 16-bit depth images (DEPTH16). Automatically rejects zero/invalid pixels.
     */
    fun extractMedianDepthFromDepthImage(
        depthImage: Image,
        normalizedBbox: RectF
    ): Float? {
        try {
            val width = depthImage.width
            val height = depthImage.height
            val plane = depthImage.planes[0]
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            // 1. Shrink bounding box to inner 50% core (avoids background pixels around object perimeter)
            val coreLeft = (normalizedBbox.left + normalizedBbox.width() * 0.25f).coerceIn(0f, 1f)
            val coreRight = (normalizedBbox.right - normalizedBbox.width() * 0.25f).coerceIn(0f, 1f)
            val coreTop = (normalizedBbox.top + normalizedBbox.height() * 0.25f).coerceIn(0f, 1f)
            val coreBottom = (normalizedBbox.bottom - normalizedBbox.height() * 0.25f).coerceIn(0f, 1f)

            val xStart = (coreLeft * width).toInt().coerceIn(0, width - 1)
            val xEnd = (coreRight * width).toInt().coerceIn(xStart + 1, width)
            val yStart = (coreTop * height).toInt().coerceIn(0, height - 1)
            val yEnd = (coreBottom * height).toInt().coerceIn(yStart + 1, height)

            val validDepths = mutableListOf<Float>()

            // 2. Sample valid non-zero depth pixels
            val step = maxOf(1, (xEnd - xStart) / 16) // Subsample for fast mobile execution
            for (y in yStart until yEnd step step) {
                for (x in xStart until xEnd step step) {
                    val byteIndex = y * rowStride + x * pixelStride
                    if (byteIndex + 1 < buffer.capacity()) {
                        val depthMm = buffer.getShort(byteIndex).toInt() and 0xFFFF
                        if (depthMm in 300..6500) { // Valid range: 0.3m to 6.5m
                            validDepths.add(depthMm / 1000.0f)
                        }
                    }
                }
            }

            if (validDepths.isEmpty()) return null

            // 3. Statistical Median (immune to specular reflections and black holes)
            validDepths.sort()
            return validDepths[validDepths.size / 2]
        } catch (_: Exception) {
            return null
        }
    }
}