# Drishti: AI Navigation & Safety Assistant for Visually Impaired People

**Drishti** is an accessibility-first Android application designed to help blind and visually impaired individuals move safely and independently. The app operates **voice-first**, combining continuous rear-camera computer vision, Google Maps pedestrian routing & location sharing, Indian document/PAN card/Aadhaar/currency OCR, bus route & destination detection, and conversational AI assistance with Gemini 3.8 Flash. All feedback is delivered through loud spoken audio, high-contrast visual cues, and distinctive vibration patterns.

---

## What's New & Core Capabilities

### 1. Google Maps Support for Location & Pedestrian Route Finding
- **Send Current Location via Google Maps:**
  - One-tap button and voice command: *"Send my location"* / *"Share my location on Google Maps"*.
  - Generates universal Google Maps coordinates link (`https://www.google.com/maps/search/?api=1&query=lat,lng`) along with street address and landmark.
  - Automatically copies to clipboard and opens the system share sheet (WhatsApp, SMS, Messaging).
- **Find Correct Walking Route to Selected Destination:**
  - Enter or speak any destination: *"Take me to New Delhi Railway Station"*, *"Directions to Apollo Hospital"*, *"Nearest metro station"*.
  - Uses Geocoding and Gemini 3.8 Flash with Google Maps Grounding to calculate real-world walking steps, total distance, and estimated walk duration.
  - **Interactive Visual Route Map:** High-contrast pedestrian map showing start location, walking polyline with waypoints, current progress marker, and destination flag.
  - **Open in Google Maps Navigation:** One-tap button launching the native Google Maps app directly in Walking Turn-by-Turn Voice Navigation mode (`google.navigation:q=lat,lng&mode=w`).

### 2. High-Accuracy Document, Currency & Bus Recognition
- **Indian PAN Card Recognition:**
  - Detects official markers (*"INCOME TAX DEPARTMENT"*, *"GOVT. OF INDIA"*, *"PERMANENT ACCOUNT NUMBER"*).
  - Extracts 10-character PAN number (`ABCDE1234F`), Holder's Name, Father's Name, and Date of Birth.
  - Speaks aloud: *"Detected Indian PAN Card issued by Income Tax Department, Government of India. PAN number is A B C D E 1 2 3 4 F. Name on card is Rajesh Sharma."*
- **Indian Aadhaar Card Recognition:**
  - Detects UIDAI markers and 12-digit Aadhaar number.
  - Speaks aloud: *"Detected Indian Aadhaar Card from Government of India. Aadhaar number ending in 1098."*
- **Bus Route Number & Destination:**
  - Scans oncoming bus destination displays and route signs (BMTC, DTC, BEST, MSRTC, etc.).
  - Extracts route numbers (e.g. *335-E*, *500-D*, *21A*) **and** route destinations (e.g. *Majestic*, *Shivajinagar*, *Kashmere Gate*, *Dadar*, *Electronic City*).
  - Speaks aloud: *"Approaching Bus Number 335-E, heading towards Majestic Bus Station."*
- **Medicine & Prescription Recognition:**
  - Scans medicine boxes, strips, and doctor prescriptions for drug names and dosages (e.g. *"Detected Medicine: Paracetamol, dosage 500 mg"*).
- **Indian Currency Notes:**
  - Full recognition for Mahatma Gandhi New Series banknotes: ₹10, ₹20, ₹50, ₹100, ₹200, ₹500, and ₹2000 notes.
- **Deep Multimodal AI Vision Analysis (Gemini 3.8 Flash):**
  - "Describe & Read with Gemini AI Vision" analyzes any document, bill, receipt, or surroundings in depth.

### 3. Loud Spoken Front Obstacle Detection
- **Ground-Baseline Proximity Calibration:**
  - Evaluates camera Y-luminance gradients with ground-contact vertical baseline tracking.
  - Proximity formula accurately calculates distance from 0.6m to 4.5m directly ahead in the user's walking path.
