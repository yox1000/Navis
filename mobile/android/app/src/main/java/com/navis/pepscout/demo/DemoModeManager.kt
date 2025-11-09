package com.navis.pepscout.demo

import android.content.Context
import android.util.Log
import com.navis.pepscout.data.PrefsStore
import com.navis.pepscout.intent.IntentToTargetPipeline
import com.navis.pepscout.intent.IntentResult
import com.navis.pepscout.mappedin.MappedinLocator
import com.navis.pepscout.mappedin.MappedinRouter
import com.navis.pepscout.mappedin.QrAnchor
import com.navis.pepscout.nav.SafetyFusionManager
import com.navis.pepscout.stt.WhisperSTT
import com.navis.pepscout.util.Events
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Demo mode manager for running full demo scenarios
 * Chains: Voice intent → Indoor guidance → Safety → Elevator QR → Ask
 */
class DemoModeManager(
    private val context: Context,
    private val prefsStore: PrefsStore,
    private val intentPipeline: IntentToTargetPipeline,
    private val mappedinRouter: MappedinRouter,
    private val mappedinLocator: MappedinLocator,
    private val safetyFusionManager: SafetyFusionManager,
    private val whisperSTT: WhisperSTT
) {
    
    companion object {
        private const val TAG = "DemoModeManager"
        private const val DEMO_TARGET_DURATION = 90000L // 90 seconds
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Demo state
    private val _isDemoMode = MutableStateFlow(false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()
    
    private val _demoState = MutableStateFlow(DemoState.Idle)
    val demoState: StateFlow<DemoState> = _demoState.asStateFlow()
    
    private val _demoProgress = MutableStateFlow(0f)
    val demoProgress: StateFlow<Float> = _demoProgress.asStateFlow()
    
    // Demo QR anchors
    private val demoQrAnchors = mapOf(
        "LIB:ENTR" to QrAnchor(
            nodeId = "entrance",
            mapId = "map-floor-1",
            x = 10.0,
            y = 10.0,
            name = "Library Entrance"
        ),
        "LIB:ELEV" to QrAnchor(
            nodeId = "elevator-f1",
            mapId = "map-floor-1",
            x = 25.0,
            y = 20.0,
            name = "Elevator Floor 1"
        ),
        "LIB:DEST" to QrAnchor(
            nodeId = "reading-room",
            mapId = "map-floor-2",
            x = 30.0,
            y = 25.0,
            name = "Reading Room"
        )
    )
    
    init {
        // Load demo mode preference
        scope.launch {
            prefsStore.demoMode.collect { enabled ->
                _isDemoMode.value = enabled
            }
        }
    }
    
    /**
     * Toggle demo mode on/off
     */
    fun toggleDemoMode() {
        scope.launch {
            val newValue = !_isDemoMode.value
            prefsStore.setDemoMode(newValue)
            Log.d(TAG, "Demo mode ${if (newValue) "enabled" else "disabled"}")
        }
    }
    
    /**
     * Run the full demo script
     * Chains through all major app features in ~90 seconds
     */
    fun runFullDemo() {
        if (demoState.value != DemoState.Idle) {
            Log.w(TAG, "Demo already running")
            return
        }
        
        scope.launch {
            try {
                _demoState.value = DemoState.Starting
                _demoProgress.value = 0f
                
                Log.d(TAG, "Starting full demo script")
                
                // Initialize router
                mappedinRouter.initialize()
                
                // Step 1: Voice intent (15s)
                _demoState.value = DemoState.VoiceIntent
                runVoiceIntentDemo()
                updateProgress(15f)
                
                // Step 2: Indoor guidance (30s)
                _demoState.value = DemoState.IndoorGuidance
                runIndoorGuidanceDemo()
                updateProgress(45f)
                
                // Step 3: Safety demo (20s)
                _demoState.value = DemoState.SafetyDemo
                runSafetyDemo()
                updateProgress(65f)
                
                // Step 4: Elevator QR handoff (15s)
                _demoState.value = DemoState.ElevatorQR
                runElevatorQRDemo()
                updateProgress(80f)
                
                // Step 5: Ask demo (10s)
                _demoState.value = DemoState.AskDemo
                runAskDemo()
                updateProgress(100f)
                
                // Complete
                _demoState.value = DemoState.Completed
                
                Log.d(TAG, "Full demo completed successfully")
                
                // Auto-reset after 5 seconds
                delay(5000)
                _demoState.value = DemoState.Idle
                _demoProgress.value = 0f
                
            } catch (e: Exception) {
                Log.e(TAG, "Demo failed", e)
                _demoState.value = DemoState.Error("Demo failed: ${e.message}")
                
                delay(3000)
                _demoState.value = DemoState.Idle
                _demoProgress.value = 0f
            }
        }
    }
    
    /**
     * Step 1: Voice intent - "Take me to the reading room"
     */
    private suspend fun runVoiceIntentDemo() {
        Log.d(TAG, "Demo: Starting voice intent")
        
        // Simulate voice recognition
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Demo mode: Processing voice command",
            priority = "normal"
        ))
        
        delay(2000)
        
        // Process intent
        val result = intentPipeline.processTranscript("Take me to the reading room")
        
        when (result) {
            is IntentResult.NavigationTarget -> {
                Events.emit(Events.VoiceEvent(
                    action = "speak",
                    text = "Navigating to ${result.place.name}",
                    priority = "normal"
                ))
                Log.d(TAG, "Demo: Intent resolved to ${result.place.name}")
            }
            else -> {
                Events.emit(Events.VoiceEvent(
                    action = "speak",
                    text = "Demo: Navigation target found",
                    priority = "normal"
                ))
            }
        }
        
        delay(3000)
    }
    
    /**
     * Step 2: Indoor guidance with Mappedin
     */
    private suspend fun runIndoorGuidanceDemo() {
        Log.d(TAG, "Demo: Starting indoor guidance")
        
        // Set position at entrance
        mappedinLocator.updateFromQrCode("LIB:ENTR", demoQrAnchors)
        
        // Calculate route to reading room
        val route = mappedinRouter.route("entrance", "reading-room")
        
        if (route != null) {
            safetyFusionManager.startNavigation(route)
            
            Events.emit(Events.VoiceEvent(
                action = "speak",
                text = "Starting indoor navigation. ${route.steps.firstOrNull()?.description ?: "Head toward the elevator"}",
                priority = "normal"
            ))
            
            delay(5000)
            
            // Simulate walking progress
            repeat(5) {
                delay(1000)
                // Could advance steps here
            }
            
            Events.emit(Events.VoiceEvent(
                action = "speak",
                text = "Approaching elevator area",
                priority = "normal"
            ))
        }
        
        delay(5000)
    }
    
    /**
     * Step 3: Safety demonstration
     */
    private suspend fun runSafetyDemo() {
        Log.d(TAG, "Demo: Starting safety demo")
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Demo: Simulating obstacle detection",
            priority = "normal"
        ))
        
        delay(2000)
        
        // Inject couch hazard
        Events.emit(Events.HazardEvent(
            id = "demo_couch_${System.currentTimeMillis()}",
            where = Events.HazardEvent.Where("indoor", "demo_library", "hallway"),
            geo = null,
            kind = "obstacle",
            label = "chair",
            severity = "warn",
            confidence = 0.9f,
            ttlSeconds = 5
        ))
        
        delay(1000)
        
        // Inject free-space guidance
        Events.emit(Events.FreeSpaceEvent(
            angleDeg = 15.0,
            confidence = 0.8f,
            binDistribution = FloatArray(7) { if (it > 4) 0.8f else 0.2f }
        ))
        
        delay(3000)
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Demo: Safety system working correctly",
            priority = "normal"
        ))
        
        delay(4000)
    }
    
    /**
     * Step 4: Elevator QR handoff
     */
    private suspend fun runElevatorQRDemo() {
        Log.d(TAG, "Demo: Starting elevator QR demo")
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Demo: Scanning elevator QR code",
            priority = "normal"
        ))
        
        delay(2000)
        
        // Simulate QR scan at elevator
        mappedinLocator.updateFromQrCode("LIB:ELEV", demoQrAnchors)
        
        delay(1000)
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Taking elevator to floor 2",
            priority = "normal"
        ))
        
        delay(3000)
        
        // Simulate floor change
        mappedinLocator.changeFloor("map-floor-2", "elevator-f2")
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Now on floor 2. Continue to reading room",
            priority = "normal"
        ))
        
        delay(3000)
        
        // Final destination
        mappedinLocator.updateFromQrCode("LIB:DEST", demoQrAnchors)
        
        Events.emit(Events.VoiceEvent(
            action = "speak", 
            text = "You have arrived at the reading room",
            priority = "normal"
        ))
        
        delay(2000)
    }
    
    /**
     * Step 5: Ask demonstration
     */
    private suspend fun runAskDemo() {
        Log.d(TAG, "Demo: Starting ask demo")
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Demo: What time does the help desk open?",
            priority = "normal"
        ))
        
        delay(3000)
        
        // Simulate NeuralSeek response
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "The help desk opens at 9 AM on weekdays",
            priority = "normal"
        ))
        
        delay(2000)
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = "Demo completed successfully",
            priority = "normal"
        ))
        
        delay(1000)
    }
    
    /**
     * Update demo progress
     */
    private suspend fun updateProgress(percentage: Float) {
        _demoProgress.value = percentage / 100f
        delay(500) // Small delay for smooth progress updates
    }
    
    /**
     * Stop demo
     */
    fun stopDemo() {
        scope.launch {
            _demoState.value = DemoState.Idle
            _demoProgress.value = 0f
            safetyFusionManager.stopNavigation()
            Log.d(TAG, "Demo stopped")
        }
    }
    
    /**
     * Get demo prefill target
     */
    fun getDemoPrefillTarget(): String {
        return if (_isDemoMode.value) "Library → SAC Bus Stop" else ""
    }
    
    /**
     * Check if QR handoff is enabled in demo mode
     */
    fun isQRHandoffEnabled(): Boolean {
        return _isDemoMode.value
    }
    
    /**
     * Get demo QR anchors
     */
    fun getDemoQRAnchors(): Map<String, QrAnchor> {
        return if (_isDemoMode.value) demoQrAnchors else emptyMap()
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
    }
}

/**
 * Demo state enumeration
 */
enum class DemoState {
    Idle,
    Starting,
    VoiceIntent,
    IndoorGuidance,
    SafetyDemo,
    ElevatorQR,
    AskDemo,
    Completed,
    Error(val message: String)
}

// Add demo mode preference to PrefsStore
fun PrefsStore.setDemoMode(enabled: Boolean) {
    // This would be implemented in the actual PrefsStore class
}