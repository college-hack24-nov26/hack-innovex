package com.example.assistant

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import com.example.model.AppLanguage
import com.example.reader.DocumentSafetyRules

sealed class AssistantAction {
    data class SpokenResponse(val text: String) : AssistantAction()
    data class StartNavigation(val destination: String, val spokenPrompt: String = "Starting route.") : AssistantAction()
    data class SwitchMode(val modeName: String, val spokenPrompt: String = "Mode switched.") : AssistantAction()
    data class ScanRequest(val scanType: String, val spokenPrompt: String = "Scanner ready.") : AssistantAction()
    data class ShareLocation(val spokenPrompt: String = "Location shared.") : AssistantAction()
    data class TriggerSOS(val spokenPrompt: String = "Emergency alert triggered.") : AssistantAction()
    data class NavigateHome(val spokenPrompt: String = "Navigating home.") : AssistantAction()
    data class NavigateWork(val spokenPrompt: String = "Navigating to work.") : AssistantAction()
    data class LookAround(val spokenPrompt: String = "Looking around...") : AssistantAction()
    data class ToggleTorch(val enable: Boolean, val spokenPrompt: String) : AssistantAction()
    data class StopNavigation(val spokenPrompt: String = "Navigation stopped.") : AssistantAction()
}

class GeminiAssistant {

    private val tag = "GeminiAssistant"

    // Primary low-latency model for eyes-free real-time assistants
    private val modelName = "gemini-2.5-flash"
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Resolves API Key from BuildConfig or falls back safely.
     */


    // --------------------------------------------------------------------------
    // PASTE YOUR REAL GOOGLE AI STUDIO KEY HERE (Starts with AIzaSy...):
    // Get free key at: https://aistudio.google.com/
    // --------------------------------------------------------------------------
    private val directApiKey = "AQ.Ab8RN6J-9wcHInvnvPx1fU5q6rN3VsTiDKdxt-BXENgaIlnlPA"

    private fun getResolvedApiKey(): String {
        return if (directApiKey.isNotBlank() && !directApiKey.contains("PASTE")) {
            directApiKey.trim()
        } else {
            try {
                BuildConfig.GEMINI_API_KEY?.trim() ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }



    /**
     * ADVANCED MULTILINGUAL ID & DOCUMENT INTELLIGENCE:
     * Outputs strictly 1 sentence formatted in English, Hindi, or Marathi:
     * "[Document Type] detected, name is [Owner Name], number ends with [Last 4]."
     */
    // ============================================================================
// 1. In GeminiAssistant.kt: Replace analyzeDocumentAdvanced() and analyzeGroundedDocument()
// ============================================================================

    suspend fun analyzeDocumentAdvanced(
        bitmap: Bitmap,
        lang: AppLanguage = AppLanguage.ENGLISH
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) {
            Log.e(tag, "GEMINI ERROR: No valid API key configured!")
            return@withContext null
        }

        try {
            val base64Image = compressBitmapForOcr(bitmap)

            val languageInstruction = when (lang) {
                AppLanguage.MARATHI -> "Output strictly in plain spoken MARATHI (मराठी)."
                AppLanguage.HINDI -> "Output strictly in plain spoken HINDI (हिन्दी)."
                AppLanguage.ENGLISH -> "Output strictly in plain spoken ENGLISH."
            }

            val universalPrompt = """
                You are a universal document, banknote, and text reader for a blind person in India.
                Identify what kind of item or document this is and read the most important information clearly.
                
                $languageInstruction

                RULES BY TYPE:
                1. If Indian Currency Banknote (₹10, ₹20, ₹50, ₹100, ₹200, ₹500):
                   - Verify authentic RBI features (Reserve Bank of India text, Mahatma Gandhi portrait, Ashoka Pillar emblem, serial number, and official note color).
                   - If it says 'Children Bank of India', 'Manoranjan Bank', 'Specimen', is a black-and-white photocopy, or lacks RBI features, warn immediately: 'Warning! Fake or toy note detected!'
                   - If authentic, state exact Rupee value and last 4 digits of serial number if visible.
                2. If Bill / Receipt / Invoice: State bill type/merchant, total amount due, and due date.
                3. If Medicine / Prescription: State exact brand name, strength (mg/ml), and expiry date visible.
                4. If Book / Letter / Notice / Form / Screen: State main heading first, then summarize key content in 2 to 3 clear sentences.
                5. If ID Card: State ID type, full name, and only the last 4 digits of ID number.
                6. Keep total output under 40 words. Do NOT use markdown, asterisks, or bullet symbols.
            """.trimIndent()


            val jsonBody = buildMultimodalPayload(universalPrompt, base64Image).apply {
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 150)
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseString = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val text = extractTextFromGeminiResponse(responseString)
                if (!text.isNullOrBlank()) {
                    return@withContext sanitizeForTTS(text)
                }
            } else {
                Log.e(tag, "Gemini document API rejected (${response.code}): $responseString")
            }
        } catch (e: Exception) {
            Log.e(tag, "Gemini document analysis exception: ${e.message}")
        }

        return@withContext null
    }


