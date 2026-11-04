package com.example.model

object AppStrings {

    // --- TOP BAR & APP HEADER ---
    fun appSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "आवाज-प्रथम दृष्टी आणि मार्गदर्शन"
        AppLanguage.HINDI -> "आवाज-प्रथम दृष्टि और मार्गदर्शन"
        AppLanguage.ENGLISH -> "Voice-First Vision & Guidance"
    }

    // --- BANNER LABELS ---
    fun lastSpokenTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "शेवटचा बोललेला इशारा"
        AppLanguage.HINDI -> "अंतिम बोला गया अलर्ट"
        AppLanguage.ENGLISH -> "LAST SPOKEN ALERT"
    }

    fun readyMessage(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "ऑरासाइट सज्ज आहे. अडथळा शोध सुरू आहे."
        AppLanguage.HINDI -> "ऑरासाइट तैयार है। रुकावट पहचान सक्रिय है।"
        AppLanguage.ENGLISH -> "AuraSight ready. Obstacle detection active."
    }

    // --- TAP TO SPEAK ---
    fun tapToSpeakTitle(lang: AppLanguage, isListening: Boolean) = if (isListening) {
        when (lang) {
            AppLanguage.MARATHI -> "ऐकत आहे... आता बोला"
            AppLanguage.HINDI -> "सुन रहा हूँ... अब बोलिए"
            AppLanguage.ENGLISH -> "LISTENING... SPEAK NOW"
        }
    } else {
        when (lang) {
            AppLanguage.MARATHI -> "माईक सुरू करण्यासाठी येथे दाबा"
            AppLanguage.HINDI -> "माइक शुरू करने के लिए यहाँ दबाएं"
            AppLanguage.ENGLISH -> "PRESS HERE TO SPEAK"
        }
    }

    fun fallDetectedWarning(lang: AppLanguage, secondsLeft: Int) = when (lang) {
        AppLanguage.MARATHI -> "सावधान! तुम्ही पडल्याचे आढळले आहे. तुम्ही ठीक आहात का? रद्द करण्यासाठी स्क्रीनवर दाबा. $secondsLeft सेकंदात आपत्कालीन मेसेज जाईल."
        AppLanguage.HINDI -> "सावधान! आपके गिरने का पता चला है। क्या आप ठीक हैं? रद्द करने के लिए स्क्रीन पर टैप करें। $secondsLeft सेकंड में आपातकालीन संदेश जाएगा।"
        AppLanguage.ENGLISH -> "Warning! Fall detected. Are you okay? Tap screen or say cancel to abort. Sending emergency SOS in $secondsLeft seconds."
    }

    fun fallCancelled(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "आपत्कालीन इशारा रद्द केला आहे."
        AppLanguage.HINDI -> "आपातकालीन अलर्ट रद्द कर दिया गया है।"
        AppLanguage.ENGLISH -> "Emergency alert cancelled. You are safe."
    }

    fun tapToSpeakSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "'मी कुठे आहे?', 'घरी जा' किंवा काहीही विचारा"
        AppLanguage.HINDI -> "'मैं कहाँ हूँ?', 'घर ले चलो' या कुछ भी पूछें"
        AppLanguage.ENGLISH -> "Ask 'Where am I?', 'Take me home', or any question"
    }

    fun listeningPrompt(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "ऐकत आहे..."
        AppLanguage.HINDI -> "सुन रहा हूँ..."
        AppLanguage.ENGLISH -> "Listening..."
    }

    // --- WALKING MODE ---
    fun pathScanning(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "मार्ग स्कॅनिंग सुरू आहे"
        AppLanguage.HINDI -> "रास्ता स्कैनिंग सक्रिय है"
        AppLanguage.ENGLISH -> "ACTIVE SCANNING PATH"
    }

    fun pathMonitoringDesc(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "कॅमेरा समोर भिंत, वाहने, अडथळे आणि पायऱ्यांवर सतत लक्ष ठेवून आहे."
        AppLanguage.HINDI -> "कैमरा सामने दीवार, वाहन, रुकावट और सीढ़ियों की निगरानी कर रहा है।"
        AppLanguage.ENGLISH -> "Camera is actively monitoring for walls, vehicles, and stairs ahead."
    }

    fun lookAroundBtn(lang: AppLanguage, isAnalyzing: Boolean) = if (isAnalyzing) {
        when (lang) {
            AppLanguage.MARATHI -> "परिसराचे विश्लेषण करत आहे..."
            AppLanguage.HINDI -> "आसपास का विश्लेषण हो रहा है..."
            AppLanguage.ENGLISH -> "Analyzing surroundings..."
        }
    } else {
        when (lang) {
            AppLanguage.MARATHI -> "आसपास पहा (व्हॉल्यूम अप)"
            AppLanguage.HINDI -> "आसपास देखें (वॉल्यूम अप)"
            AppLanguage.ENGLISH -> "Look Around (Volume Up)"
        }
    }

    fun findWalkingRoute(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "चालण्याचा मार्ग शोधा"
        AppLanguage.HINDI -> "पैदल मार्ग खोजें"
        AppLanguage.ENGLISH -> "Find Walking Route"
    }

    fun destinationPlaceholder(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "गंतव्य उदा. मेट्रो स्टेशन, रुग्णालय"
        AppLanguage.HINDI -> "मंजिल उदा. मेट्रो स्टेशन, अस्पताल"
        AppLanguage.ENGLISH -> "Destination e.g. Metro Station, Hospital"
    }

    fun go(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "जा"
        AppLanguage.HINDI -> "जाएं"
        AppLanguage.ENGLISH -> "Go"
    }

    fun goHome(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "घरी जा"
        AppLanguage.HINDI -> "घर जाएं"
        AppLanguage.ENGLISH -> "Go Home"
    }

    fun goWork(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "कामावर जा"
        AppLanguage.HINDI -> "काम पर जाएं"
        AppLanguage.ENGLISH -> "Go Work"
    }

    fun sendLocation(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "लोकेशन पाठवा"
        AppLanguage.HINDI -> "लोकेशन भेजें"
        AppLanguage.ENGLISH -> "Send Location"
    }

    // --- SCAN MODE ---
    fun scanModeTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "स्कॅन मोड"
        AppLanguage.HINDI -> "स्कैन मोड"
        AppLanguage.ENGLISH -> "Scan Mode"
    }

    fun scanButton(lang: AppLanguage, isAnalyzing: Boolean) = if (isAnalyzing) {
        when (lang) {
            AppLanguage.MARATHI -> "वाचत आहे..."
            AppLanguage.HINDI -> "पढ़ रहा हूँ..."
            AppLanguage.ENGLISH -> "Reading Document..."
        }
    } else {
        when (lang) {
            AppLanguage.MARATHI -> "कागदपत्र / ओळखपत्र / औषध स्कॅन करा"
            AppLanguage.HINDI -> "दस्तावेज़ / आईडी कार्ड / दवा स्कैन करें"
            AppLanguage.ENGLISH -> "Scan Document / ID Card / Medicine"
        }
    }

    fun scannedResultTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "स्कॅन निकाल"
        AppLanguage.HINDI -> "स्कैन परिणाम"
        AppLanguage.ENGLISH -> "SCANNED RESULT"
    }

    fun scanPlaceholder(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "नोट, पॅन कार्ड किंवा ओळखपत्र समोर धरा."
        AppLanguage.HINDI -> "नोट, पैन कार्ड या आईडी सामने रखें।"
        AppLanguage.ENGLISH -> "Hold note, PAN card, or ID in view."
    }

    // --- ASSISTANT MODE ---
    fun assistantTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "ऑरासाइट एआय मदतनीस"
        AppLanguage.HINDI -> "ऑरासाइट एआई सहायक"
        AppLanguage.ENGLISH -> "AuraSight AI Spoken Assistant"
    }

    fun assistantSubtitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "बोलण्यासाठी स्पर्श करा. जेमिनी एआय द्वारे जलद उत्तरे."
        AppLanguage.HINDI -> "बोलने के लिए कहीं भी टैप करें। जेमिनी एआई द्वारा त्वरित उत्तर।"
        AppLanguage.ENGLISH -> "Tap anywhere to speak. Fast answers powered by Gemini AI."
    }

    fun queryWhereAmI(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "मी आता कुठे आहे?"
        AppLanguage.HINDI -> "मैं अभी कहाँ हूँ?"
        AppLanguage.ENGLISH -> "Where am I right now?"
    }



    fun queryTakeMeHome(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "मला घरी घेऊन चला"
        AppLanguage.HINDI -> "मुझे घर ले चलो"
        AppLanguage.ENGLISH -> "Take me home"
    }
    fun queryMyEmergencyDetails(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "माझी सर्व सेव्ह केलेली माहिती काय आहे?"
        AppLanguage.HINDI -> "मेरी सभी सहेजी गई जानकारी क्या है?"
        AppLanguage.ENGLISH -> "What are all my saved details?"
    }

    fun queryShareLocation(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "गुगल मॅप्सवर माझे लोकेशन पाठवा"
        AppLanguage.HINDI -> "गूगल मैप्स पर मेरा लोकेशन भेजें"
        AppLanguage.ENGLISH -> "Send my current location on Google Maps"
    }

    fun queryFrontObstacle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "माझ्या अगदी समोर काय आहे?"
        AppLanguage.HINDI -> "मेरे ठीक सामने क्या है?"
        AppLanguage.ENGLISH -> "What is directly in front of me?"
    }



    // --- SAFETY DISCLAIMER CARD ---
    fun safetyTitle(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "सुरक्षा आणि कॅमेरा वापर मार्गदर्शक तत्त्वे"
        AppLanguage.HINDI -> "सुरक्षा और कैमरा उपयोग निर्देश"
        AppLanguage.ENGLISH -> "Safety & Camera Usage Guidelines"
    }

    fun safetyGuidelines(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "• फोन छातीवर लॅनयार्डमध्ये अडकवून सरळ आणि किंचित खाली ठेवा.\n• पायऱ्या किंवा खड्ड्यांवर त्वरित लक्ष ठेवा.\n• ऑरासाइट सोबत नेहमी पांढरी काठी वापरा."
        AppLanguage.HINDI -> "• फोन को सीने पर लटकाएं, आगे और थोड़ा नीचे की ओर रखें।\n• सीढ़ियों और गड्ढों पर विशेष ध्यान दें।\n• हमेशा ऑरासाइट के साथ सफेद छड़ी का उपयोग करें।"
        AppLanguage.ENGLISH -> "• Wear phone on a chest lanyard pointing forward and slightly down.\n• Camera monitors road barriers and drop-offs.\n• Always use AuraSight alongside a traditional white cane."
    }

    // --- HARDWARE & SYSTEM VOICE PROMPTS ---
    fun torchOn(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "फ्लॅशलाइट सुरू केली."
        AppLanguage.HINDI -> "टॉर्च चालू कर दी गई।"
        AppLanguage.ENGLISH -> "Flashlight on."
    }

    fun torchOff(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "फ्लॅशलाइट बंद केली."
        AppLanguage.HINDI -> "टॉर्च बंद कर दी गई।"
        AppLanguage.ENGLISH -> "Flashlight off."
    }

    fun lowLightTorchOn(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "कमी प्रकाश. फ्लॅशलाइट सुरू केली."
        AppLanguage.HINDI -> "कम रोशनी है। टॉर्च चालू कर दी गई।"
        AppLanguage.ENGLISH -> "Low light. Flashlight turned on."
    }

    fun lightSufficientTorchOff(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "पुरेसा प्रकाश. फ्लॅशलाइट बंद केली."
        AppLanguage.HINDI -> "पर्याप्त रोशनी है। टॉर्च बंद कर दी गई।"
        AppLanguage.ENGLISH -> "Light sufficient. Flashlight turned off."
    }

    fun locationOffWarning(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "डिव्हाइसचे लोकेशन बंद आहे. कृपया लोकेशन सुरू करा."
        AppLanguage.HINDI -> "डिवाइस का लोकेशन बंद है। कृपया लोकेशन चालू करें।"
        AppLanguage.ENGLISH -> "Device location is off. Please turn on location."
    }

    fun sosDispatched(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "तातडीचा एसओएस एसएमएस पाठवला गेला आहे."
        AppLanguage.HINDI -> "आपातकालीन एसओएस एसएमएस भेज दिया गया है।"
        AppLanguage.ENGLISH -> "Emergency SOS dispatched via SMS."
    }

    fun navArrived(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "तुम्ही तुमच्या इच्छित स्थळी पोहोचला आहात."
        AppLanguage.HINDI -> "आप अपनी मंजिल पर पहुंच चुके हैं।"
        AppLanguage.ENGLISH -> "You have arrived at your destination."
    }

    fun navStopped(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "नेव्हिगेशन थांबवले."
        AppLanguage.HINDI -> "नेविगेशन रोक दिया गया।"
        AppLanguage.ENGLISH -> "Navigation stopped."
    }

    fun cameraBlocked(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "कॅमेरा लेन्स झाकलेली आहे. दृश्य मार्गदर्शन उपलब्ध नाही."
        AppLanguage.HINDI -> "कैमरा लेंस ढका हुआ है। दृश्य मार्गदर्शन उपलब्ध नहीं है।"
        AppLanguage.ENGLISH -> "Camera lens is covered or blocked. Visual guidance unavailable."
    }


    // --- SETTINGS SECTION TRANSLATIONS ---
    fun settingsOpened(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "सेटिंग्ज उघडले. येथे तुम्ही भाषा, नाव आणि आपत्कालीन नंबर बदलू शकता."
        AppLanguage.HINDI -> "सेटिंग्स खुल गई हैं। यहाँ आप भाषा, नाम और आपातकालीन नंबर बदल सकते हैं।"
        AppLanguage.ENGLISH -> "Settings opened. Here you can configure language, name, and emergency numbers."
    }

    fun settingsSaved(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "माहिती यशस्वीरित्या जतन केली आहे."
        AppLanguage.HINDI -> "सेटिंग्स सफलतापूर्वक सहेज ली गई हैं।"
        AppLanguage.ENGLISH -> "Settings saved successfully."
    }

    fun fullNameLabel(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "तुमचे पूर्ण नाव"
        AppLanguage.HINDI -> "आपका पूरा नाम"
        AppLanguage.ENGLISH -> "Your Full Name"
    }

    fun contactPhone1Label(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "तातडीचा फोन नंबर १ (उदा. +९१ ९८७६५४३२१०)"
        AppLanguage.HINDI -> "आपातकालीन फोन नंबर 1 (उदा. +91 9876543210)"
        AppLanguage.ENGLISH -> "Emergency Contact Phone 1"
    }

    fun contactNameLabel(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "संपर्काचे नाव (उदा. भाऊ, मित्र)"
        AppLanguage.HINDI -> "संपर्क व्यक्ति का नाम (उदा. भाई, अभिभावक)"
        AppLanguage.ENGLISH -> "Emergency Contact Name"
    }

    fun contactPhone2Label(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "तातडीचा फोन नंबर २ (पर्यायी)"
        AppLanguage.HINDI -> "आपातकालीन फोन नंबर 2 (वैकल्पिक)"
        AppLanguage.ENGLISH -> "Emergency Contact Phone 2 (Optional)"
    }

    fun homeAddressLabel(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "घराचा सेव्ह केलेला पत्ता"
        AppLanguage.HINDI -> "घर का सहेजा गया पता"
        AppLanguage.ENGLISH -> "Saved Home Address"
    }

    fun workAddressLabel(lang: AppLanguage) = when (lang) {
        AppLanguage.MARATHI -> "कार्यालय / कामाचा पत्ता"
        AppLanguage.HINDI -> "कार्यालय / ऑफिस का पता"
        AppLanguage.ENGLISH -> "Saved Work / Office Address"
    }
}