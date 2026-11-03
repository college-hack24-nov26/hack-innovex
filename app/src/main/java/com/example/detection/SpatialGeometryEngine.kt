package com.example.detection

import com.example.model.Direction
import com.example.model.Obstacle
import com.example.model.ObstacleCategory
import com.example.model.ObstacleSeverity
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round

class SpatialGeometryEngine(
    private val fovHorizontalRad: Float = 1.30f, // ~74 degrees
    private val fovVerticalRad: Float = 1.05f     // ~60 degrees
) {
    private val walkingCorridorHalfWidth = 0.60f // 1.2m total corridor width
    private val maxEvaluationDistance = 4.0f     // 4 meters forward scan

    // TER-06: Excessive downward pitch cutoff (55 degrees = 0.96 rad)
    private val maxWalkingPitchRad = 0.96f

    fun rawToMeters(rawDisparity: Float): Float {
        val clamped = rawDisparity.coerceIn(0.01f, 1.0f)
        val meters = 0.72f / (clamped.pow(1.15f) + 0.04f)
        return meters.coerceIn(0.35f, 8.0f)
    }

    /**
     * MAXIMUM ACCURACY 3D GEOMETRIC SCANNER (TER-01, TER-02, TER-04, TER-05, TER-06):
     * Suppresses false alarms when user bends down to tie shoes (TER-06).
     */
    fun extractHazardsFrom3DGeometry(
        depthMap: DepthMap,
        rotationMatrix: FloatArray,
        phonePitchRad: Float = 0.22f
    ): List<Obstacle> {

        // -------------------------------------------------------------
        // TER-06 FIX: POSTURE-AWARE DOWNWARD TILT SUPPRESSION
        // If phone tilts > 55 degrees downward, user is bending to tie shoes
        // or pick up cane. Suppress all foot/floor false alarms!
        // -------------------------------------------------------------
        if (phonePitchRad > maxWalkingPitchRad) {
            return emptyList()
        }

        val hazards = mutableListOf<Obstacle>()
        val gridH = depthMap.gridHeight
        val gridW = depthMap.gridWidth

        val r00 = rotationMatrix[0]; val r01 = rotationMatrix[1]; val r02 = rotationMatrix[2]
        val r10 = rotationMatrix[3]; val r11 = rotationMatrix[4]; val r12 = rotationMatrix[5]
        val r20 = rotationMatrix[6]; val r21 = rotationMatrix[7]; val r22 = rotationMatrix[8]

        // -------------------------------------------------------------
        // STEP 1: AUTO-CALIBRATE GROUND HEIGHT DYNAMICALLY
        // -------------------------------------------------------------
        var floorHeightSum = 0f
        var floorSampleCount = 0
        val bottomRowStart = (gridH * 0.82f).toInt()
        val centerCol = gridW / 2

        for (row in bottomRowStart until gridH step 2) {
            val zCam = rawToMeters(depthMap.depthGrid[row][centerCol])
            if (zCam >= 0.5f && zCam <= 2.5f) {
                val normY = (row.toFloat() / gridH) - 0.5f
                val yCam = -normY * zCam * fovVerticalRad
                val yRotated = r10 * 0f + r11 * yCam + r12 * zCam
                floorHeightSum += -yRotated
                floorSampleCount++
            }
        }

        val dynamicCameraHeight = if (floorSampleCount > 3) {
            (floorHeightSum / floorSampleCount).coerceIn(0.70f, 1.95f)
        } else {
            1.35f
        }

        // -------------------------------------------------------------
        // STEP 2: 3D DENSITY MATRICES (Ground, Obstacles & Chest Barriers)
        // -------------------------------------------------------------
        val numBins = 18 // 20cm bins from 0.4m to 4.0m
        val positiveObstacleDensity = IntArray(numBins)
        val negativeDropDensity = IntArray(numBins)
        val chestBarrierDensity = IntArray(numBins)

        val colStart = (gridW * 0.25f).toInt()
        val colEnd = (gridW * 0.75f).toInt()
        val rowTopStart = (gridH * 0.08f).toInt()

        for (row in rowTopStart until gridH step 2) {
            val normY = (row.toFloat() / gridH) - 0.5f

            for (col in colStart until colEnd step 2) {
                val zCam = rawToMeters(depthMap.depthGrid[row][col])
                if (zCam < 0.4f || zCam > maxEvaluationDistance) continue

                val normX = (col.toFloat() / gridW) - 0.5f

                val xCam = normX * zCam * fovHorizontalRad
                val yCam = -normY * zCam * fovVerticalRad

                val xWorld = r00 * xCam + r01 * yCam + r02 * zCam
                val yWorld = (r10 * xCam + r11 * yCam + r12 * zCam) + dynamicCameraHeight
                val zWorld = r20 * xCam + r21 * yCam + r22 * zCam

                if (abs(xWorld) <= walkingCorridorHalfWidth && zWorld >= 0.4f && zWorld <= maxEvaluationDistance) {
                    val binIndex = ((zWorld - 0.4f) / 0.20f).toInt().coerceIn(0, numBins - 1)

                    if (yWorld >= 0.16f && yWorld <= 2.10f) {
                        positiveObstacleDensity[binIndex]++
                    }

                    if (yWorld <= -0.16f) {
                        negativeDropDensity[binIndex]++
                    }

                    if (yWorld in 0.85f..1.95f) {
                        chestBarrierDensity[binIndex]++
                    }
                }
            }
        }

        val minPointsForConfirmation = 6

        // Check for Drop-Off / Open Drain first
        for (b in 0 until numBins) {
            if (negativeDropDensity[b] >= minPointsForConfirmation) {
                val distance = 0.4f + (b * 0.20f)
                val category = if (distance <= 1.8f) ObstacleCategory.OPEN_DRAIN else ObstacleCategory.STAIRS_DOWN
                val label = if (category == ObstacleCategory.OPEN_DRAIN) "Open drain ahead" else "Drop-off ahead"

                hazards.add(
                    Obstacle(
                        trackId = 3333,
                        category = category,
                        label = label,
                        distanceMeters = roundTo1Decimal(distance),
                        direction = Direction.CENTER,
                        severity = ObstacleSeverity.DANGER,
                        confidence = 0.98f
                    )
                )
                break
            }
        }

        // Check for Chest & Head-Height Barriers (TER-04)
        for (b in 0 until numBins) {
            if (chestBarrierDensity[b] >= minPointsForConfirmation) {
                val distance = 0.4f + (b * 0.20f)
                val severity = if (distance <= 1.1f) ObstacleSeverity.DANGER else ObstacleSeverity.WARNING

                hazards.add(
                    Obstacle(
                        trackId = 2222,
                        category = ObstacleCategory.UNKNOWN,
                        label = "Obstacle",
                        distanceMeters = roundTo1Decimal(distance),
                        direction = Direction.CENTER,
                        severity = severity,
                        confidence = 0.96f
                    )
                )
                break
            }
        }

        // Check for Glass Doors & Optical Frame Displacement (TER-05)
        var glassFrameDist = Float.MAX_VALUE
        val midRowStart = (gridH * 0.35f).toInt()
        val midRowEnd = (gridH * 0.65f).toInt()

        for (row in midRowStart until midRowEnd step 3) {
            var hasCloseFrame = false
            var hasDeepBackground = false
            var rowCloseZ = Float.MAX_VALUE

            for (col in colStart until colEnd step 2) {
                val z = rawToMeters(depthMap.depthGrid[row][col])
                if (z <= 1.35f) {
                    hasCloseFrame = true
                    if (z < rowCloseZ) rowCloseZ = z
                } else if (z >= 2.6f) {
                    hasDeepBackground = true
                }
            }

            if (hasCloseFrame && hasDeepBackground && rowCloseZ < glassFrameDist) {
                glassFrameDist = rowCloseZ
            }
        }

        if (glassFrameDist <= 1.35f) {
            val dist = roundTo1Decimal(glassFrameDist)
            val severity = if (dist <= 0.9f) ObstacleSeverity.DANGER else ObstacleSeverity.WARNING

            if (hazards.none { it.direction == Direction.CENTER && it.distanceMeters <= dist }) {
                hazards.add(
                    Obstacle(
                        trackId = 1111,
                        category = ObstacleCategory.UNKNOWN,
                        label = "Obstacle",
                        distanceMeters = dist,
                        direction = Direction.CENTER,
                        severity = severity,
                        confidence = 0.94f
                    )
                )
            }
        }

        // Check for General Solid Obstacles & Walls
        for (b in 0 until numBins) {
            if (positiveObstacleDensity[b] >= minPointsForConfirmation) {
                val distance = 0.4f + (b * 0.20f)
                val severity = if (distance <= 1.1f) ObstacleSeverity.DANGER else ObstacleSeverity.WARNING

                if (hazards.none { it.direction == Direction.CENTER && abs(it.distanceMeters - distance) < 0.4f }) {
                    hazards.add(
                        Obstacle(
                            trackId = 4445,
                            category = ObstacleCategory.UNKNOWN,
                            label = "Obstacle",
                            distanceMeters = roundTo1Decimal(distance),
                            direction = Direction.CENTER,
                            severity = severity,
                            confidence = 0.95f
                        )
                    )
                }
                break
            }
        }

        return hazards.sortedBy { it.distanceMeters }
    }

    private fun roundTo1Decimal(v: Float): Float {
        return round(v * 10f) / 10f
    }
}