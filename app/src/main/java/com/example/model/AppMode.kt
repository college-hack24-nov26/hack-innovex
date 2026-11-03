package com.example.model

enum class AppMode(val englishName: String) {
    WALKING("Walking"),
    SCAN("Scan"),
    ASSISTANT("Assistant"),
    SETTINGS("Settings");

    fun displayName(lang: AppLanguage): String = when (lang) {
        AppLanguage.MARATHI -> when (this) {
            WALKING -> "चालणे"
            SCAN -> "स्कॅन"
            ASSISTANT -> "मदतनीस"
            SETTINGS -> "सेटिंग्ज"
        }
        AppLanguage.HINDI -> when (this) {
            WALKING -> "चलना"
            SCAN -> "स्कैन"
            ASSISTANT -> "सहायक"
            SETTINGS -> "सेटिंग्स"
        }
        AppLanguage.ENGLISH -> this.englishName
    }

    fun shortSpoken(lang: AppLanguage): String = when (lang) {
        AppLanguage.MARATHI -> when (this) {
            WALKING -> "चालणे मोड सुरू झाला."
            SCAN -> "स्कॅन मोड सुरू झाला."
            ASSISTANT -> "व्हॉइस असिस्टंट सुरू झाला."
            SETTINGS -> "सेटिंग्ज उघडले."
        }
        AppLanguage.HINDI -> when (this) {
            WALKING -> "वॉकिंग मोड चालू।"
            SCAN -> "स्कैन मोड चालू।"
            ASSISTANT -> "वॉइस असिस्टेंट चालू।"
            SETTINGS -> "सेटिंग्स खुली।"
        }
        AppLanguage.ENGLISH -> when (this) {
            WALKING -> "Walking mode on."
            SCAN -> "Scan mode on."
            ASSISTANT -> "Assistant mode on."
            SETTINGS -> "Settings on."
        }
    }
}

enum class ScanType(val englishTitle: String) {
    ALL("Auto All"),
    DOCUMENTS("Documents"),
    CURRENCY("Rupees"),
    MEDICINE("Medicine");

    fun title(lang: AppLanguage): String = when (lang) {
        AppLanguage.MARATHI -> when (this) {
            ALL -> "सर्व बाबी"
            DOCUMENTS -> "कागदपत्रे"
            CURRENCY -> "रुपये"
            MEDICINE -> "औषधे"
        }
        AppLanguage.HINDI -> when (this) {
            ALL -> "ऑटो ऑल"
            DOCUMENTS -> "दस्तावेज़"
            CURRENCY -> "रुपये"
            MEDICINE -> "दवाइयां"
        }
        AppLanguage.ENGLISH -> this.englishTitle
    }

    fun shortSpokenPrompt(lang: AppLanguage): String = when (lang) {
        AppLanguage.MARATHI -> when (this) {
            ALL -> "सर्व बाबी स्कॅनिंग सुरू."
            DOCUMENTS -> "कागदपत्र स्कॅनर सक्रिय."
            CURRENCY -> "चलन नोट स्कॅनर सक्रिय."
            MEDICINE -> "औषध स्कॅनर सक्रिय."
        }
        AppLanguage.HINDI -> when (this) {
            ALL -> "सभी चीजें स्कैनिंग शुरू।"
            DOCUMENTS -> "दस्तावेज़ स्कैनर सक्रिय।"
            CURRENCY -> "करेंसी नोट स्कैनर सक्रिय।"
            MEDICINE -> "दवा स्कैनर सक्रिय।"
        }
        AppLanguage.ENGLISH -> when (this) {
            ALL -> "Scanning all items."
            DOCUMENTS -> "Document scanner active."
            CURRENCY -> "Currency reader active."
            MEDICINE -> "Medicine reader active."
        }
    }
}