// ============================================================================
// 1. In CaptureGuider.kt: Replace the file contents with 4-Direction (Left, Right, Up, Down, Closer, Back)
//    framing guidance for Documents, Rupees, and Medicine in English, Hindi, and Marathi
// ============================================================================

package com.example.reader

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.model.AppLanguage
import kotlin.math.abs

enum class GuidanceInstruction {
    MOVE_LEFT,
    MOVE_RIGHT,
    MOVE_UP,
    MOVE_DOWN,
    MOVE_CLOSER,
    MOVE_FARTHER,
    TOO_DARK,
    BLURRY_HOLD_STEADY,
    HOLD_STEADY,
    READY_TO_CAPTURE;

    fun getSpoken(lang: AppLanguage): String = when (lang) {
        AppLanguage.MARATHI -> when (this) {
            MOVE_LEFT -> "कॅमेरा थोडा डाव्या बाजूला सरकवा."
            MOVE_RIGHT -> "कॅमेरा थोडा उजव्या बाजूला सरकवा."
            MOVE_UP -> "कॅमेरा थोडा वर करा."
            MOVE_DOWN -> "कॅमेरा थोडा खाली करा."
            MOVE_CLOSER -> "वस्तू खूप लांब आहे, कॅमेरा थोडा जवळ न्या."
            MOVE_FARTHER -> "कागदपत्र कडेला कापले जात आहे, कॅमेरा थोडा मागे घ्या."
            TOO_DARK -> "प्रकाश कमी आहे."
            BLURRY_HOLD_STEADY -> "हात स्थिर ठेवा."
            HOLD_STEADY -> "बरोबर मध्यभागी आहे. स्थिर धरा."
            READY_TO_CAPTURE -> "वाचत आहे."
        }
        AppLanguage.HINDI -> when (this) {
            MOVE_LEFT -> "कैमरा थोड़ा बाईं ओर ले जाएं।"
            MOVE_RIGHT -> "कैमरा थोड़ा दाईं ओर ले जाएं।"
            MOVE_UP -> "कैमरा थोड़ा ऊपर करें।"
            MOVE_DOWN -> "कैमरा थोड़ा नीचे करें।"
            MOVE_CLOSER -> "कैमरा थोड़ा पास ले जाएं।"
            MOVE_FARTHER -> "किनारे कट रहे हैं, कैमरा थोड़ा पीछे ले जाएं।"
            TOO_DARK -> "रोशनी कम है।"
            BLURRY_HOLD_STEADY -> "फोन को स्थिर रखें।"
            HOLD_STEADY -> "बिल्कुल बीच में है। स्थिर रखें।"
            READY_TO_CAPTURE -> "पढ़ रहा हूँ।"
        }
        AppLanguage.ENGLISH -> when (this) {
            MOVE_LEFT -> "Move camera slightly left."
            MOVE_RIGHT -> "Move camera slightly right."
            MOVE_UP -> "Move camera slightly up."
            MOVE_DOWN -> "Move camera slightly down."
            MOVE_CLOSER -> "Move camera closer."
            MOVE_FARTHER -> "Cut off at edges. Move camera slightly back."
            TOO_DARK -> "Lighting is too dark."
            BLURRY_HOLD_STEADY -> "Hold phone steady."
            HOLD_STEADY -> "Centered in frame. Hold steady."
            READY_TO_CAPTURE -> "Reading now."
        }
    }
}

class CaptureGuider {

    private var stableFrameCount = 0
    private var lastSpokenInstruction = ""
    private var lastInstructionTime = 0L

