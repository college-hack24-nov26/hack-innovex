package com.example.detection

import android.content.Context
import android.media.Image
import android.util.Log
import com.example.model.Direction
import com.example.model.Obstacle
import com.example.model.ObstacleCategory
import com.example.model.ObstacleSeverity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteOrder

class ArDepthRadar(private val context: Context) {

    private val tag = "ArDepthRadar"
    private var arSession: Session? = null
    var isDepthSupported: Boolean = false
        private set

    init {
        checkAndInitArCore()
    }

    private fun checkAndInitArCore() {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            if (availability.isSupported) {
                val session = Session(context)
                val config = Config(session)

                // Enable Automatic Metric Depth
                if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                    session.configure(config)
                    arSession = session
                    isDepthSupported = true
                    Log.i(tag, "ARCore Depth API successfully initialized with 16-bit metric depth!")
                } else {
                    Log.w(tag, "Device supports ARCore, but DepthMode.AUTOMATIC is not supported.")
                }
            } else {
                Log.w(tag, "ARCore is not supported on this device.")
            }
        } catch (e: Exception) {
            Log.e(tag, "ARCore initialization error: ${e.message}")
        }
    }

    /**
     * Reads the 16-bit depth buffer from the current AR frame.
     * Samples the center walking corridor (middle 40% of view) and returns the median distance.
     */
    fun measureCenterDistance(frame: Frame): Obstacle? {
        var depthImage: Image? = null
        try {
            // Acquire the 16-bit raw millimeter depth image
            depthImage = frame.acquireDepthImage16Bits()
            val plane = depthImage.planes[0]
            val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = depthImage.width
            val height = depthImage.height

            // Center Walking Corridor: x from 30% to 70%, y from 30% to 70%
            val startX = (width * 0.30f).toInt()
            val endX = (width * 0.70f).toInt()
            val startY = (height * 0.30f).toInt()
            val endY = (height * 0.70f).toInt()

            val validDepthsMeters = mutableListOf<Float>()
            val step = 4 // Subsample every 4th pixel for real-time 30 FPS speed

            for (y in startY until endY step step) {
                for (x in startX until endX step step) {
                    val byteIndex = y * rowStride + x * pixelStride
                    if (byteIndex + 1 < buffer.capacity()) {
                        val depthMm = buffer.getShort(byteIndex).toInt() and 0xFFFF
                        // Valid depth range: 20cm (0.2m) to 5.0m
                        if (depthMm in 200..5000) {
                            validDepthsMeters.add(depthMm / 1000.0f)
                        }
                    }
                }
            }

            if (validDepthsMeters.isEmpty()) return null

            // Statistical Median: completely ignores reflections, transparent gaps, or noise
            validDepthsMeters.sort()
            val medianDistance = validDepthsMeters[validDepthsMeters.size / 2]

            // Determine Severity based on TRUE physical distance
            val severity = when {
                medianDistance <= 1.0f -> ObstacleSeverity.DANGER   // Stop range
                medianDistance <= 2.2f -> ObstacleSeverity.WARNING  // Caution range
                else -> ObstacleSeverity.INFO
            }

            return Obstacle(
                trackId = 1001,
                category = ObstacleCategory.UNKNOWN,
                label = if (medianDistance <= 1.0f) "Obstacle directly ahead" else "Obstacle ahead",
                distanceMeters = String.format("%.1f", medianDistance).toFloat(),
                direction = Direction.CENTER,
                severity = severity,
                confidence = 0.98f
            )

        } catch (_: NotYetAvailableException) {
            return null
        } catch (e: Exception) {
            Log.e(tag, "Depth read error: ${e.message}")
            return null
        } finally {
            depthImage?.close() // Always close to prevent memory leaks
        }
    }

    fun pause() {
        try {
            arSession?.pause()
        } catch (_: Exception) {}
    }

    fun resume() {
        try {
            arSession?.resume()
        } catch (_: Exception) {}
    }

    fun close() {
        try {
            arSession?.close()
        } catch (_: Exception) {}
    }
}