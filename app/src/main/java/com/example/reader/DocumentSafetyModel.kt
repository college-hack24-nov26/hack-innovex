package com.example.reader

import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DetectedDocType {
    MEDICINE,
    PRESCRIPTION,
    ID_CARD,
    BILL_INVOICE,
    GENERAL_NOTE
}

data class MedicineSafetyReport(
    val brandName: String,
    val strength: String? = null,
    val expiryRaw: String? = null,
    val isExpired: Boolean = false,
    val expiryWarning: String? = null,
    val batchNo: String? = null,
    val mrp: String? = null,
    val isGrounded: Boolean = true
)

object DocumentSafetyRules {

    // Deterministic Medical & Billing Regexes
    val STRENGTH_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(mg|mcg|µg|g|ml|IU)\b""", RegexOption.IGNORE_CASE)
    val EXPIRY_REGEX = Regex("""(?:EXP(?:IRY)?\.?|USE BEFORE|EXP DATE)\s*[:\-]?\s*((?:0?[1-9]|1[0-2])[/\-]\d{2,4}|[A-Z]{3}\.?\s?\d{2,4})""", RegexOption.IGNORE_CASE)
    val BATCH_REGEX = Regex("""B(?:ATCH)?\.?\s?(?:NO\.?)?\s*[:\-]?\s*([A-Z0-9]{4,12})""", RegexOption.IGNORE_CASE)
    val MRP_REGEX = Regex("""MRP\s*(?:Rs\.?|₹)?\s*(\d+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)

    // Document Classification Keywords
    val MEDICINE_KEYWORDS = listOf("TABLET", "CAPSULE", "I.P.", "B.P.", "RX", "BATCH", "EXP", "MFG", "MG", "SCHEDULE H", "SYRUP", "OINTMENT")
    val BILL_KEYWORDS = listOf("INVOICE", "TOTAL", "AMOUNT", "GST", "BILL", "SUBTOTAL", "TAX INVOICE")
    val PRESCRIPTION_KEYWORDS = listOf("DR.", "DOCTOR", "CLINIC", "HOSPITAL", "PATIENT", "RX", "DOSAGE")

    /**
     * Parse date and evaluate whether the medicine is safe or expired.
     */
    fun evaluateExpiry(expiryStr: String): Pair<Boolean, String> {
        return try {
            val clean = expiryStr.trim().replace(".", "").replace(" ", "/")
            val parts = clean.split("/", "-")
            if (parts.size >= 2) {
                val month = parts[0].toIntOrNull() ?: parseMonthName(parts[0])
                var year = parts[1].toIntOrNull() ?: 2026
                if (year < 100) year += 2000

                val expYearMonth = YearMonth.of(year, month.coerceIn(1, 12))
                val currentYearMonth = YearMonth.now()

                when {
                    expYearMonth.isBefore(currentYearMonth) -> {
                        Pair(true, "Warning! This medicine expired in ${expYearMonth.month} ${expYearMonth.year}. Do not use it.")
                    }
                    expYearMonth == currentYearMonth -> {
                        Pair(false, "Caution! This medicine expires this month.")
                    }
                    else -> {
                        Pair(false, "Expiry date: ${expYearMonth.month} ${expYearMonth.year}.")
                    }
                }
            } else {
                Pair(false, "Expiry date: $expiryStr.")
            }
        } catch (_: Exception) {
            Pair(false, "Expiry date: $expiryStr.")
        }
    }

    private fun parseMonthName(monthStr: String): Int = when (monthStr.uppercase().take(3)) {
        "JAN" -> 1; "FEB" -> 2; "MAR" -> 3; "APR" -> 4; "MAY" -> 5; "JUN" -> 6
        "JUL" -> 7; "AUG" -> 8; "SEP" -> 9; "OCT" -> 10; "NOV" -> 11; "DEC" -> 12
        else -> 1
    }

    /**
     * GROUNDING VALIDATOR:
     * Guarantees that any text extracted by Gemini Vision physically exists
     * in the on-device OCR text. If not, the AI hallucinated it!
     */
    fun isGrounded(extractedValue: String?, rawOcrText: String): Boolean {
        if (extractedValue.isNullOrBlank()) return true
        val cleanValue = extractedValue.trim().lowercase(Locale.ROOT)
        val cleanOcr = rawOcrText.lowercase(Locale.ROOT)
        return cleanOcr.contains(cleanValue) || calculateSimilarity(cleanValue, cleanOcr) > 0.82
    }

    private fun calculateSimilarity(target: String, text: String): Double {
        val words = text.split("\\s+".toRegex())
        val maxSim = words.maxOfOrNull { word ->
            val common = target.toSet().intersect(word.toSet()).size
            (2.0 * common) / (target.length + word.length)
        } ?: 0.0
        return maxSim
    }
}