    /**
     * Evaluates the position of a document, currency note, or medicine pack in the camera frame.
     * Uses OCR text boxes if available, or falls back to visual rectangular object detection.
     */
    fun evaluateLiveFraming(
        bitmap: Bitmap,
        textBoundingBoxes: List<RectF>
    ): Pair<GuidanceInstruction?, Boolean> {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        // 1. Lighting check
        val avgLum = computeAverageLuminance(bitmap)
        if (avgLum < 22f) {
            return Pair(GuidanceInstruction.TOO_DARK, false)
        }

        // 2. Blur / Motion Check
        val blurScore = estimateSharpness(bitmap)
        if (blurScore < 32f) {
            stableFrameCount = 0
            return Pair(GuidanceInstruction.BLURRY_HOLD_STEADY, false)
        }

        // 3. Find target rectangle (from OCR text boxes OR visual object contour for Rupees/Medicine)
        val normRect: RectF = if (textBoundingBoxes.isNotEmpty()) {
            val union = RectF()
            textBoundingBoxes.forEach { union.union(it) }
            RectF(
                (union.left / width).coerceIn(0f, 1f),
                (union.top / height).coerceIn(0f, 1f),
                (union.right / width).coerceIn(0f, 1f),
                (union.bottom / height).coerceIn(0f, 1f)
            )
        } else {
            detectVisualObjectRect(bitmap) ?: run {
                stableFrameCount = 0
                return Pair(GuidanceInstruction.MOVE_CLOSER, false)
            }
        }

        val boxW = normRect.width()
        val boxH = normRect.height()
        val coverage = boxW * boxH
        val centerX = normRect.centerX()
        val centerY = normRect.centerY()

        // Check if cut off on edges
        val cutLeft = normRect.left <= 0.03f
        val cutRight = normRect.right >= 0.97f
        val cutTop = normRect.top <= 0.03f
        val cutBottom = normRect.bottom >= 0.97f

        val instruction = when {
            // Cut off on opposite sides simultaneously -> Too close!
            (cutLeft && cutRight) || (cutTop && cutBottom) || coverage > 0.90f -> GuidanceInstruction.MOVE_FARTHER

            // Cut off or shifted to Left -> Move camera Left
            cutLeft || centerX < 0.34f -> GuidanceInstruction.MOVE_LEFT

            // Cut off or shifted to Right -> Move camera Right
            cutRight || centerX > 0.66f -> GuidanceInstruction.MOVE_RIGHT

            // Cut off or shifted to Top -> Move camera Up
            cutTop || centerY < 0.32f -> GuidanceInstruction.MOVE_UP

            // Cut off or shifted to Bottom -> Move camera Down
            cutBottom || centerY > 0.68f -> GuidanceInstruction.MOVE_DOWN

            // Too small / far away
            coverage < 0.10f -> GuidanceInstruction.MOVE_CLOSER

            else -> GuidanceInstruction.HOLD_STEADY
        }

        return if (instruction == GuidanceInstruction.HOLD_STEADY) {
            stableFrameCount++
            if (stableFrameCount >= 2) {
                Pair(GuidanceInstruction.READY_TO_CAPTURE, true)
            } else {
                Pair(GuidanceInstruction.HOLD_STEADY, false)
            }
        } else {
            stableFrameCount = 0
            Pair(instruction, false)
        }
    }

    /**
     * Fast 32x32 contrast & saturation bounding box detector.
     * Detects currency notes, medicine strips, or cards even before OCR text is readable.
     */
    private fun detectVisualObjectRect(bitmap: Bitmap): RectF? {
        val grid = 32
        val small = Bitmap.createScaledBitmap(bitmap, grid, grid, false)
        val pixels = IntArray(grid * grid)
        small.getPixels(pixels, 0, grid, 0, 0, grid, grid)

        // Estimate background luminance from outer border pixels
        var borderSum = 0f
        var borderCount = 0
        for (i in 0 until grid) {
            borderSum += pixelLum(pixels[i]) + pixelLum(pixels[(grid - 1) * grid + i])
            borderCount += 2
        }
        val bgLum = borderSum / borderCount

        var minX = grid
        var maxX = 0
        var minY = grid
        var maxY = 0
        var activeCells = 0

        for (y in 1 until grid - 1) {
            for (x in 1 until grid - 1) {
                val lum = pixelLum(pixels[y * grid + x])
                val rightLum = pixelLum(pixels[y * grid + x + 1])
                val downLum = pixelLum(pixels[(y + 1) * grid + x])
                val edgeGrad = abs(lum - rightLum) + abs(lum - downLum)

                // Cell belongs to foreground document/note if it has strong edges or contrasts with background
                if (edgeGrad > 28f || abs(lum - bgLum) > 35f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    activeCells++
                }
            }
        }

        if (activeCells < 18 || maxX <= minX || maxY <= minY) return null

        return RectF(
            minX.toFloat() / grid,
            minY.toFloat() / grid,
            maxX.toFloat() / grid,
            maxY.toFloat() / grid
        )
    }

    private fun pixelLum(p: Int): Float {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    private fun estimateSharpness(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 48, 48, false)
        val pixels = IntArray(48 * 48)
        small.getPixels(pixels, 0, 48, 0, 0, 48, 48)

        var laplacianSum = 0f
        var laplacianSqSum = 0f
        val count = 46 * 46

        for (y in 1 until 47) {
            for (x in 1 until 47) {
                val center = (pixels[y * 48 + x] and 0xFF)
                val up = (pixels[(y - 1) * 48 + x] and 0xFF)
                val down = (pixels[(y + 1) * 48 + x] and 0xFF)
                val left = (pixels[y * 48 + (x - 1)] and 0xFF)
                val right = (pixels[y * 48 + (x + 1)] and 0xFF)

                val lap = (up + down + left + right - 4 * center).toFloat()
                laplacianSum += lap
                laplacianSqSum += lap * lap
            }
        }

        val mean = laplacianSum / count
        return (laplacianSqSum / count) - (mean * mean)
    }

    private fun computeAverageLuminance(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, false)
        val pixels = IntArray(256)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        var sum = 0L
        for (p in pixels) {
            sum += pixelLum(p).toLong()
        }
        return (sum / 256).toFloat()
    }

    fun shouldSpeakGuidance(instruction: GuidanceInstruction): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastInstructionTime >= 10_000L) {
            lastSpokenInstruction = instruction.name
            lastInstructionTime = now
            return true
        }
        return false
    }

    fun reset() {
        stableFrameCount = 0
        lastSpokenInstruction = ""
    }
}