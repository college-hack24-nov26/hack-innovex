package com.example.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.model.AlertItem
import com.example.model.AppLanguage
import com.example.model.ToneType
import com.example.model.VibrationPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.PriorityQueue

class AlertManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val tag = "AlertManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 95)
    } catch (_: Throwable) {
        null
    }

    private val alertQueue = PriorityQueue<AlertItem>(compareBy { it.priority })
    private val pendingOnInitAlerts = mutableListOf<AlertItem>()
    private var currentSpeakingPriority: Int = Int.MAX_VALUE
    private var isSpeaking = false

    private val _lastSpokenMessage = MutableStateFlow("")
    val lastSpokenMessage: StateFlow<String> = _lastSpokenMessage.asStateFlow()

    private val _isAudioActive = MutableStateFlow(false)
    val isAudioActive: StateFlow<Boolean> = _isAudioActive.asStateFlow()

    // AUD-01 & AUD-02 Watchdog
    private var volumeSafetyJob: Job? = null
    private var lastVolumeAlertTime = 0L

    private var currentLanguage: AppLanguage = AppLanguage.ENGLISH

    // AUD-05: Tactile Geiger Counter Engine
    private var geigerJob: Job? = null
    @Volatile private var geigerIntervalMs: Long = 0L // 0 = silent
    @Volatile private var isDangerVibrating: Boolean = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize TextToSpeech: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.ENGLISH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(tag, "English TTS not supported, falling back to default locale")
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.02f)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            isTtsReady = true
            setupUtteranceListener()
            Log.d(tag, "TTS initialized and ready")

            // 1. Start AUD-01 & AUD-02 Mute Watchdog
            startVolumeSafetyMonitor()

            // 2. Start AUD-05 Tactile Geiger Engine
            startGeigerEngine()

            synchronized(pendingOnInitAlerts) {
                val toSpeak = pendingOnInitAlerts.maxByOrNull { it.timestamp }
                pendingOnInitAlerts.clear()
                if (toSpeak != null) {
                    speakImmediate(toSpeak)
                } else {
                    speakImmediate(
                        AlertItem(
                            priority = AlertItem.PRIORITY_ASSISTANT,
                            message = "AuraSight ready. Obstacle detection active.",
                            vibration = VibrationPattern.CONFIRM_SHORT,
                            tone = ToneType.NAV_CHIME
                        )
                    )
                }
            }
        } else {
            Log.e(tag, "Failed to initialize TTS engine with status code $status")
        }
    }

    fun setAppLanguage(language: AppLanguage, announceChange: Boolean = false) {
        currentLanguage = language
        if (!isTtsReady || tts == null) return

        try {
            var result = tts?.setLanguage(language.locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                val hiLocale = Locale("hi", "IN")
                result = tts?.setLanguage(hiLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale("en", "IN")
                }
            }

            if (announceChange) {
                val confirmation = when (language) {
                    AppLanguage.MARATHI -> "भाषा बदलून मराठी केली आहे."
                    AppLanguage.HINDI -> "भाषा बदलकर हिन्दी कर दी गई है।"
                    AppLanguage.ENGLISH -> "Language changed to English."
                }
                speakNowLoudly(confirmation)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error setting TTS language: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // AUD-05: TACTILE GEIGER COUNTER ENGINE (850ms -> 380ms -> 160ms)
    // -------------------------------------------------------------------------
    fun startGeigerEngine() {
        geigerJob?.cancel()
        geigerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val interval = geigerIntervalMs
                if (interval > 0L && !isDangerVibrating) {
                    triggerGeigerClick(interval)
                    delay(interval)
                } else {
                    delay(60L) // Idle sleep when path is clear
                }
            }
        }
    }

    /**
     * Updates tactile Geiger pulse frequency based on obstacle distance:
     * - > 2.5m: Silent (0ms)
     * - 1.6m - 2.5m: 850ms tick
     * - 0.8m - 1.6m: 380ms pulse
     * - < 0.8m: 160ms rapid buzz
     */
    fun updateProximityDistance(distanceMeters: Float?) {
        if (distanceMeters == null || distanceMeters > 2.5f) {
            geigerIntervalMs = 0L
            return
        }

        geigerIntervalMs = when {
            distanceMeters < 0.8f -> 160L // Rapid danger buzz
            distanceMeters <= 1.6f -> 380L // Caution pulse
            else -> 850L                   // Awareness tick
        }
    }

    private fun triggerGeigerClick(interval: Long) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val (duration, amplitude) = when {
                    interval <= 180L -> 75L to 255 // Emergency buzz
                    interval <= 400L -> 40L to 180 // Medium pulse
                    else -> 18L to 110             // Gentle tick
                }
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                @Suppress("DEPRECATION")
                val duration = if (interval <= 180L) 60L else 25L
                vibrator.vibrate(duration)
            }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // AUD-01 & AUD-02: 3-SECOND MUTE WATCHDOG & 5-MIN REMINDER
    // -------------------------------------------------------------------------
    private fun startVolumeSafetyMonitor() {
        volumeSafetyJob?.cancel()
        volumeSafetyJob = scope.launch(Dispatchers.Default) {
            delay(3000L) // Wait 3s after startup

            while (isActive) {
                checkVolumeSafety()
                delay(3000L) // Poll every 3 seconds for instant response
            }
        }
    }

    private fun checkVolumeSafety() {
        try {
            val am = audioManager ?: return
            val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volumeRatio = if (maxVol > 0) currentVol.toFloat() / maxVol.toFloat() else 1.0f
            val now = System.currentTimeMillis()

            // AUD-01: Volume Muted to 0
            if (currentVol == 0) {
                lastVolumeAlertTime = now

                // Nudge to level 1 so user hears warning
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
                } catch (_: Exception) {}

                postAlert(
                    AlertItem(
                        priority = AlertItem.PRIORITY_DANGER,
                        message = "Low volume. Please increase the volume.",
                        interrupts = true,
                        vibration = VibrationPattern.CAUTION_PULSE,
                        tone = ToneType.WARNING_DOUBLE
                    )
                )
                Log.w(tag, "AUD-01: Mute detected. Nudged to Level 1 and spoke warning.")
                return
            }

            // AUD-02: Volume Low (<= 25%) - 5-minute reminder
            if (volumeRatio <= 0.25f) {
                if (now - lastVolumeAlertTime >= 5 * 60 * 1000L) {
                    lastVolumeAlertTime = now
                    postAlert(
                        AlertItem(
                            priority = AlertItem.PRIORITY_ASSISTANT,
                            message = "Low volume. Please increase the volume.",
                            interrupts = false,
                            vibration = VibrationPattern.CAUTION_PULSE,
                            tone = ToneType.WARNING_DOUBLE
                        )
                    )
                    Log.w(tag, "AUD-02: Low volume 5-min persistent warning.")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Volume safety check error: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // AUD-03: HARD PREEMPTION ENGINE (Preempts Long Navigation Speech at 0.7m)
    // -------------------------------------------------------------------------
    fun postAlert(alert: AlertItem) {
        if (!isTtsReady) {
            synchronized(pendingOnInitAlerts) {
                if (pendingOnInitAlerts.size > 3) pendingOnInitAlerts.removeAt(0)
                pendingOnInitAlerts.add(alert)
            }
            _lastSpokenMessage.value = alert.message
            return
        }

        // AUD-03: IMMEDIATE DANGER PREEMPTION
        if (alert.priority == AlertItem.PRIORITY_DANGER || alert.interrupts) {
            stopAllSpeech()

            synchronized(alertQueue) {
                alertQueue.clear()
            }

            triggerTone(alert.tone)
            triggerHaptic(alert.vibration)
            speakImmediate(alert)
            return
        }

        // Standard prioritization for non-danger speech
        if (isSpeaking && alert.priority >= currentSpeakingPriority) {
            synchronized(alertQueue) {
                if (alertQueue.size > 5) alertQueue.poll()
                alertQueue.offer(alert)
            }
        } else if (isSpeaking && alert.priority < currentSpeakingPriority) {
            stopAllSpeech()
            triggerHaptic(alert.vibration)
            triggerTone(alert.tone)
            speakImmediate(alert)
        } else {
            triggerHaptic(alert.vibration)
            triggerTone(alert.tone)
            speakImmediate(alert)
        }
    }

    private fun speakImmediate(alert: AlertItem) {
        currentSpeakingPriority = alert.priority
        _lastSpokenMessage.value = alert.message
        val utteranceId = alert.id

        requestTransientAudioFocus()

        try {
            // Count Devanagari characters (Marathi / Hindi script)
            val devCount = alert.message.count { it in '\u0900'..'\u097F' }
            val hasDevanagari = devCount >= 2

            if (hasDevanagari || currentLanguage == AppLanguage.MARATHI || currentLanguage == AppLanguage.HINDI) {
                val targetLocale = if (currentLanguage == AppLanguage.HINDI) {
                    Locale("hi", "IN")
                } else {
                    Locale("mr", "IN")
                }

                // Check if device has the voice pack installed
                val res = tts?.setLanguage(targetLocale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fallback to Hindi engine (which is pre-installed on 100% of Indian Android devices and reads Marathi clearly!)
                    tts?.setLanguage(Locale("hi", "IN"))
                }
            } else {
                tts?.setLanguage(currentLanguage.locale)
            }
        } catch (_: Exception) {}

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        tts?.speak(
            alert.message,
            TextToSpeech.QUEUE_FLUSH,
            params,
            utteranceId
        )
    }

    private fun requestTransientAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .build()
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) {}
    }

    /**
     * DOCUMENT SCAN PREEMPTION:
     * Immediately stops reading any previous document and speaks the new document instantly.
     */
    fun speakDocumentScan(text: String) {
        stopAllSpeech()

        synchronized(alertQueue) {
            alertQueue.clear()
        }

        postAlert(
            AlertItem(
                priority = AlertItem.PRIORITY_READER,
                message = text,
                interrupts = true,
                vibration = VibrationPattern.CONFIRM_SHORT,
                tone = ToneType.SUCCESS_CHIME
            )
        )
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isAudioActive.value = true
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                scope.launch {
                    isSpeaking = false
                    currentSpeakingPriority = Int.MAX_VALUE
                    _isAudioActive.value = false
                    processNextAlert()
                }
            }

            override fun onError(utteranceId: String?) {
                scope.launch {
                    isSpeaking = false
                    currentSpeakingPriority = Int.MAX_VALUE
                    _isAudioActive.value = false
                    processNextAlert()
                }
            }
        })
    }

    private fun processNextAlert() {
        synchronized(alertQueue) {
            if (alertQueue.isNotEmpty()) {
                val next = alertQueue.poll()
                if (next != null && (System.currentTimeMillis() - next.timestamp) < 8000) {
                    speakImmediate(next)
                }
            }
        }
    }

    fun stopAllSpeech() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(tag, "Error stopping TTS: ${e.message}")
        }
        isSpeaking = false
        currentSpeakingPriority = Int.MAX_VALUE
        _isAudioActive.value = false
    }

    fun speakNowLoudly(text: String, isDanger: Boolean = false) {
        val priority = if (isDanger) AlertItem.PRIORITY_DANGER else AlertItem.PRIORITY_ASSISTANT
        val vibration = if (isDanger) VibrationPattern.RAPID_DANGER else VibrationPattern.CONFIRM_SHORT
        val tone = if (isDanger) ToneType.HIGH_DANGER else ToneType.NONE
        postAlert(
            AlertItem(
                priority = priority,
                message = text,
                interrupts = isDanger,
                vibration = vibration,
                tone = tone
            )
        )
    }

    fun triggerHaptic(pattern: VibrationPattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return

        scope.launch(Dispatchers.Default) {
            try {
                if (pattern == VibrationPattern.RAPID_DANGER) {
                    isDangerVibrating = true
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val effect = when (pattern) {
                        VibrationPattern.RAPID_DANGER -> {
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 150, 70, 200, 70, 250),
                                intArrayOf(0, 255, 0, 255, 0, 255),
                                -1
                            )
                        }
                        VibrationPattern.CAUTION_PULSE -> {
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 120, 80, 150),
                                intArrayOf(0, 200, 0, 200),
                                -1
                            )
                        }
                        VibrationPattern.CONFIRM_SHORT -> {
                            VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE)
                        }
                        VibrationPattern.ARRIVAL_SUCCESS -> {
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 80, 80, 120, 80, 220),
                                intArrayOf(0, 180, 0, 220, 0, 255),
                                -1
                            )
                        }
                        VibrationPattern.NONE -> null
                    }
                    effect?.let { vibrator.vibrate(it) }
                } else {
                    @Suppress("DEPRECATION")
                    when (pattern) {
                        VibrationPattern.RAPID_DANGER -> vibrator.vibrate(longArrayOf(0, 150, 70, 200), -1)
                        VibrationPattern.CAUTION_PULSE -> vibrator.vibrate(longArrayOf(0, 120, 80, 150), -1)
                        VibrationPattern.CONFIRM_SHORT -> vibrator.vibrate(60)
                        VibrationPattern.ARRIVAL_SUCCESS -> vibrator.vibrate(longArrayOf(0, 80, 80, 120, 80, 220), -1)
                        VibrationPattern.NONE -> {}
                    }
                }

                if (pattern == VibrationPattern.RAPID_DANGER) {
                    delay(700L)
                    isDangerVibrating = false
                }
            } catch (e: Exception) {
                Log.e(tag, "Vibration failed: ${e.message}")
                isDangerVibrating = false
            }
        }
    }

    fun triggerTone(tone: ToneType) {
        try {
            when (tone) {
                ToneType.HIGH_DANGER -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 250)
                }
                ToneType.WARNING_DOUBLE -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 200)
                }
                ToneType.NAV_CHIME -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_PROMPT, 180)
                }
                ToneType.SUCCESS_CHIME -> {
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 220)
                }
                ToneType.NONE -> {}
            }
        } catch (e: Exception) {
            Log.w(tag, "Tone generation failed: ${e.message}")
        }
    }

    fun release() {
        try {
            volumeSafetyJob?.cancel()
            volumeSafetyJob = null
            geigerJob?.cancel()
            geigerJob = null
            scope.cancel()
            tts?.stop()
            tts?.shutdown()
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing alert manager: ${e.message}")
        }
    }
}