package com.example.alerts

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.model.Obstacle
import com.example.model.ObstacleSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ProximityHapticEngine(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var pulsingJob: Job? = null

    @Volatile
    private var currentIntervalMs: Long = 0L // 0 means stopped/silent

    fun start() {
        if (pulsingJob?.isActive == true) return
        pulsingJob = scope.launch {
            while (isActive) {
                val interval = currentIntervalMs
                if (interval > 0L) {
                    triggerPulse(interval)
                    delay(interval)
                } else {
                    delay(100L) // Idle sleep when path is clear
                }
            }
        }
    }

    fun stop() {
        currentIntervalMs = 0L
        pulsingJob?.cancel()
        pulsingJob = null
        try {
            vibrator.cancel()
        } catch (_: Exception) {}
    }

    /**
     * Updates vibration rhythm based on the closest obstacle directly in the collision path.
     */
    fun updateProximity(closestThreat: Obstacle?) {
        if (closestThreat == null) {
            currentIntervalMs = 0L
            return
        }

        val dist = closestThreat.distanceMeters

        currentIntervalMs = when {
            // Immediate Danger Zone (< 0.8m) or ground fall hazard -> Fast buzz
            dist <= 0.8f || closestThreat.severity == ObstacleSeverity.DANGER -> 160L

            // Caution Zone (0.8m - 1.6m) -> Medium pulse
            dist <= 1.6f -> 380L

            // Awareness Zone (1.6m - 2.4m) -> Slow tick
            dist <= 2.4f -> 850L

            // Safe / Clear (> 2.4m) -> Silent
            else -> 0L
        }
    }

    private fun triggerPulse(interval: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val duration = when {
                    interval <= 180L -> 85L // Stronger emergency buzz
                    interval <= 400L -> 45L // Medium pulse
                    else -> 20L             // Subtle tick
                }
                val amplitude = when {
                    interval <= 180L -> VibrationEffect.DEFAULT_AMPLITUDE
                    interval <= 400L -> 180
                    else -> 100
                }
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(35L)
            }
        } catch (_: Exception) {}
    }
}