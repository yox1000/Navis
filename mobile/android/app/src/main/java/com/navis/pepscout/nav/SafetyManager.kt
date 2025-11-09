package com.navis.pepscout.nav

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.navis.pepscout.data.PrefsStore
import com.navis.pepscout.util.Events
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Safety manager that implements fusion logic for hazard detection and avoidance
 * Subscribes to hazard, free_space, wall events and provides safety guidance
 */
class SafetyManager(
    private val context: Context,
    private val prefsStore: PrefsStore
) {
    
    companion object {
        private const val TAG = "SafetyManager"
        private val VIBRATION_PATTERN = longArrayOf(80, 60, 80) // Safety vibration
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    
    // State tracking
    private var currentHeading: Double = 0.0
    private var currentLocation: Pair<Double, Double>? = null
    private var currentSpeed: Double = 0.0
    private var lastLocationTime: Long = 0
    
    private var blockedObstacles = mutableMapOf<String, ObstacleTracker>()
    private var isBlocked = false
    private var lastSafetyVoiceTime = 0L
    private var wallDeadmanUntil = 0L
    private var lastFreeSpaceAngle: Double? = null
    private var persistentBlockStartTime: Long? = null
    
    // State flows for UI
    private val _safetyState = MutableStateFlow(SafetyState.Clear)
    val safetyState: StateFlow<SafetyState> = _safetyState.asStateFlow()
    
    private val _safetyBanner = MutableStateFlow<SafetyBanner?>(null)
    val safetyBanner: StateFlow<SafetyBanner?> = _safetyBanner.asStateFlow()
    
    // Safety configuration - loaded from prefs
    private var coneDegrees = 20f
    private var minBoxAreaRatio = 0.04f
    private var minBlockMs = 300
    private var cooldownMs = 2000
    private var wallTriggerFrames = 10
    
    init {
        startEventSubscription()
        loadConfiguration()
    }
    
    private fun startEventSubscription() {
        // Subscribe to hazard events
        Events.hazardEvents()
            .onEach { handleHazardEvent(it) }
            .launchIn(scope)
        
        // Subscribe to location events  
        Events.locationEvents()
            .onEach { handleLocationEvent(it) }
            .launchIn(scope)
        
        // Subscribe to heading events
        Events.headingEvents()
            .onEach { handleHeadingEvent(it) }
            .launchIn(scope)
        
        // Subscribe to free space events
        Events.freeSpaceEvents()
            .onEach { handleFreeSpaceEvent(it) }
            .launchIn(scope)
        
        // Subscribe to wall events
        Events.wallEvents()
            .onEach { handleWallEvent(it) }
            .launchIn(scope)
        
        Log.d(TAG, "SafetyManager event subscriptions started")
    }
    
    private fun loadConfiguration() {
        scope.launch {
            combine(
                prefsStore.safetyConeDegrees,
                prefsStore.safetyMinBoxAreaRatio,
                prefsStore.safetyMinBlockMs,
                prefsStore.safetyCooldownMs,
                prefsStore.safetyWallTriggerFrames
            ) { cone, boxRatio, blockMs, cooldown, wallFrames ->
                SafetyConfig(cone, boxRatio, blockMs, cooldown, wallFrames)
            }.collect { config ->
                coneDegrees = config.coneDegrees
                minBoxAreaRatio = config.minBoxAreaRatio
                minBlockMs = config.minBlockMs
                cooldownMs = config.cooldownMs
                wallTriggerFrames = config.wallTriggerFrames
                
                Log.d(TAG, "Safety config loaded: cone=${coneDegrees}°, cooldown=${cooldownMs}ms")
            }
        }
    }
    
    private fun handleHazardEvent(event: Events.HazardEvent) {
        // Calculate if obstacle is in the heading cone
        val obstacleInCone = isObstacleInHeadingCone(event)
        
        if (obstacleInCone && isSignificantObstacle(event)) {
            val tracker = blockedObstacles.getOrPut(event.id) {
                ObstacleTracker(
                    id = event.id,
                    firstSeen = System.currentTimeMillis(),
                    label = event.label,
                    severity = event.severity
                )
            }
            tracker.lastSeen = System.currentTimeMillis()
            
            // Check if obstacle has been blocking long enough
            val blockDuration = tracker.lastSeen - tracker.firstSeen
            if (blockDuration >= minBlockMs && !isBlocked) {
                markBlocked(event)
            }
        } else {
            // Remove from tracking if not in cone or not significant
            blockedObstacles.remove(event.id)
        }
        
        // Clean up expired obstacles
        cleanupExpiredObstacles()
        
        // Update blocked state
        updateBlockedState()
    }
    
    private fun isObstacleInHeadingCone(event: Events.HazardEvent): Boolean {
        // For now, assume obstacles are in cone if they're "in front"
        // In a real implementation, you'd use relative bearing calculation
        return event.kind in listOf("obstacle", "moving_object") &&
               event.label in listOf("person", "chair", "bike", "unknown")
    }
    
    private fun isSignificantObstacle(event: Events.HazardEvent): Boolean {
        // Check if the obstacle is large enough to be a navigation concern
        // This is simplified - in reality you'd check bounding box area
        return event.severity in listOf("warn", "danger")
    }
    
    private fun handleLocationEvent(event: Events.LocationEvent) {
        val currentTime = System.currentTimeMillis()
        val lastLocation = currentLocation
        
        if (lastLocation != null && lastLocationTime > 0) {
            val timeDelta = (currentTime - lastLocationTime) / 1000.0 // seconds
            if (timeDelta > 0) {
                val distance = calculateDistance(
                    lastLocation.first, lastLocation.second,
                    event.lat, event.lon
                )
                currentSpeed = distance / timeDelta // m/s
            }
        }
        
        currentLocation = Pair(event.lat, event.lon)
        lastLocationTime = currentTime
    }
    
    private fun handleHeadingEvent(event: Events.HeadingEvent) {
        currentHeading = event.azimuthDeg
    }
    
    private fun handleFreeSpaceEvent(event: Events.FreeSpaceEvent) {
        lastFreeSpaceAngle = event.angleDeg
        
        // If we're blocked, provide directional guidance
        if (isBlocked && canSpeak()) {
            provideSideGuidance(event.angleDeg)
        }
    }
    
    private fun handleWallEvent(event: Events.WallEvent) {
        if (currentSpeed > 0.5 && canSpeak()) { // Moving faster than 0.5 m/s
            speakSafety("Stop. Wall ahead.", SafetyState.Wall)
            setWallDeadman(2000) // 2 second deadman
            vibrate()
        }
        
        updateSafetyBanner(SafetyBanner(
            message = "Wall",
            type = SafetyBanner.Type.DANGER,
            autoHide = false // Don't auto-hide wall warnings
        ))
    }
    
    private fun markBlocked(event: Events.HazardEvent) {
        isBlocked = true
        persistentBlockStartTime = System.currentTimeMillis()
        
        // Emit initial obstacle warning
        if (canSpeak()) {
            speakSafety("Careful. Obstacle ahead.", SafetyState.Obstacle)
            vibrate()
        }
        
        updateSafetyBanner(SafetyBanner(
            message = "Obstacle",
            type = SafetyBanner.Type.WARNING,
            autoHide = true
        ))
        
        Log.d(TAG, "Marked as blocked by ${event.label}")
    }
    
    private fun provideSideGuidance(angleDeg: Double) {
        val message = when {
            angleDeg >= 12.0 -> "Small right. Take the open side."
            angleDeg <= -12.0 -> "Small left. Take the open side."
            else -> "Path closed. Please wait."
        }
        
        val bannerType = when {
            angleDeg >= 12.0 -> SafetyBanner.Type.CLEAR_RIGHT
            angleDeg <= -12.0 -> SafetyBanner.Type.CLEAR_LEFT
            else -> SafetyBanner.Type.BLOCKED
        }
        
        speakSafety(message, SafetyState.Guidance)
        updateSafetyBanner(SafetyBanner(
            message = when (bannerType) {
                SafetyBanner.Type.CLEAR_RIGHT -> "Clear-right"
                SafetyBanner.Type.CLEAR_LEFT -> "Clear-left"
                else -> "Closed"
            },
            type = bannerType,
            autoHide = true
        ))
    }
    
    private fun updateBlockedState() {
        val currentTime = System.currentTimeMillis()
        val hasActiveObstacles = blockedObstacles.values.any { 
            currentTime - it.lastSeen < 1000 // 1 second grace period
        }
        
        if (!hasActiveObstacles && isBlocked) {
            // Clear blocked state
            isBlocked = false
            persistentBlockStartTime = null
            _safetyState.value = SafetyState.Clear
            clearSafetyBanner()
            Log.d(TAG, "Cleared blocked state")
            
        } else if (isBlocked && persistentBlockStartTime != null) {
            // Check for persistent block (indoor corridor guard)
            val blockDuration = currentTime - persistentBlockStartTime!!
            if (blockDuration > 2000 && canSpeak()) { // 2 seconds
                handlePersistentBlock()
            }
        }
    }
    
    private fun handlePersistentBlock() {
        if (canSpeak()) {
            speakSafety("Path closed. Please wait.", SafetyState.PersistentBlock)
            
            // TODO: Compute alternate route if indoor
            // For now, just provide generic guidance
            scope.launch {
                delay(2000)
                if (isBlocked && canSpeak()) {
                    speakSafety("Please step aside or wait for path to clear.", SafetyState.Guidance)
                }
            }
        }
    }
    
    private fun cleanupExpiredObstacles() {
        val currentTime = System.currentTimeMillis()
        val iterator = blockedObstacles.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val tracker = entry.value
            
            // Remove obstacles not seen for 3 seconds
            if (currentTime - tracker.lastSeen > 3000) {
                iterator.remove()
                Log.d(TAG, "Removed expired obstacle: ${tracker.id}")
            }
        }
    }
    
    private fun canSpeak(): Boolean {
        val currentTime = System.currentTimeMillis()
        val inDeadman = currentTime < wallDeadmanUntil
        val cooldownPassed = currentTime - lastSafetyVoiceTime >= cooldownMs
        
        return !inDeadman && cooldownPassed
    }
    
    private fun speakSafety(message: String, state: SafetyState) {
        lastSafetyVoiceTime = System.currentTimeMillis()
        _safetyState.value = state
        
        // Emit high-priority voice event
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = message,
            priority = "urgent"
        ))
        
        Log.d(TAG, "Safety voice: $message")
    }
    
    private fun setWallDeadman(durationMs: Long) {
        wallDeadmanUntil = System.currentTimeMillis() + durationMs
        Log.d(TAG, "Wall deadman set for ${durationMs}ms")
    }
    
    private fun vibrate() {
        if (vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATION_PATTERN, -1)
            }
        }
    }
    
    private fun updateSafetyBanner(banner: SafetyBanner) {
        _safetyBanner.value = banner
        
        // Auto-hide after 2 seconds if configured
        if (banner.autoHide) {
            scope.launch {
                delay(2000)
                if (_safetyBanner.value == banner) {
                    clearSafetyBanner()
                }
            }
        }
    }
    
    private fun clearSafetyBanner() {
        _safetyBanner.value = null
    }
    
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Simplified distance calculation for small distances
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
        return 6371000 * c // Earth radius in meters
    }
    
    /**
     * Check if navigation should be paused due to safety concerns
     */
    fun shouldPauseNavigation(): Boolean {
        return isBlocked || System.currentTimeMillis() < wallDeadmanUntil
    }
    
    /**
     * Get current safety guidance for manual navigation
     */
    fun getCurrentGuidance(): String? {
        if (!isBlocked) return null
        
        return lastFreeSpaceAngle?.let { angle ->
            when {
                angle >= 12.0 -> "Clear on the right"
                angle <= -12.0 -> "Clear on the left"
                else -> "Path blocked"
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
        Log.d(TAG, "SafetyManager destroyed")
    }
    
    // Data classes
    data class ObstacleTracker(
        val id: String,
        val firstSeen: Long,
        var lastSeen: Long,
        val label: String,
        val severity: String
    )
    
    data class SafetyConfig(
        val coneDegrees: Float,
        val minBoxAreaRatio: Float,
        val minBlockMs: Int,
        val cooldownMs: Int,
        val wallTriggerFrames: Int
    )
    
    enum class SafetyState {
        Clear,
        Obstacle,
        Wall,
        Guidance,
        PersistentBlock
    }
    
    data class SafetyBanner(
        val message: String,
        val type: Type,
        val autoHide: Boolean
    ) {
        enum class Type {
            OBSTACLE,
            WALL,
            CLEAR_LEFT,
            CLEAR_RIGHT,
            BLOCKED,
            WARNING,
            DANGER
        }
    }
}