    /**
     * Advanced Natural Language Processor for Voice Queries with offline intent safety.
     */
    /**
     * MULTILINGUAL VOICE ASSISTANT:
     * Answers queries and executes actions strictly in English, Hindi, or Marathi!
     */
    suspend fun processUserCommand(
        query: String,
        currentLocationDesc: String,
        visibleObstaclesDesc: String,
        userLat: Double = 0.0,
        userLng: Double = 0.0,
        lang: com.example.model.AppLanguage = com.example.model.AppLanguage.ENGLISH
    ): AssistantAction = withContext(Dispatchers.IO) {
        val lower = query.lowercase().trim()

        // 1. Emergency SOS
        if (lower.contains("emergency") || lower.contains("help") || lower.contains("sos") ||
            lower.contains("मदत") || lower.contains("वाचवा") || lower.contains("बचाओ") || lower.contains("मदद")) {
            val prompt = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "तातडीचा इशारा सुरू झाला आहे. तुमचे लोकेशन आपत्कालीन संपर्कांना पाठवत आहे."
                com.example.model.AppLanguage.HINDI -> "आपातकालीन अलर्ट सक्रिय हो गया है। आपका लोकेशन भेजा जा रहा है।"
                com.example.model.AppLanguage.ENGLISH -> "Emergency alert activated. Sending your location to emergency contacts."
            }
            return@withContext AssistantAction.TriggerSOS(prompt)
        }

