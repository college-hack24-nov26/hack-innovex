package com.example.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.model.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VoiceManager(private val context: Context) {

    private val tag = "VoiceManager"
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    private val _soundLevelRms = MutableStateFlow(0f)
    val soundLevelRms: StateFlow<Float> = _soundLevelRms.asStateFlow()

    private var onSpeechResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    init {
        mainHandler.post {
            initRecognizer()
        }
    }

    private fun initRecognizer() {
        try {
            speechRecognizer?.destroy()
            // Directly create SpeechRecognizer (bypasses Android 11+ isRecognitionAvailable false-negative)
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        _soundLevelRms.value = (rmsdB.coerceIn(0f, 10f) / 10f)
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        // If partial speech was already recognized before timeout, use it!
                        val partial = _lastRecognizedText.value.trim()
                        if (partial.isNotEmpty() &&
                            !partial.startsWith("Listening", ignoreCase = true) &&
                            !partial.startsWith("Tap", ignoreCase = true)
                        ) {
                            onSpeechResultCallback?.invoke(partial)
                            return
                        }

                        val errorDesc = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                            else -> "Speech recognition not available"
                        }
                        Log.d(tag, "Speech recognizer error $error: $errorDesc")
                        onErrorCallback?.invoke(errorDesc)
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim()
                        if (!text.isNullOrEmpty()) {
                            _lastRecognizedText.value = text
                            onSpeechResultCallback?.invoke(text)
                        } else {
                            onErrorCallback?.invoke("No speech recognized")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim()
                        if (!partial.isNullOrEmpty()) {
                            _lastRecognizedText.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(tag, "Error initializing SpeechRecognizer: ${e.message}")
        }
    }

    fun startListening(
        lang: AppLanguage = AppLanguage.ENGLISH,
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        this.onSpeechResultCallback = onResult
        this.onErrorCallback = onError

        mainHandler.post {
            try {
                // Cancel ongoing session and recreate recognizer cleanly
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
                speechRecognizer = null

                initRecognizer()

                val recognizer = speechRecognizer
                if (recognizer == null) {
                    _isListening.value = false
                    onError("Microphone not available.")
                    return@post
                }

                val languageTag = when (lang) {
                    AppLanguage.MARATHI -> "mr-IN"
                    AppLanguage.HINDI -> "hi-IN"
                    AppLanguage.ENGLISH -> "en-IN"
                }

                _lastRecognizedText.value = ""

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                recognizer.startListening(intent)
                _isListening.value = true
            } catch (e: Exception) {
                Log.e(tag, "Error starting SpeechRecognizer: ${e.message}")
                _isListening.value = false
                onError("Microphone error.")
            }
        }
    }



    fun stopListening() {
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping SpeechRecognizer: ${e.message}")
            }
            _isListening.value = false
        }
    }

    fun simulateSpokenCommand(command: String) {
        _lastRecognizedText.value = command
        onSpeechResultCallback?.invoke(command)
    }

    fun release() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.e(tag, "Error destroying speech recognizer: ${e.message}")
            }
            speechRecognizer = null
        }
    }
}