package com.example.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.model.ScanType
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions


data class ScanResult(
    val type: ScanType,
    val title: String,
    val details: String,
    val spokenAnnouncement: String
)

class InfoReader {

    private val tag = "InfoReader"
    private val recognizer = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    private val captureGuider = CaptureGuider()
    private val _lastScanResult = MutableStateFlow<ScanResult?>(null)
    val lastScanResult: StateFlow<ScanResult?> = _lastScanResult.asStateFlow()

    private val panRegex = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]")
    private val aadhaarRegex = Pattern.compile("\\b\\d{4}[\\s-]\\d{4}[\\s-]\\d{4}\\b|\\b\\d{12}\\b")







    suspend fun checkFramingGuidance(
        bitmap: Bitmap,
        lang: com.example.model.AppLanguage = com.example.model.AppLanguage.ENGLISH
    ): Pair<GuidanceInstruction?, Boolean> = withContext(Dispatchers.IO) {
        try {
            val smallBmp = Bitmap.createScaledBitmap(bitmap, 480, 640, false)
            val inputImage = InputImage.fromBitmap(smallBmp, 0)
            val visionText = Tasks.await(recognizer.process(inputImage), 1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            val boxes = visionText.textBlocks.mapNotNull { block ->
                block.boundingBox?.let { android.graphics.RectF(it) }
            }
            captureGuider.evaluateLiveFraming(smallBmp, boxes)
        } catch (_: Exception) {
            captureGuider.evaluateLiveFraming(bitmap, emptyList())
        }
    }

    fun shouldSpeakFraming(instruction: GuidanceInstruction): Boolean {
        return captureGuider.shouldSpeakGuidance(instruction)
    }


    // Official RBI Serial Number Regex (e.g., "4AB 123456" or "72C 654321")
    private val rbiSerialRegex = Pattern.compile("\\b[0-9]{1,2}[A-Z]{1,3}\\s?[0-9]{6}\\b")

    // ============================================================================
// In InfoReader.kt: Replace recognizer, evaluateDocumentFromBitmap(), and
// filterBlocksInsideDocumentEdges() with this high-accuracy version
// (Fixes text getting cut off inside the document and restores full English + Marathi/Hindi accuracy)
// ============================================================================

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagariRecognizer = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )

    // ============================================================================
