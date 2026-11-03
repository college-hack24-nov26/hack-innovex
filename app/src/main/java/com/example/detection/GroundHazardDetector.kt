package com.example.detection

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.model.Direction
import com.example.model.Obstacle
import com.example.model.ObstacleCategory
import com.example.model.ObstacleSeverity
import kotlin.math.abs
import kotlin.math.max

object GroundHazardDetector {

    // Process lower 35% of the screen (ground 0.8m to 3m ahead)
    private const val GROUND_START_Y = 0.62f
    private const val GRID_WIDTH = 48
    private const val GRID_HEIGHT = 48

    /**
     * Analyzes ground luminance gradients and edge periodicity for drop-offs and stairs.
     */
    fun scanGroundPlane(bitmap: Bitmap): List<Obstacle> {
        val width = bitmap.width
        val height = bitmap.height

        // Downsample ground ROI to lightweight 48x48 luminance matrix (< 3ms CPU execution)
        val groundTop = (height * GROUND_START_Y).toInt()
        val groundHeight = height - groundTop
        if (groundHeight <= 10) return emptyList()

        val groundBitmap = Bitmap.createBitmap(bitmap, 0, groundTop, width, groundHeight)
        val scaled = Bitmap.createScaledBitmap(groundBitmap, GRID_WIDTH, GRID_HEIGHT, false)

        val lum = Array(GRID_HEIGHT) { FloatArray(GRID_WIDTH) }
        val pixels = IntArray(GRID_WIDTH * GRID_HEIGHT)
        scaled.getPixels(pixels, 0, GRID_WIDTH, 0, 0, GRID_WIDTH, GRID_HEIGHT)

        var idx = 0
        var totalLum = 0f
        for (y in 0 until GRID_HEIGHT) {
            for (x in 0 until GRID_WIDTH) {
                val p = pixels[idx++]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                lum[y][x] = l
                totalLum += l
            }
        }

        // Pocket / complete darkness rejection
        val avgLum = totalLum / (GRID_WIDTH * GRID_HEIGHT)
        if (avgLum < 18f) return emptyList()

        // 1. Horizontal gradient analysis (detects horizontal rungs and cliff edges)
        val horizontalEdgeEnergy = FloatArray(GRID_HEIGHT - 1)
        for (y in 0 until GRID_HEIGHT - 1) {
            var rowEnergy = 0f
            // Focus on central walking corridor (middle 60% of corridor)
            val startX = (GRID_WIDTH * 0.20f).toInt()
            val endX = (GRID_WIDTH * 0.80f).toInt()
            for (x in startX until endX) {
                val verticalDiff = abs(lum[y + 1][x] - lum[y][x])
                val horizontalDiff = abs(lum[y][x + 1] - lum[y][x])
                // We reward strong horizontal edges (vertical gradient >> horizontal gradient)
                if (verticalDiff > horizontalDiff * 1.5f && verticalDiff > 28f) {
                    rowEnergy += verticalDiff
                }
            }
            horizontalEdgeEnergy[y] = rowEnergy
        }

        // 2. Count significant parallel horizontal lines
        val strongEdgeRows = mutableListOf<Int>()
        for (y in 1 until GRID_HEIGHT - 2) {
            if (horizontalEdgeEnergy[y] > 260f &&
                horizontalEdgeEnergy[y] > horizontalEdgeEnergy[y - 1] &&
                horizontalEdgeEnergy[y] > horizontalEdgeEnergy[y + 1]
            ) {
                strongEdgeRows.add(y)
            }
        }

        val detectedHazards = mutableListOf<Obstacle>()

        // -------------------------------------------------------------------------
        // PATTERN A: STAIRS DETECTION (3+ evenly spaced horizontal edges)
        // -------------------------------------------------------------------------
        if (strongEdgeRows.size >= 3) {
            // Check spacing consistency between detected rungs
            val intervals = mutableListOf<Int>()
            for (i in 0 until strongEdgeRows.size - 1) {
                intervals.add(strongEdgeRows[i + 1] - strongEdgeRows[i])
            }
            val avgInterval = intervals.average()
            val consistentSpacing = intervals.all { abs(it - avgInterval) <= 4 }

            if (consistentSpacing && avgInterval in 4.0..18.0) {
                val closestRow = strongEdgeRows.maxOrNull() ?: (GRID_HEIGHT - 1)
                // Downward perspective: closest row closer to bottom indicates stairs down
                val isDown = closestRow > (GRID_HEIGHT * 0.55f)
                val category = if (isDown) ObstacleCategory.STAIRS_DOWN else ObstacleCategory.STAIRS_UP

                val distance = calculateGroundDistance(closestRow)

                val rect = RectF(0.20f, GROUND_START_Y + (strongEdgeRows.first() / GRID_HEIGHT.toFloat()) * (1f - GROUND_START_Y),
                    0.80f, GROUND_START_Y + (closestRow / GRID_HEIGHT.toFloat()) * (1f - GROUND_START_Y))

                detectedHazards.add(
                    Obstacle(
                        category = category,
                        label = if (isDown) "Stairs down" else "Stairs up",
                        distanceMeters = distance,
                        direction = Direction.CENTER,
                        severity = if (isDown) ObstacleSeverity.DANGER else ObstacleSeverity.WARNING,
                        boundingBox = rect,
                        confidence = 0.92f
                    )
                )
                return detectedHazards
            }
        }

        // -------------------------------------------------------------------------
        // PATTERN B: OPEN DRAIN / DEEP HOLE / SUDDEN DROP-OFF
        // Look for sharp horizontal contrast boundary followed by a dark void
        // -------------------------------------------------------------------------
        for (y in 5 until GRID_HEIGHT - 6) {
            if (horizontalEdgeEnergy[y] > 380f) {
                val upperLum = calculateZoneLuminance(lum, y - 4, y, (GRID_WIDTH * 0.3f).toInt(), (GRID_WIDTH * 0.7f).toInt())
                val lowerLum = calculateZoneLuminance(lum, y, y + 5, (GRID_WIDTH * 0.3f).toInt(), (GRID_WIDTH * 0.7f).toInt())

                // Sudden transition into dark void or sharp pavement gap
                if (upperLum > lowerLum * 2.2f && lowerLum < 65f) {
                    val distance = calculateGroundDistance(y)
                    val rect = RectF(0.25f, GROUND_START_Y + (y / GRID_HEIGHT.toFloat()) * (1f - GROUND_START_Y),
                        0.75f, 0.98f)

                    detectedHazards.add(
                        Obstacle(
                            category = ObstacleCategory.OPEN_DRAIN,
                            label = "Open drain or drop",
                            distanceMeters = distance,
                            direction = Direction.CENTER,
                            severity = ObstacleSeverity.DANGER,
                            boundingBox = rect,
                            confidence = 0.94f
                        )
                    )
                    break
                }
            }
        }

        return detectedHazards
    }

    private fun calculateZoneLuminance(matrix: Array<FloatArray>, startY: Int, endY: Int, startX: Int, endX: Int): Float {
        var sum = 0f
        var count = 0
        for (y in max(0, startY) until minOf(GRID_HEIGHT, endY)) {
            for (x in max(0, startX) until minOf(GRID_WIDTH, endX)) {
                sum += matrix[y][x]
                count++
            }
        }
        return if (count > 0) sum / count else 1f
    }

    /**
     * Converts normalized vertical position in ground ROI into real-world walking distance.
     * Near bottom of frame = ~0.8m from feet; near horizon = ~2.8m from feet.
     */
    private fun calculateGroundDistance(gridRow: Int): Float {
        val normalizedY = (gridRow.toFloat() / GRID_HEIGHT.toFloat()).coerceIn(0.1f, 1.0f)
        // Perspective ground formula for camera held at 1.35m height tilted ~12 degrees down
        val distance = 0.8f + (1.0f - normalizedY) * 2.2f
        return String.format("%.1f", distance).toFloat()
    }
}