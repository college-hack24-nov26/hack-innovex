package com.example.detection

import android.graphics.RectF
import com.example.model.*
import kotlin.math.abs

class ObstacleTracker {

    private data class Track(
        val id: Int,
        var category: ObstacleCategory,
        var label: String,
        var bbox: RectF,
        var distance: Float,
        var lastSeenTimestamp: Long,
        var hits: Int,
        var velocityMps: Float = 0f
    )

    private val tracks = mutableListOf<Track>()
    private var nextTrackId = 1

    companion object {
        private const val MIN_HITS_FOR_ALERT = 3     // Suppress single-frame blips
        private const val IOU_THRESHOLD = 0.35f
        private const val MAX_TRACK_AGE_MS = 600L    // Forget track if lost for > 600ms
    }

    @Synchronized
    fun processFrame(
        detections: List<RawDetection>,
        timestamp: Long
    ): List<Obstacle> {
        // 1. Remove stale tracks
        tracks.removeAll { (timestamp - it.lastSeenTimestamp) > MAX_TRACK_AGE_MS }

        // 2. Associate detections with existing tracks via IoU
        val matchedTracks = mutableSetOf<Track>()
        val matchedDetections = mutableSetOf<RawDetection>()

        for (detection in detections) {
            var bestTrack: Track? = null
            var bestIou = IOU_THRESHOLD

            for (track in tracks) {
                if (track in matchedTracks) continue
                val iou = calculateIoU(track.bbox, detection.boundingBox)
                if (iou > bestIou) {
                    bestIou = iou
                    bestTrack = track
                }
            }

            if (bestTrack != null) {
                val dtSec = maxOf(0.03f, (timestamp - bestTrack.lastSeenTimestamp) / 1000f)
                val deltaDist = bestTrack.distance - detection.distanceMeters // + means approaching
                val instantVelocity = deltaDist / dtSec

                // Alpha-beta smoothing on velocity & distance
                bestTrack.velocityMps = 0.7f * bestTrack.velocityMps + 0.3f * instantVelocity
                bestTrack.distance = 0.65f * bestTrack.distance + 0.35f * detection.distanceMeters
                bestTrack.bbox = detection.boundingBox
                bestTrack.lastSeenTimestamp = timestamp
                bestTrack.hits++

                matchedTracks.add(bestTrack)
                matchedDetections.add(detection)
            }
        }

        // 3. Create new tracks for unmatched detections
        for (detection in detections) {
            if (detection !in matchedDetections) {
                tracks.add(
                    Track(
                        id = nextTrackId++,
                        category = detection.category,
                        label = detection.label,
                        bbox = detection.boundingBox,
                        distance = detection.distanceMeters,
                        lastSeenTimestamp = timestamp,
                        hits = 1
                    )
                )
            }
        }

        // 4. Filter for verified, stable obstacles
        return tracks
            .filter { it.hits >= MIN_HITS_FOR_ALERT }
            .map { track ->
                val centerX = track.bbox.centerX()
                val direction = when {
                    centerX < 0.33f -> Direction.LEFT
                    centerX > 0.66f -> Direction.RIGHT
                    else -> Direction.CENTER
                }

                // Determine severity based on distance and approach speed
                val severity = when {
                    track.distance <= 1.5f || (track.distance <= 3.0f && track.velocityMps > 1.8f) -> ObstacleSeverity.DANGER
                    track.distance <= 3.2f || (track.velocityMps > 2.5f) -> ObstacleSeverity.WARNING
                    else -> ObstacleSeverity.INFO
                }

                Obstacle(
                    trackId = track.id,
                    category = track.category,
                    label = track.label,
                    distanceMeters = track.distance,
                    approachSpeedMps = track.velocityMps,
                    direction = direction,
                    severity = severity,
                    boundingBox = track.bbox,
                    confidence = 0.9f,
                    consecutiveHits = track.hits
                )
            }
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

data class RawDetection(
    val category: ObstacleCategory,
    val label: String,
    val boundingBox: RectF,
    val distanceMeters: Float,
    val confidence: Float
)