- **Loud Spoken Warnings:**
  - Danger (< 1.5m): Speaks loudly at 100% volume: *"Danger! Wall 1.2 meters directly ahead! Stop moving."*
  - Warning (1.5m – 2.8m): *"Caution, Person directly ahead at 2.0 meters."*
  - Side obstacles: *"Vehicle on your right, 2.2 meters."*
  - Staircases: *"Danger! Stairs detected ahead! Step carefully."*
- **Alarm Tone & Pulsed Haptic Feedback:** High-frequency tone generator and rapid sharp vibration pulses.
- **Preemption:** Danger alerts instantly preempt and interrupt all other audio.

### 4. Tap Anywhere to Speak
- Touch anywhere on the screen background or on the prominent top master card.
- Instantly triggers audio tone chime, short vibration confirmation, and begins speech recognition.
- Hardware Volume Down button also triggers voice listening without touching the screen.
- Clear spoken status: *"Listening... speak now."*

---

## Audio & Alert Priority Levels

| Priority | Level | Action on Conflict |
|:---|:---|:---|
| **1 (Highest)** | **Danger / Front Obstacles** | **Immediately interrupts** current speech (`tts.stop()`), fires rapid danger vibration and alarm tone |
| **2** | **Navigation Instructions** | Interrupts assistant or reader, announces walking maneuvers and distances |
| **3** | **AI Assistant Replies** | Speaks Gemini 3.8 Flash replies with Search and Maps grounding |
| **4** | **Information & Documents** | PAN cards, Aadhaar, currency, bus routes, medicines |

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml          # Camera, Audio, Location, Internet, Vibrate
├── java/com/example/
│   ├── MainActivity.kt          # Permissions & hardware volume triggers
│   ├── model/
│   │   ├── AppMode.kt           # WALKING, SCAN, ASSISTANT modes & ScanType
│   │   ├── Obstacle.kt          # Obstacle, Severity, Direction & Distance
│   │   ├── AlertItem.kt         # Priority queue models & Vibration/Tone types
│   │   └── NavigationData.kt    # Walking steps, maneuvers & GPS state
│   ├── alerts/
│   │   └── AlertManager.kt      # Central TTS, loud audio volume, & preemption
│   ├── detection/
│   │   └── ObstacleDetector.kt  # CameraX ground baseline & front obstacle detector
│   ├── reader/
│   │   └── InfoReader.kt        # PAN Card, Aadhaar, Bus route, Currency, Medicine OCR
│   ├── navigation/
│   │   └── NavigationManager.kt # Google Maps location sharing, routes, & turn guidance
│   ├── assistant/
│   │   ├── VoiceManager.kt      # Android SpeechRecognizer wrapper
│   │   └── GeminiAssistant.kt   # Gemini 3.8 Flash with Search & Maps Grounding
│   └── ui/
│       ├── DrishtiViewModel.kt  # Central reactive MVVM coordinator
│       ├── CameraPreviewView.kt # CameraX preview with high-contrast AR overlay
│       ├── DrishtiMainScreen.kt # Accessibility UI, Google Maps canvas, & loud alerts
│       └── theme/               # High-contrast accessibility theme & colors
```

---

## Setup & API Keys

### 1. Gemini API Key Configuration
1. In Google AI Studio, open the **Secrets** panel.
2. Add your `GEMINI_API_KEY`.
3. The Secrets Gradle Plugin automatically injects it into `BuildConfig.GEMINI_API_KEY` at build time.
4. If no key is set, the app seamlessly runs its built-in local offline intent engine.

### 2. Required Device Permissions
The app prompts for the following runtime permissions on launch:
- `CAMERA`: Continuous obstacle detection and document/bus OCR.
- `RECORD_AUDIO`: Voice command recognition.
- `ACCESS_FINE_LOCATION` & `ACCESS_COARSE_LOCATION`: Walking navigation and Google Maps location tracking.
- `VIBRATE`: Normal permission declared in Manifest.

---

## Safety Disclaimer
Drishti is designed as an electronic assistive aid. It is intended to complement, not replace, a traditional white cane, guide dog, or sighted human guide.
# Imagination