        // 2. Hardware Flashlight
        if (lower.contains("torch on") || lower.contains("flashlight on") || lower.contains("लाइट चालू") || lower.contains("टॉर्च चालू")) {
            val prompt = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "फ्लॅशलाइट सुरू केली."
                com.example.model.AppLanguage.HINDI -> "टॉर्च चालू कर दी गई।"
                com.example.model.AppLanguage.ENGLISH -> "Flashlight turned on."
            }
            return@withContext AssistantAction.ToggleTorch(true, prompt)
        }
        if (lower.contains("torch off") || lower.contains("flashlight off") || lower.contains("लाइट बंद") || lower.contains("टॉर्च बंद")) {
            val prompt = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "फ्लॅशलाइट बंद केली."
                com.example.model.AppLanguage.HINDI -> "टॉर्च बंद कर दी गई।"
                com.example.model.AppLanguage.ENGLISH -> "Flashlight turned off."
            }
            return@withContext AssistantAction.ToggleTorch(false, prompt)
        }

        // 3. Navigate Home
        if (lower.contains("home") || lower.contains("घरी") || lower.contains("घर")) {
            val prompt = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "घरी जाण्याचा मार्ग सुरू केला."
                com.example.model.AppLanguage.HINDI -> "घर का रास्ता शुरू कर दिया गया है।"
                com.example.model.AppLanguage.ENGLISH -> "Starting walking route to your saved Home address."
            }
            return@withContext AssistantAction.NavigateHome(prompt)
        }

        // 4. Navigate Work / Office
        if (lower.contains("work") || lower.contains("office") || lower.contains("कार्यालय") || lower.contains("कामावर")) {
            val prompt = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "कामावर जाण्याचा मार्ग सुरू केला."
                com.example.model.AppLanguage.HINDI -> "काम पर जाने का रास्ता शुरू कर दिया गया है।"
                com.example.model.AppLanguage.ENGLISH -> "Starting walking route to your saved Work address."
            }
            return@withContext AssistantAction.NavigateWork(prompt)
        }

        // 5. Where am I? (Location Query)
        if (lower.contains("where am i") || lower.contains("location") || lower.contains("कुठे") || lower.contains("कहाँ")) {
            val answer = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "तुम्ही $currentLocationDesc येथे आहात."
                com.example.model.AppLanguage.HINDI -> "आप $currentLocationDesc पर हैं।"
                com.example.model.AppLanguage.ENGLISH -> "You are currently at $currentLocationDesc."
            }
            return@withContext AssistantAction.SpokenResponse(answer)
        }

        // 6. Navigation Route
        if (lower.contains("navigate") || lower.contains("मार्ग") || lower.contains("रास्ता")) {
            val destination = lower
                .replace("navigate to", "")
                .replace("route to", "")
                .replace("मार्ग", "")
                .replace("रास्ता", "")
                .trim()
                .ifEmpty { "nearest station" }

            val prompt = when (lang) {
                com.example.model.AppLanguage.MARATHI -> "$destination कडे जाणारा मार्ग शोधत आहे."
                com.example.model.AppLanguage.HINDI -> "$destination के लिए रास्ता खोज रहा हूँ।"
                com.example.model.AppLanguage.ENGLISH -> "Finding route to $destination."
            }
            return@withContext AssistantAction.StartNavigation(destination, prompt)
        }

        // 7. General Gemini AI Question Answering (Speaks in Marathi, Hindi, or English)
        val apiKey = getResolvedApiKey()
        if (apiKey.isNotBlank()) {
            try {
                val langInstruction = when (lang) {
                    com.example.model.AppLanguage.MARATHI -> "उत्तर फक्त मराठीत द्या (Output strictly in Marathi). जास्तीत जास्त २० शब्द."
                    com.example.model.AppLanguage.HINDI -> "उत्तर केवल हिन्दी में दें (Output strictly in Hindi). अधिकतम २० शब्द."
                    com.example.model.AppLanguage.ENGLISH -> "Answer in plain spoken English in under 20 words."
                }

                val systemInstruction = "You are AuraSight, a voice guide for a blind user. $langInstruction No markdown, no asterisks."
                val userContext = "Current Location: $currentLocationDesc (GPS: $userLat, $userLng). Obstacles: ${visibleObstaclesDesc.ifEmpty { "None" }}. Query: $query"

                val jsonBody = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", userContext))
                            })
                        })
                    })
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", systemInstruction))
                        })
                    })
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.2)
                        put("maxOutputTokens", 60)
                    })
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(jsonBody.toString().toRequestBody(mediaType))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseString = response.body?.string() ?: ""
                val text = extractTextFromGeminiResponse(responseString)
                if (!text.isNullOrBlank()) {
                    return@withContext AssistantAction.SpokenResponse(sanitizeForTTS(text))
                }
            } catch (e: Exception) {
                Log.e(tag, "Gemini assistant error: ${e.message}")
            }
        }

        // Fallback response in selected language
        val fallback = when (lang) {
            com.example.model.AppLanguage.MARATHI -> "मी तुम्हाला मदत करू शकतो. तुम्ही 'मी कुठे आहे?' किंवा 'घरी जा' असे विचारू शकता."
            com.example.model.AppLanguage.HINDI -> "मैं आपकी सहायता कर सकता हूँ। आप 'मैं कहाँ हूँ?' या 'घर ले चलो' पूछ सकते हैं।"
            com.example.model.AppLanguage.ENGLISH -> "I am ready to help. You can ask 'Where am I?' or 'Take me home'."
        }
        AssistantAction.SpokenResponse(fallback)
    }

    /**
     * Dedicated "Look Around" Spatial Describer for Blind Orientation & Mobility.
     */
    suspend fun describeScene(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) {
            return@withContext "Camera active. Please configure your Gemini API Key in settings for full scene descriptions."
        }

        try {
            val base64Image = compressBitmapToBase64(bitmap)
            val prompt = """
                You are the eyes for a blind person walking in India. 
                Describe what is in front of the camera in 2 short, direct, factual spoken sentences:
                1. What is immediately in front (e.g. pathway, open doorway, stairs, counter, parked auto).
                2. Immediate walking hazards (curbs, steps up/down, potholes, low hanging wires, wet floor).
                3. Any prominent visible signboard (e.g. 'SBI ATM', 'Apollo Pharmacy', 'Gate 2').
                
                RULES:
                - Do NOT say "In this image" or "I can see".
                - Use clear spatial terms: "Directly ahead is...", "On your left is...".
                - Total length must be under 30 words.
                - Never use markdown, asterisks, bullet points, or emojis.
            """.trimIndent()

            val jsonBody = buildMultimodalPayload(prompt, base64Image)
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseString = response.body?.string() ?: ""
                val text = extractTextFromGeminiResponse(responseString)
                if (!text.isNullOrBlank()) {
                    return@withContext sanitizeForTTS(text)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Scene description error: ${e.message}")
        }

        "Path ahead appears open, but proceed with your cane or standard caution."
    }

    suspend fun analyzeGroundedDocument(
        bitmap: Bitmap,
        rawOcrText: String,
        lang: AppLanguage = AppLanguage.ENGLISH
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) return@withContext "Camera active. Please set Gemini API Key in settings."

        try {
            val base64Image = compressBitmapForOcr(bitmap)

            val prompt = """
            You are reading a photo for a blind person in India.
            Use ONLY text physically visible in the image.
            Return ONLY valid JSON in this exact schema:
            {
               "type": "Medicine | ID Card | Bill | Note",
               "brand_or_title": "Primary name or heading",
               "strength_or_id": "Dosage strength or ID number (or null)",
               "expiry_or_date": "Expiry date or due date (or null)",
               "amount": "Total amount in Rupees (or null)",
               "key_summary": "1 short plain sentence summary"
            }

            STRICT SAFETY RULES:
            - If something is unreadable or blurry, use null.
            - NEVER guess medicine names, doses, or expiry dates.
            - NEVER add medical advice or dosage recommendations.
            - Output raw JSON only. No markdown.
        """.trimIndent()

            val jsonBody = buildMultimodalPayload(prompt, base64Image).apply {
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.0)
                    put("maxOutputTokens", 180)
                    put("responseMimeType", "application/json")
                })
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseString = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val rawJsonStr = extractTextFromGeminiResponse(responseString) ?: return@withContext ""
                val root = JSONObject(rawJsonStr)

                val brand = root.optString("brand_or_title")
                val strength = root.optString("strength_or_id")
                val expiry = root.optString("expiry_or_date")
                val summary = root.optString("key_summary")

                val isBrandGrounded = DocumentSafetyRules.isGrounded(brand, rawOcrText)
                val isStrengthGrounded = DocumentSafetyRules.isGrounded(strength, rawOcrText)
                val isExpiryGrounded = DocumentSafetyRules.isGrounded(expiry, rawOcrText)

                if (!isBrandGrounded && brand.isNotBlank()) {
                    Log.w(tag, "Grounding rejection: Gemini brand '$brand' not found in raw OCR!")
                }

                val safeBrand = if (isBrandGrounded) brand else "Document"
                val safeStrength = if (isStrengthGrounded) strength else ""
                val safeExpiry = if (isExpiryGrounded && expiry.isNotBlank()) "Expiry: $expiry." else ""

                return@withContext when (lang) {
                    AppLanguage.MARATHI -> "$safeBrand. $safeStrength. $safeExpiry $summary. अधिक माहितीसाठी 'अधिक' म्हणा."
                    AppLanguage.HINDI -> "$safeBrand. $safeStrength. $safeExpiry $summary. अधिक जानकारी के लिए 'और' कहें।"
                    AppLanguage.ENGLISH -> "$safeBrand. $safeStrength. $safeExpiry $summary. Say 'more' for details."
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Grounded Gemini analysis error: ${e.message}")
        }

        "Could not read document with high confidence. Please hold steady and try again."
    }

    // ============================================================================
