package com.example.settings

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

object NativeEmailSender {

    private const val TAG = "NativeEmailSender"

    // --------------------------------------------------------------------------
    // 1. YOUR SENDER GMAIL & APP PASSWORD:
    // Generate at: https://myaccount.google.com/security -> 2-Step Verification -> App Passwords
    // --------------------------------------------------------------------------
    private const val SENDER_EMAIL = "kokareakshay2006@gmail.com" // <-- PUT YOUR REAL GMAIL HERE
    private const val SENDER_APP_PASSWORD = "dqwp atqu oqil dejq" // <-- PUT YOUR 16-CHAR APP PASSWORD

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun sendEmergencyEmail(
        recipientEmail: String,
        userName: String,
        locationStreet: String,
        gmapsUrl: String
    ): Boolean = withContext(Dispatchers.IO) {

        // Check if real credentials were provided
        val hasRealCredentials = SENDER_EMAIL != "your.email@gmail.com" &&
                !SENDER_APP_PASSWORD.contains("abcd") &&
                SENDER_APP_PASSWORD.isNotBlank()

        if (hasRealCredentials) {
            // ENGINE 1: Native Gmail SMTP
            val smtpSuccess = sendViaJavaMailSmtp(recipientEmail, userName, locationStreet, gmapsUrl)
            if (smtpSuccess) {
                Log.i(TAG, "Email successfully sent via Native SMTP to $recipientEmail")
                return@withContext true
            }
            Log.w(TAG, "Native SMTP failed. Triggering automatic HTTPS fallback...")
        } else {
            Log.w(TAG, "Real Gmail credentials not set in NativeEmailSender.kt. Using direct HTTPS email dispatcher.")
        }

        // ENGINE 2: Direct HTTPS Email Fallback (Zero password / Zero port blocking)
        return@withContext sendViaDirectHttps(recipientEmail, userName, locationStreet, gmapsUrl)
    }

    /**
     * Engine 1: Android JavaMail SMTP with TLS 1.2/1.3 and SSL trust.
     */
    private fun sendViaJavaMailSmtp(
        recipientEmail: String,
        userName: String,
        locationStreet: String,
        gmapsUrl: String
    ): Boolean {
        return try {
            val cleanPassword = SENDER_APP_PASSWORD.replace(" ", "").trim()

            val props = Properties().apply {
                put("mail.transport.protocol", "smtp")
                put("mail.smtp.host", "smtp.gmail.com")
                put("mail.smtp.port", "465")
                put("mail.smtp.auth", "true")
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.trust", "smtp.gmail.com")
                put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
                put("mail.smtp.connectiontimeout", "8000")
                put("mail.smtp.timeout", "8000")
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(SENDER_EMAIL.trim(), cleanPassword)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(SENDER_EMAIL, "AuraSight SOS Alert"))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipientEmail.trim()))
                subject = "🚨 EMERGENCY SOS: ${if (userName.isBlank()) "AuraSight User" else userName} needs help!"

                val htmlBody = """
                    <div style="font-family: Arial, sans-serif; padding: 22px; border: 3px solid #d32f2f; border-radius: 12px; background-color: #fff9f9;">
                        <h1 style="color: #d32f2f; margin-top: 0;">🚨 EMERGENCY SOS ALERT</h1>
                        <p style="font-size: 16px; color: #333;">
                            An emergency SOS was triggered from the AuraSight assistive app.
                        </p>
                        <div style="background-color: #ffffff; padding: 15px; border-left: 4px solid #d32f2f; margin: 18px 0;">
                            <p style="margin: 5px 0;"><strong>User Name:</strong> ${if (userName.isBlank()) "AuraSight User" else userName}</p>
                            <p style="margin: 5px 0;"><strong>Last Known Location:</strong> $locationStreet</p>
                        </div>
                        <div style="text-align: center; margin: 25px 0;">
                            <a href="$gmapsUrl" style="background-color: #d32f2f; color: #ffffff; padding: 14px 28px; text-decoration: none; font-size: 16px; font-weight: bold; border-radius: 6px; display: inline-block;">
                                📍 OPEN LIVE GOOGLE MAPS LOCATION
                            </a>
                        </div>
                        <p style="color: #777; font-size: 12px;">Direct link: <a href="$gmapsUrl">$gmapsUrl</a></p>
                    </div>
                """.trimIndent()

                setContent(htmlBody, "text/html; charset=utf-8")
            }

            Transport.send(message)
            true
        } catch (e: Exception) {
            Log.e(TAG, "SMTP Error: ${e.javaClass.simpleName} - ${e.message}")
            false
        }
    }

    /**
     * Engine 2: Direct HTTPS Dispatcher via FormSubmit (Port 443).
     * Works on any carrier, requires NO password, NO API key.
     */
    private fun sendViaDirectHttps(
        recipientEmail: String,
        userName: String,
        locationStreet: String,
        gmapsUrl: String
    ): Boolean {
        return try {
            val target = recipientEmail.trim()
            val url = "https://formsubmit.co/ajax/$target"

            val json = JSONObject().apply {
                put("_subject", "🚨 EMERGENCY SOS: ${if (userName.isBlank()) "AuraSight User" else userName} needs help!")
                put("User", if (userName.isBlank()) "AuraSight User" else userName)
                put("Emergency Location", locationStreet)
                put("Google Maps Live Link", gmapsUrl)
                put("_template", "table")
                put("_captcha", "false")
            }

            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val success = response.isSuccessful
            if (success) {
                Log.i(TAG, "Direct HTTPS email successfully delivered to $target")
            } else {
                Log.e(TAG, "Direct HTTPS email returned code: ${response.code}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Direct HTTPS delivery error: ${e.message}")
            false
        }
    }
}