package com.navis.pepscout.debug

import android.content.Context
import android.util.Log
import com.navis.pepscout.util.Events
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Debug logging system for safety events and telemetry
 * Shows recent events in debug UI and logs sessions to CSV
 */
class DebugLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "DebugLogger"
        private const val MAX_HAZARD_EVENTS = 20
        private const val MAX_FREE_SPACE_EVENTS = 10
        private const val MAX_WALL_EVENTS = 10
        private const val MAX_VOICE_EVENTS = 10
        
        private val CSV_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val SESSION_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Ring buffers for recent events
    private val recentHazardEvents = ConcurrentLinkedQueue<DebugHazardEvent>()
    private val recentFreeSpaceEvents = ConcurrentLinkedQueue<DebugFreeSpaceEvent>()
    private val recentWallEvents = ConcurrentLinkedQueue<DebugWallEvent>()
    private val recentVoiceEvents = ConcurrentLinkedQueue<DebugVoiceEvent>()
    
    // State flows for UI
    private val _hazardEvents = MutableStateFlow<List<DebugHazardEvent>>(emptyList())
    val hazardEvents: StateFlow<List<DebugHazardEvent>> = _hazardEvents.asStateFlow()
    
    private val _freeSpaceEvents = MutableStateFlow<List<DebugFreeSpaceEvent>>(emptyList())
    val freeSpaceEvents: StateFlow<List<DebugFreeSpaceEvent>> = _freeSpaceEvents.asStateFlow()
    
    private val _wallEvents = MutableStateFlow<List<DebugWallEvent>>(emptyList())
    val wallEvents: StateFlow<List<DebugWallEvent>> = _wallEvents.asStateFlow()
    
    private val _voiceEvents = MutableStateFlow<List<DebugVoiceEvent>>(emptyList())
    val voiceEvents: StateFlow<List<DebugVoiceEvent>> = _voiceEvents.asStateFlow()
    
    // Session logging
    private var sessionStartTime: Long = 0
    private var sessionFile: File? = null
    private var csvWriter: FileWriter? = null
    
    init {
        startEventSubscription()
        initializeSessionLogging()
    }
    
    private fun startEventSubscription() {
        // Subscribe to hazard events
        Events.hazardEvents()
            .onEach { event -> logHazardEvent(event) }
            .launchIn(scope)
        
        // Subscribe to free space events
        Events.freeSpaceEvents()
            .onEach { event -> logFreeSpaceEvent(event) }
            .launchIn(scope)
        
        // Subscribe to wall events
        Events.wallEvents()
            .onEach { event -> logWallEvent(event) }
            .launchIn(scope)
        
        // Subscribe to voice events
        Events.flowOf<Events.VoiceEvent>()
            .onEach { event -> logVoiceEvent(event) }
            .launchIn(scope)
        
        Log.d(TAG, "DebugLogger event subscriptions started")
    }
    
    private fun initializeSessionLogging() {
        sessionStartTime = System.currentTimeMillis()
        
        scope.launch {
            try {
                val logsDir = File(context.filesDir, "logs")
                if (!logsDir.exists()) {
                    logsDir.mkdirs()
                }
                
                val sessionTimestamp = SESSION_DATE_FORMAT.format(Date(sessionStartTime))
                sessionFile = File(logsDir, "session_${sessionTimestamp}.csv")
                
                csvWriter = FileWriter(sessionFile!!, true).apply {
                    // Write CSV header
                    write("timestamp,event_type,data\n")
                    flush()
                }
                
                Log.d(TAG, "Session logging initialized: ${sessionFile!!.absolutePath}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize session logging", e)
            }
        }
    }
    
    private fun logHazardEvent(event: Events.HazardEvent) {
        val debugEvent = DebugHazardEvent(
            timestamp = System.currentTimeMillis(),
            id = event.id,
            kind = event.kind,
            label = event.label,
            severity = event.severity,
            confidence = event.confidence
        )
        
        // Add to ring buffer
        addToRingBuffer(recentHazardEvents, debugEvent, MAX_HAZARD_EVENTS)
        _hazardEvents.value = recentHazardEvents.toList().sortedByDescending { it.timestamp }
        
        // Log to CSV
        logToCsv("hazard", 
            "id=${event.id},kind=${event.kind},label=${event.label}," +
            "severity=${event.severity},confidence=${event.confidence}"
        )
        
        Log.v(TAG, "Logged hazard: ${event.label} (${event.severity})")
    }
    
    private fun logFreeSpaceEvent(event: Events.FreeSpaceEvent) {
        val debugEvent = DebugFreeSpaceEvent(
            timestamp = System.currentTimeMillis(),
            angleDeg = event.angleDeg,
            confidence = event.confidence,
            binDistribution = event.binDistribution?.toList() ?: emptyList()
        )
        
        // Add to ring buffer
        addToRingBuffer(recentFreeSpaceEvents, debugEvent, MAX_FREE_SPACE_EVENTS)
        _freeSpaceEvents.value = recentFreeSpaceEvents.toList().sortedByDescending { it.timestamp }
        
        // Log to CSV
        logToCsv("free_space", 
            "angle=${event.angleDeg},confidence=${event.confidence}," +
            "bins=${event.binDistribution?.joinToString(";") ?: ""}"
        )
        
        Log.v(TAG, "Logged free space: ${event.angleDeg}° (${event.confidence})")
    }
    
    private fun logWallEvent(event: Events.WallEvent) {
        val debugEvent = DebugWallEvent(
            timestamp = System.currentTimeMillis(),
            detected = event.detected,
            edgeDensity = event.edgeDensity,
            foeConfidence = event.foeConfidence
        )
        
        // Add to ring buffer
        addToRingBuffer(recentWallEvents, debugEvent, MAX_WALL_EVENTS)
        _wallEvents.value = recentWallEvents.toList().sortedByDescending { it.timestamp }
        
        // Log to CSV
        logToCsv("wall", 
            "detected=${event.detected},edge_density=${event.edgeDensity}," +
            "foe_confidence=${event.foeConfidence}"
        )
        
        Log.v(TAG, "Logged wall: detected=${event.detected}, density=${event.edgeDensity}")
    }
    
    private fun logVoiceEvent(event: Events.VoiceEvent) {
        val debugEvent = DebugVoiceEvent(
            timestamp = System.currentTimeMillis(),
            action = event.action,
            text = event.text,
            priority = event.priority
        )
        
        // Add to ring buffer
        addToRingBuffer(recentVoiceEvents, debugEvent, MAX_VOICE_EVENTS)
        _voiceEvents.value = recentVoiceEvents.toList().sortedByDescending { it.timestamp }
        
        // Log to CSV
        logToCsv("voice", 
            "action=${event.action},priority=${event.priority}," +
            "text=${event.text.replace(",", ";")}") // Escape commas in text
        
        Log.v(TAG, "Logged voice: ${event.action} - ${event.text.take(30)}...")
    }
    
    private fun <T> addToRingBuffer(buffer: ConcurrentLinkedQueue<T>, item: T, maxSize: Int) {
        buffer.offer(item)
        while (buffer.size > maxSize) {
            buffer.poll()
        }
    }
    
    private fun logToCsv(eventType: String, data: String) {
        scope.launch {
            try {
                csvWriter?.let { writer ->
                    val timestamp = CSV_DATE_FORMAT.format(Date())
                    writer.write("$timestamp,$eventType,\"$data\"\n")
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write to CSV", e)
            }
        }
    }
    
    /**
     * Get session duration in milliseconds
     */
    fun getSessionDuration(): Long {
        return System.currentTimeMillis() - sessionStartTime
    }
    
    /**
     * Get session file path for sharing/debugging
     */
    fun getSessionFilePath(): String? = sessionFile?.absolutePath
    
    /**
     * Clear all debug event buffers
     */
    fun clearEventBuffers() {
        recentHazardEvents.clear()
        recentFreeSpaceEvents.clear()
        recentWallEvents.clear()
        recentVoiceEvents.clear()
        
        _hazardEvents.value = emptyList()
        _freeSpaceEvents.value = emptyList()
        _wallEvents.value = emptyList()
        _voiceEvents.value = emptyList()
        
        Log.d(TAG, "Debug event buffers cleared")
    }
    
    /**
     * Get summary statistics for current session
     */
    fun getSessionStats(): SessionStats {
        return SessionStats(
            sessionDuration = getSessionDuration(),
            totalHazardEvents = recentHazardEvents.size,
            totalFreeSpaceEvents = recentFreeSpaceEvents.size,
            totalWallEvents = recentWallEvents.size,
            totalVoiceEvents = recentVoiceEvents.size,
            sessionFilePath = sessionFile?.absolutePath
        )
    }
    
    /**
     * Clean up old log files (keep last 10 sessions)
     */
    fun cleanupOldLogs() {
        scope.launch {
            try {
                val logsDir = File(context.filesDir, "logs")
                if (!logsDir.exists()) return@launch
                
                val logFiles = logsDir.listFiles { file ->
                    file.name.startsWith("session_") && file.name.endsWith(".csv")
                }?.sortedByDescending { it.lastModified() }
                
                logFiles?.drop(10)?.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old log file: ${file.name}")
                    }
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup old logs", e)
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
        
        scope.launch {
            try {
                csvWriter?.close()
                csvWriter = null
                Log.d(TAG, "Session logging closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing CSV writer", e)
            }
        }
        
        Log.d(TAG, "DebugLogger destroyed")
    }
    
    // Data classes for debug events
    data class DebugHazardEvent(
        val timestamp: Long,
        val id: String,
        val kind: String,
        val label: String,
        val severity: String,
        val confidence: Float
    )
    
    data class DebugFreeSpaceEvent(
        val timestamp: Long,
        val angleDeg: Double,
        val confidence: Float,
        val binDistribution: List<Float>
    )
    
    data class DebugWallEvent(
        val timestamp: Long,
        val detected: Boolean,
        val edgeDensity: Float,
        val foeConfidence: Float
    )
    
    data class DebugVoiceEvent(
        val timestamp: Long,
        val action: String,
        val text: String,
        val priority: String
    )
    
    data class SessionStats(
        val sessionDuration: Long,
        val totalHazardEvents: Int,
        val totalFreeSpaceEvents: Int,
        val totalWallEvents: Int,
        val totalVoiceEvents: Int,
        val sessionFilePath: String?
    )
}