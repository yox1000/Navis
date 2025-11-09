package com.navis.pepscout.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Speech capture using Android's built-in SpeechRecognizer
 * Provides partial results and final transcripts for voice commands
 */
class SpeechCapture(private val context: Context) {
    
    companion object {
        private const val TAG = "SpeechCapture"
    }
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    // State flows for UI updates
    private val _state = MutableStateFlow(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()
    
    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult.asStateFlow()
    
    private val _finalResult = MutableStateFlow("")
    val finalResult: StateFlow<String> = _finalResult.asStateFlow()

    private var onResultCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            Log.d(TAG, "SpeechRecognizer initialized")
        } else {
            Log.w(TAG, "Speech recognition not available on this device")
        }
    }

    /**
     * Start listening for speech input
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        if (speechRecognizer == null) {
            onError("Speech recognition not available")
            return
        }
        
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }

        onResultCallback = onResult
        onErrorCallback = onError

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            
            // Optimize for voice commands
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            _state.value = SpeechState.Listening
            _partialResult.value = ""
            _finalResult.value = ""
            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            onError("Failed to start speech recognition")
            _state.value = SpeechState.Error("Failed to start listening")
        }
    }

    /**
     * Stop listening for speech input
     */
    fun stopListening() {
        if (!isListening) return
        
        try {
            speechRecognizer?.stopListening()
            isListening = false
            Log.d(TAG, "Stopped listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listening", e)
        }
    }

    /**
     * Cancel current speech recognition
     */
    fun cancel() {
        if (!isListening) return
        
        try {
            speechRecognizer?.cancel()
            isListening = false
            _state.value = SpeechState.Idle
            _partialResult.value = ""
            _finalResult.value = ""
            Log.d(TAG, "Cancelled listening")
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling listening", e)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SpeechState.ReadyForSpeech
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            _state.value = SpeechState.Speaking
            Log.d(TAG, "Beginning of speech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Audio level changed - could use for visual feedback
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            // Raw audio buffer - not used
        }

        override fun onEndOfSpeech() {
            _state.value = SpeechState.Processing
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            isListening = false
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match found"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input detected"
                else -> "Unknown speech recognition error"
            }
            
            Log.w(TAG, "Speech recognition error: $errorMessage (code: $error)")
            _state.value = SpeechState.Error(errorMessage)
            
            // Don't report "no match" or "timeout" as errors to user
            if (error != SpeechRecognizer.ERROR_NO_MATCH && 
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onErrorCallback?.invoke(errorMessage)
            } else {
                _state.value = SpeechState.Idle
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            _state.value = SpeechState.Completed
            
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null && matches.isNotEmpty()) {
                val result = matches[0]
                _finalResult.value = result
                _partialResult.value = ""
                Log.d(TAG, "Final result: $result")
                onResultCallback?.invoke(result)
            } else {
                Log.w(TAG, "No results returned")
                _state.value = SpeechState.Idle
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null && matches.isNotEmpty()) {
                val partial = matches[0]
                _partialResult.value = partial
                Log.d(TAG, "Partial result: $partial")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Additional events - not used
        }
    }

    /**
     * Check if speech recognition is available
     */
    fun isAvailable(): Boolean {
        return speechRecognizer != null
    }

    /**
     * Check if currently listening
     */
    fun isListening(): Boolean = isListening

    /**
     * Clean up resources
     */
    fun destroy() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "SpeechCapture destroyed")
    }

    // Speech recognition state
    sealed class SpeechState {
        object Idle : SpeechState()
        object Listening : SpeechState()
        object ReadyForSpeech : SpeechState()
        object Speaking : SpeechState()
        object Processing : SpeechState()
        object Completed : SpeechState()
        data class Error(val message: String) : SpeechState()
    }
}