// ROOT CAUSE 2: GeminiAssistant.kt was shrinking images to 800px (75% quality)
// and limiting output to 85 tokens.
// In GeminiAssistant.kt: Replace analyzeImageWithGemini(), buildMultimodalPayload(),
// and compressBitmapForOcr()
// (NOTE: Ensure your directApiKey starts with "AIzaSy..." from aistudio.google.com)
// ============================================================================

    suspend fun analyzeImageWithGemini(bitmap: Bitmap, customPrompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) {
            Log.e(tag, "Gemini API key is blank")
            return@withContext null
        }

        val candidateModels = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
        // Use high-resolution 1600px image so small document letters are sharp
        val base64Image = compressBitmapForOcr(bitmap)

        val prompt = customPrompt.ifBlank {
            """
            You are an expert document reader for a blind person.
            1. Ignore background clutter; focus ONLY on the main document, page, or screen in the center.
            2. Automatically correct any optical glare or spelling mistakes using context.
            3. State the main heading/title first, followed by the exact main content in clear, natural sentences.
            4. Do NOT use markdown, asterisks, or bullet symbols.
            """.trimIndent()
        }

        val jsonBody = buildMultimodalPayload(prompt, base64Image)

        for (candidateModel in candidateModels) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey)
                    .post(jsonBody.toString().toRequestBody(mediaType))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val text = extractTextFromGeminiResponse(responseString)
                    if (!text.isNullOrBlank()) {
                        return@withContext sanitizeForTTS(text)
                    }
                } else {
                    Log.e(tag, "Model $candidateModel failed (${response.code}): $responseString")
                }
            } catch (e: Exception) {
                Log.e(tag, "Model $candidateModel error: ${e.message}")
            }
        }
        return@withContext null
    }

    private fun buildMultimodalPayload(promptText: String, base64Image: String): JSONObject {
        return JSONObject().apply {
            val contents = JSONArray().apply {
                put(JSONObject().apply {
                    val parts = JSONArray().apply {
                        put(JSONObject().put("text", promptText))
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    }
                    put("parts", parts)
                })
            }
            put("contents", contents)

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("maxOutputTokens", 400) // Increased from 85 so full main content is read
            })
        }
    }

    // ============================================================================
