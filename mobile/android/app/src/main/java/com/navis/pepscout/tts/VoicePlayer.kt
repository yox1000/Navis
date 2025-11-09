package com.navis.pepscout.tts

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.navis.pepscout.data.Cache
import com.navis.pepscout.data.Keystore
import com.navis.pepscout.data.PrefsStore
import com.navis.pepscout.net.ElevenLabsClient
import com.navis.pepscout.util.Events
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Voice player that handles TTS audio playback with queueing and preloading
 * Uses ExoPlayer for reliable audio playback
 */
class VoicePlayer(
    private val context: Context,
    private val elevenLabsClient: ElevenLabsClient,
    private val prefsStore: PrefsStore,
    private val cache: Cache
) {
    
    companion object {
        private const val TAG = "VoicePlayer"
        private const val MAX_QUEUE_SIZE = 5
        private const val PRELOAD_COUNT = 2
    }
    
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Playback state
    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    
    // Queue management
    private val playQueue = ConcurrentLinkedQueue<VoiceItem>()
    private val preloadQueue = ConcurrentLinkedQueue<String>()
    private var isProcessingQueue = false
    
    init {
        initializePlayer()
        startEventSubscription()
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(playerListener)
        }
        Log.d(TAG, "ExoPlayer initialized")
    }

    private fun startEventSubscription() {
        // Listen for voice events from safety system and other components
        scope.launch {
            Events.flowOf<Events.VoiceEvent>().collect { event ->
                when (event.action) {
                    "speak" -> {
                        val priority = when (event.priority) {
                            "urgent" -> Priority.Urgent
                            "low" -> Priority.Low
                            else -> Priority.Normal
                        }
                        speak(event.text, priority)
                    }
                }
            }
        }
        Log.d(TAG, "Voice event subscription started")
    }

    /**
     * Play text using TTS, with optional queueing
     */
    fun speak(
        text: String, 
        priority: Priority = Priority.Normal,
        onComplete: (() -> Unit)? = null
    ) {
        if (text.isBlank()) return
        
        Log.d(TAG, "Speaking: ${text.take(50)}...")
        
        val voiceItem = VoiceItem(
            text = text.trim(),
            priority = priority,
            onComplete = onComplete
        )
        
        when (priority) {
            Priority.Urgent -> {
                // Stop current playback and play immediately
                clearQueue()
                exoPlayer?.stop()
                playQueue.offer(voiceItem)
            }
            Priority.Normal -> {
                // Add to queue normally
                if (playQueue.size < MAX_QUEUE_SIZE) {
                    playQueue.offer(voiceItem)
                } else {
                    Log.w(TAG, "Queue full, dropping voice item")
                }
            }
            Priority.Low -> {
                // Only play if queue is empty
                if (playQueue.isEmpty() && _state.value == PlaybackState.Idle) {
                    playQueue.offer(voiceItem)
                }
            }
        }
        
        if (!isProcessingQueue) {
            processQueue()
        }
    }

    /**
     * Preload TTS audio for upcoming text (e.g., next navigation steps)
     */
    fun preload(texts: List<String>) {
        preloadQueue.clear()
        texts.take(PRELOAD_COUNT).forEach { text ->
            if (text.isNotBlank()) {
                preloadQueue.offer(text.trim())
            }
        }
        
        scope.launch {
            processPreloadQueue()
        }
    }

    private fun processQueue() {
        if (isProcessingQueue) return
        isProcessingQueue = true
        
        scope.launch {
            while (playQueue.isNotEmpty()) {
                val item = playQueue.poll() ?: break
                
                try {
                    _state.value = PlaybackState.Loading
                    
                    // Get voice settings
                    val voiceId = prefsStore.voiceId.first()
                    
                    // Get or generate TTS audio
                    val audioFile = elevenLabsClient.textToSpeech(item.text, voiceId)
                    
                    if (audioFile != null) {
                        // Play the audio
                        playAudioFile(audioFile, item)
                        
                        // Wait for playback to complete
                        while (_state.value == PlaybackState.Playing || 
                               _state.value == PlaybackState.Loading) {
                            delay(100)
                        }
                        
                        item.onComplete?.invoke()
                        
                        // Emit completion event
                        Events.emit(Events.VoiceEvent(
                            action = "completed",
                            text = item.text,
                            priority = item.priority.name.lowercase()
                        ))
                        
                    } else {
                        Log.e(TAG, "Failed to get TTS audio for: ${item.text}")
                        _state.value = PlaybackState.Error("TTS generation failed")
                        item.onComplete?.invoke()
                        
                        // Emit error event  
                        Events.emit(Events.VoiceEvent(
                            action = "error",
                            text = item.text,
                            priority = item.priority.name.lowercase()
                        ))
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing voice item", e)
                    _state.value = PlaybackState.Error(e.message ?: "Playback error")
                    item.onComplete?.invoke()
                }
                
                // Small delay between items
                delay(200)
            }
            
            _state.value = PlaybackState.Idle
            isProcessingQueue = false
        }
    }

    private suspend fun processPreloadQueue() {
        while (preloadQueue.isNotEmpty()) {
            val text = preloadQueue.poll() ?: break
            
            try {
                val voiceId = prefsStore.voiceId.first()
                
                // Check if already cached
                if (cache.getCachedTtsFile(text) == null) {
                    // Generate and cache TTS
                    Log.d(TAG, "Preloading: ${text.take(30)}...")
                    elevenLabsClient.textToSpeech(text, voiceId, useCache = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload: $text", e)
            }
        }
    }

    private fun playAudioFile(filePath: String, item: VoiceItem) {
        try {
            val mediaItem = MediaItem.fromUri("file://$filePath")
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
            
            _state.value = PlaybackState.Playing
            Log.d(TAG, "Playing audio: ${item.text.take(30)}...")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio file: $filePath", e)
            _state.value = PlaybackState.Error("Audio playback failed")
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    _state.value = PlaybackState.Completed
                    Log.d(TAG, "Playback completed")
                }
                Player.STATE_READY -> {
                    if (exoPlayer?.isPlaying == true) {
                        _state.value = PlaybackState.Playing
                    }
                }
                Player.STATE_BUFFERING -> {
                    _state.value = PlaybackState.Loading
                }
                Player.STATE_IDLE -> {
                    // Player is idle
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayer error", error)
            _state.value = PlaybackState.Error("Playback error: ${error.message}")
        }
    }

    /**
     * Stop current playback
     */
    fun stop() {
        exoPlayer?.stop()
        _state.value = PlaybackState.Idle
        Log.d(TAG, "Playback stopped")
    }

    /**
     * Pause current playback
     */
    fun pause() {
        exoPlayer?.pause()
        if (_state.value == PlaybackState.Playing) {
            _state.value = PlaybackState.Paused
        }
        Log.d(TAG, "Playback paused")
    }

    /**
     * Resume playback
     */
    fun resume() {
        if (_state.value == PlaybackState.Paused) {
            exoPlayer?.play()
            _state.value = PlaybackState.Playing
            Log.d(TAG, "Playback resumed")
        }
    }

    /**
     * Clear the voice queue
     */
    fun clearQueue() {
        playQueue.clear()
        preloadQueue.clear()
        Log.d(TAG, "Voice queue cleared")
    }

    /**
     * Check if currently playing
     */
    fun isPlaying(): Boolean {
        return _state.value == PlaybackState.Playing
    }

    /**
     * Get queue size
     */
    fun getQueueSize(): Int = playQueue.size

    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
        exoPlayer?.release()
        exoPlayer = null
        clearQueue()
        Log.d(TAG, "VoicePlayer destroyed")
    }

    // Data classes
    data class VoiceItem(
        val text: String,
        val priority: Priority,
        val onComplete: (() -> Unit)? = null
    )

    enum class Priority {
        Low,    // Only play if queue empty
        Normal, // Add to queue
        Urgent  // Stop current and play immediately
    }

    sealed class PlaybackState {
        object Idle : PlaybackState()
        object Loading : PlaybackState()
        object Playing : PlaybackState()
        object Paused : PlaybackState()
        object Completed : PlaybackState()
        data class Error(val message: String) : PlaybackState()
    }
}