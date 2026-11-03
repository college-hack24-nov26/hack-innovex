package com.example.ui

import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.Obstacle
import com.example.model.ObstacleSeverity
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraPreviewWithOverlay(
    modifier: Modifier = Modifier,
    isTorchOn: Boolean = false,
    obstacles: List<Obstacle> = emptyList(),
    onFrameCaptured: (ImageProxy) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraRef by remember { mutableStateOf<Camera?>(null) }

    // Reactively update hardware LED torch whenever isTorchOn changes or camera binds
    LaunchedEffect(isTorchOn, cameraRef) {
        val cam = cameraRef
        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
            try {
                cam.cameraControl.enableTorch(isTorchOn)
                Log.d("CameraPreview", "Hardware torch toggled: $isTorchOn")
            } catch (e: Exception) {
                Log.w("CameraPreview", "Hardware torch toggle error: ${e.message}")
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        // Correctly initialize ImageAnalysis.Builder first
                        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                androidx.camera.core.resolutionselector.ResolutionStrategy(
                                    android.util.Size(1080, 1920),
                                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()

                        val imageAnalysisBuilder = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                        val imageAnalysis = imageAnalysisBuilder.build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            onFrameCaptured(imageProxy)
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                        cameraProvider.unbindAll()

                        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )

                            cameraRef = camera
                            if (camera.cameraInfo.hasFlashUnit()) {
                                camera.cameraControl.enableTorch(isTorchOn)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CameraPreview", "Camera binding error: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            update = {
                cameraRef?.let { cam ->
                    if (cam.cameraInfo.hasFlashUnit()) {
                        try {
                            cam.cameraControl.enableTorch(isTorchOn)
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }
            }
        )

        // Real-Time High-Contrast Assistive AR HUD Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val leftBound = width * 0.28f
            val rightBound = width * 0.72f

            drawLine(
                color = Color(0x5538BDF8),
                start = Offset(leftBound, height * 0.25f),
                end = Offset(leftBound, height * 0.95f),
                strokeWidth = 4f
            )
            drawLine(
                color = Color(0x5538BDF8),
                start = Offset(rightBound, height * 0.25f),
                end = Offset(rightBound, height * 0.95f),
                strokeWidth = 4f
            )

            for (obs in obstacles) {
                val box = obs.boundingBox
                val left = box.left * width
                val top = box.top * height
                val right = box.right * width
                val bottom = box.bottom * height
                val boxWidth = right - left
                val boxHeight = bottom - top

                val (boxColor, nativeColor) = when (obs.severity) {
                    ObstacleSeverity.DANGER -> Color(0xFFEF4444) to AndroidColor.parseColor("#EF4444")
                    ObstacleSeverity.WARNING -> Color(0xFFF97316) to AndroidColor.parseColor("#F97316")
                    ObstacleSeverity.INFO -> Color(0xFF38BDF8) to AndroidColor.parseColor("#38BDF8")
                }

                drawRect(
                    color = boxColor,
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    style = Stroke(width = 6f)
                )

                drawRect(
                    color = boxColor.copy(alpha = 0.22f),
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight)
                )

                drawContext.canvas.nativeCanvas.apply {
                    val paintBg = Paint().apply {
                        color = nativeColor
                        style = Paint.Style.FILL
                    }
                    val paintText = Paint().apply {
                        color = AndroidColor.WHITE
                        textSize = 36f
                        isFakeBoldText = true
                        isAntiAlias = true
                    }

                    val labelText = "${obs.label} • ${"%.1f".format(obs.distanceMeters)}m"
                    val textWidth = paintText.measureText(labelText)
                    val textHeight = 44f

                    val pillTop = (top - textHeight - 12f).coerceAtLeast(16f)
                    drawRoundRect(
                        left,
                        pillTop,
                        left + textWidth + 24f,
                        pillTop + textHeight + 8f,
                        12f,
                        12f,
                        paintBg
                    )

                    drawText(labelText, left + 12f, pillTop + textHeight - 4f, paintText)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraRef?.cameraControl?.enableTorch(false)
            } catch (e: Exception) {
                // Ignore
            }
            cameraExecutor.shutdown()
        }
    }
}