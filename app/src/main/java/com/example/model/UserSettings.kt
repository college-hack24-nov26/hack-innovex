package com.example.model

import java.util.Locale



// 2. DATA CLASS
data class UserSettings(
    val userName: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val emergencyContactPhone2: String = "",
    val caregiverEmail: String = "",
    val homeAddress: String = "",
    val workAddress: String = "",
    val language: AppLanguage = AppLanguage.ENGLISH
) {
    val isHomeSet: Boolean get() = homeAddress.isNotBlank()
    val isWorkSet: Boolean get() = workAddress.isNotBlank()
    // DO NOT place enum class AppLanguage inside here!
}