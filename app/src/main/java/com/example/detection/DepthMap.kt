package com.example.detection

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.pow

class DepthMap(
    val depthGrid: Array<FloatArray>,
    val gridHeight: Int,
    val gridWidth: Int
) {
    fun rawToMeters(rawDisparity: Float): Float {
        val clamped = rawDisparity.coerceIn(0.01f, 1.0f)
        val meters = 0.72f / (clamped.pow(1.15f) + 0.04f)
        return meters.coerceIn(0.35f, 8.0f)
    }

    fun getMedianDistanceForBox(bbox: RectF): Float {
        val coreLeft = (bbox.left + bbox.width() * 0.25f).coerceIn(0f, 1f)
        val coreRight = (bbox.right - bbox.width() * 0.25f).coerceIn(0f, 1f)
        val coreTop = (bbox.top + bbox.height() * 0.25f).coerceIn(0f, 1f)
        val coreBottom = (bbox.bottom - bbox.height() * 0.25f).coerceIn(0f, 1f)

        val xStart = (coreLeft * gridWidth).toInt().coerceIn(0, gridWidth - 1)
        val xEnd = (coreRight * gridWidth).toInt().coerceIn(xStart + 1, gridWidth)
        val yStart = (coreTop * gridHeight).toInt().coerceIn(0, gridHeight - 1)
        val yEnd = (coreBottom * gridHeight).toInt().coerceIn(yStart + 1, gridHeight)

        val depthSamples = mutableListOf<Float>()
        val step = max(1, (xEnd - xStart) / 12)

        for (y in yStart until yEnd step step) {
            for (x in xStart until xEnd step step) {
                depthSamples.add(rawToMeters(depthGrid[y][x]))
            }
        }

        if (depthSamples.isEmpty()) return 1.8f
        depthSamples.sort()
        return depthSamples[depthSamples.size / 2]
    }

    fun detectDropOff(): Float? {
        val centerX = gridWidth / 2
        val yNear = (gridHeight * 0.85f).toInt()
        val yMid = (gridHeight * 0.65f).toInt()

        val nearDist = rawToMeters(depthGrid[yNear][centerX])
        val midDist = rawToMeters(depthGrid[yMid][centerX])

        val depthJump = midDist - nearDist
        return if (depthJump > 0.60f && nearDist <= 2.0f) nearDist else null
    }
}