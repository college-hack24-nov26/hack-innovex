package com.example.settings

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import com.example.model.AppLanguage
import com.example.model.UserSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserSettingsManager(private val context: Context) {

    private val tag = "UserSettingsManager"

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    private fun loadSettings(): UserSettings {
        val prefs = context.getSharedPreferences("drishti_user_settings", Context.MODE_PRIVATE)
        val langStr = prefs.getString("app_language", AppLanguage.ENGLISH.name) ?: AppLanguage.ENGLISH.name
        val language =  try {
            AppLanguage.valueOf(langStr)
        } catch (_: Exception) {
            AppLanguage.ENGLISH
        }
        return UserSettings(
            userName = prefs.getString("user_name", "") ?: "",
            emergencyContactName = prefs.getString("contact_name", "") ?: "",
            emergencyContactPhone = prefs.getString("contact_phone", "") ?: "",
            emergencyContactPhone2 = prefs.getString("contact_phone_2", "") ?: "",
            caregiverEmail = "", // Email removed
            homeAddress = prefs.getString("home_address", "") ?: "",
            workAddress = prefs.getString("work_address", "") ?: "",
            language = language
        )
    }

    fun saveSettings(s: UserSettings) {
        val prefs = context.getSharedPreferences("drishti_user_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("user_name", s.userName)
            .putString("contact_name", s.emergencyContactName)
            .putString("contact_phone", s.emergencyContactPhone)
            .putString("contact_phone_2", s.emergencyContactPhone2)
            .putString("home_address", s.homeAddress)
            .putString("work_address", s.workAddress)
            .putString("app_language", s.language.name)
            .apply()
        _settings.value = s
    }

    /**
     * Direct Background SMS SOS (Email completely removed):
     * Dispatches live GPS coordinates and Google Maps link to emergency contacts.
     */
    fun triggerEmergencySOS(locationStreet: String, gmapsUrl: String): String {
        val s = _settings.value

        val smsMessage = "EMERGENCY SOS! I need help. My location: $locationStreet. Track me live on Google Maps: $gmapsUrl"
        val phoneNumbers = listOf(s.emergencyContactPhone, s.emergencyContactPhone2).filter { it.isNotBlank() }

        if (phoneNumbers.isNotEmpty()) {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                for (phone in phoneNumbers) {
                    val parts = smsManager.divideMessage(smsMessage)
                    smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
                    Log.i(tag, "SOS SMS sent to $phone")
                }
                return "Emergency SOS dispatched via SMS."
            } catch (e: Exception) {
                Log.e(tag, "SMS dispatch error: ${e.message}")
                return "SMS dispatch failed. Please check phone balance."
            }
        }

        return "No emergency contact numbers saved in Settings."
    }
}