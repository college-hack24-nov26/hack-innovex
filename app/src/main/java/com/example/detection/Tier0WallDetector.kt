package com.example.detection

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sqrt
import kotlin.math.tan

object Tier0WallDetector {

    private const val CAMERA_HEIGHT = 1.35f
    private const val FOCAL_Y = 580f

    /**
     * TIER 0: Floor-to-Wall Boundary Scanner.
     * Prevents false alarms on empty rooms, shadows, and far horizons.
     */
    fun scanWallBoundary(bitmap: Bitmap, phonePitchRad: Float): Float? {
        val sampleGrid = 48
        val scaled = Bitmap.createScaledBitmap(bitmap, sampleGrid, sampleGrid, false)
        val pixels = IntArray(sampleGrid * sampleGrid)
        scaled.getPixels(pixels, 0, sampleGrid, 0, 0, sampleGrid, sampleGrid)

        // 1. Fit Gaussian model to the floor at the user's feet (Rows 41 to 47)
        var meanA = 0f
        var meanB = 0f
        var floorSamples = 0

        for (y in 41 until sampleGrid) {
            for (x in 18 until 30) {
                val p = pixels[y * sampleGrid + x]
                val (a, b) = rgbToLabChroma(p)
                meanA += a
                meanB += b
                floorSamples++
            }
        }

        if (floorSamples == 0) return null
        meanA /= floorSamples
        meanB /= floorSamples

        var varA = 0f
        var varB = 0f
        for (y in 41 until sampleGrid) {
            for (x in 18 until 30) {
                val p = pixels[y * sampleGrid + x]
                val (a, b) = rgbToLabChroma(p)
                varA += (a - meanA) * (a - meanA)
                varB += (b - meanB) * (b - meanB)
            }
        }
        val stdA = maxOf(sqrt(varA / floorSamples), 3.0f)
        val stdB = maxOf(sqrt(varB / floorSamples), 3.0f)

        // 2. Scan central walking corridor upward
        // CRITICAL FIX: Only scan rows 40 down to 26!
        // Anything above row 26 is the far end of the room (4-6 meters away) -> IGNORE!
        val corridorStartCol = 18
        val corridorEndCol = 30
        val wallBaseRows = mutableListOf<Int>()

        for (x in corridorStartCol until corridorEndCol step 2) {
            for (y in 40 downTo 26) {
                val p = pixels[y * sampleGrid + x]
                val (a, b) = rgbToLabChroma(p)

                val distChroma = sqrt(((a - meanA) / stdA).let { it * it } + ((b - meanB) / stdB).let { it * it })

                // Strong chroma divergence where floor meets a solid obstacle/wall
                if (distChroma > 3.6f) {
                    wallBaseRows.add(y)
                    break
                }
            }
        }

        // Must be a consistent horizontal boundary confirmed across at least 5 columns
        if (wallBaseRows.size >= 5) {
            wallBaseRows.sort()
            val medianRow = wallBaseRows[wallBaseRows.size / 2]

            // 3. SOLID WALL SURFACE VERIFICATION:
            // Check that the area above the boundary is a solid surface (not open air/empty room)
            var aboveVariance = 0f
            var aboveCount = 0
            val checkRow = maxOf(4, medianRow - 8)

            for (x in corridorStartCol until corridorEndCol step 2) {
                val p1 = pixels[checkRow * sampleGrid + x]
                val p2 = pixels[(checkRow + 3) * sampleGrid + x]
                val (a1, b1) = rgbToLabChroma(p1)
                val (a2, b2) = rgbToLabChroma(p2)
                aboveVariance += abs(a1 - a2) + abs(b1 - b2)
                aboveCount++
            }

            val avgAboveVariance = if (aboveCount > 0) aboveVariance / aboveCount else 100f
            // If the surface above is heavily noisy/cluttered, it's open room background -> IGNORE!
            if (avgAboveVariance > 18f) return null

            // 4. Calculate true metric distance
            val vNorm = (medianRow.toFloat() / sampleGrid) - 0.5f
            val phi = phonePitchRad + atan(vNorm * (sampleGrid / FOCAL_Y))

            if (phi > 0.12f) {
                val distance = CAMERA_HEIGHT / tan(phi)
                // Only alert if within actual threat range (0.5m to 1.8m)
                if (distance in 0.5f..1.8f) {
                    return Math.round(distance * 10f) / 10f
                }
            }
        }

        return null
    }

    private fun rgbToLabChroma(pixel: Int): Pair<Float, Float> {
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f

        val rLin = if (r > 0.04045f) Math.pow(((r + 0.055) / 1.055), 2.4).toFloat() else r / 12.92f
        val gLin = if (g > 0.04045f) Math.pow(((g + 0.055) / 1.055), 2.4).toFloat() else g / 12.92f
        val bLin = if (b > 0.04045f) Math.pow(((b + 0.055) / 1.055), 2.4).toFloat() else b / 12.92f

        val x = rLin * 0.4124f + gLin * 0.3576f + bLin * 0.1805f
        val y = rLin * 0.2126f + gLin * 0.7152f + bLin * 0.0722f
        val z = rLin * 0.0193f + gLin * 0.1192f + bLin * 0.9505f

        val xn = x / 0.95047f
        val yn = y / 1.00000f
        val zn = z / 1.08883f

        val fx = if (xn > 0.008856f) Math.pow(xn.toDouble(), 1.0 / 3.0).toFloat() else (7.787f * xn) + (16f / 116f)
        val fy = if (yn > 0.008856f) Math.pow(yn.toDouble(), 1.0 / 3.0).toFloat() else (7.787f * yn) + (16f / 116f)
        val fz = if (zn > 0.008856f) Math.pow(zn.toDouble(), 1.0 / 3.0).toFloat() else (7.787f * zn) + (16f / 116f)

        val aStar = 500f * (fx - fy)
        val bStar = 200f * (fy - fz)

        return Pair(aStar, bStar)
    }
}