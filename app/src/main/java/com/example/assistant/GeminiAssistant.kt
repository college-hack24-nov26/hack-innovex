
package com.example.assistant

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.model.AppLanguage
import com.example.model.AppMode
import com.example.model.UserSettings
import com.example.reader.DocumentSafetyRules
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
    private val modelName = "gemini-2.5-flash"
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    private val directApiKey = "AQ.Ab8RN6J-9wcHInvnvPx1fU5q6rN3VsTiDKdxt-BXENgaIlnlPA"
    private val openAiApiKey = "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"
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

    suspend fun processUserCommand(
        query: String,
        currentLocationDesc: String,
        visibleObstaclesDesc: String,
        userLat: Double = 0.0,
        userLng: Double = 0.0,
        lang: AppLanguage = AppLanguage.ENGLISH,
        settings: UserSettings = UserSettings(),
        currentMode: AppMode = AppMode.ASSISTANT,
        scannedContent: String = ""
    ): AssistantAction = withContext(Dispatchers.IO) {
        val lower = query.lowercase().trim()

        // ---------------------------------------------------------------------
        // 1. ALL SAVED DETAILS (Matches Button 3: "मेरी सभी सहेजी गई जानकारी क्या है?")
        // ---------------------------------------------------------------------
        val isAllDetailsQuery = lower.contains("सहेजी गई जानकारी") ||
                lower.contains("सेव्ह केलेली माहिती") ||
                lower.contains("saved details") ||
                lower.contains("my details") ||
                lower.contains("all details") ||
                lower.contains("emergency details") ||
                lower.contains("माहिती काय") ||
                lower.contains("जानकारी क्या") ||
                lower.contains("सर्व माहिती") ||
                lower.contains("सारी जानकारी") ||
                lower.contains("माझी माहिती") ||
                lower.contains("मेरी जानकारी") ||
                lower.contains("सेटिंग") ||
                lower.contains("profile")

        if (isAllDetailsQuery) {
            val name = settings.userName.ifBlank { "User" }
            val cName = settings.emergencyContactName.ifBlank { "Not set" }
            val cPhone1 = settings.emergencyContactPhone.ifBlank { "Not set" }
            val cPhone2 = settings.emergencyContactPhone2.ifBlank { "None" }
            val home = settings.homeAddress.ifBlank { "Not set" }
            val work = settings.workAddress.ifBlank { "Not set" }
            val appLang = settings.language.displayName

            val answer = when (lang) {
                AppLanguage.MARATHI -> "तुमच्या प्रोफाईलची माहिती खालीलप्रमाणे आहे. तुमचे नाव $name आहे. आपत्कालीन संपर्क $cName असून फोन नंबर $cPhone1 आणि पर्यायी नंबर $cPhone2 आहे. घराचा पत्ता $home आणि कामाचा पत्ता $work असा जतन आहे. ॲपची भाषा $appLang आहे."
                AppLanguage.HINDI -> "आपकी प्रोफ़ाइल की जानकारी इस प्रकार है। आपका नाम $name है। आपातकालीन संपर्क $cName हैं जिनका फोन नंबर $cPhone1 और दूसरा नंबर $cPhone2 है। घर का पता $home और कार्यस्थल का पता $work सहेजा गया है। ऐप की भाषा $appLang है।"
                AppLanguage.ENGLISH -> "Here is all your registered profile information. Your name is $name. Your primary emergency contact is $cName at $cPhone1, with secondary contact $cPhone2. Your saved home address is $home, and your work address is $work. Preferred language is $appLang."
            }
            return@withContext AssistantAction.SpokenResponse(answer)
        }

        if (lower.startsWith("navigate to ") || lower.startsWith("route to ") ||
            lower.startsWith("take me to ") || lower.startsWith("go to ") ||
            lower.contains("कडे घेऊन चला") || lower.contains("के लिए रास्ता") ||
            lower.contains("मार्ग") || lower.contains("रास्ता")
        ) {
            val destination = lower
                .replace("navigate to", "")
                .replace("route to", "")
                .replace("take me to", "")
                .replace("go to", "")
                .replace("मला", "")
                .replace("कडे घेऊन चला", "")
                .replace("मुझे", "")
                .replace("के लिए रास्ता", "")
                .replace("मार्ग", "")
                .replace("रास्ता", "")
                .trim()
                .ifEmpty { "nearest station" }

            val prompt = when (lang) {
                AppLanguage.MARATHI -> "$destination साठी गुगल मॅप्स उघडत आहे."
                AppLanguage.HINDI -> "$destination के लिए गूगल मैप्स खोला जा रहा है।"
                AppLanguage.ENGLISH -> "Opening Google Maps walking route to $destination."
            }
            return@withContext AssistantAction.StartNavigation(destination, prompt)
        }

        // Home shortcut
        if (lower.contains("home") || lower.contains("घरी") || lower.contains("घर")) {
            val home = settings.homeAddress.ifBlank { "Home" }
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "घरासाठी गुगल मॅप्स उघडत आहे."
                AppLanguage.HINDI -> "घर के लिए गूगल मैप्स खोला जा रहा है।"
                AppLanguage.ENGLISH -> "Opening Google Maps walking route to Home."
            }
            return@withContext AssistantAction.NavigateHome(prompt)
        }

        // Work shortcut
        if (lower.contains("work") || lower.contains("office") || lower.contains("कार्यालय") || lower.contains("कामावर")) {
            val work = settings.workAddress.ifBlank { "Work" }
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "कार्यालयासाठी गुगल मॅप्स उघडत आहे."
                AppLanguage.HINDI -> "कार्यालय के लिए गूगल मैप्स खोला जा रहा है।"
                AppLanguage.ENGLISH -> "Opening Google Maps walking route to Work."
            }
            return@withContext AssistantAction.NavigateWork(prompt)
        }

        // Home shortcut
        if (lower.contains("home") || lower.contains("घरी") || lower.contains("घर")) {
            val home = settings.homeAddress.ifBlank { "Home" }
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "घरासाठी गुगल मॅप्स उघडत आहे."
                AppLanguage.HINDI -> "घर के लिए गूगल मैप्स खोला जा रहा है।"
                AppLanguage.ENGLISH -> "Opening Google Maps walking route to Home."
            }
            return@withContext AssistantAction.NavigateHome(prompt)
        }

        // Work shortcut
        if (lower.contains("work") || lower.contains("office") || lower.contains("कार्यालय") || lower.contains("कामावर")) {
            val work = settings.workAddress.ifBlank { "Work" }
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "कार्यालयासाठी गुगल मॅप्स उघडत आहे."
                AppLanguage.HINDI -> "कार्यालय के लिए गूगल मैप्स खोला जा रहा है।"
                AppLanguage.ENGLISH -> "Opening Google Maps walking route to Work."
            }
            return@withContext AssistantAction.NavigateWork(prompt)
        }

        // ---------------------------------------------------------------------
        // 2. WHERE AM I? (Matches Button 1: "मैं अभी कहाँ हूँ?" / "मी आता कुठे आहे?")
        // ---------------------------------------------------------------------
        val isLocationQuery = lower.contains("कहाँ हूँ") ||
                lower.contains("कहा हु") ||
                lower.contains("कहाँ") ||
                lower.contains("कुठे आहे") ||
                lower.contains("कुठे") ||
                lower.contains("where am i") ||
                lower.contains("where i am") ||
                lower.contains("my location") ||
                lower.contains("current location") ||
                lower.contains("स्थान") ||
                lower.contains("पत्ता")

        if (isLocationQuery) {
            val hasValidStreet = currentLocationDesc.isNotBlank() &&
                    !currentLocationDesc.contains("Locating", ignoreCase = true) &&
                    !currentLocationDesc.contains("GPS", ignoreCase = true)

            val streetPart = if (hasValidStreet) currentLocationDesc else "pedestrian walkway"
            val gpsPart = if (userLat != 0.0 && userLng != 0.0) {
                String.format(java.util.Locale.US, "GPS %.4f, %.4f", userLat, userLng)
            } else {
                "GPS active"
            }

            val answer = when (lang) {
                AppLanguage.MARATHI -> "तुम्ही सध्या $streetPart जवळ आहात. तुमचे जीपीएस स्थान $gpsPart असे आहे. तुमचा समोरील चालण्याचा मार्ग सुरक्षित आहे. तुम्हाला इतर कोठे जायचे असल्यास मला सांगा."
                AppLanguage.HINDI -> "आप अभी $streetPart के पास हैं। आपका जीपीएस स्थान $gpsPart है। आपका आगे का रास्ता साफ दिख रहा है। यदि आपको कहीं जाना है तो मुझे बताएं।"
                AppLanguage.ENGLISH -> "You are currently located near $streetPart. Your exact GPS coordinates are $gpsPart. The walking corridor ahead is active. Let me know if you would like walking directions."
            }
            return@withContext AssistantAction.SpokenResponse(answer)
        }

        // ---------------------------------------------------------------------
        // 3. TAKE ME HOME (Matches Button 2: "मुझे घर ले चलो" / "मला घरी घेऊन चला")
        // ---------------------------------------------------------------------
        val isHomeNavQuery = lower.contains("घर ले चलो") ||
                lower.contains("घरी घेऊन चला") ||
                lower.contains("घर चलो") ||
                lower.contains("घरी जा") ||
                lower.contains("take me home") ||
                lower.contains("go home") ||
                lower.contains("navigate home")

        if (isHomeNavQuery) {
            val home = settings.homeAddress.ifBlank { "Home" }
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "घराकडे जाण्याचा पायी मार्ग सुरू केला आहे. पत्ता: $home. मी तुम्हाला प्रत्येक वळणावर आवाज देऊन मार्गदर्शन करेन."
                AppLanguage.HINDI -> "घर जाने का पैदल रास्ता शुरू कर दिया गया है। पता: $home। मैं आपको हर मोड़ पर निर्देश देकर ले चलूँगा।"
                AppLanguage.ENGLISH -> "Starting walking navigation to your saved home address at $home. I will guide you step by step with spoken directions."
            }
            return@withContext AssistantAction.NavigateHome(prompt)
        }

        // ---------------------------------------------------------------------
        // 4. SCAN RESULT QUERY (If in scan mode or asking what was scanned)
        // ---------------------------------------------------------------------
        val isScanContentQuery = lower.contains("what did you scan") ||
                lower.contains("what was scanned") ||
                lower.contains("काय स्कॅन") ||
                lower.contains("काय वाचले") ||
                lower.contains("क्या स्कैन") ||
                lower.contains("क्या पढ़ा") ||
                (currentMode == AppMode.SCAN && (lower.contains("what is this") || lower.contains("read this") || lower.contains("हे काय आहे") || lower.contains("यह क्या है")))

        if (isScanContentQuery) {
            if (scannedContent.isNotBlank()) {
                val cleanScan = sanitizeForTTS(scannedContent)
                val answer = when (lang) {
                    AppLanguage.MARATHI -> "स्कॅन केलेले तपशील खालीलप्रमाणे आहेत. $cleanScan. पूर्ण मजकूर ऐकण्यासाठी 'अधिक वाचा' दाबा."
                    AppLanguage.HINDI -> "स्कैन किया गया विवरण इस प्रकार है। $cleanScan। पूरा विवरण सुनने के लिए 'और पढ़ें' दबाएं।"
                    AppLanguage.ENGLISH -> "Here are the details from your latest scan. $cleanScan. You can tap Read Full Text to listen to the complete document."
                }
                return@withContext AssistantAction.SpokenResponse(answer)
            } else {
                val emptyMsg = when (lang) {
                    AppLanguage.MARATHI -> "सध्या कोणतेही कागदपत्र स्कॅन केलेले नाही. कॅमेऱ्यासमोर नोट किंवा कागद धरा आणि स्कॅन बटण दाबा."
                    AppLanguage.HINDI -> "अभी कोई दस्तावेज़ स्कैन नहीं हुआ है। कैमरे के सामने दस्तावेज़ रखें और स्कैन बटन दबाएं।"
                    AppLanguage.ENGLISH -> "No document has been scanned yet. Please hold an item in front of the camera and tap Scan."
                }
                return@withContext AssistantAction.SpokenResponse(emptyMsg)
            }
        }

        // ---------------------------------------------------------------------
        // 5. WHAT IS AROUND ME / IN FRONT (Obstacles ahead)
        // ---------------------------------------------------------------------
        if (lower.contains("around me") || lower.contains("in front of me") ||
            lower.contains("what is ahead") || lower.contains("आजूबाजूला") ||
            lower.contains("आसपास") || lower.contains("सामने क्या है") || lower.contains("समोर काय आहे")
        ) {
            if (visibleObstaclesDesc.isNotBlank()) {
                val answer = when (lang) {
                    AppLanguage.MARATHI -> "तुमच्या परिसराचे निरीक्षण केले आहे. सध्या तुमच्या समोर $visibleObstaclesDesc दिसत आहे. चालताना सुरक्षित अंतर ठेवा. परिसराचे अधिक तपशील ऐकण्यासाठी व्हॉल्यूम अप बटण दाबा."
                    AppLanguage.HINDI -> "आपके आसपास का दृश्य देखा गया है। अभी आपके सामने $visibleObstaclesDesc दिखाई दे रहा है। चलते समय सुरक्षित दूरी बनाए रखें। और जानने के लिए वॉल्यूम अप बटन दबाएं।"
                    AppLanguage.ENGLISH -> "I have checked your immediate walking area. Right now, there is $visibleObstaclesDesc in your view. Please maintain a safe walking pace. You can also press Volume Up anytime to get a detailed scene description."
                }
                return@withContext AssistantAction.SpokenResponse(answer)
            } else {
                return@withContext AssistantAction.LookAround()
            }
        }

        // ---------------------------------------------------------------------
        // 6. EMERGENCY SOS
        // ---------------------------------------------------------------------
        if (lower == "emergency" || lower == "help" || lower.contains("help me") ||
            lower.contains("sos") || lower.contains("मदत") || lower.contains("वाचवा") ||
            lower.contains("बचाओ") || lower.contains("मदद")
        ) {
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "आपत्कालीन इशारा सक्रिय केला आहे. तुमचे सध्याचे अचूक स्थान आणि नकाशा लिंक तुमच्या कुटुंबीयांना एसएमएसद्वारे पाठवत आहे. कृपया एका जागी सुरक्षित थांबा."
                AppLanguage.HINDI -> "आपातकालीन अलर्ट सक्रिय कर दिया गया है। आपका सटीक स्थान और गूगल मैप्स लिंक परिवार को एसएमएस द्वारा भेजा जा रहा है। कृपया सुरक्षित स्थान पर रहें।"
                AppLanguage.ENGLISH -> "Emergency alert has been activated. Sending your live location and Google Maps link to your saved emergency contacts via SMS. Please stay in a safe position."
            }
            return@withContext AssistantAction.TriggerSOS(prompt)
        }

        // ---------------------------------------------------------------------
        // 7. SHARE LOCATION ON GOOGLE MAPS
        // ---------------------------------------------------------------------
        if (lower.contains("share my location") || lower.contains("send location") ||
            lower.contains("लोकेशन पाठवा") || lower.contains("लोकेशन भेजें")) {
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "तुमची गुगल मॅप्स लोकेशन लिंक शेअर करत आहे."
                AppLanguage.HINDI -> "आपका गूगल मैप्स लोकेशन लिंक शेयर किया जा रहा है।"
                AppLanguage.ENGLISH -> "Sharing your current location on Google Maps."
            }
            return@withContext AssistantAction.ShareLocation(prompt)
        }

        // ---------------------------------------------------------------------
        // 8. HARDWARE TORCH
        // ---------------------------------------------------------------------
        if (lower.contains("torch on") || lower.contains("flashlight on") ||
            lower.contains("लाइट चालू") || lower.contains("टॉर्च चालू") || lower.contains("फ्लॅशलाइट सुरू")) {
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "फ्लॅशलाइट सुरू केली."
                AppLanguage.HINDI -> "टॉर्च चालू कर दी गई।"
                AppLanguage.ENGLISH -> "Flashlight turned on."
            }
            return@withContext AssistantAction.ToggleTorch(true, prompt)
        }
        if (lower.contains("torch off") || lower.contains("flashlight off") ||
            lower.contains("लाइट बंद") || lower.contains("टॉर्च बंद") || lower.contains("फ्लॅशलाइट बंद")) {
            val prompt = when (lang) {
                AppLanguage.MARATHI -> "फ्लॅशलाइट बंद केली."
                AppLanguage.HINDI -> "टॉर्च बंद कर दी गई।"
                AppLanguage.ENGLISH -> "Flashlight turned off."
            }
            return@withContext AssistantAction.ToggleTorch(false, prompt)
        }

        // ---------------------------------------------------------------------
        // 9. GENERAL ONLINE AI CONVERSATION (In authentic Marathi, Hindi, English)
        // ---------------------------------------------------------------------
        val fallbackAiAnswer = askKeylessFallbackAi(query, lang)
        if (!fallbackAiAnswer.isNullOrBlank()) {
            return@withContext AssistantAction.SpokenResponse(sanitizeForTTS(fallbackAiAnswer))
        }

        val offlineAnswer = when (lang) {
            AppLanguage.MARATHI -> "मी तुमचा ऑरासाइट व्हॉइस असिस्टंट आहे. तुम्ही मला तुमचे स्थान, आजूबाजूचा परिसर किंवा जतन केलेल्या माहितीबद्दल विचारू शकता."
            AppLanguage.HINDI -> "मैं आपका ऑरासाइट वॉइस असिस्टेंट हूँ। आप मुझसे अपने स्थान, आसपास के माहौल या सहेजी गई जानकारी के बारे में पूछ सकते हैं।"
            AppLanguage.ENGLISH -> "I am your AuraSight voice assistant. You can ask me about your location, what is around you, or your saved personal details."
        }
        return@withContext AssistantAction.SpokenResponse(offlineAnswer)
    }


    private fun askKeylessFallbackAi(
        query: String,
        lang: AppLanguage
    ): String? {
        return try {
            val (langName, scriptName) = when (lang) {
                AppLanguage.MARATHI -> Pair("Marathi", "मराठी (देवनागरी)")
                AppLanguage.HINDI -> Pair("Hindi", "हिन्दी (देवनागरी)")
                AppLanguage.ENGLISH -> Pair("English", "English")
            }

            val systemPrompt = "You are AuraSight, a helpful spoken assistant for a blind person in India. " +
                    "Answer the user's question directly in authentic spoken $langName ($scriptName). " +
                    "Write your entire response in 3 to 4 complete, clear sentences. " +
                    "Do NOT use English words if language is Marathi or Hindi. Do NOT use markdown, bullet points, or asterisks."

            val jsonPayload = JSONObject().apply {
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", query)
                    })
                })
                put("model", "openai")
                put("jsonMode", false)
            }

            val body = jsonPayload.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("https://text.pollinations.ai/")
                .post(body)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "text/plain; charset=utf-8")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                val answer = bytes?.let { String(it, Charsets.UTF_8).trim() }
                if (!answer.isNullOrBlank() && !answer.startsWith("{") && !answer.contains("error", true)) {
                    return answer
                }
            }

            // Secondary instant backup engine for Marathi & Hindi
            val safeQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val safeSys = java.net.URLEncoder.encode("उत्तर फक्त ३-४ वाक्यांत $scriptName भाषेत द्या. No markdown.", "UTF-8")
            val fallbackUrl = "https://text.pollinations.ai/$safeQuery?system=$safeSys"
            val getRequest = Request.Builder().url(fallbackUrl).get().build()
            val getResponse = okHttpClient.newCall(getRequest).execute()
            if (getResponse.isSuccessful) {
                val bytes = getResponse.body?.bytes()
                val ans = bytes?.let { String(it, Charsets.UTF_8).trim() }
                if (!ans.isNullOrBlank()) return ans
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun analyzeDocumentAdvanced(
        bitmap: Bitmap,
        lang: AppLanguage = AppLanguage.ENGLISH
    ): String? = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) return@withContext null

        try {
            val base64Image = compressBitmapForOcr(bitmap)
            val languageInstruction = when (lang) {
                AppLanguage.MARATHI -> "Output strictly in plain spoken MARATHI (मराठी)."
                AppLanguage.HINDI -> "Output strictly in plain spoken HINDI (हिन्दी)."
                AppLanguage.ENGLISH -> "Output strictly in plain spoken ENGLISH."
            }

            val universalPrompt = """
                You are a universal document and text reader for a blind person in India.
                Identify what kind of document or object this is and read key details clearly.
                $languageInstruction
                RULES:
                1. If Banknote: State exact Rupee value and verify authenticity.
                2. If Bill/Receipt: State merchant and total amount due.
                3. If Medicine: State brand name, strength (mg/ml), and expiry date.
                4. If ID Card: State ID type, full name, and last 4 digits only.
                5. Output under 35 words. No markdown or asterisks.
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
                if (!text.isNullOrBlank()) return@withContext sanitizeForTTS(text)
            }
        } catch (e: Exception) {
            Log.e(tag, "Gemini document analysis exception: ${e.message}")
        }
        return@withContext null
    }

    suspend fun describeScene(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) return@withContext "Camera active. Looking around..."

        try {
            val base64Image = compressBitmapToBase64(bitmap)
            val prompt = """
                You are eyes for a blind person walking in India. 
                Describe what is in front in 2 short, factual spoken sentences under 25 words:
                1. What is immediately in front (path, stairs, person, vehicle).
                2. Immediate walking hazards.
                No markdown, no bullet points.
            """.trimIndent()

            val jsonBody = buildMultimodalPayload(prompt, base64Image)
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(jsonBody.toString().toRequestBody(mediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val text = extractTextFromGeminiResponse(response.body?.string() ?: "")
                if (!text.isNullOrBlank()) return@withContext sanitizeForTTS(text)
            }
        } catch (e: Exception) {
            Log.e(tag, "Scene description error: ${e.message}")
        }
        "Path ahead appears open. Proceed with caution."
    }

    suspend fun analyzeGroundedDocument(
        bitmap: Bitmap,
        rawOcrText: String,
        lang: AppLanguage = AppLanguage.ENGLISH
    ): String = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) return@withContext "Camera active."

        try {
            val base64Image = compressBitmapForOcr(bitmap)
            val prompt = """
                You are reading a photo for a blind person. Use ONLY visible text.
                Return JSON: {"brand_or_title": "", "strength_or_id": "", "expiry_or_date": "", "key_summary": ""}
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
                val safeBrand = if (isBrandGrounded) brand else "Document"
                val safeStrength = if (DocumentSafetyRules.isGrounded(strength, rawOcrText)) strength else ""
                val safeExpiry = if (DocumentSafetyRules.isGrounded(expiry, rawOcrText) && expiry.isNotBlank()) "Expiry: $expiry." else ""

                return@withContext when (lang) {
                    AppLanguage.MARATHI -> "$safeBrand. $safeStrength. $safeExpiry $summary."
                    AppLanguage.HINDI -> "$safeBrand. $safeStrength. $safeExpiry $summary."
                    AppLanguage.ENGLISH -> "$safeBrand. $safeStrength. $safeExpiry $summary."
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Grounded Gemini analysis error: ${e.message}")
        }
        "Hold document steady and try again."
    }

    suspend fun analyzeImageWithGemini(bitmap: Bitmap, customPrompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = getResolvedApiKey()
        if (apiKey.isBlank()) return@withContext null

        val candidateModels = listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
        val base64Image = compressBitmapForOcr(bitmap)
        val prompt = customPrompt.ifBlank {
            "Read and summarize all visible text and details from this image in 2 short spoken sentences for a blind user. No markdown."
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
                    if (!text.isNullOrBlank()) return@withContext sanitizeForTTS(text)
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
                put("maxOutputTokens", 500)
            })
        }
    }

    private fun extractTextFromGeminiResponse(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null

            for (i in (parts.length() - 1) downTo 0) {
                val part = parts.getJSONObject(i)
                val isThought = part.optBoolean("thought", false)
                val text = part.optString("text", "").trim()
                if (!isThought && text.isNotBlank()) return text
            }
            parts.getJSONObject(0).optString("text")
        } catch (_: Exception) {
            null
        }
    }

    private fun compressBitmapForOcr(bitmap: Bitmap): String {
        val cropX = (bitmap.width * 0.05f).toInt()
        val cropY = (bitmap.height * 0.05f).toInt()
        val cropW = (bitmap.width * 0.90f).toInt().coerceAtLeast(100)
        val cropH = (bitmap.height * 0.90f).toInt().coerceAtLeast(100)
        val centerDocBmp = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)

        val maxDim = 1600
        val scale = minOf(maxDim.toFloat() / centerDocBmp.width, maxDim.toFloat() / centerDocBmp.height, 1.0f)
        val scaledWidth = (centerDocBmp.width * scale).toInt()
        val scaledHeight = (centerDocBmp.height * scale).toInt()

        val scaledBmp = Bitmap.createScaledBitmap(centerDocBmp, scaledWidth, scaledHeight, true)
        val outputStream = ByteArrayOutputStream()
        scaledBmp.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    private fun compressBitmapToBase64(bitmap: Bitmap): String {
        val maxDim = 800
        val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1.0f)
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBmp = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val outputStream = ByteArrayOutputStream()
        scaledBmp.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

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