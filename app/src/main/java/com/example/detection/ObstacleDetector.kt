@file:Suppress("SpellCheckingInspection", "DEPRECATION", "UNUSED_VARIABLE")

package com.example.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.camera.core.ImageProxy
import com.example.model.Direction
import com.example.model.Obstacle
import com.example.model.ObstacleCategory
import com.example.model.ObstacleSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.tan

class ObstacleDetector(private val context: Context) {

    private val tag = "ObstacleDetector"

    private val _currentObstacles = MutableStateFlow<List<Obstacle>>(emptyList())
    val currentObstacles: StateFlow<List<Obstacle>> = _currentObstacles.asStateFlow()

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private val processingMutex = Mutex()

    val orientationEstimator = DeviceOrientationEstimator(context)
    val depthEstimator = DepthEstimator(context)
    val geometricReasoner = GeometricReasoner()

    private val inputSize = 640
    private var isNchw = false
    private var isTransposedOutput = false
    private var numChannels = 84
    private var numBoxes = 8400
    private var isCocoModel = true
    var activeModelLoadedName: String = "None"
        private set

    private val verticalFovRad = 1.05f
    private val horizontalFovRad = 1.30f
    private val cameraHeightMeters = 1.35f
    private val corridorHalfWidth = 0.45f

    private val minConfidenceThreshold = 0.30f

    private val reusableInputBuffer: ByteBuffer = ByteBuffer.allocateDirect(4 * 1 * inputSize * inputSize * 3).apply {
        order(ByteOrder.nativeOrder())
    }
    private val reusablePixelArray = IntArray(inputSize * inputSize)
    private var outputBuffer: Array<Array<FloatArray>> = Array(1) { Array(84) { FloatArray(8400) } }

    private data class TrackedItem(
        val id: Int,
        var bbox: RectF,
        var category: ObstacleCategory,
        var label: String,
        var distance: Float,
        var lateralOffset: Float = 0f,
        var inCorridor: Boolean = true,
        var lastSeen: Long,
        var hits: Int,
        var velocityMps: Float = 0f
    )

    private val activeTracks = mutableListOf<TrackedItem>()
    private var nextTrackId = 1

    init {
        initTFLite()
        orientationEstimator.start()
    }

    private fun initTFLite() {
        val modelName = findAvailableModel()
        if (modelName == null) {
            Log.e(tag, "CRITICAL: No .tflite model file found in assets!")
            return
        }
        activeModelLoadedName = modelName

        // 1. Try GPU Delegate First
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, modelName)
            val options = Interpreter.Options()
            val compat = CompatibilityList()

            if (compat.isDelegateSupportedOnThisDevice) {
                gpuDelegate = GpuDelegate(compat.bestOptionsForThisDevice)
                options.addDelegate(gpuDelegate)
            } else {
                options.setNumThreads(4)
            }

