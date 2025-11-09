package com.navis.pepscout.nav

import android.content.Context
import android.util.Log
import com.navis.pepscout.data.PrefsStore
import com.navis.pepscout.mappedin.MappedinRouter
import com.navis.pepscout.mappedin.MappedinLocator
import com.navis.pepscout.mappedin.MappedinRoute
import com.navis.pepscout.util.Events
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs

/**
 * Enhanced safety manager with Mappedin routing integration
 * Provides intelligent detour routing and context-aware safety guidance
 */
class SafetyFusionManager(
    private val context: Context,
    private val prefsStore: PrefsStore,
    private val mappedinRouter: MappedinRouter,
    private val mappedinLocator: MappedinLocator
) {
    
    companion object {
        private const val TAG = "SafetyFusionManager"
        private const val DETOUR_SEARCH_RADIUS = 50.0 // meters
        private const val MAX_DETOUR_DISTANCE = 100.0 // meters
        private const val PERSISTENT_BLOCK_THRESHOLD = 3000L // 3 seconds
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // State tracking
    private var currentRoute: MappedinRoute? = null
    private var currentStepIndex = 0
    private var isNavigating = false
    private var blockedSince: Long? = null
    private var lastDetourAttempt: Long = 0
    private var detourCooldown = 5000L // 5 seconds between detour attempts
    
    // Safety state
    private val _safetyState = MutableStateFlow(SafetyFusionState.Clear)
    val safetyState: StateFlow<SafetyFusionState> = _safetyState.asStateFlow()
    
    // Navigation integration
    private val _routeGuidance = MutableStateFlow<RouteGuidance?>(null)
    val routeGuidance: StateFlow<RouteGuidance?> = _routeGuidance.asStateFlow()
    
    init {
        startEventSubscription()
    }
    
    /**
     * Start navigation route with safety monitoring
     */
    fun startNavigation(route: MappedinRoute) {
        currentRoute = route
        currentStepIndex = 0
        isNavigating = true
        blockedSince = null
        
        _safetyState.value = SafetyFusionState.Navigating
        updateRouteGuidance()
        
        Log.d(TAG, "Started navigation with ${route.steps.size} steps")
    }
    
    /**
     * Stop navigation
     */
    fun stopNavigation() {
        currentRoute = null
        currentStepIndex = 0
        isNavigating = false
        blockedSince = null
        
        _safetyState.value = SafetyFusionState.Clear
        _routeGuidance.value = null
        
        Log.d(TAG, "Navigation stopped")
    }
    
    /**
     * Advance to next navigation step
     */
    fun advanceStep() {
        val route = currentRoute ?: return
        
        if (currentStepIndex < route.steps.size - 1) {
            currentStepIndex++
            updateRouteGuidance()
            Log.d(TAG, "Advanced to step ${currentStepIndex + 1}/${route.steps.size}")
        } else {
            // Navigation completed
            _safetyState.value = SafetyFusionState.Completed
            _routeGuidance.value = RouteGuidance(
                stepDescription = "You have arrived at your destination!",
                stepIndex = currentStepIndex,
                totalSteps = route.steps.size,
                isComplete = true
            )
            Log.d(TAG, "Navigation completed")
        }
    }
    
    private fun startEventSubscription() {
        // Listen for safety events
        Events.hazardEvents()
            .onEach { handleHazardEvent(it) }
            .launchIn(scope)
        
        Events.freeSpaceEvents()
            .onEach { handleFreeSpaceEvent(it) }
            .launchIn(scope)
        
        Events.wallEvents()
            .onEach { handleWallEvent(it) }
            .launchIn(scope)
        
        Log.d(TAG, "Safety fusion event subscriptions started")
    }
    
    private fun handleHazardEvent(event: Events.HazardEvent) {
        if (!isNavigating) return
        
        val isObstacleInPath = isObstacleBlockingPath(event)
        
        if (isObstacleInPath) {
            val currentTime = System.currentTimeMillis()
            
            if (blockedSince == null) {
                blockedSince = currentTime
                _safetyState.value = SafetyFusionState.ObstacleDetected
                
                // Emit voice guidance
                Events.emit(Events.VoiceEvent(
                    action = "speak",
                    text = "Careful. Obstacle ahead.",
                    priority = "urgent"
                ))
                
                Log.d(TAG, "Obstacle detected, starting block timer")
            }
            
            // Check for persistent block
            val blockDuration = currentTime - (blockedSince ?: currentTime)
            if (blockDuration > PERSISTENT_BLOCK_THRESHOLD) {
                handlePersistentBlock()
            }
            
        } else {
            // Clear blocked state
            if (blockedSince != null) {
                blockedSince = null
                _safetyState.value = SafetyFusionState.Navigating
                updateRouteGuidance()
                Log.d(TAG, "Obstacle cleared, resuming navigation")
            }
        }
    }
    
    private fun handleFreeSpaceEvent(event: Events.FreeSpaceEvent) {
        if (!isNavigating || blockedSince == null) return
        
        // Provide directional guidance when blocked
        val guidance = when {
            event.angleDeg >= 15.0 -> "Clear path on the right. Small step right."
            event.angleDeg <= -15.0 -> "Clear path on the left. Small step left."
            abs(event.angleDeg) < 8.0 -> "Path ahead is narrow. Proceed carefully."
            else -> "Limited space. Please wait for path to clear."
        }
        
        Events.emit(Events.VoiceEvent(
            action = "speak",
            text = guidance,
            priority = "normal"
        ))
        
        _safetyState.value = SafetyFusionState.GuidingAroundObstacle
        
        Log.d(TAG, "Provided free-space guidance: angle=${event.angleDeg}°")
    }
    
    private fun handleWallEvent(event: Events.WallEvent) {
        if (!isNavigating) return
        
        if (event.detected) {
            _safetyState.value = SafetyFusionState.WallDetected
            
            Events.emit(Events.VoiceEvent(
                action = "speak",
                text = "Stop. Wall ahead.",
                priority = "urgent"
            ))
            
            Log.d(TAG, "Wall detected during navigation")
        }
    }
    
    private fun handlePersistentBlock() {
        val currentTime = System.currentTimeMillis()
        
        // Avoid too frequent detour attempts
        if (currentTime - lastDetourAttempt < detourCooldown) {
            return
        }
        
        lastDetourAttempt = currentTime
        
        scope.launch {
            try {
                val detourRoute = findDetourRoute()
                
                if (detourRoute != null) {
                    // Switch to detour route
                    currentRoute = detourRoute
                    currentStepIndex = 0
                    blockedSince = null
                    
                    _safetyState.value = SafetyFusionState.DetourActive
                    updateRouteGuidance()
                    
                    Events.emit(Events.VoiceEvent(
                        action = "speak",
                        text = "Taking alternate route around obstacle.",
                        priority = "normal"
                    ))
                    
                    Log.d(TAG, "Activated detour route with ${detourRoute.steps.size} steps")
                    
                } else {
                    // No detour available
                    _safetyState.value = SafetyFusionState.NoDetourAvailable
                    
                    Events.emit(Events.VoiceEvent(
                        action = "speak",
                        text = "Path closed. Please wait for path to clear.",
                        priority = "normal"
                    ))
                    
                    Log.d(TAG, "No detour route available")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error finding detour route", e)
            }
        }
    }
    
    private suspend fun findDetourRoute(): MappedinRoute? {
        val currentPosition = mappedinLocator.getCurrentPosition() ?: return null
        val route = currentRoute ?: return null
        
        if (currentStepIndex >= route.steps.size) return null
        
        val currentStep = route.steps[currentStepIndex]
        val targetNodeId = currentStep.nodeId
        
        // Try to find alternate route to same destination
        return try {
            // Get neighboring nodes to current position
            val currentMap = mappedinRouter.getMap(currentPosition.mapId)
            val neighbors = currentMap?.edges?.filter { edge ->
                edge.fromNodeId == (currentPosition.nodeId ?: return null)
            }?.map { it.toNodeId } ?: return null
            
            // Try routing through each neighbor
            for (neighborId in neighbors) {
                val detourSegment1 = mappedinRouter.route(
                    fromNodeId = currentPosition.nodeId!!,
                    toNodeId = neighborId,
                    mapId = currentPosition.mapId
                )
                
                val detourSegment2 = mappedinRouter.route(
                    fromNodeId = neighborId,
                    toNodeId = targetNodeId,
                    mapId = currentStep.mapId
                )
                
                if (detourSegment1 != null && detourSegment2 != null) {
                    // Check if detour is reasonable (not too long)
                    val detourDistance = detourSegment1.distance + detourSegment2.distance
                    val originalDistance = route.distance - 
                        route.steps.take(currentStepIndex).sumOf { it.distance }
                    
                    if (detourDistance <= originalDistance + MAX_DETOUR_DISTANCE) {
                        // Combine detour segments
                        return combineRouteSegments(detourSegment1, detourSegment2)
                    }
                }
            }
            
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error computing detour route", e)
            null
        }
    }
    
    private fun combineRouteSegments(segment1: MappedinRoute, segment2: MappedinRoute): MappedinRoute {
        return MappedinRoute(
            distance = segment1.distance + segment2.distance,
            duration = segment1.duration + segment2.duration,
            steps = segment1.steps + segment2.steps,
            geometry = segment1.geometry + segment2.geometry
        )
    }
    
    private fun isObstacleBlockingPath(event: Events.HazardEvent): Boolean {
        // For indoor navigation, consider obstacles in front as blocking
        // This could be enhanced with more sophisticated spatial analysis
        return event.kind in listOf("obstacle", "moving_object") &&
               event.severity in listOf("warn", "danger")
    }
    
    private fun updateRouteGuidance() {
        val route = currentRoute ?: return
        
        if (currentStepIndex < route.steps.size) {
            val currentStep = route.steps[currentStepIndex]
            
            _routeGuidance.value = RouteGuidance(
                stepDescription = currentStep.description,
                stepIndex = currentStepIndex,
                totalSteps = route.steps.size,
                remainingDistance = route.steps.drop(currentStepIndex).sumOf { it.distance },
                estimatedTimeRemaining = route.steps.drop(currentStepIndex).sumOf { it.duration },
                isComplete = false
            )
        }
    }
    
    /**
     * Check if navigation should be paused
     */
    fun shouldPauseNavigation(): Boolean {
        return _safetyState.value in listOf(
            SafetyFusionState.ObstacleDetected,
            SafetyFusionState.WallDetected,
            SafetyFusionState.NoDetourAvailable
        )
    }
    
    /**
     * Get current navigation progress
     */
    fun getNavigationProgress(): Float {
        val route = currentRoute ?: return 0.0f
        if (route.steps.isEmpty()) return 0.0f
        
        return currentStepIndex.toFloat() / route.steps.size
    }
    
    /**
     * Manual position update (e.g., from QR scan)
     */
    fun updatePosition(nodeId: String, mapId: String) {
        val route = currentRoute ?: return
        
        // Try to find this node in the remaining route steps
        val stepIndex = route.steps.drop(currentStepIndex).indexOfFirst { step ->
            step.nodeId == nodeId && step.mapId == mapId
        }
        
        if (stepIndex >= 0) {
            // Jump to this step
            currentStepIndex += stepIndex
            updateRouteGuidance()
            
            Log.d(TAG, "Position updated to step ${currentStepIndex + 1} via QR/manual")
        }
    }
    
    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
        Log.d(TAG, "SafetyFusionManager destroyed")
    }
}

/**
 * Safety fusion state enumeration
 */
enum class SafetyFusionState {
    Clear,
    Navigating,
    ObstacleDetected,
    GuidingAroundObstacle,
    WallDetected,
    DetourActive,
    NoDetourAvailable,
    Completed
}

/**
 * Route guidance information
 */
data class RouteGuidance(
    val stepDescription: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val remainingDistance: Double = 0.0,
    val estimatedTimeRemaining: Double = 0.0,
    val isComplete: Boolean = false
)