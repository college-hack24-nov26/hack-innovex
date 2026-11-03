@file:Suppress("SpellCheckingInspection")

package com.example.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthEstimator(private val context: Context) {

    private val tag = "DepthEstimator"
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Model dimensions (dynamically inspected from .tflite tensor)
    private var inputH = 518
    private var inputW = 518
    private var isModelLoaded = false

    init {
        initDepthModel()
    }

    private fun initDepthModel() {
        try {
            val modelName = "depth_anything_v2_small.tflite"
            val modelBuffer = FileUtil.loadMappedFile(context, modelName)
            val options = Interpreter.Options()

            val compat = CompatibilityList()
            if (compat.isDelegateSupportedOnThisDevice) {
                // Direct GPU delegate instantiation without missing options classes
                gpuDelegate = GpuDelegate()
                options.addDelegate(gpuDelegate)
            } else {
                options.setNumThreads(4)
            }

            val interp = Interpreter(modelBuffer, options)
            val shape = interp.getInputTensor(0).shape()
            if (shape.size == 4) {
                if (shape[1] == 3) {
                    inputH = shape[2]
                    inputW = shape[3]
                } else {
                    inputH = shape[1]
                    inputW = shape[2]
                }
            }

            interpreter = interp
            isModelLoaded = true
            Log.i(tag, "Depth Anything V2 loaded successfully! Input: ${inputW}x${inputH}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to load Depth Anything V2: ${e.message}")
            gpuDelegate?.close()
            gpuDelegate = null
        }
    }

    /**
     * Runs dense depth estimation on a camera frame.
     * Returns a DepthMap containing the 3D metric grid.
     */
    suspend fun estimateDepth(bitmap: Bitmap): DepthMap? = withContext(Dispatchers.Default) {
        val interp = interpreter ?: return@withContext null

        try {
            val resized = Bitmap.createScaledBitmap(bitmap, inputW, inputH, true)
            val inputBuffer = ByteBuffer.allocateDirect(4 * 1 * 3 * inputH * inputW).apply {
                order(ByteOrder.nativeOrder())
                rewind()
            }

            val pixels = IntArray(inputW * inputH)
            resized.getPixels(pixels, 0, inputW, 0, 0, inputW, inputH)

            // ImageNet Normalization: (pixel/255.0 - mean) / std
            val meanR = 0.485f; val meanG = 0.456f; val meanB = 0.406f
            val stdR = 0.229f;  val stdG = 0.224f;  val stdB = 0.225f

            // Pack in NCHW format (Channel R, Channel G, Channel B)
            val totalPixels = inputW * inputH
            // Red channel
            for (p in 0 until totalPixels) {
                val r = ((pixels[p] shr 16) and 0xFF) / 255.0f
                inputBuffer.putFloat((r - meanR) / stdR)
            }
            // Green channel
            for (p in 0 until totalPixels) {
                val g = ((pixels[p] shr 8) and 0xFF) / 255.0f
                inputBuffer.putFloat((g - meanG) / stdG)
            }
            // Blue channel
            for (p in 0 until totalPixels) {
                val b = (pixels[p] and 0xFF) / 255.0f
                inputBuffer.putFloat((b - meanB) / stdB)
            }

            inputBuffer.rewind()

            // Output tensor: [1, inputH, inputW]
            val output = Array(1) { Array(inputH) { FloatArray(inputW) } }
            interp.run(inputBuffer, output)

            // Normalize raw output disparity to [0.0..1.0] range
            var minVal = Float.MAX_VALUE
            var maxVal = Float.MIN_VALUE
            val rawGrid = output[0]

            for (y in 0 until inputH) {
                for (x in 0 until inputW) {
                    val v = rawGrid[y][x]
                    if (v < minVal) minVal = v
                    if (v > maxVal) maxVal = v
                }
            }

            val range = maxOf(0.0001f, maxVal - minVal)
            val normalizedGrid = Array(inputH) { y ->
                FloatArray(inputW) { x ->
                    (rawGrid[y][x] - minVal) / range
                }
            }

            DepthMap(normalizedGrid, inputH, inputW)
        } catch (e: Exception) {
            Log.e(tag, "Depth inference error: ${e.message}")
            null
        }
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}