            val interp = Interpreter(modelBuffer, options)
            setupTensors(interp)
            interpreter = interp
            Log.i(tag, "Object detector loaded with GPU: $modelName")
            return
        } catch (e: Throwable) {
            Log.w(tag, "GPU Delegate failed. Falling back to CPU: ${e.message}")
            gpuDelegate?.close()
            gpuDelegate = null
        }

        // 2. CPU Fallback
        try {
            val modelBuffer = FileUtil.loadMappedFile(context, modelName)
            val cpuOptions = Interpreter.Options().apply { setNumThreads(4) }
            val interp = Interpreter(modelBuffer, cpuOptions)
            setupTensors(interp)
            interpreter = interp
            Log.i(tag, "Object detector loaded on CPU: $modelName")
        } catch (e: Throwable) {
            Log.e(tag, "Failed to load TFLite model on CPU: ${e.message}")
        }
    }

    private fun setupTensors(interp: Interpreter) {
        val inShape = interp.getInputTensor(0).shape()
        isNchw = (inShape.size == 4 && inShape[1] == 3)

        val outShape = interp.getOutputTensor(0).shape()
        if (outShape.size == 3 && outShape[1] >= outShape[2]) {
            // Transposed: [1, 8400, 84]
            numBoxes = outShape[1]
            numChannels = outShape[2]
            isTransposedOutput = true
            outputBuffer = Array(1) { Array(numBoxes) { FloatArray(numChannels) } }
        } else {
            // Standard: [1, 84, 8400]
            numChannels = if (outShape.size >= 2) outShape[1] else 84
            numBoxes = if (outShape.size >= 3) outShape[2] else 8400
            isTransposedOutput = false
            outputBuffer = Array(1) { Array(numChannels) { FloatArray(numBoxes) } }
        }
        isCocoModel = (numChannels >= 80)
    }

    private fun findAvailableModel(): String? {
        return try {
            val assets = context.assets.list("") ?: emptyArray()
            when {
                "yolo_indian_roads_fp16.tflite" in assets -> "yolo_indian_roads_fp16.tflite"
                "yolo_indoor_fp16.tflite" in assets -> "yolo_indoor_fp16.tflite"
                "best.tflite" in assets -> "best.tflite"
                else -> assets.firstOrNull { it.endsWith(".tflite") }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun processImageProxy(imageProxy: ImageProxy) {
        scope.launch {
            if (!processingMutex.tryLock()) {
                imageProxy.close()
                return@launch
            }

            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmap = imageProxy.toBitmap()
                val rotatedBitmap = if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }

                // Check for camera occlusion
                val avgLuminance = calculateAverageLuminance(rotatedBitmap)
                if (avgLuminance < 12.0f) {
                    _currentObstacles.value = listOf(
                        Obstacle(
                            trackId = 9991,
                            category = ObstacleCategory.UNKNOWN,
                            label = "Camera blocked",
                            distanceMeters = 0.0f,
                            direction = Direction.CENTER,
                            severity = ObstacleSeverity.DANGER,
                            confidence = 1.0f
                        )
                    )
                    return@launch
                }

                val depthMap = depthEstimator.estimateDepth(rotatedBitmap)
                val detected = runInferencePipeline(rotatedBitmap, depthMap)
                _currentObstacles.value = detected

            } catch (e: Exception) {
                Log.e(tag, "Inference error: ${e.message}")
            } finally {
                processingMutex.unlock()
                imageProxy.close()
            }
        }
    }

    private fun calculateAverageLuminance(bitmap: Bitmap): Float {
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

    private fun runInferencePipeline(bitmap: Bitmap, depthMap: DepthMap?): List<Obstacle> {
        // Suppress alerts if bending down
        if (orientationEstimator.pitchAngleRadians > 0.96f) {
            activeTracks.clear()
            return emptyList()
        }

        val results = mutableListOf<Obstacle>()

        // 1. Geometric hazards (drains, drop-offs)
        if (depthMap != null) {
            try {
                val geometricHazards = geometricReasoner.analyzeGeometry(
                    depthMap = depthMap,
                    phonePitchRad = orientationEstimator.pitchAngleRadians
                )

                for (hazard in geometricHazards) {
                    val isDrop = (hazard.type == HazardType.NEGATIVE_DROP_OFF)
                    val category = when {
                        isDrop && hazard.distanceMeters <= 1.8f -> ObstacleCategory.OPEN_DRAIN
                        isDrop -> ObstacleCategory.STAIRS_DOWN
                        else -> ObstacleCategory.UNKNOWN
                    }

                    val label = when (category) {
                        ObstacleCategory.OPEN_DRAIN -> "Open drain ahead"
                        ObstacleCategory.STAIRS_DOWN -> "Drop-off ahead"
                        else -> "Obstacle"
                    }

                    results.add(
                        Obstacle(
                            trackId = nextTrackId++,
                            category = category,
                            label = label,
                            distanceMeters = roundTo1Decimal(hazard.distanceMeters),
                            direction = hazard.direction,
                            severity = hazard.severity,
                            confidence = 0.98f
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Geometric reasoning pipeline error: ${e.message}")
            }
        }

        // 2. YOLO object detection + depth fusion
        val yoloBoxes = runYoloInference(bitmap)
        for (raw in yoloBoxes) {
            val finalDistance = if (depthMap != null) {
                depthMap.getMedianDistanceForBox(raw.rect)
            } else {
                raw.distance
            }

            val centerX = raw.rect.centerX()
            val pixelAngleX = (centerX - 0.5f) * horizontalFovRad
            val lateralOffset = finalDistance * tan(pixelAngleX.toDouble()).toFloat()

            val direction = when {
                centerX > 0.62f || lateralOffset > corridorHalfWidth -> Direction.RIGHT
                centerX < 0.38f || lateralOffset < -corridorHalfWidth -> Direction.LEFT
                else -> Direction.CENTER
            }

            val inCorridor = (direction == Direction.CENTER)
            val severity = when {
                inCorridor && finalDistance <= 1.2f -> ObstacleSeverity.DANGER
                inCorridor && finalDistance <= 2.5f -> ObstacleSeverity.WARNING
                else -> ObstacleSeverity.INFO
            }

            results.add(
                Obstacle(
                    trackId = nextTrackId++,
                    category = raw.category,
                    label = raw.label,
                    distanceMeters = roundTo1Decimal(finalDistance),
                    direction = direction,
                    severity = severity,
                    boundingBox = raw.rect,
                    confidence = raw.score
                )
            )
        }

        return updateTracking(results)
    }

    private fun runYoloInference(bitmap: Bitmap): List<RawBox> {
        val interp = interpreter ?: return emptyList()

        val srcW = bitmap.width.toFloat()
        val srcH = bitmap.height.toFloat()
        val scale = minOf(inputSize / srcW, inputSize / srcH)
        val scaledW = (srcW * scale).toInt()
        val scaledH = (srcH * scale).toInt()
        val padX = (inputSize - scaledW) / 2f
        val padY = (inputSize - scaledH) / 2f

        val letterboxed = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = Rect(padX.toInt(), padY.toInt(), (padX + scaledW).toInt(), (padY + scaledH).toInt())
        canvas.drawBitmap(bitmap, srcRect, dstRect, null)

        synchronized(reusableInputBuffer) {
            reusableInputBuffer.rewind()
            letterboxed.getPixels(reusablePixelArray, 0, inputSize, 0, 0, inputSize, inputSize)

            if (isNchw) {
                val totalPixels = inputSize * inputSize
                for (c in 0..2) {
                    val shift = (2 - c) * 8
                    for (p in 0 until totalPixels) {
                        val v = ((reusablePixelArray[p] shr shift) and 0xFF) / 255.0f
                        reusableInputBuffer.putFloat(v)
                    }
                }
            } else {
                var p = 0
                val totalPixels = inputSize * inputSize
                while (p < totalPixels) {
                    val pixel = reusablePixelArray[p++]
                    reusableInputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                    reusableInputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                    reusableInputBuffer.putFloat((pixel and 0xFF) / 255.0f)
                }
            }

            reusableInputBuffer.rewind()
            interp.run(reusableInputBuffer, outputBuffer)

            return parseYoloOutput(outputBuffer, padX, padY, scaledW.toFloat(), scaledH.toFloat())
        }
    }

    private fun parseYoloOutput(
        outputData: Array<Array<FloatArray>>,
        padX: Float,
        padY: Float,
        scaledW: Float,
        scaledH: Float
    ): List<RawBox> {
        val boxes = mutableListOf<RawBox>()
        val numClasses = numChannels - 4
        val data2D = outputData[0]

        for (i in 0 until numBoxes) {
            var maxScore = 0f
            var bestClass = -1

            for (c in 0 until numClasses) {
                val score = if (isTransposedOutput) data2D[i][4 + c] else data2D[4 + c][i]
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            val category: ObstacleCategory? = if (isCocoModel) {
                when (bestClass) {
                    0 -> ObstacleCategory.PERSON
                    56 -> ObstacleCategory.CHAIR
                    57 -> ObstacleCategory.COUCH
                    60 -> ObstacleCategory.TABLE
                    15, 16, 17, 18, 19 -> ObstacleCategory.ANIMAL
                    1, 3 -> ObstacleCategory.TWO_WHEELER
                    2, 5, 7 -> ObstacleCategory.BUS_CAR
                    else -> null
                }
            } else {
                when (bestClass) {
                    2 -> ObstacleCategory.TWO_WHEELER
                    3 -> ObstacleCategory.AUTO_RICKSHAW
                    0, 1, 4, 5 -> ObstacleCategory.BUS_CAR
                    else -> ObstacleCategory.UNKNOWN
                }
            }

            val label = when (category) {
                ObstacleCategory.PERSON -> "Person"
                ObstacleCategory.AUTO_RICKSHAW -> "Auto Rickshaw"
                ObstacleCategory.TWO_WHEELER -> "Two Wheeler"
                ObstacleCategory.BUS_CAR -> "Vehicle"
                ObstacleCategory.ANIMAL -> "Animal"
                ObstacleCategory.CHAIR -> "Chair"
                ObstacleCategory.TABLE -> "Table"
                ObstacleCategory.COUCH -> "Couch"
                else -> "Obstacle"
            }

            if (category != null && maxScore >= minConfidenceThreshold) {
                val rawCx = if (isTransposedOutput) data2D[i][0] else data2D[0][i]
                val rawCy = if (isTransposedOutput) data2D[i][1] else data2D[1][i]
                val rawW = if (isTransposedOutput) data2D[i][2] else data2D[2][i]
                val rawH = if (isTransposedOutput) data2D[i][3] else data2D[3][i]

                val scaleMultiplier = if (rawW <= 1.0f && rawH <= 1.0f) inputSize.toFloat() else 1.0f
                val pixelCx = rawCx * scaleMultiplier
                val pixelCy = rawCy * scaleMultiplier
                val pixelW = rawW * scaleMultiplier
                val pixelH = rawH * scaleMultiplier

                val cxUnpadded = (pixelCx - padX) / scaledW
                val cyUnpadded = (pixelCy - padY) / scaledH
                val wUnpadded = pixelW / scaledW
                val hUnpadded = pixelH / scaledH

                val rect = RectF(
                    (cxUnpadded - wUnpadded / 2f).coerceIn(0f, 1f),
                    (cyUnpadded - hUnpadded / 2f).coerceIn(0f, 1f),
                    (cxUnpadded + wUnpadded / 2f).coerceIn(0f, 1f),
                    (cyUnpadded + hUnpadded / 2f).coerceIn(0f, 1f)
                )

                val bottomY = rect.bottom.coerceIn(0.1f, 1.0f)
                val pixelAngleY = (bottomY - 0.5f) * verticalFovRad
                val totalGroundAngle = orientationEstimator.pitchAngleRadians + pixelAngleY
                val tanGround = tan(totalGroundAngle.toDouble()).toFloat()

                val raycastDist = if (totalGroundAngle > 0.08f && tanGround > 0.01f) {
                    (cameraHeightMeters / tanGround).coerceIn(0.4f, 10.0f)
                } else {
                    calculateOpticalHeightDistance(category, rect.height().coerceIn(0.08f, 0.95f))
                }

                boxes.add(
                    RawBox(
                        category = category,
                        label = label,
                        rect = rect,
                        distance = raycastDist,
                        score = maxScore
                    )
                )
            }
        }

        return applyNms(boxes)
    }

    private fun calculateOpticalHeightDistance(category: ObstacleCategory, boxHeight: Float): Float {
        val realHeight = when (category) {
            ObstacleCategory.PERSON -> 1.65f
            ObstacleCategory.CHAIR -> 0.85f
            ObstacleCategory.TABLE -> 0.75f
            ObstacleCategory.AUTO_RICKSHAW -> 1.70f
            ObstacleCategory.TWO_WHEELER -> 1.10f
            ObstacleCategory.BUS_CAR -> 2.20f
            ObstacleCategory.ANIMAL -> 1.20f
            else -> 1.40f
        }
        val safeHeight = boxHeight.coerceIn(0.04f, 1.0f)
        val rawDist = (realHeight * 850f) / (safeHeight * 1080f)
        return (0.92f * rawDist + 0.15f).coerceIn(0.5f, 6.0f)
    }

    private fun updateTracking(rawList: List<Obstacle>): List<Obstacle> {
        val now = System.currentTimeMillis()
        activeTracks.removeAll { (now - it.lastSeen) > 800L }

        for (raw in rawList) {
            var matched: TrackedItem? = null
            var bestScore = 0f

            for (track in activeTracks) {
                val iou = calculateIoU(track.bbox, raw.boundingBox)
                val dx = track.bbox.centerX() - raw.boundingBox.centerX()
                val dy = track.bbox.centerY() - raw.boundingBox.centerY()
                val distScreen = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                val matchScore = if (iou > 0.15f) iou else if (distScreen < 0.25f) (0.25f - distScreen) else 0f

                if (matchScore > bestScore) {
                    bestScore = matchScore
                    matched = track
                }
            }

            val centerX = raw.boundingBox.centerX()
            val pixelAngleX = (centerX - 0.5f) * horizontalFovRad
            val lateralOffset = raw.distanceMeters * tan(pixelAngleX.toDouble()).toFloat()
            val inCorridor = (raw.direction == Direction.CENTER)

            if (matched != null) {
                val dt = max(0.03f, (now - matched.lastSeen) / 1000f)
                val instantSpeed = (matched.distance - raw.distanceMeters) / dt
                matched.velocityMps = 0.6f * matched.velocityMps + 0.4f * instantSpeed
                matched.distance = 0.5f * matched.distance + 0.5f * raw.distanceMeters
                matched.lateralOffset = lateralOffset
                matched.inCorridor = inCorridor
                matched.bbox = raw.boundingBox
                matched.lastSeen = now
                matched.hits++
            } else {
                activeTracks.add(
                    TrackedItem(
                        id = nextTrackId++,
                        bbox = raw.boundingBox,
                        category = raw.category,
                        label = raw.label,
                        distance = raw.distanceMeters,
                        lateralOffset = lateralOffset,
                        inCorridor = inCorridor,
                        lastSeen = now,
                        hits = 1
                    )
                )
            }
        }

        return activeTracks.filter { it.hits >= 1 }.map { t ->
            val direction = when {
                t.bbox.centerX() > 0.62f || t.lateralOffset > corridorHalfWidth -> Direction.RIGHT
                t.bbox.centerX() < 0.38f || t.lateralOffset < -corridorHalfWidth -> Direction.LEFT
                else -> Direction.CENTER
            }

            val severity = when {
                t.inCorridor && t.distance <= 1.2f -> ObstacleSeverity.DANGER
                t.inCorridor && t.distance <= 2.5f -> ObstacleSeverity.WARNING
                else -> ObstacleSeverity.INFO
            }

            Obstacle(
                trackId = t.id,
                category = t.category,
                label = t.label,
                distanceMeters = roundTo1Decimal(t.distance),
                approachSpeedMps = roundTo1Decimal(t.velocityMps),
                direction = direction,
                severity = severity,
                boundingBox = t.bbox,
                confidence = 0.90f,
                consecutiveHits = t.hits
            )
        }.sortedBy { it.distanceMeters }
    }

    private fun applyNms(boxes: List<RawBox>): List<RawBox> {
        val sorted = boxes.sortedByDescending { it.score }
        val result = mutableListOf<RawBox>()
        for (b in sorted) {
            if (result.none { calculateIoU(b.rect, it.rect) > 0.40f }) {
                result.add(b)
            }
        }
        return result
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = (a.width() * a.height()) + (b.width() * b.height()) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun roundTo1Decimal(value: Float): Float {
        return round(value * 10f) / 10f
    }

    fun clearObstacles() {
        activeTracks.clear()
        _currentObstacles.value = emptyList()
    }

    fun close() {
        orientationEstimator.stop()
        depthEstimator.close()
        interpreter?.close()
        gpuDelegate?.close()
    }

    private data class RawBox(
        val category: ObstacleCategory,
        val label: String,
        val rect: RectF,
        val distance: Float,
        val score: Float
    )
}

class DeviceOrientationEstimator(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    @Volatile
    var rotationMatrix = FloatArray(9).apply {
        this[0] = 1f; this[4] = 1f; this[8] = 1f
    }
        private set

    @Volatile
    var pitchAngleRadians: Float = 0.22f
        private set

    private val tempMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(tempMatrix, event.values)
            System.arraycopy(tempMatrix, 0, rotationMatrix, 0, 9)

            SensorManager.getOrientation(tempMatrix, orientationAngles)
            val rawPitch = -orientationAngles[1]
            pitchAngleRadians = 0.85f * pitchAngleRadians + 0.15f * rawPitch.coerceIn(0.05f, 1.40f)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}