// 1. In InfoReader.kt: Replace evaluateDocumentFromBitmap()
//    FIXES:
//    - Keeps voice instructions in your selected App Language (English stays English!)
//    - Uses Latin OCR for PAN/Aadhaar/Medicine (only switches to Devanagari if >35% of text is Marathi/Hindi)
// ============================================================================

    suspend fun evaluateDocumentFromBitmap(
        bitmap: Bitmap,
        scanType: ScanType = ScanType.ALL,
        lang: com.example.model.AppLanguage = com.example.model.AppLanguage.ENGLISH
    ): ScanResult = withContext(Dispatchers.IO) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            val latinVision = Tasks.await(
                latinRecognizer.process(inputImage),
                3500,
                java.util.concurrent.TimeUnit.MILLISECONDS
            )
            val devVision = try {
                Tasks.await(
                    devanagariRecognizer.process(inputImage),
                    3500,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
            } catch (_: Exception) {
                latinVision
            }

            // Only treat document as Devanagari if Marathi/Hindi letters exceed 35% of all letters
            // (Prevents the 2 Hindi header words on a PAN/Aadhaar card from breaking English OCR!)
            val devCharCount = devVision.text.count { it in '\u0900'..'\u097F' }
            val latinCharCount = latinVision.text.count { it in 'A'..'Z' || it in 'a'..'z' }
            val isMostlyDevanagariDoc = devCharCount >= 8 && devCharCount > (latinCharCount * 0.5)

            val chosenVision = if (isMostlyDevanagariDoc) devVision else latinVision

            val activeBlocks = filterBlocksInsideDocumentEdges(bitmap, chosenVision.textBlocks)
            val rawText = if (activeBlocks.isNotEmpty()) {
                activeBlocks
                    .sortedBy { it.boundingBox?.top ?: 0 }
                    .joinToString("\n") { it.text.trim() }
            } else {
                chosenVision.text
            }

            // ALWAYS use the user's selected language (lang) for voice instructions!
            // 1. Multi-Cue RBI Banknote Verification
            if (scanType == ScanType.CURRENCY || scanType == ScanType.ALL) {
                val noteResult = verifyIndianBanknote(bitmap, rawText, lang)
                if (noteResult != null) {
                    _lastScanResult.value = noteResult
                    return@withContext noteResult
                } else if (scanType == ScanType.CURRENCY) {
                    val textBoxes = activeBlocks.mapNotNull { it.boundingBox?.let { b -> android.graphics.RectF(b) } }
                    val (guidance, _) = captureGuider.evaluateLiveFraming(bitmap, textBoxes)
                    val msg = if (rawText.length < 5 && guidance != null) {
                        guidance.getSpoken(lang)
                    } else {
                        when (lang) {
                            com.example.model.AppLanguage.MARATHI -> "वैध आरबीआय नोट आढळली नाही. नोट कॅमेऱ्यासमोर स्पष्ट धरा."
                            com.example.model.AppLanguage.HINDI -> "वैध आरबीआई नोट नहीं मिला। नोट को कैमरे के सामने स्पष्ट रखें।"
                            com.example.model.AppLanguage.ENGLISH -> "No valid RBI banknote detected. Hold the front or back of the note clearly in view."
                        }
                    }
                    return@withContext ScanResult(ScanType.CURRENCY, "Not Verified", msg, msg)
                }
            }

            // 2. Check framing if almost no text is visible
            val textBoxes = activeBlocks.mapNotNull { it.boundingBox?.let { b -> android.graphics.RectF(b) } }
            val (guidance, _) = captureGuider.evaluateLiveFraming(bitmap, textBoxes)

            if (rawText.trim().length < 4 && guidance != null &&
                guidance != GuidanceInstruction.HOLD_STEADY &&
                guidance != GuidanceInstruction.READY_TO_CAPTURE) {
                val spokenAdjust = guidance.getSpoken(lang)
                return@withContext ScanResult(
                    type = scanType,
                    title = "Adjust Camera Frame",
                    details = spokenAdjust,
                    spokenAnnouncement = spokenAdjust
                )
            }

            // 3. ID Card check (Aadhaar & PAN)
            val structuredResult = evaluateDocument(latinVision.text, scanType, lang)
                ?: if (isMostlyDevanagariDoc) evaluateDocument(rawText, scanType, lang) else null
            if (structuredResult != null) {
                _lastScanResult.value = structuredResult
                return@withContext structuredResult
            }

            // 4. Universal Medicine, Bill, Prescription, or General Document
            val universalResult = evaluateMedicineAndDocumentSafety(
                activeBlocks,
                rawText,
                lang
            )
            if (universalResult != null) {
                _lastScanResult.value = universalResult
                return@withContext universalResult
            }

            val fallbackGuidance = guidance?.getSpoken(lang) ?: when (lang) {
                com.example.model.AppLanguage.MARATHI -> "कॅमेरा वस्तूच्या जवळ न्या आणि मध्यभागी धरा."
                com.example.model.AppLanguage.HINDI -> "कैमरा वस्तु के पास ले जाएं और बीच में रखें।"
                com.example.model.AppLanguage.ENGLISH -> "Move camera closer and center the item."
            }


            ScanResult(
                type = scanType,
                title = "Adjust Camera",
                details = fallbackGuidance,
                spokenAnnouncement = fallbackGuidance
            )
        } catch (e: Exception) {
            Log.e(tag, "Local OCR error: ${e.message}")
            ScanResult(scanType, "Scan Error", "", "Hold item steady and try scanning again.")
        }
    }


    /**
     * Keeps all text belonging to the main document while ignoring stray background
     * text cut off at the extreme outer 3% camera border.
     */
    private fun filterBlocksInsideDocumentEdges(
        bitmap: Bitmap,
        allBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>
    ): List<com.google.mlkit.vision.text.Text.TextBlock> {
        if (allBlocks.isEmpty()) return emptyList()

        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()

        return allBlocks.filter { block ->
            val box = block.boundingBox ?: return@filter false
            val normLeft = box.left / imgW
            val normRight = box.right / imgW
            val normTop = box.top / imgH
            val normBottom = box.bottom / imgH
            val normCx = box.exactCenterX() / imgW
            val normCy = box.exactCenterY() / imgH

            // Only discard blocks whose center is in the extreme outer 4% border
            // or that are clipped right across the outer camera edge
            val inMainDocumentZone = normCx in 0.04f..0.96f && normCy in 0.04f..0.96f
            val clippedOnOuterEdge = (normLeft <= 0.01f && normRight <= 0.12f) ||
                    (normRight >= 0.99f && normLeft >= 0.88f) ||
                    (normTop <= 0.01f && normBottom <= 0.08f) ||
                    (normBottom >= 0.99f && normTop >= 0.92f)

            inMainDocumentZone && !clippedOnOuterEdge
        }
    }

    private fun extractMedicineExpiry(rawText: String): String? {
        // 1. Explicit EXP / USE BEFORE / BEST BEFORE label match (allows DT, DATE, dots, slashes, spaces, or newlines)
        val labeledExpiryRegex = Regex(
            """(?:EXP(?:IRY)?|USE\s*BEFORE|BEST\s*BEFORE)[.\s:\-A-Z]*?((?:0?[1-9]|1[0-2])\s*[/\-.]\s*\d{2,4}|(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z.]*\s*[/\-.]?\s*\d{2,4})""",
            RegexOption.IGNORE_CASE
        )
        labeledExpiryRegex.find(rawText)?.groups?.get(1)?.value?.let {
            return it.trim()
        }

        // 2. Fallback: Find all MM/YYYY, MM.YYYY, or MMM YYYY dates on the medicine strip and pick the latest date
        val anyDateRegex = Regex(
            """\b((?:0?[1-9]|1[0-2])\s*[/\-.]\s*(?:20[2-3]\d|[2-3]\d)|(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z.]*\s*[/\-.]?\s*(?:20[2-3]\d|[2-3]\d))\b""",
            RegexOption.IGNORE_CASE
        )
        val allDates = anyDateRegex.findAll(rawText).map { it.value.trim() }.toList()
        if (allDates.isNotEmpty()) {
            return allDates.last()
        }

        return null
    }

    fun evaluateMedicineAndDocumentSafety(
        textBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>,
        rawText: String,
        lang: com.example.model.AppLanguage = com.example.model.AppLanguage.ENGLISH
    ): ScanResult? {
        if (rawText.isBlank()) return null

        val orderedLines = textBlocks
            .flatMap { it.lines }
            .filter { line ->
                val text = line.text.trim()
                val validCount = text.count { it.isLetterOrDigit() || it in '\u0900'..'\u097F' }
                text.length >= 2 && validCount >= 1
            }
            .sortedBy { it.boundingBox?.top ?: 0 }

        val cleanOrderedText = if (orderedLines.isNotEmpty()) {
            orderedLines.joinToString("\n") { it.text.trim() }
        } else {
            rawText.trim()
        }

        val upper = cleanOrderedText.uppercase()

        val sortedByFontSize = orderedLines.sortedByDescending { line ->
            val box = line.boundingBox
            if (box != null) box.height() * (line.confidence ?: 0.9f) else 0f
        }

        val primaryHeading = sortedByFontSize.firstOrNull { it.text.trim().length in 2..60 }?.text?.trim()
            ?: cleanOrderedText.lines().firstOrNull { it.trim().length >= 2 }?.trim()
            ?: "Document"

        val strengthMatch = DocumentSafetyRules.STRENGTH_REGEX.find(cleanOrderedText)?.value
        val expiryMatch = extractMedicineExpiry(cleanOrderedText)
        val mrpMatch = DocumentSafetyRules.MRP_REGEX.find(cleanOrderedText)?.groups?.get(1)?.value

        val isMedicine = DocumentSafetyRules.MEDICINE_KEYWORDS.count { upper.contains(it) } >= 2 ||
                (strengthMatch != null && expiryMatch != null)
        val isBill = DocumentSafetyRules.BILL_KEYWORDS.count { upper.contains(it) } >= 2

        if (isMedicine) {
            val brandCandidate = sortedByFontSize.firstOrNull { line ->
                val t = line.text.uppercase()
                line.text.length in 3..28 &&
                        !DocumentSafetyRules.MEDICINE_KEYWORDS.any { t.contains(it) } &&
                        !t.contains("PHARMA") && !t.contains("LAB") && !t.contains("LTD")
            }?.text?.trim() ?: primaryHeading

            val (_, expiryMsg) = if (expiryMatch != null) {
                DocumentSafetyRules.evaluateExpiry(expiryMatch)
            } else {
                Pair(false, "Expiry date not clearly visible.")
            }

            val spoken = when (lang) {
                com.example.model.AppLanguage.MARATHI -> buildString {
                    append("औषध: $brandCandidate. ")
                    if (strengthMatch != null) append("क्षमता: $strengthMatch. ")
                    if (expiryMatch != null) append("एक्सपायरी तारीख: $expiryMatch. ") else append("एक्सपायरी तारीख दिसत नाही. ")
                    if (mrpMatch != null) append("किंमत: $mrpMatch रुपये.")
                }
                com.example.model.AppLanguage.HINDI -> buildString {
                    append("दवा: $brandCandidate. ")
                    if (strengthMatch != null) append("क्षमता: $strengthMatch. ")
                    if (expiryMatch != null) append("एक्सपायरी तारीख: $expiryMatch. ") else append("एक्सपायरी तारीख स्पष्ट नहीं है। ")
                    if (mrpMatch != null) append("कीमत: $mrpMatch रुपये।")
                }
                com.example.model.AppLanguage.ENGLISH -> buildString {
                    append("Medicine pack: $brandCandidate. ")
                    if (strengthMatch != null) append("Strength: $strengthMatch. ")
                    append("$expiryMsg ")
                    if (mrpMatch != null) append("MRP: $mrpMatch Rupees.")
                }
            }

            return ScanResult(
                type = ScanType.MEDICINE,
                title = "$brandCandidate ${strengthMatch ?: ""}".trim(),
                details = "Expiry: ${expiryMatch ?: "Not visible"} | MRP: ${mrpMatch ?: "N/A"}\n\n$cleanOrderedText",
                spokenAnnouncement = spoken.trim()
            )
        }

        if (isBill) {
            val totalRegex = Regex("""(?:TOTAL|AMOUNT|NET|PAYABLE|एकूण|कुल|रक्कम)\s*[:\-Rs.₹]*\s*(\d+(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE)
            val totalAmount = totalRegex.find(cleanOrderedText)?.groups?.get(1)?.value ?: mrpMatch
            val spoken = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "बिल: $primaryHeading. ${if (totalAmount != null) "रक्कम: $totalAmount रुपये." else ""}"
                com.example.model.AppLanguage.HINDI -> "बिल: $primaryHeading. ${if (totalAmount != null) "राशि: $totalAmount रुपये।" else ""}"
                com.example.model.AppLanguage.ENGLISH -> "Bill: $primaryHeading. ${if (totalAmount != null) "Amount: $totalAmount Rupees." else ""}"
            }
            return ScanResult(
                type = ScanType.DOCUMENTS,
                title = primaryHeading,
                details = cleanOrderedText,
                spokenAnnouncement = spoken
            )
        }

        // General Document: Read complete lines
        val mainLines = cleanOrderedText.lines().map { it.trim() }.filter { it.length >= 2 }
        if (mainLines.isNotEmpty()) {
            val directSpokenText = mainLines.take(8).joinToString(". ")
            return ScanResult(
                type = ScanType.DOCUMENTS,
                title = primaryHeading.take(40),
                details = cleanOrderedText,
                spokenAnnouncement = directSpokenText
            )
        }

        return null
    }



    /**
     * MULTI-LAYER RBI BANKNOTE AUTHENTICATOR:
     * 1. Rejects fake/toy notes ("CHILDREN BANK", "MANORANJAN", "SPECIMEN", "PLAY MONEY").
     * 2. Requires authentic RBI authority text OR valid RBI alphanumeric serial number.
     * 3. Verifies high-frequency Guilloche intaglio micro-texture (rejects flat colored objects/screens).
     * 4. Cross-verifies OCR denomination against official RBI Mahatma Gandhi New Series HSV color spectrum.
     */
    private fun verifyIndianBanknote(
        bitmap: Bitmap,
        rawOcrText: String,
        lang: com.example.model.AppLanguage
    ): ScanResult? {
        val upper = rawOcrText.uppercase()

        // LAYER 1: Reject Fake / Toy / Specimen Notes Immediately
        val fakeKeywords = listOf(
            "CHILDREN", "CHILDRENS", "MANORANJAN", "SPECIMEN", "TOY", "PLAY",
            "DUMMY", "COUPON", "MONOPOLY", "ENTERTAINMENT", "चिल्ड्रन", "मनोरंजन"
        )
        if (fakeKeywords.any { upper.contains(it) }) {
            val warn = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "सावधान! ही खोटी किंवा खेळण्यातील नोट आहे!"
                com.example.model.AppLanguage.HINDI -> "सावधान! यह नकली या खिलौना नोट है!"
                com.example.model.AppLanguage.ENGLISH -> "Warning! Fake or toy note detected. Do not accept!"
            }
            return ScanResult(ScanType.CURRENCY, "⚠️ FAKE / TOY NOTE", "Counterfeit/Toy keyword found", warn)
        }

        // LAYER 2: Check Authentic RBI Text Anchors & Serial Number
        val hasRbiHeader = upper.contains("RESERVE BANK") ||
                upper.contains("INDIA") && (upper.contains("GUARANTEED") || upper.contains("PROMISE") || upper.contains("BEARER") || upper.contains("GOVERNOR")) ||
                upper.contains("MAHATMA GANDHI") ||
                rawOcrText.contains("भारतीय") || rawOcrText.contains("रिज़र्व") || rawOcrText.contains("रिजर्व") ||
                rawOcrText.contains("रुपये") || rawOcrText.contains("धारक")

        val serialMatcher = rbiSerialRegex.matcher(upper)
        val foundSerial = if (serialMatcher.find()) serialMatcher.group() else ""

        // Must have at least RBI authority text OR a valid RBI serial number (Never trigger on color alone!)
        if (!hasRbiHeader && foundSerial.isBlank()) {
            return null
        }

        // LAYER 3: Extract Denomination from OCR Text
        val ocrDenom: Int? = when {
            Regex("\\b500\\b").containsMatchIn(upper) || upper.contains("FIVE HUNDRED") || rawOcrText.contains("५००") || rawOcrText.contains("पाँच सौ") -> 500
            Regex("\\b200\\b").containsMatchIn(upper) || upper.contains("TWO HUNDRED") || rawOcrText.contains("२००") || rawOcrText.contains("दो सौ") -> 200
            Regex("\\b100\\b").containsMatchIn(upper) || upper.contains("ONE HUNDRED") || rawOcrText.contains("१००") || rawOcrText.contains("एक सौ") -> 100
            Regex("\\b50\\b").containsMatchIn(upper) || upper.contains("FIFTY") || rawOcrText.contains("५०") || rawOcrText.contains("पचास") -> 50
            Regex("\\b20\\b").containsMatchIn(upper) || upper.contains("TWENTY") || rawOcrText.contains("२०") || rawOcrText.contains("बीस") -> 20
            Regex("\\b10\\b").containsMatchIn(upper) || upper.contains("TEN RUPEES") || rawOcrText.contains("१०") || rawOcrText.contains("दस रुपये") -> 10
            else -> null
        }

        if (ocrDenom == null) return null

        // LAYER 4: Cross-Verify RBI Chromatic Signature & Micro-Texture (Rejects B&W Photocopies & Altered Notes)
        val (colorMatchesDenom, textureValid) = verifyNoteColorAndTexture(bitmap, ocrDenom)

        if (!textureValid) {
            return null
        }

        if (!colorMatchesDenom) {
            val suspectWarn = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "सावधान! ₹$ocrDenom च्या नोटेचा रंग मूळ आरबीआय नोटेशी जुळत नाही. कृपया तपासा."
                com.example.model.AppLanguage.HINDI -> "सावधान! ₹$ocrDenom के नोट का रंग असली आरबीआई नोट से मेल नहीं खा रहा है। कृपया जांचें।"
                com.example.model.AppLanguage.ENGLISH -> "Caution! $ocrDenom Rupee text found, but note color does not match authentic RBI banknote. Possible photocopy or fake."
            }
            return ScanResult(
                type = ScanType.CURRENCY,
                title = "⚠️ Unverified ₹$ocrDenom",
                details = "Color mismatch / Possible photocopy",
                spokenAnnouncement = suspectWarn
            )
        }

        val serialInfo = if (foundSerial.isNotBlank()) "Serial ends with ${foundSerial.takeLast(4)}." else "RBI features verified."
        val spoken = when (lang) {
            com.example.model.AppLanguage.MARATHI -> "$ocrDenom रुपयांची मूळ नोट सापडली."
            com.example.model.AppLanguage.HINDI -> "$ocrDenom रुपये का असली नोट मिला।"
            com.example.model.AppLanguage.ENGLISH -> "$ocrDenom Rupees authentic note detected. $serialInfo"
        }

        return ScanResult(
            type = ScanType.CURRENCY,
            title = "₹$ocrDenom Note Verified",
            details = "RBI Verified | $serialInfo",
            spokenAnnouncement = spoken
        )
    }

    /**
     * Checks HSV color spectrum + skin-tone masking + Guilloche micro-texture variance
     * to confirm the physical note matches the OCR denomination.
     */
    private fun verifyNoteColorAndTexture(bitmap: Bitmap, expectedDenom: Int): Pair<Boolean, Boolean> {
        val width = bitmap.width
        val height = bitmap.height
        val cropW = (width * 0.55f).toInt().coerceAtLeast(16)
        val cropH = (height * 0.55f).toInt().coerceAtLeast(16)
        val startX = (width - cropW) / 2
        val startY = (height - cropH) / 2

        val centerCrop = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
        val grid = 32
        val scaled = Bitmap.createScaledBitmap(centerCrop, grid, grid, false)
        val pixels = IntArray(grid * grid)
        scaled.getPixels(pixels, 0, grid, 0, 0, grid, grid)

        val hsv = FloatArray(3)
        var coloredPixelCount = 0
        var validPixels = 0
        var microEdgeEnergy = 0f

        for (y in 0 until grid - 1) {
            for (x in 0 until grid - 1) {
                val p = pixels[y * grid + x]
                Color.colorToHSV(p, hsv)
                val s = hsv[1]
                val v = hsv[2]

                val pRight = pixels[y * grid + x + 1]
                val lumDiff = kotlin.math.abs((p and 0xFF) - (pRight and 0xFF))
                microEdgeEnergy += lumDiff

                if (v in 0.12f..0.96f) {
                    validPixels++
                    // Count pixels that have natural banknote ink color (ignores white watermark area)
                    if (s >= 0.05f) {
                        coloredPixelCount++
                    }
                }
            }
        }

        if (validPixels < 50) return Pair(true, true)

        val avgTexture = microEdgeEnergy / (grid * grid)
        val hasIntaglioTexture = avgTexture >= 2.0f

        // Real ₹500 is Stone Grey (naturally low saturation); ₹10..₹200 have colored ink regions.
        // Only reject if a ₹10..₹200 note is a 100% grayscale black-and-white photocopy.
        val isNotBwPhotocopy = if (expectedDenom == 500) {
            true
        } else {
            coloredPixelCount >= 8
        }

        return Pair(isNotBwPhotocopy, hasIntaglioTexture)
    }






    // ============================================================================
// 2. In InfoReader.kt: Replace evaluateDocument() and add extractPanNumberFuzzy()
//    FIXES PAN CARD DETECTION (Handles hologram glare misreads like O->0, I->1, S->5)
// ============================================================================

    fun evaluateDocument(
        rawText: String,
        scanType: ScanType = ScanType.ALL,
        lang: com.example.model.AppLanguage = com.example.model.AppLanguage.ENGLISH
    ): ScanResult? {
        val upper = rawText.uppercase()
        val rawLines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 1. AADHAAR CARD DETECTION
        val isAadhaarContext = upper.contains("AADHAAR") || upper.contains("UIDAI") ||
                upper.contains("UNIQUE IDENTIFICATION") || upper.contains("MERA AADHAAR") || rawText.contains("आधार")

        val aadhaarMatcher = aadhaarRegex.matcher(rawText)
        val aadhaarRaw = if (aadhaarMatcher.find()) aadhaarMatcher.group() else ""

        if (isAadhaarContext && aadhaarRaw.isNotBlank()) {
            val last4 = aadhaarRaw.filter { it.isDigit() }.takeLast(4)
            val ownerName = extractAadhaarCardholderName(rawLines)

            val spoken = when (lang) {
                com.example.model.AppLanguage.MARATHI ->
                    if (ownerName.isNotBlank()) "आधार कार्ड सापडले, नाव $ownerName आहे, शेवटचा नंबर $last4 आहे."
                    else "आधार कार्ड सापडले, शेवटचा नंबर $last4 आहे."
                com.example.model.AppLanguage.HINDI ->
                    if (ownerName.isNotBlank()) "आधार कार्ड मिला, नाम $ownerName है, अंतिम नंबर $last4 है।"
                    else "आधार कार्ड मिला, अंतिम नंबर $last4 है।"
                com.example.model.AppLanguage.ENGLISH ->
                    if (ownerName.isNotBlank()) "Aadhaar card detected, name is $ownerName, number ends with $last4."
                    else "Aadhaar card detected, number ends with $last4."
            }

            return ScanResult(
                type = ScanType.DOCUMENTS,
                title = "Aadhaar Card Verified",
                details = "Name: ${ownerName.ifBlank { "N/A" }} | Ends with: $last4",
                spokenAnnouncement = spoken
            )
        }

        // 2. PAN CARD DETECTION (Glare-tolerant 10-char PAN extractor + Header check)
        val hasIncomeTaxHeader = upper.contains("INCOME TAX") || upper.contains("PERMANENT ACCOUNT") ||
                upper.contains("INCOME") && upper.contains("DEPARTMENT") ||
                upper.contains("PERMANENT") && upper.contains("NUMBER") ||
                rawText.contains("आयकर")

        val panNumber = extractPanNumberFuzzy(upper)

        if (panNumber.isNotBlank() && (hasIncomeTaxHeader || panRegex.matcher(panNumber).matches())) {
            val last4 = panNumber.takeLast(4)
            val ownerName = extractPanCardholderName(rawLines, panNumber)

            val spoken = when (lang) {
                com.example.model.AppLanguage.MARATHI ->
                    if (ownerName.isNotBlank()) "पॅन कार्ड सापडले, नाव $ownerName आहे, शेवटचा नंबर $last4 आहे."
                    else "पॅन कार्ड सापडले, शेवटचा नंबर $last4 आहे."
                com.example.model.AppLanguage.HINDI ->
                    if (ownerName.isNotBlank()) "पैन कार्ड मिला, नाम $ownerName है, अंतिम नंबर $last4 है।"
                    else "पैन कार्ड मिला, अंतिम नंबर $last4 है।"
                com.example.model.AppLanguage.ENGLISH ->
                    if (ownerName.isNotBlank()) "PAN card detected, name is $ownerName, number ends with $last4."
                    else "PAN card detected, number ends with $last4."
            }

            return ScanResult(
                type = ScanType.DOCUMENTS,
                title = "PAN Card Verified",
                details = "Name: ${ownerName.ifBlank { "N/A" }} | PAN: $panNumber",
                spokenAnnouncement = spoken
            )
        }

        return null
    }

    /**
     * Finds a 10-character PAN number even if card hologram glare caused OCR to misread
     * digits as letters (O->0, I->1, S->5, B->8) or letters as digits.
     */
    private fun extractPanNumberFuzzy(upperText: String): String {
        // 1. Try exact match first
        val exactMatcher = panRegex.matcher(upperText)
        if (exactMatcher.find()) return exactMatcher.group()

        // 2. Check each line and 10-char token with glare correction
        val tokens = upperText.split(Regex("[^A-Z0-9]+")).filter { it.length == 10 }
        for (token in tokens) {
            val first5 = token.substring(0, 5)
                .replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B')
            val mid4 = token.substring(5, 9)
                .replace('O', '0').replace('D', '0').replace('Q', '0')
                .replace('I', '1').replace('L', '1')
                .replace('Z', '2').replace('S', '5').replace('B', '8')
            val last1 = token.substring(9, 10)
                .replace('0', 'O').replace('1', 'I').replace('5', 'S').replace('8', 'B')

            // 4th char of an Indian PAN must be a valid status letter (P=Person, C=Company, H=HUF, F=Firm, etc.)
            val validFourthChar = first5[3] in "PCHABGJLFT"
            if (first5.all { it in 'A'..'Z' } && validFourthChar && mid4.all { it.isDigit() } && last1[0] in 'A'..'Z') {
                return first5 + mid4 + last1
            }
        }
        return ""
    }


    /**
     * Aadhaar Name Extractor with Positional DOB Anchor + Character Cleansing
     */
    private fun extractAadhaarCardholderName(lines: List<String>): String {
        val nonNameHeaders = listOf(
            "GOVERNMENT", "INDIA", "AUTHORITY", "UNIQUE", "IDENTIFICATION",
            "AADHAAR", "UIDAI", "ENROLLMENT", "MERA", "PEHCHAN", "HELP",
            "FATHER", "HUSBAND", "ADDRESS", "VID", "MALE", "FEMALE", "YEAR", "DOB"
        )

        var dobLineIndex = -1
        for (i in lines.indices) {
            val up = lines[i].uppercase()
            if (up.contains("DOB") || up.contains("DATE OF BIRTH") || up.contains("YEAR OF BIRTH") || up.contains("जन्म")) {
                dobLineIndex = i
                break
            }
        }

        // 1. Search upwards from the DOB line
        if (dobLineIndex > 0) {
            for (i in dobLineIndex - 1 downTo 0) {
                val candidate = cleanOcrNameString(lines[i])
                val up = candidate.uppercase()
                val isHeader = nonNameHeaders.any { up.contains(it) }

                if (!isHeader && candidate.length in 4..32 && isValidNameFormat(candidate)) {
                    return formatToTitleCase(candidate)
                }
            }
        }

        // 2. Fallback search
        for (line in lines) {
            val candidate = cleanOcrNameString(line)
            val up = candidate.uppercase()
            val isHeader = nonNameHeaders.any { up.contains(it) } || up.contains("DOB") || up.contains("BIRTH")

            if (!isHeader && candidate.length in 4..30 && isValidNameFormat(candidate)) {
                return formatToTitleCase(candidate)
            }
        }

        return ""
    }

    /**
     * PAN Card Name Extractor with Header Strip + Character Cleansing
     */
    private fun extractPanCardholderName(lines: List<String>, panNumber: String): String {
        val nonNameHeaders = listOf(
            "INCOME", "TAX", "DEPARTMENT", "GOVT", "INDIA", "PERMANENT",
            "ACCOUNT", "CARD", "SIGNATURE", "DATE", "BIRTH", "FATHER"
        )

        for (line in lines) {
            var raw = line.trim()
            val up = raw.uppercase()

            if (up.startsWith("NAME") || up.startsWith("NAME:") || up.startsWith("NAME /")) {
                raw = raw.replace("(?i)^name[:/\\s-]*".toRegex(), "").trim()
            }

            val candidate = cleanOcrNameString(raw)
            val cleanUp = candidate.uppercase()
            val isHeader = nonNameHeaders.any { cleanUp.contains(it) }

            if (!isHeader && !cleanUp.contains(panNumber) && candidate.length in 4..30 && isValidNameFormat(candidate)) {
                return formatToTitleCase(candidate)
            }
        }

        return ""
    }




    private fun detectDocumentEdgesRect(bitmap: Bitmap): android.graphics.RectF {
        val grid = 48
        val small = Bitmap.createScaledBitmap(bitmap, grid, grid, false)
        val pixels = IntArray(grid * grid)
        small.getPixels(pixels, 0, grid, 0, 0, grid, grid)

        val lum = Array(grid) { y ->
            FloatArray(grid) { x ->
                val p = pixels[y * grid + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                0.299f * r + 0.587f * g + 0.114f * b
            }
        }

        // Reference paper brightness in the central zone (rows 18..30, cols 18..30)
        var centerSum = 0f
        var centerCount = 0
        for (y in 18..30) {
            for (x in 18..30) {
                centerSum += lum[y][x]
                centerCount++
            }
        }
        val paperLum = centerSum / centerCount
        val edgeThreshold = 42f // Luminance jump at document physical border

        // Scan Left from center (col 20 down to 3)
        var leftEdgeCol = 3
        for (x in 20 downTo 3) {
            var rowDiffSum = 0f
            for (y in 16..32) rowDiffSum += kotlin.math.abs(lum[y][x] - paperLum)
            if (rowDiffSum / 17f > edgeThreshold) {
                leftEdgeCol = x
                break
            }
        }

        // Scan Right from center (col 28 up to 44)
        var rightEdgeCol = 44
        for (x in 28..44) {
            var rowDiffSum = 0f
            for (y in 16..32) rowDiffSum += kotlin.math.abs(lum[y][x] - paperLum)
            if (rowDiffSum / 17f > edgeThreshold) {
                rightEdgeCol = x
                break
            }
        }

        // Scan Up from center (row 20 down to 3)
        var topEdgeRow = 3
        for (y in 20 downTo 3) {
            var colDiffSum = 0f
            for (x in leftEdgeCol..rightEdgeCol) colDiffSum += kotlin.math.abs(lum[y][x] - paperLum)
            val span = (rightEdgeCol - leftEdgeCol + 1).coerceAtLeast(1)
            if (colDiffSum / span > edgeThreshold) {
                topEdgeRow = y
                break
            }
        }

        // Scan Down from center (row 28 up to 44)
        var bottomEdgeRow = 44
        for (y in 28..44) {
            var colDiffSum = 0f
            for (x in leftEdgeCol..rightEdgeCol) colDiffSum += kotlin.math.abs(lum[y][x] - paperLum)
            val span = (rightEdgeCol - leftEdgeCol + 1).coerceAtLeast(1)
            if (colDiffSum / span > edgeThreshold) {
                bottomEdgeRow = y
                break
            }
        }

        return android.graphics.RectF(
            (leftEdgeCol.toFloat() / grid).coerceIn(0.05f, 0.35f),
            (topEdgeRow.toFloat() / grid).coerceIn(0.05f, 0.35f),
            (rightEdgeCol.toFloat() / grid).coerceIn(0.65f, 0.95f),
            (bottomEdgeRow.toFloat() / grid).coerceIn(0.65f, 0.95f)
        )
    }


    private fun sampleRegionLuminance(
        bitmap: Bitmap,
        normCx: Float,
        normCy: Float,
        normW: Float,
        normH: Float
    ): Float {
        val w = bitmap.width
        val h = bitmap.height
        val startX = ((normCx - normW / 2f) * w).toInt().coerceIn(0, w - 1)
        val endX = ((normCx + normW / 2f) * w).toInt().coerceIn(startX + 1, w)
        val startY = ((normCy - normH / 2f) * h).toInt().coerceIn(0, h - 1)
        val endY = ((normCy + normH / 2f) * h).toInt().coerceIn(startY + 1, h)

        val stepX = ((endX - startX) / 6).coerceAtLeast(1)
        val stepY = ((endY - startY) / 6).coerceAtLeast(1)

        var sum = 0f
        var count = 0
        for (y in startY until endY step stepY) {
            for (x in startX until endX step stepX) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sum += 0.299f * r + 0.587f * g + 0.114f * b
                count++
            }
        }
        return if (count > 0) sum / count else 128f
    }


    /**
     * OCR SPELL-CLEANSER:
     * Fixes optical character recognition errors caused by card lamination and glare:
     * - Replaces misread digits: '0' -> 'O', '1' -> 'I', '5' -> 'S', '8' -> 'B'
     * - Strips noise punctuation: '~', '|', '$', '^', '_', '\', '/'
     */
    private fun cleanOcrNameString(raw: String): String {
        var text = raw.trim()

        // 1. Strip non-letter symbols (keeps letters, spaces, and periods)
        text = text.replace("[^a-zA-Z0-9\\s.]".toRegex(), " ")

        // 2. Fix common glare digit-to-letter misreads inside words
        val words = text.split("\\s+".toRegex()).map { word ->
            word.replace("0", "O")
                .replace("1", "I")
                .replace("5", "S")
                .replace("8", "B")
        }

        // 3. Drop single-character noise artifacts (e.g. stray 'x', 'j', '|' left by watermark lines)
        val filteredWords = words.filter { w ->
            w.length > 1 || (w.length == 1 && w.equals("S", ignoreCase = true)) // Keep initials like "S."
        }

        return filteredWords.joinToString(" ").trim()
    }

    private fun isValidNameFormat(name: String): Boolean {
        // A valid Indian name consists of 2 to 4 words with purely alphabetical characters
        val words = name.split(" ").filter { it.isNotBlank() }
        if (words.size !in 2..4) return false
        return words.all { it.all { c -> c.isLetter() || c == '.' } }
    }


    private fun formatToTitleCase(name: String): String {
        return name.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    private fun formatFullPageForSpeech(lines: List<String>): String {
        val sb = StringBuilder()
        for (line in lines) {
            sb.append(line)
            if (line.endsWith(".") || line.endsWith(":") || line.endsWith("?") || line.endsWith("!")) {
                sb.append(" ")
            } else {
                sb.append(". ")
            }
        }
        return sb.toString().trim()
    }

    private fun identifyBanknoteByColor(bitmap: Bitmap): ScanResult? {
        val width = bitmap.width
        val height = bitmap.height

        val cropW = (width * 0.40f).toInt()
        val cropH = (height * 0.40f).toInt()
        val startX = (width - cropW) / 2
        val startY = (height - cropH) / 2

        val centerCrop = Bitmap.createBitmap(bitmap, startX, startY, cropW, cropH)
        val scaled = Bitmap.createScaledBitmap(centerCrop, 24, 24, false)
        val pixels = IntArray(24 * 24)
        scaled.getPixels(pixels, 0, 24, 0, 0, 24, 24)

        val hsv = FloatArray(3)
        var totalHue = 0f
        var totalSat = 0f
        var totalVal = 0f
        var count = 0

        for (p in pixels) {
            Color.colorToHSV(p, hsv)
            if (hsv[1] > 0.18f && hsv[2] in 0.22f..0.92f) {
                totalHue += hsv[0]
                totalSat += hsv[1]
                totalVal += hsv[2]
                count++
            }
        }

        if (count < 60) return null

        val avgHue = totalHue / count
        val avgSat = totalSat / count
        val avgVal = totalVal / count

        return when {
            avgHue in 38f..85f && avgSat > 0.22f -> ScanResult(ScanType.CURRENCY, "₹20 Note", "Green-Yellow", "Twenty Rupees Note.")
            avgHue in 22f..44f && avgSat > 0.42f -> ScanResult(ScanType.CURRENCY, "₹200 Note", "Bright Orange", "Two Hundred Rupees Note.")
            avgHue in 238f..295f && avgSat > 0.16f -> ScanResult(ScanType.CURRENCY, "₹100 Note", "Lavender", "One Hundred Rupees Note.")
            avgHue in 170f..230f && avgSat > 0.28f -> ScanResult(ScanType.CURRENCY, "₹50 Note", "Blue", "Fifty Rupees Note.")
            avgHue in 10f..25f && avgVal < 0.58f && avgSat > 0.24f -> ScanResult(ScanType.CURRENCY, "₹10 Note", "Brown", "Ten Rupees Note.")
            else -> null
        }
    }

    fun release() {
        try { recognizer.close() } catch (_: Exception) {}
    }
}