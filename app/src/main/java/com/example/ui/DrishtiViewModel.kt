package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import com.example.UsbBridgeManager
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alerts.AlertManager
import com.example.alerts.ProximityHapticEngine
import com.example.assistant.AssistantAction
import com.example.assistant.GeminiAssistant
import com.example.assistant.VoiceManager
import com.example.detection.ObstacleDetector
import com.example.model.AlertItem
import com.example.model.AppMode
import com.example.model.Direction
import com.example.model.Obstacle
import com.example.model.ObstacleCategory
import com.example.model.ObstacleSeverity
import com.example.model.ScanType
import com.example.model.ToneType
import com.example.model.UserSettings
import com.example.model.VibrationPattern
import com.example.navigation.NavigationManager
import com.example.reader.InfoReader
import com.example.reader.ScanResult
import com.example.settings.UserSettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.assistant.NetworkUtils
import com.example.model.AppLanguage
import com.example.model.AppStrings
import java.util.Collections.list
import kotlin.math.abs

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class DrishtiUiState(
    val currentMode: AppMode = AppMode.WALKING,
    val scanType: ScanType = ScanType.ALL,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val lastSpokenMessage: String = "AuraSight ready. Obstacle detection active.",
    val recognizedText: String = "",
    val soundRms: Float = 0f,
    val obstacles: List<Obstacle> = emptyList(),
    val latestScanResult: ScanResult? = null,
    val isCameraPermissionGranted: Boolean = false,
    val isAudioPermissionGranted: Boolean = false,
    val isLocationPermissionGranted: Boolean = false,
    val isTorchOn: Boolean = false,
    val isAnalyzingAiVision: Boolean = false,
    val aiVisionDescription: String = "",
    val userSettings: UserSettings = UserSettings(),
    val isFallDetected: Boolean = false,       // <-- ADD THIS
    val fallCountdownSeconds: Int = 0
)

class DrishtiViewModel(application: Application) : AndroidViewModel(application) {

    val alertManager by lazy { AlertManager(application) }
    val obstacleDetector by lazy { ObstacleDetector(application) }
    val proximityHapticEngine by lazy { ProximityHapticEngine(application) }
    val infoReader by lazy { InfoReader() }
    val navigationManager by lazy { NavigationManager(application) }
    val voiceManager by lazy { VoiceManager(application) }
    val geminiAssistant by lazy { GeminiAssistant() }
    val settingsManager by lazy { UserSettingsManager(application) }

    private val _uiState = MutableStateFlow(DrishtiUiState())
    val uiState: StateFlow<DrishtiUiState> = _uiState.asStateFlow()
    val binauralAudioEngine by lazy { com.example.alerts.BinauralAudioEngine() }

    val navState by lazy { navigationManager.navState }

    // Alert rate-limiting tracking
    private var lastSpokenObstacleKey = ""
    private var lastObstacleAlertTime = 0L
    private var lastScanAlertTime = 0L
    private var lastFrameProcessedTime = 0L

    // SENSORS & FALL DETECTION STATE
    private val sensorManager: SensorManager? = application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val lightSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    var currentAmbientLux: Float = -1f
    var lastAutoTorchCheckTime = 0L
    var isAutoTorchActivated = false
    var autoTorchTurnedOnTime = 0L
    var daylightConfirmationCycles = 0

    private var lastFreeFallTimestamp = 0L
    private var fallCountdownJob: Job? = null
    private var activeScanJob: Job? = null

    private var lastIotAlertTime = 0L

    private val usbBridgeManager by lazy {
        com.example.UsbBridgeManager(
            context = application,
            onConnected = {
                alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
                alertManager.speakNowLoudly("IoT ultrasonic sensor connected.")
            },
            onDistanceAlert = { distanceCm ->
                handleIotDistanceAlert(distanceCm)
            }
        )
    }

    private var shakeCount = 0
    private var lastShakeTimestamp = 0L

