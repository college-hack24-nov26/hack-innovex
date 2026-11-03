// Keep this file in your project (com/example/alerts/BinauralAudioEngine.kt)
// so DrishtiViewModel.kt compiles without errors and provides 3D spatial audio in Walking Mode.

package com.example.alerts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.example.model.Direction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class BinauralAudioEngine {

    private val sampleRate = 48_000
    private val scope = CoroutineScope(Dispatchers.Default)
    private var pingJob: Job? = null

    private var audioTrack: AudioTrack? = null

    @Volatile private var activeAzimuthRad: Float = 0f
    @Volatile private var activeDistanceM: Float = 0f
    @Volatile private var isPingingActive: Boolean = false

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        audioTrack = builder.build()
        audioTrack?.play()
    }

    fun start() {
        if (pingJob?.isActive == true) return
        pingJob = scope.launch {
            while (isActive) {
                if (isPingingActive && activeDistanceM in 0.4f..3.0f) {
                    val pcmData = renderPing(activeAzimuthRad, activeDistanceM, sampleRate)
                    audioTrack?.write(pcmData, 0, pcmData.size)

                    // Repetition interval scales with distance (closer = faster pings)
                    val repeatIntervalMs = (180L + (activeDistanceM / 3.0f * 650L).toLong()).coerceIn(160L, 850L)
                    delay(repeatIntervalMs)
                } else {
                    delay(80L)
                }
            }
        }
    }

    fun updateSpatialTarget(direction: Direction, distanceMeters: Float?) {
        if (distanceMeters == null || distanceMeters > 3.0f) {
            isPingingActive = false
            return
        }

        activeDistanceM = distanceMeters
        activeAzimuthRad = when (direction) {
            Direction.LEFT -> -0.75f   // ~ -43 degrees
            Direction.RIGHT -> 0.75f   // ~ +43 degrees
            Direction.CENTER -> 0.0f
        }
        isPingingActive = true
    }

    /**
     * Woodworth ITD (Interaural Time Difference) + ILD (Interaural Level Difference)
     * Synthesizes a directional 60ms acoustic earcon.
     */
    private fun renderPing(azRad: Float, distM: Float, sr: Int): ShortArray {
        val th = azRad.coerceIn(-PI.toFloat() / 2, PI.toFloat() / 2)
        // Woodworth ITD: (a/c)*(theta + sin(theta))
        val itd = ((0.0875f / 343f) * (abs(th) + sin(abs(th))) * sr).roundToInt()
        // Attenuate far ear by up to ~8 dB
        val farGain = 10f.pow(-8f * sin(abs(th)) / 20f)

        val n = (0.06f * sr).toInt() // 60 ms burst
        // Pitch scales with proximity (closer = higher pitch: 600Hz to 1200Hz)
        val f = 600f + 600f * (1f - (distM / 3f).coerceIn(0f, 1f))
        val out = ShortArray((n + itd) * 2)

        for (i in 0 until n) {
            val env = 0.5f * (1f - cos(2f * PI.toFloat() * i / n)) // Hann window envelope
            val s = sin(2f * PI.toFloat() * f * i / sr) * env * 0.8f
            val near = (s * 32767).toInt().toShort()
            val far = (s * farGain * 32767).toInt().toShort()

            val rightIsNear = th > 0
            val iL = (i + if (rightIsNear) itd else 0) * 2
            val iR = (i + if (rightIsNear) 0 else itd) * 2 + 1

            if (iL < out.size && iR < out.size) {
                out[iL] = if (rightIsNear) far else near
                out[iR] = if (rightIsNear) near else far
            }
        }
        return out
    }

    fun release() {
        pingJob?.cancel()
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
    }
}