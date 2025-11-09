package com.navis.pepscout.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.*

/**
 * Speech-to-text with Whisper on-device and system fallback
 * Provides unified interface for voice input
 */
class WhisperSTT(private val context: Context) {
    
    companion object {
        private const val TAG = "WhisperSTT"
        private const val WHISPER_MODEL_PATH = "models/whisper-tiny.tflite"
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // State management
    private val _state = MutableStateFlow(STTState.Idle)
    val state: StateFlow<STTState> = _state.asStateFlow()
    
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()
    
    // STT engines
    private var whisperEngine: WhisperEngine? = null
    private var systemSTT: SpeechRecognizer? = null
    private var useWhisper = false
    
    // Result channel
    private var resultChannel: Channel<STTResult>? = null
    
    init {
        initializeEngines()
    }
    
    /**
     * Initialize both Whisper and system STT engines
     */
    private fun initializeEngines() {
        scope.launch {
            try {
                // Try to initialize Whisper first
                whisperEngine = WhisperEngine(context)
                if (whisperEngine?.initialize() == true) {
                    useWhisper = true
                    Log.d(TAG, "Whisper engine initialized successfully")
                } else {
                    Log.d(TAG, "Whisper not available, using system STT")
                    useWhisper = false
                }
                
                // Always initialize system STT as fallback
                initializeSystemSTT()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing STT engines", e)
                useWhisper = false
                initializeSystemSTT()
            }
        }
    }
    
    /**
     * Initialize system speech recognizer
     */
    private fun initializeSystemSTT() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                systemSTT = SpeechRecognizer.createSpeechRecognizer(context)
                Log.d(TAG, "System STT initialized")
            } else {
                Log.w(TAG, "System STT not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing system STT", e)
        }
    }
    
    /**
     * Start listening for speech input
     */
    suspend fun startListening(): STTResult = withContext(Dispatchers.Main) {
        if (_isListening.value) {
            return@withContext STTResult.Error("Already listening")
        }
        
        _isListening.value = true
        _state.value = STTState.Listening
        
        resultChannel = Channel(1)
        
        try {
            if (useWhisper && whisperEngine != null) {
                Log.d(TAG, "Starting Whisper listening")
                whisperEngine!!.startListening { result ->
                    scope.launch {
                        resultChannel?.send(result)
                    }
                }
            } else if (systemSTT != null) {
                Log.d(TAG, "Starting system STT listening")
                startSystemSTTListening()
            } else {
                _isListening.value = false
                _state.value = STTState.Error("No STT engine available")
                return@withContext STTResult.Error("No STT engine available")
            }
            
            // Wait for result
            val result = resultChannel!!.receive()
            
            _isListening.value = false
            _state.value = when (result) {
                is STTResult.Success -> STTState.Completed
                is STTResult.Error -> STTState.Error(result.message)
            }
            
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during STT listening", e)
            _isListening.value = false
            _state.value = STTState.Error(e.message ?: "Unknown error")
            return@withContext STTResult.Error(e.message ?: "Unknown error")
        } finally {
            resultChannel?.close()
            resultChannel = null
        }
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        if (!_isListening.value) return
        
        try {
            if (useWhisper) {
                whisperEngine?.stopListening()
            } else {
                systemSTT?.stopListening()
            }
            
            _isListening.value = false
            _state.value = STTState.Idle
            
            Log.d(TAG, "Stopped listening")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping STT", e)
        }
    }
    
    /**
     * Start system STT with recognition listener
     */
    private fun startSystemSTTListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        systemSTT?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "System STT ready for speech")
                _state.value = STTState.Listening
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "System STT detected speech beginning")
                _state.value = STTState.Processing
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // Audio level feedback could be added here
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // Audio buffer received
            }
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "System STT detected speech end")
                _state.value = STTState.Processing
            }
            
            override fun onError(error: Int) {
                val errorMessage = getErrorMessage(error)
                Log.e(TAG, "System STT error: $errorMessage")
                
                scope.launch {
                    resultChannel?.send(STTResult.Error(errorMessage))
                }
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val transcript = matches?.firstOrNull()
                
                if (transcript.isNullOrBlank()) {
                    scope.launch {
                        resultChannel?.send(STTResult.Error("No speech recognized"))
                    }
                } else {
                    Log.d(TAG, "System STT result: $transcript")
                    scope.launch {
                        resultChannel?.send(STTResult.Success(transcript, 1.0f))
                    }
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    Log.d(TAG, "System STT partial: $partial")
                    _state.value = STTState.Processing
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                // Additional events
            }
        })
        
        systemSTT?.startListening(intent)
    }
    
    /**
     * Get human-readable error message from system STT error code
     */
    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error: $error"
        }
    }
    
    /**
     * Check if STT is available
     */
    fun isAvailable(): Boolean {
        return (useWhisper && whisperEngine != null) || systemSTT != null
    }
    
    /**
     * Get current STT engine type
     */
    fun getCurrentEngine(): String {
        return if (useWhisper) "Whisper" else "System"
    }
    
    /**
     * Force use of specific engine
     */
    fun setEngine(useWhisperEngine: Boolean) {
        if (useWhisperEngine && whisperEngine == null) {
            Log.w(TAG, "Whisper not available, staying with system STT")
            return
        }
        
        if (!useWhisperEngine && systemSTT == null) {
            Log.w(TAG, "System STT not available, staying with Whisper")
            return
        }
        
        useWhisper = useWhisperEngine
        Log.d(TAG, "Switched to ${if (useWhisper) "Whisper" else "System"} STT")
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopListening()
        scope.cancel()
        
        whisperEngine?.cleanup()
        systemSTT?.destroy()
        
        whisperEngine = null
        systemSTT = null
        
        Log.d(TAG, "WhisperSTT cleaned up")
    }
}

/**
 * Stub Whisper engine - replace with actual implementation when available
 */
private class WhisperEngine(private val context: Context) {
    
    companion object {
        private const val TAG = "WhisperEngine"
    }
    
    private var isInitialized = false
    private var callback: ((STTResult) -> Unit)? = null
    
    /**
     * Initialize Whisper model
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // TODO: Initialize actual Whisper model
            // For now, return false to use system STT
            Log.d(TAG, "Whisper initialization stubbed - using system STT fallback")
            isInitialized = false
            false
            
        } catch (e: Exception) {
            Log.e(TAG, "Whisper initialization failed", e)
            false
        }
    }
    
    /**
     * Start listening with Whisper
     */
    fun startListening(callback: (STTResult) -> Unit) {
        this.callback = callback
        
        // TODO: Implement actual Whisper listening
        // For now, immediately callback with error to trigger fallback
        callback(STTResult.Error("Whisper not implemented"))
    }
    
    /**
     * Stop Whisper listening
     */
    fun stopListening() {
        callback = null
    }
    
    /**
     * Clean up Whisper resources
     */
    fun cleanup() {
        isInitialized = false
        callback = null
    }
}

/**
 * STT state enumeration
 */
sealed class STTState {
    object Idle : STTState()
    object Listening : STTState()
    object Processing : STTState()
    object Completed : STTState()
    data class Error(val message: String) : STTState()
}

/**
 * STT result sealed class
 */
sealed class STTResult {
    data class Success(
        val transcript: String,
        val confidence: Float
    ) : STTResult()
    
    data class Error(
        val message: String
    ) : STTResult()
}