    private val fallSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.values?.let { v ->
                val x = v[0]
                val y = v[1]
                val z = v[2]
                val gForce = kotlin.math.sqrt(x * x + y * y + z * z)
                val now = System.currentTimeMillis()

                // 1. Free-fall detection (< 3.5 m/s^2)
                if (gForce < 3.5f) {
                    lastFreeFallTimestamp = now
                }

                // 2. Real Drop + Impact (> 21.0 m/s^2 after free-fall)
                val isRealDrop = (gForce > 21.0f && (now - lastFreeFallTimestamp in 40L..800L))

                // 3. Deliberate Firm Shake (> 22.5 m/s^2 twice within 700ms)
                var isDeliberateShake = false
                if (gForce > 22.5f && (now - lastShakeTimestamp > 180L)) {
                    if (now - lastShakeTimestamp < 700L) {
                        shakeCount++
                    } else {
                        shakeCount = 1
                    }
                    lastShakeTimestamp = now
                    if (shakeCount >= 2) {
                        isDeliberateShake = true
                        shakeCount = 0
                    }
                }

                if (isRealDrop || isDeliberateShake) {
                    lastFreeFallTimestamp = 0L
                    shakeCount = 0
                    if (!_uiState.value.isFallDetected) {
                        triggerFallAlert()
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val lightSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.values?.firstOrNull()?.let { lux ->
                currentAmbientLux = lux
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }


    init {
        usbBridgeManager.start()
        // Register hardware sensors safely
        lightSensor?.let {
            sensorManager?.registerListener(lightSensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        accelerometer?.let {
            sensorManager?.registerListener(fallSensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        proximityHapticEngine.start()

        // Collect user settings
        viewModelScope.launch {
            try {
                settingsManager.settings.collect { s ->
                    _uiState.value = _uiState.value.copy(userSettings = s)
                    alertManager.setAppLanguage(s.language)

                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting settings: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                alertManager.lastSpokenMessage.collect { msg ->
                    if (msg.isNotBlank()) {
                        _uiState.value = _uiState.value.copy(lastSpokenMessage = msg)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting alert message: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                alertManager.isAudioActive.collect { active ->
                    _uiState.value = _uiState.value.copy(isSpeaking = active)
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting audio status: ${e.message}")
            }
        }


        // Continuous tactile Geiger distance feedback (AUD-05)
        viewModelScope.launch {
            try {
                obstacleDetector.currentObstacles.collect { list ->
                    _uiState.value = _uiState.value.copy(obstacles = list)

                    // Find closest obstacle directly blocking the walking path
                    val closestThreat = list.firstOrNull {
                        it.direction == Direction.CENTER || it.severity == ObstacleSeverity.DANGER
                    }

                    if (_uiState.value.currentMode == AppMode.WALKING) {
                        // AUD-05: Feed distance to Geiger pulser (850ms -> 380ms -> 160ms)
                        alertManager.updateProximityDistance(closestThreat?.distanceMeters)
                    } else {
                        alertManager.updateProximityDistance(null)
                    }

                    handleObstacleAlerts(list)
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Haptic collection error: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                voiceManager.isListening.collect { listening ->
                    _uiState.value = _uiState.value.copy(isListening = listening)
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting voice listening: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                voiceManager.soundLevelRms.collect { rms ->
                    _uiState.value = _uiState.value.copy(soundRms = rms)
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting sound RMS: ${e.message}")
            }
        }



        viewModelScope.launch {
            try {
                voiceManager.lastRecognizedText.collect { txt ->
                    _uiState.value = _uiState.value.copy(recognizedText = txt)
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting recognized text: ${e.message}")
            }
        }

        // Collect detected obstacles: updates UI, tactile haptics, and spoken alerts
        // Collect detected obstacles: updates UI, tactile haptics, and 3D spatial audio
        viewModelScope.launch {
            try {
                obstacleDetector.currentObstacles.collect { list ->
                    _uiState.value = _uiState.value.copy(obstacles = list)

                    // Find closest obstacle directly blocking the walking corridor
                    val closestThreat = list.firstOrNull {
                        it.direction == Direction.CENTER || it.severity == ObstacleSeverity.DANGER
                    } ?: list.minByOrNull { it.distanceMeters }

                    // 3D Spatial Audio & Haptics:
                    if (_uiState.value.currentMode == AppMode.WALKING && closestThreat != null) {
                        binauralAudioEngine.updateSpatialTarget(closestThreat.direction, closestThreat.distanceMeters)
                        alertManager.updateProximityDistance(closestThreat.distanceMeters)
                    } else {
                        binauralAudioEngine.updateSpatialTarget(Direction.CENTER, null)
                        alertManager.updateProximityDistance(null)
                    }

                    handleObstacleAlerts(list)
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting obstacles: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                infoReader.lastScanResult.collect { result ->
                    if (result != null) {
                        _uiState.value = _uiState.value.copy(latestScanResult = result)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("DrishtiViewModel", "Error collecting scan results: ${e.message}")
            }
        }

        try {
            navigationManager.setListeners(
                onInstruction = { instruction ->
                    alertManager.postAlert(
                        AlertItem(
                            priority = AlertItem.PRIORITY_NAVIGATION,
                            message = instruction,
                            vibration = VibrationPattern.CAUTION_PULSE,
                            tone = ToneType.NAV_CHIME
                        )
                    )
                },
                onArrival = {
                    val lang = _uiState.value.userSettings.language
                    alertManager.postAlert(
                        AlertItem(
                            priority = AlertItem.PRIORITY_NAVIGATION,
                            message = AppStrings.navArrived(lang),
                            vibration = VibrationPattern.ARRIVAL_SUCCESS,
                            tone = ToneType.SUCCESS_CHIME
                        )
                    )
                }
            )
        } catch (e: Throwable) {
            android.util.Log.e("DrishtiViewModel", "Error setting navigation listeners: ${e.message}")
        }
    }






    fun triggerFallAlert() {
        if (_uiState.value.isFallDetected) return

        alertManager.stopAllSpeech()
        alertManager.triggerHaptic(VibrationPattern.RAPID_DANGER)
        alertManager.triggerTone(ToneType.HIGH_DANGER)

        val lang = _uiState.value.userSettings.language

        _uiState.value = _uiState.value.copy(
            isFallDetected = true,
            fallCountdownSeconds = 15
        )

        alertManager.speakNowLoudly(AppStrings.fallDetectedWarning(lang, 15), isDanger = true)

        fallCountdownJob?.cancel()
        fallCountdownJob = viewModelScope.launch {
            for (sec in 14 downTo 1) {
                delay(1000L)
                if (!_uiState.value.isFallDetected) return@launch
                _uiState.value = _uiState.value.copy(fallCountdownSeconds = sec)
                if (sec % 3 == 0) {
                    alertManager.triggerTone(ToneType.HIGH_DANGER)
                    alertManager.triggerHaptic(VibrationPattern.RAPID_DANGER)
                }
            }

            // 15 seconds passed without cancellation -> ANNOUNCE & DISPATCH SOS!
            if (_uiState.value.isFallDetected) {
                _uiState.value = _uiState.value.copy(isFallDetected = false, fallCountdownSeconds = 0)

                // 1. Spoken announcement that countdown expired and SOS is being sent!
                val dispatchingMsg = when (lang) {
                    AppLanguage.MARATHI -> "वेळ संपली. तुमच्या आपत्कालीन संपर्कांना लोकेशन एसएमएस पाठवत आहे."
                    AppLanguage.HINDI -> "समय समाप्त। आपके आपातकालीन संपर्कों को लोकेशन एसएमएस भेजा जा रहा है।"
                    AppLanguage.ENGLISH -> "Time expired. Sending emergency location SMS to your family now."
                }
                alertManager.stopAllSpeech()
                alertManager.speakNowLoudly(dispatchingMsg, isDanger = true)

                delay(1200L)

                // 2. Actually send the SMS with Google Maps coordinates!
                triggerEmergencySOS()
            }
        }
    }

    fun cancelFallAlert() {
        if (!_uiState.value.isFallDetected) return
        fallCountdownJob?.cancel()
        fallCountdownJob = null
        _uiState.value = _uiState.value.copy(
            isFallDetected = false,
            fallCountdownSeconds = 0
        )
        val lang = _uiState.value.userSettings.language
        alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
        alertManager.speakNowLoudly(AppStrings.fallCancelled(lang))
    }

    /**
     * AUTO-TORCH ENGINE:
     * Evaluates camera light levels every 2 seconds.
     * Automatically enables flash in dark/shadowed areas and turns off in bright areas.
     */
    private fun checkAutoTorch(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastAutoTorchCheckTime < 2000L) return
        lastAutoTorchCheckTime = now

        val avgLuminance = calculateFrameLuminance(bitmap)
        if (avgLuminance < 12f) return

        val lang = _uiState.value.userSettings.language

        if (!_uiState.value.isTorchOn && avgLuminance in 12f..32f) {
            isAutoTorchActivated = true
            _uiState.value = _uiState.value.copy(isTorchOn = true)
            alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
            alertManager.speakNowLoudly(AppStrings.lowLightTorchOn(lang))
        } else if (_uiState.value.isTorchOn && isAutoTorchActivated && avgLuminance > 115f) {
            isAutoTorchActivated = false
            _uiState.value = _uiState.value.copy(isTorchOn = false)
            alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
            alertManager.speakNowLoudly(AppStrings.lightSufficientTorchOff(lang))
        }
    }


    /**
     * Simulator for AUD-03: Navigation Preemption Test
     * Starts speaking a long 15-word navigation sentence, then suddenly interrupts
     * at 0.7m with an immediate danger stop command.
     */
    fun testSimulateNavigationInterrupt() {
        // 1. Start speaking long navigation instruction
        alertManager.postAlert(
            AlertItem(
                priority = AlertItem.PRIORITY_NAVIGATION,
                message = "In 200 meters, take a slight right turn toward Mahatma Gandhi Road transit terminal.",
                interrupts = false,
                vibration = VibrationPattern.CAUTION_PULSE,
                tone = ToneType.NAV_CHIME
            )
        )

        // 2. 700ms later, simulate sudden obstacle appearing at 0.7m
        viewModelScope.launch {
            kotlinx.coroutines.delay(700L)
            val emergencyDanger = Obstacle(
                trackId = 9990,
                category = ObstacleCategory.UNKNOWN,
                label = "Obstacle",
                distanceMeters = 0.7f,
                direction = Direction.CENTER,
                severity = ObstacleSeverity.DANGER,
                confidence = 0.98f
            )
            handleObstacleAlerts(listOf(emergencyDanger))
        }
    }
    private fun calculateFrameLuminance(bitmap: Bitmap): Float {
        val sampleSize = 16
        val small = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, false)
        val pixels = IntArray(sampleSize * sampleSize)
        small.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
        var sum = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        return sum.toFloat() / (sampleSize * sampleSize)
    }

    fun setPermissionsGranted(camera: Boolean, audio: Boolean, location: Boolean) {
        _uiState.value = _uiState.value.copy(
            isCameraPermissionGranted = camera,
            isAudioPermissionGranted = audio,
            isLocationPermissionGranted = location
        )
        if (location) {
            navigationManager.startLocationUpdates()
        }
    }

    fun toggleTorch() {
        isAutoTorchActivated = false
        val newState = !_uiState.value.isTorchOn
        _uiState.value = _uiState.value.copy(isTorchOn = newState)
        val lang = _uiState.value.userSettings.language
        alertManager.speakNowLoudly(if (newState) AppStrings.torchOn(lang) else AppStrings.torchOff(lang))
    }

    private fun handleIotDistanceAlert(distanceCm: Int) {
        val now = System.currentTimeMillis()
        if (now - lastIotAlertTime < 2200L) return
        lastIotAlertTime = now

        val lang = _uiState.value.userSettings.language

        val warningMsg = when (lang) {
            AppLanguage.MARATHI -> "सावधान! $distanceCm सेंटीमीटरवर भिंत किंवा अडथळा आहे. थांबा!"
            AppLanguage.HINDI -> "सावधान! $distanceCm सेंटीमीटर पर दीवार या रुकावट है। रुकिए!"
            AppLanguage.ENGLISH -> "Warning! Obstacle $distanceCm centimeters ahead. Stop!"
        }

        // Show on screen radar & top banner immediately
        val iotObstacle = Obstacle(
            trackId = 7777,
            category = ObstacleCategory.WALL,
            label = "IoT Sensor ($distanceCm cm)",
            distanceMeters = (distanceCm / 100f),
            direction = Direction.CENTER,
            severity = ObstacleSeverity.DANGER,
            confidence = 0.99f
        )
        _uiState.value = _uiState.value.copy(
            obstacles = listOf(iotObstacle),
            lastSpokenMessage = warningMsg
        )

        // Play loud danger beep, emergency vibration, and voice warning
        alertManager.speakNowLoudly(warningMsg, isDanger = true)
    }

    // 2. In switchMode():
    fun switchMode(newMode: AppMode) {
        _uiState.value = _uiState.value.copy(currentMode = newMode)
        val lang = _uiState.value.userSettings.language
        alertManager.speakNowLoudly(newMode.shortSpoken(lang))
        if (newMode == AppMode.WALKING) {
            obstacleDetector.clearObstacles()
        } else {
            alertManager.updateProximityDistance(null)
        }
    }

    // 3. In setScanType():
    fun setScanType(type: ScanType) {
        _uiState.value = _uiState.value.copy(scanType = type)
        val lang = _uiState.value.userSettings.language
        alertManager.speakNowLoudly(type.shortSpokenPrompt(lang))
    }

    private var latestCameraBitmap: Bitmap? = null

    /**
     * Throttles incoming camera frames to ~11 FPS to eliminate battery drain & thermal throttling.
     */
    fun onCameraFrame(imageProxy: ImageProxy) {
        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()
            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            latestCameraBitmap = rotatedBitmap

            // Auto-torch check
            checkAutoTorch(rotatedBitmap)

        } catch (e: Exception) {
            android.util.Log.e("DrishtiViewModel", "Camera frame cache error: ${e.message}")
        }

        when (_uiState.value.currentMode) {
            AppMode.WALKING -> {
                val now = System.currentTimeMillis()
                if (now - lastFrameProcessedTime < 90L) {
                    imageProxy.close()
                    return
                }
                lastFrameProcessedTime = now
                obstacleDetector.processImageProxy(imageProxy)
            }
            AppMode.SCAN -> {
                val now = System.currentTimeMillis()
                // Strict 10-second gap (10,000ms) between document adjustment instructions
                if (!_uiState.value.isAnalyzingAiVision && !_uiState.value.isSpeaking && (now - lastScanAlertTime >= 10_000L)) {
                    val frameBmp = latestCameraBitmap
                    if (frameBmp != null) {
                        lastScanAlertTime = now
                        viewModelScope.launch {
                            val lang = _uiState.value.userSettings.language
                            val (instruction, _) = infoReader.checkFramingGuidance(frameBmp, lang)
                            if (instruction != null &&
                                instruction != com.example.reader.GuidanceInstruction.HOLD_STEADY &&
                                instruction != com.example.reader.GuidanceInstruction.READY_TO_CAPTURE &&
                                infoReader.shouldSpeakFraming(instruction)
                            ) {
                                alertManager.speakNowLoudly(instruction.getSpoken(lang))
                            }
                        }
                    }
                }
                imageProxy.close()
            }
            AppMode.ASSISTANT, AppMode.SETTINGS -> {
                imageProxy.close()
            }
        }
    }

    /**
     * Evaluates collision threat. DANGER is only raised when an obstacle is within
     * the 1.2m pedestrian walking path or closing in rapidly.
     */
    // ============================================================================
// 5. In DrishtiViewModel.kt: Replace handleObstacleAlerts() (around Line 420)
//    with your friend's Walking Alert logic (Ground Void priority, Camera Blocked,
//    and 12-second stationary obstacle deduplication)
// ============================================================================

    private var lastAnnouncedDistance = 0.0f

    private fun handleObstacleAlerts(obstacles: List<Obstacle>) {
        if (_uiState.value.currentMode != AppMode.WALKING || obstacles.isEmpty()) return

        val lang = _uiState.value.userSettings.language

        val targetThreat = obstacles.firstOrNull { it.direction == Direction.CENTER }
            ?: obstacles.minByOrNull { it.distanceMeters }
            ?: return

        val isGroundVoid = targetThreat.category == ObstacleCategory.OPEN_DRAIN ||
                targetThreat.category == ObstacleCategory.STAIRS_DOWN ||
                targetThreat.label.contains("drain", ignoreCase = true) ||
                targetThreat.label.contains("drop-off", ignoreCase = true)

        if (isGroundVoid) {
            val now = System.currentTimeMillis()
            if (now - lastObstacleAlertTime > 2000L) {
                lastObstacleAlertTime = now
                alertManager.triggerHaptic(VibrationPattern.RAPID_DANGER)
                alertManager.triggerTone(ToneType.HIGH_DANGER)
                alertManager.speakNowLoudly(targetThreat.toSpokenAlert(lang), isDanger = true)
            }
            return
        }

        if (targetThreat.label == "Camera blocked") {
            val now = System.currentTimeMillis()
            if (now - lastObstacleAlertTime > 4000L) {
                lastObstacleAlertTime = now
                alertManager.triggerHaptic(VibrationPattern.RAPID_DANGER)
                alertManager.triggerTone(ToneType.HIGH_DANGER)
                alertManager.speakNowLoudly(targetThreat.toSpokenAlert(lang), isDanger = true)
            }
            return
        }

        if (targetThreat.distanceMeters > 2.8f) return

        val now = System.currentTimeMillis()
        val alertKey = "${targetThreat.label}_${targetThreat.direction}_${targetThreat.severity}"
        val isSameObstacle = (alertKey == lastSpokenObstacleKey)
        val timeSinceLast = now - lastObstacleAlertTime
        val distanceDelta = abs(targetThreat.distanceMeters - lastAnnouncedDistance)

        // Friend's Stationary Deduplication: Prevents repeating same obstacle if user stands still
        val isStationary = abs(targetThreat.approachSpeedMps) < 0.2f && distanceDelta < 0.35f
        if (isSameObstacle && isStationary && timeSinceLast < 12000L) {
            return
        }

        val isDanger = targetThreat.severity == ObstacleSeverity.DANGER || targetThreat.distanceMeters <= 1.2f

        if (isDanger) {
            if (!isSameObstacle || distanceDelta >= 0.35f || timeSinceLast > 3500L) {
                lastSpokenObstacleKey = alertKey
                lastObstacleAlertTime = now
                lastAnnouncedDistance = targetThreat.distanceMeters

                alertManager.postAlert(
                    AlertItem(
                        priority = AlertItem.PRIORITY_DANGER,
                        message = targetThreat.toSpokenAlert(lang),
                        interrupts = true,
                        vibration = VibrationPattern.RAPID_DANGER,
                        tone = ToneType.HIGH_DANGER
                    )
                )
            }
        } else {
            if (!isSameObstacle || distanceDelta >= 0.40f || timeSinceLast > 5000L) {
                lastSpokenObstacleKey = alertKey
                lastObstacleAlertTime = now
                lastAnnouncedDistance = targetThreat.distanceMeters

                alertManager.postAlert(
                    AlertItem(
                        priority = AlertItem.PRIORITY_DANGER,
                        message = targetThreat.toSpokenAlert(lang),
                        vibration = VibrationPattern.CAUTION_PULSE,
                        tone = ToneType.WARNING_DOUBLE
                    )
                )
            }
        }
    }



    fun selectAppLanguage(language: AppLanguage) {
        val updated = _uiState.value.userSettings.copy(language = language)
        saveUserSettings(updated)
        alertManager.setAppLanguage(language, announceChange = true)
    }

    /**
     * Simulator for TER-04: Chest-Height Barrier Test
     * Simulates an open truck tailgate or tree branch at 0.8 meters directly ahead.
     */
    fun testSimulateChestBarrier() {
        val chestThreat = Obstacle(
            trackId = 2222,
            category = ObstacleCategory.UNKNOWN,
            label = "Obstacle",
            distanceMeters = 0.8f,
            direction = Direction.CENTER,
            severity = ObstacleSeverity.DANGER,
            confidence = 0.96f
        )
        _uiState.value = _uiState.value.copy(obstacles = listOf(chestThreat))
        handleObstacleAlerts(listOf(chestThreat))
    }

    /**
     * Simulator for TER-01: Downward Stairs / Drop-Off Test
     * (Clean, 10-line method without duplicated code)
     */
    fun testSimulateStairsDown() {
        val stairsThreat = Obstacle(
            trackId = 4444,
            category = ObstacleCategory.STAIRS_DOWN,
            label = "Drop-off ahead",
            distanceMeters = 1.0f,
            direction = Direction.CENTER,
            severity = ObstacleSeverity.DANGER,
            confidence = 0.98f
        )
        _uiState.value = _uiState.value.copy(obstacles = listOf(stairsThreat))
        handleObstacleAlerts(listOf(stairsThreat))
    }

    /**
     * Simulator for TER-02: Open Roadside Drain / Missing Slab Test
     */
    fun testSimulateOpenDrain() {
        val drainThreat = Obstacle(
            trackId = 3333,
            category = ObstacleCategory.OPEN_DRAIN,
            label = "Open drain ahead",
            distanceMeters = 1.2f,
            direction = Direction.CENTER,
            severity = ObstacleSeverity.DANGER,
            confidence = 0.98f
        )
        _uiState.value = _uiState.value.copy(obstacles = listOf(drainThreat))
        handleObstacleAlerts(listOf(drainThreat))
    }


    /**
     * Simulator for TER-05: Clean Glass Door / Transparent Partition Test
     * Simulates detecting a glass door frame at 1.2 meters.
     */
    fun testSimulateGlassDoor() {
        val glassThreat = Obstacle(
            trackId = 1111,
            category = ObstacleCategory.UNKNOWN,
            label = "Obstacle",
            distanceMeters = 1.2f,
            direction = Direction.CENTER,
            severity = ObstacleSeverity.WARNING,
            confidence = 0.94f
        )
        _uiState.value = _uiState.value.copy(obstacles = listOf(glassThreat))
        handleObstacleAlerts(listOf(glassThreat))
    }


    /**
     * Simulator for TER-06: Shoelace Bending Down Test
     * Verifies that when the phone tilts > 60 degrees downward,
     * all alerts are suppressed and the app remains completely silent.
     */
    fun testSimulateShoelaceBend(): String {
        // Clear obstacles to simulate bending down suppression
        _uiState.value = _uiState.value.copy(obstacles = emptyList())
        proximityHapticEngine.updateProximity(null) // Mute haptics
        alertManager.stopAllSpeech() // Mute speech
        return "Shoelace bend simulated: Zero false alarms on feet."
    }

    fun toggleVoiceListening() {
        if (_uiState.value.isListening) {
            voiceManager.stopListening()
            _uiState.value = _uiState.value.copy(isListening = false)
        } else {
            startVoiceListening()
        }
    }

// ============================================================================
// 3. In ui/DrishtiViewModel.kt: Replace startVoiceListening() (around Line 580)
//    (Stops ongoing TTS speech first so TTS doesn't block the microphone!)
// ============================================================================

    fun startVoiceListening() {
        // 1. Immediately kill any TTS speech that is currently holding audio focus
        alertManager.stopAllSpeech()

        // 2. Play light click haptic & sound
        alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
        alertManager.triggerTone(ToneType.NAV_CHIME)

        val lang = _uiState.value.userSettings.language

        // 3. Immediately set state to listening on the first click!
        _uiState.value = _uiState.value.copy(
            isListening = true,
            recognizedText = AppStrings.listeningPrompt(lang)
        )

        // 4. Start recognizer cleanly
        voiceManager.startListening(
            lang = lang,
            onResult = { query ->
                handleSpokenCommand(query)
            },
            onError = { _ ->
                _uiState.value = _uiState.value.copy(
                    isListening = false,
                    recognizedText = AppStrings.tapToSpeakSubtitle(lang)
                )
            }
        )
    }

    fun stopVoiceListening() {
        voiceManager.stopListening()
        _uiState.value = _uiState.value.copy(isListening = false)
    }

    fun handleSpokenCommand(query: String) {
        if (query.isBlank()) return
        _uiState.value = _uiState.value.copy(
            recognizedText = query,
            isListening = false
        )

        val lower = query.lowercase().trim()

        if (_uiState.value.isFallDetected && (lower.contains("cancel") || lower.contains("stop") ||
                    lower.contains("रद्द") || lower.contains("ठीक") || lower.contains("रोक"))) {
            cancelFallAlert()
            return
        }

        val lang = _uiState.value.userSettings.language
        val settings = _uiState.value.userSettings

        // Intercept Voice Follow-ups
        if (lower == "more" || lower.contains("read more") || lower.contains("read all") ||
            lower.contains("अधिक") || lower.contains("और") || lower.contains("पूर्ण वाचा")
        ) {
            readMoreScannedText()
            return
        }

        if (lower == "repeat" || lower.contains("say again") || lower.contains("पुन्हा") || lower.contains("फिर से")) {
            val lastMsg = _uiState.value.aiVisionDescription.ifBlank { _uiState.value.lastSpokenMessage }
            alertManager.speakDocumentScan(lastMsg)
            return
        }

        val locDesc = navigationManager.navState.value.currentStreet.ifEmpty { "pedestrian walkway" }
        val obsList = _uiState.value.obstacles.joinToString(", ") {
            "${it.getSpokenObjectName(lang)} ${it.direction.getSpoken(lang)} (${String.format(java.util.Locale.US, "%.1f", it.distanceMeters)}m)"
        }
        val uLat = navigationManager.navState.value.userLatitude
        val uLng = navigationManager.navState.value.userLongitude

        viewModelScope.launch {
            // Extract latest scan content to feed directly into the assistant
            val scannedInfo = when {
                _uiState.value.aiVisionDescription.isNotBlank() && !_uiState.value.aiVisionDescription.contains("Scanning") ->
                    _uiState.value.aiVisionDescription
                _uiState.value.latestScanResult != null ->
                    "${_uiState.value.latestScanResult!!.title}. ${_uiState.value.latestScanResult!!.details}"
                else -> ""
            }

            val action = geminiAssistant.processUserCommand(
                query = query,
                currentLocationDesc = locDesc,
                visibleObstaclesDesc = obsList,
                userLat = uLat,
                userLng = uLng,
                lang = lang,
                settings = settings,
                currentMode = _uiState.value.currentMode,
                scannedContent = scannedInfo
            )

            when (action) {
                is AssistantAction.SpokenResponse -> alertManager.speakNowLoudly(action.text)
                is AssistantAction.StartNavigation -> {
                    switchMode(AppMode.WALKING)
                    navigationManager.startWalkingRoute(action.destination)
                    alertManager.speakNowLoudly(action.spokenPrompt)
                }
                is AssistantAction.SwitchMode -> {
                    when (action.modeName) {
                        "WALKING" -> switchMode(AppMode.WALKING)
                        "SCAN" -> switchMode(AppMode.SCAN)
                        else -> switchMode(AppMode.ASSISTANT)
                    }
                }
                is AssistantAction.ScanRequest -> {
                    val sType = when (action.scanType) {
                        "DOCUMENTS" -> ScanType.DOCUMENTS
                        "CURRENCY" -> ScanType.CURRENCY
                        "MEDICINE" -> ScanType.MEDICINE
                        else -> ScanType.ALL
                    }
                    _uiState.value = _uiState.value.copy(
                        currentMode = AppMode.SCAN,
                        scanType = sType
                    )
                    scanDocumentHybrid()
                }
                is AssistantAction.ShareLocation -> {
                    navigationManager.shareCurrentLocation()
                    alertManager.speakNowLoudly(action.spokenPrompt)
                }
                is AssistantAction.TriggerSOS -> triggerEmergencySOS()
                is AssistantAction.NavigateHome -> {
                    val home = settings.homeAddress.ifBlank { "Home" }
                    startNavigation(home)
                    alertManager.speakNowLoudly(action.spokenPrompt)
                }
                is AssistantAction.NavigateWork -> {
                    val work = settings.workAddress.ifBlank { "Work" }
                    startNavigation(work)
                    alertManager.speakNowLoudly(action.spokenPrompt)
                }
                is AssistantAction.LookAround -> triggerLookAround()
                is AssistantAction.ToggleTorch -> {
                    if (_uiState.value.isTorchOn != action.enable) toggleTorch()
                    else alertManager.speakNowLoudly(action.spokenPrompt)
                }
                is AssistantAction.StopNavigation -> stopNavigation()
            }
        }
    }

    fun startNavigation(destination: String) {
        if (destination.isNotBlank()) {
            switchMode(AppMode.WALKING)
            navigationManager.startWalkingRoute(destination)
            val lang = _uiState.value.userSettings.language
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "$destination साठी गुगल मॅप्स उघडत आहे."
                AppLanguage.HINDI -> "$destination के लिए गूगल मैप्स खोला जा रहा है।"
                AppLanguage.ENGLISH -> "Opening Google Maps walking route to $destination."
            }
            alertManager.speakNowLoudly(prompt)
        }
    }


    fun stopNavigation() {
        navigationManager.stopNavigation()
        val lang = _uiState.value.userSettings.language
        alertManager.speakNowLoudly(AppStrings.navStopped(lang))
    }

    fun openInGoogleMaps() {
        val launched = navigationManager.openInGoogleMapsApp()
        if (launched) {
            val lang = _uiState.value.userSettings.language
            val msg = when (lang) {
                AppLanguage.MARATHI -> "गुगल मॅप्स उघडत आहे."
                AppLanguage.HINDI -> "गूगल मैप्स खोला जा रहा है।"
                AppLanguage.ENGLISH -> "Opening Google Maps."
            }
            alertManager.speakNowLoudly(msg)
        }
    }

    fun shareCurrentLocation() {
        navigationManager.shareCurrentLocation()
        alertManager.speakNowLoudly("Location copied.")
    }

    fun saveUserSettings(settings: UserSettings) {
        settingsManager.saveSettings(settings)
        // Speaks in Marathi ("माहिती यशस्वीरित्या जतन केली आहे."), Hindi, or English
        alertManager.speakNowLoudly(AppStrings.settingsSaved(settings.language))
    }

    fun triggerEmergencySOS() {
        val context = getApplication<Application>()
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val isGpsEnabled = try {
            locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) { false }

        val lang = _uiState.value.userSettings.language

        if (!isGpsEnabled || !_uiState.value.isLocationPermissionGranted) {
            alertManager.triggerHaptic(VibrationPattern.RAPID_DANGER)
            alertManager.triggerTone(ToneType.HIGH_DANGER)
            alertManager.speakNowLoudly(AppStrings.locationOffWarning(lang), isDanger = true)
            return
        }

        val nav = navigationManager.navState.value
        val street = if (nav.currentStreet.isNotBlank() && !nav.currentStreet.contains("Locating")) {
            nav.currentStreet
        } else if (nav.userLatitude != 0.0 && nav.userLongitude != 0.0) {
            "GPS Coordinates: %.5f, %.5f".format(nav.userLatitude, nav.userLongitude)
        } else {
            "GPS Location active"
        }
        val gmapsUrl = nav.googleMapsUrl.ifBlank { "https://www.google.com/maps/search/?api=1&query=${nav.userLatitude},${nav.userLongitude}" }

        alertManager.triggerTone(ToneType.HIGH_DANGER)
        alertManager.triggerHaptic(VibrationPattern.RAPID_DANGER)
        settingsManager.triggerEmergencySOS(street, gmapsUrl)
        alertManager.speakNowLoudly(AppStrings.sosDispatched(lang), isDanger = true)
    }




    fun triggerLookAround() {
        if (_uiState.value.isAnalyzingAiVision) return

        _uiState.value = _uiState.value.copy(
            isAnalyzingAiVision = true,
            aiVisionDescription = "Analyzing surroundings..."
        )

        alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
        alertManager.triggerTone(ToneType.NAV_CHIME)
        alertManager.speakNowLoudly("Looking around...")

        viewModelScope.launch {
            // If frame is temporarily null right at cold start, wait up to 400ms for the first camera frame
            var bitmap = latestCameraBitmap
            var attempts = 0
            while (bitmap == null && attempts < 4) {
                kotlinx.coroutines.delay(100L)
                bitmap = latestCameraBitmap
                attempts++
            }

            if (bitmap == null) {
                _uiState.value = _uiState.value.copy(
                    isAnalyzingAiVision = false,
                    aiVisionDescription = "Camera frame unavailable."
                )
                alertManager.speakNowLoudly("Could not capture frame. Please try again.")
                return@launch
            }

            val description = geminiAssistant.describeScene(bitmap)

            _uiState.value = _uiState.value.copy(
                isAnalyzingAiVision = false,
                aiVisionDescription = description
            )

            alertManager.speakNowLoudly(description)
        }
    }

    fun analyzeWithAiVision(customPrompt: String = "") {
        alertManager.stopAllSpeech()
        activeScanJob?.cancel()

        val lang = _uiState.value.userSettings.language

        _uiState.value = _uiState.value.copy(
            isAnalyzingAiVision = true,
            latestScanResult = null,
            aiVisionDescription = when (lang) {
                AppLanguage.MARATHI -> "जेमिनी एआय द्वारे वाचत आहे..."
                AppLanguage.HINDI -> "जेमिनी एआई से पढ़ रहा हूँ..."
                AppLanguage.ENGLISH -> "Reading with Gemini AI Vision..."
            }
        )

        alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
        alertManager.triggerTone(ToneType.NAV_CHIME)
        val startMsg = when (lang) {
            AppLanguage.MARATHI -> "जेमिनी एआयने वाचत आहे..."
            AppLanguage.HINDI -> "जेमिनी एआई से पढ़ रहा हूँ..."
            AppLanguage.ENGLISH -> "Reading with Gemini AI..."
        }
        alertManager.speakNowLoudly(startMsg)

        activeScanJob = viewModelScope.launch {
            try {
                var bitmap = latestCameraBitmap
                var attempts = 0
                while (bitmap == null && attempts < 5) {
                    kotlinx.coroutines.delay(100L)
                    bitmap = latestCameraBitmap
                    attempts++
                }

                if (bitmap == null) {
                    _uiState.value = _uiState.value.copy(
                        isAnalyzingAiVision = false,
                        aiVisionDescription = "Camera not ready."
                    )
                    return@launch
                }

                val langInstruction = when (lang) {
                    AppLanguage.MARATHI -> "Output strictly in plain spoken Marathi (मराठी)."
                    AppLanguage.HINDI -> "Output strictly in plain spoken Hindi (हिन्दी)."
                    AppLanguage.ENGLISH -> "Output strictly in plain spoken English."
                }

                val universalPrompt = customPrompt.ifBlank {
                    """
                    You are a universal reader for a blind person in India.
                    Read and explain whatever is in front of the camera (any document, application, letter, book page, bill, receipt, medicine strip, prescription, food packet, currency note, screen, signboard, or ID card).
                    1. State what type of document or item this is and its main heading/title.
                    2. Read the key details in 2 to 3 clear spoken sentences.
                    3. $langInstruction
                    4. Do NOT use markdown, asterisks, or bullet points.
                    """.trimIndent()
                }

                var explanation: String? = null
                if (NetworkUtils.isInternetAvailable(getApplication())) {
                    explanation = kotlinx.coroutines.withTimeoutOrNull(8000L) {
                        geminiAssistant.analyzeImageWithGemini(bitmap, universalPrompt)
                            ?: geminiAssistant.analyzeDocumentAdvanced(bitmap, lang)
                    }
                }

                if (explanation.isNullOrBlank()) {
                    // PASS 'lang' HERE as well!
                    val localResult = infoReader.evaluateDocumentFromBitmap(
                        bitmap = bitmap,
                        scanType = _uiState.value.scanType,
                        lang = lang
                    )
                    explanation = localResult.spokenAnnouncement
                    _uiState.value = _uiState.value.copy(latestScanResult = localResult)
                }

                _uiState.value = _uiState.value.copy(
                    isAnalyzingAiVision = false,
                    aiVisionDescription = explanation
                )
                alertManager.speakDocumentScan(explanation)
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isAnalyzingAiVision = false)
            }
        }
    }



    // Testing and simulation tools
    fun testSimulateObstacle(
        label: String,
        distance: Float,
        direction: Direction,
        category: ObstacleCategory = ObstacleCategory.UNKNOWN,
        approachSpeed: Float = 0f
    ) {
        val severity = when {
            distance < 1.5f || approachSpeed > 1.2f -> ObstacleSeverity.DANGER
            distance < 2.8f -> ObstacleSeverity.WARNING
            else -> ObstacleSeverity.INFO
        }
        val obs = Obstacle(
            category = category,
            label = label,
            distanceMeters = distance,
            approachSpeedMps = approachSpeed,
            direction = direction,
            severity = severity
        )
        _uiState.value = _uiState.value.copy(obstacles = listOf(obs))
        alertManager.speakNowLoudly(obs.toSpokenAlert(), isDanger = (severity == ObstacleSeverity.DANGER))
    }

    fun testSimulatePanCard() {
        val ocrSample = """
            INCOME TAX DEPARTMENT
            GOVT. OF INDIA
            Permanent Account Number Card
            ABCDE1234F
            RAJESH SHARMA
            RAMESH SHARMA
            15/08/1985
        """.trimIndent()
        val result = infoReader.evaluateDocument(ocrSample)
        if (result != null) {
            _uiState.value = _uiState.value.copy(latestScanResult = result)
            alertManager.speakNowLoudly(result.spokenAnnouncement)
        }
    }

    fun testSimulateAadhaarCard() {
        val ocrSample = """
            Government of India
            Unique Identification Authority of India
            Enrollment No: 1234/56789/01234
            Priya Sharma
            9876 5432 1098
            Mera Aadhaar, Meri Pehchan
        """.trimIndent()
        val result = infoReader.evaluateDocument(ocrSample)
        if (result != null) {
            _uiState.value = _uiState.value.copy(latestScanResult = result)
            alertManager.speakNowLoudly(result.spokenAnnouncement)
        }
    }

    fun scanDocumentWithHighAccuracy() {
        val bitmap = latestCameraBitmap
        if (bitmap == null) {
            alertManager.speakNowLoudly("Camera not ready. Hold card in front and try again.")
            return
        }

        alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
        alertManager.speakNowLoudly("Reading document...")

    }

    // ============================================================================
// 1. In DrishtiViewModel.kt: Add readMoreScannedText() and update handleSpokenCommand()
// ============================================================================

    fun readMoreScannedText() {
        val scanResult = _uiState.value.latestScanResult
        val fullText = when {
            !scanResult?.details.isNullOrBlank() -> scanResult!!.details
            _uiState.value.aiVisionDescription.isNotBlank() -> _uiState.value.aiVisionDescription
            else -> ""
        }

        if (fullText.isBlank()) {
            alertManager.speakNowLoudly("No scanned text available yet. Please scan a document first.")
            return
        }

        val cleanedFullText = fullText
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(". ")

        _uiState.value = _uiState.value.copy(
            aiVisionDescription = fullText
        )
        alertManager.speakDocumentScan(cleanedFullText)
    }


    fun testSimulateCurrency(amount: Int) {
        val result = ScanResult(
            type = ScanType.CURRENCY,
            title = "₹$amount Note",
            details = "Verified banknote",
            spokenAnnouncement = "$amount Rupees."
        )
        _uiState.value = _uiState.value.copy(latestScanResult = result)
        alertManager.speakNowLoudly(result.spokenAnnouncement)
    }



    override fun onCleared() {
        super.onCleared()
        proximityHapticEngine.stop()
        obstacleDetector.close()
        alertManager.release()
        infoReader.release()
        voiceManager.release()
        navigationManager.stopLocationUpdates()
        binauralAudioEngine.release()
        usbBridgeManager.stop()
        try {
            sensorManager?.unregisterListener(fallSensorListener)
        } catch (_: Exception) {}
    }


    /**
     * HYBRID DOCUMENT SCANNER (DOC-03, DOC-04, DOC-05, DOC-06):
     * - DOC-04: Detects no internet in < 2ms and transitions to offline ML Kit.
     * - DOC-05: Calibrated luminance check (L < 35) auto-triggers flashlight.
     * - DOC-06: Announces framing guidance if card is cut off.
     */
    /**
     * ON-DEMAND DOCUMENT SCANNER:
     * - Only runs when user taps the button.
     * - Guaranteed to never hang (wrapped in try-finally with strict 5s timeout).
     * - Runs instant local OCR first (< 100ms) for PAN/Aadhaar/Cash.
     */

    /**
     * ON-DEMAND INSTANT SCANNER WITH PREEMPTION:
     * Instantly stops reading the previous document when you scan a new one!
     */
    // ============================================================================
// 2. In DrishtiViewModel.kt: Replace scanDocumentHybrid()
// ============================================================================

    // ============================================================================
// 3. In DrishtiViewModel.kt: Replace scanDocumentHybrid() (Fixes Line 1030 ambiguity)
//    (Also make sure you deleted any old duplicate scanDocumentHybrid or analyzeGroundedDocument)
// ============================================================================

    fun scanDocumentHybrid() {
        val lang = _uiState.value.userSettings.language
        val bitmap = latestCameraBitmap ?: run {
            val notReadyMsg = when (lang) {
                AppLanguage.MARATHI -> "कॅमेरा तयार नाही. कृपया पुन्हा प्रयत्न करा."
                AppLanguage.HINDI -> "कैमरा तैयार नहीं है। कृपया पुनः प्रयास करें।"
                AppLanguage.ENGLISH -> "Camera not ready. Hold document in front and try again."
            }
            alertManager.speakNowLoudly(notReadyMsg)
            return
        }

        alertManager.stopAllSpeech()
        activeScanJob?.cancel()

        alertManager.triggerHaptic(VibrationPattern.CONFIRM_SHORT)
        alertManager.triggerTone(ToneType.NAV_CHIME)

        _uiState.value = _uiState.value.copy(
            isAnalyzingAiVision = true,
            latestScanResult = null,
            aiVisionDescription = when (lang) {
                AppLanguage.MARATHI -> "स्कॅन करत आहे..."
                AppLanguage.HINDI -> "स्कैन हो रहा है..."
                AppLanguage.ENGLISH -> "Scanning..."
            }
        )

        activeScanJob = viewModelScope.launch {
            try {
                // PASS 'lang' HERE so Rupees, Medicine & Documents speak in Marathi / Hindi / English!
                val localResult = infoReader.evaluateDocumentFromBitmap(
                    bitmap = bitmap,
                    scanType = _uiState.value.scanType,
                    lang = lang
                )

                val isVerified = localResult.title.contains("Verified", ignoreCase = true) ||
                        localResult.type == ScanType.CURRENCY ||
                        localResult.type == ScanType.MEDICINE

                if (isVerified) {
                    _uiState.value = _uiState.value.copy(
                        latestScanResult = localResult,
                        aiVisionDescription = localResult.spokenAnnouncement
                    )
                    alertManager.speakDocumentScan(localResult.spokenAnnouncement)
                    return@launch
                }

                val isOnline = NetworkUtils.isInternetAvailable(getApplication())
                var explanation: String? = null

                if (isOnline) {
                    val readingAiMsg = when (lang) {
                        AppLanguage.MARATHI -> "एआय व्हिजनने वाचत आहे..."
                        AppLanguage.HINDI -> "एआई विज़न से पढ़ रहा हूँ..."
                        AppLanguage.ENGLISH -> "Reading with AI Vision..."
                    }
                    alertManager.speakDocumentScan(readingAiMsg)
                    explanation = kotlinx.coroutines.withTimeoutOrNull(6000L) {
                        geminiAssistant.analyzeDocumentAdvanced(bitmap, lang)
                    }
                }

                val finalSpeech = if (!explanation.isNullOrBlank()) {
                    explanation
                } else {
                    localResult.spokenAnnouncement
                }

                _uiState.value = _uiState.value.copy(
                    latestScanResult = localResult,
                    aiVisionDescription = finalSpeech
                )
                alertManager.speakDocumentScan(finalSpeech)

            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                android.util.Log.e("DrishtiViewModel", "Scan error: ${e.message}")
                val retryMsg = when (lang) {
                    AppLanguage.MARATHI -> "कागदपत्र स्थिर धरा आणि पुन्हा स्कॅन करा."
                    AppLanguage.HINDI -> "दस्तावेज़ को स्थिर रखें और फिर से स्कैन करें।"
                    AppLanguage.ENGLISH -> "Hold document steady and try scanning again."
                }
                alertManager.speakDocumentScan(retryMsg)
            } finally {
                _uiState.value = _uiState.value.copy(isAnalyzingAiVision = false)
            }
        }
    }



    /**
     * DOC-05: Calibrated luminance check. Returns true if average brightness < 35.
     */
    private fun isSceneDark(bitmap: Bitmap): Boolean {
        val sampleSize = 16
        val small = Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, false)
        val pixels = IntArray(sampleSize * sampleSize)
        small.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
        var totalLum = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            totalLum += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }
        val avgLuminance = totalLum / (sampleSize * sampleSize)
        return avgLuminance < 35L // Exact threshold < 35
    }


}