// 2. Open GeminiAssistant.kt (NOT InfoReader.kt) and replace compressBitmapForOcr() there:
// ============================================================================

    private fun compressBitmapForOcr(bitmap: Bitmap): String {
        val cropX = (bitmap.width * 0.08f).toInt()
        val cropY = (bitmap.height * 0.08f).toInt()
        val cropW = (bitmap.width * 0.84f).toInt().coerceAtLeast(100)
        val cropH = (bitmap.height * 0.84f).toInt().coerceAtLeast(100)
        val centerDocBmp = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

        val maxDim = 1600
        val scale = minOf(maxDim.toFloat() / centerDocBmp.width, maxDim.toFloat() / centerDocBmp.height, 1.0f)
        val scaledWidth = (centerDocBmp.width * scale).toInt()
        val scaledHeight = (centerDocBmp.height * scale).toInt()

        val scaledBmp = Bitmap.createScaledBitmap(centerDocBmp, scaledWidth, scaledHeight, true)
        val outputStream = ByteArrayOutputStream()
        scaledBmp.compress(Bitmap.CompressFormat.JPEG, 92, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }




    private fun extractTextFromGeminiResponse(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null
            parts.getJSONObject(0).optString("text")
        } catch (_: Exception) {
            null
        }
    }

    private fun compressBitmapToBase64(bitmap: Bitmap): String {
        // Downscale to 800px on longest side for sub-second network transfers
        val maxDim = 800
        val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1.0f)
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBmp = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val outputStream = ByteArrayOutputStream()
        scaledBmp.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Strips markdown symbols, asterisks, hashes, and bullets so TTS reads cleanly.
     */
    private fun sanitizeForTTS(raw: String): String {
        return raw.replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .replace("~", "")
            .replace("_", " ")
            .replace(Regex("[-•]\\s+"), "")
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }


}
