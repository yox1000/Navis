package com.navis.pepscout.nav

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Indoor navigation engine that manages QR handoffs and step advancement
 * State machine pattern inspired by PathSense event handling
 */
class IndoorEngine(private val graph: IndoorGraph) {
    
    companion object {
        private const val TAG = "IndoorEngine"
    }
    
    // Navigation state
    private val _state = MutableStateFlow(IndoorNavState.Idle)
    val state: StateFlow<IndoorNavState> = _state.asStateFlow()
    
    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()
    
    private val _currentFloor = MutableStateFlow(1)
    val currentFloor: StateFlow<Int> = _currentFloor.asStateFlow()
    
    private var currentPath: IndoorGraph.PathResult? = null
    private var currentPosition: IndoorGraph.Node? = null

    /**
     * Start navigation from entrance to destination
     */
    suspend fun startNavigation(
        startNodeId: String = "ENTR",
        endNodeId: String = "DEST",
        avoidStairs: Boolean = false
    ): Boolean {
        return try {
            Log.d(TAG, "Starting indoor navigation: $startNodeId -> $endNodeId")
            
            val pathResult = graph.findPath(
                startNodeId,
                endNodeId,
                IndoorGraph.PathConstraints(avoidStairs = avoidStairs)
            )
            
            if (pathResult != null) {
                currentPath = pathResult
                currentPosition = pathResult.nodes.first()
                _currentStep.value = 0
                _currentFloor.value = currentPosition?.floor ?: 1
                _state.value = IndoorNavState.NavigatingToFirst
                
                Log.d(TAG, "Navigation started: ${pathResult.steps.size} steps, ${pathResult.totalDistance}m")
                true
            } else {
                Log.w(TAG, "No path found")
                _state.value = IndoorNavState.Error("No path found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start navigation", e)
            _state.value = IndoorNavState.Error("Navigation failed: ${e.message}")
            false
        }
    }

    /**
     * Handle QR code scan at a location
     */
    fun onQrScanned(payload: String) {
        Log.d(TAG, "QR scanned: $payload")
        
        val node = graph.getNodeByQrPayload(payload)
        if (node == null) {
            Log.w(TAG, "Unknown QR payload: $payload")
            return
        }
        
        val path = currentPath
        if (path == null) {
            Log.w(TAG, "No active navigation")
            return
        }
        
        // Find this node in our current path
        val nodeIndex = path.nodes.indexOfFirst { it.id == node.id }
        if (nodeIndex == -1) {
            Log.w(TAG, "Scanned node ${node.id} not in current path")
            return
        }
        
        // Update position and step
        currentPosition = node
        _currentStep.value = nodeIndex
        _currentFloor.value = node.floor
        
        // Update state based on progress
        when {
            nodeIndex == 0 -> {
                _state.value = IndoorNavState.AtStart
                Log.d(TAG, "At starting position: ${node.name}")
            }
            nodeIndex == path.nodes.size - 1 -> {
                _state.value = IndoorNavState.Completed
                Log.d(TAG, "Navigation completed at: ${node.name}")
            }
            else -> {
                _state.value = IndoorNavState.InProgress
                Log.d(TAG, "Progress: ${node.name} (step ${nodeIndex + 1}/${path.nodes.size})")
            }
        }
    }

    /**
     * Advance to next step manually (fallback for failed QR scans)
     */
    fun advanceStep() {
        val path = currentPath ?: return
        val currentStepIndex = _currentStep.value
        
        if (currentStepIndex < path.nodes.size - 1) {
            val newStep = currentStepIndex + 1
            _currentStep.value = newStep
            
            currentPosition = path.nodes[newStep]
            _currentFloor.value = currentPosition?.floor ?: _currentFloor.value
            
            if (newStep == path.nodes.size - 1) {
                _state.value = IndoorNavState.Completed
                Log.d(TAG, "Manual advance completed navigation")
            } else {
                _state.value = IndoorNavState.InProgress
                Log.d(TAG, "Manual advance to step ${newStep + 1}/${path.nodes.size}")
            }
        }
    }

    /**
     * Go back to previous step
     */
    fun goBackStep() {
        val currentStepIndex = _currentStep.value
        
        if (currentStepIndex > 0) {
            val newStep = currentStepIndex - 1
            _currentStep.value = newStep
            
            val path = currentPath
            if (path != null && newStep < path.nodes.size) {
                currentPosition = path.nodes[newStep]
                _currentFloor.value = currentPosition?.floor ?: _currentFloor.value
                _state.value = if (newStep == 0) IndoorNavState.AtStart else IndoorNavState.InProgress
                
                Log.d(TAG, "Back to step ${newStep + 1}/${path.nodes.size}")
            }
        }
    }

    /**
     * Get current step instruction
     */
    fun getCurrentInstruction(): String? {
        val path = currentPath ?: return null
        val stepIndex = _currentStep.value
        
        return when {
            stepIndex == 0 && path.steps.isNotEmpty() -> 
                "Start here. ${path.steps[0].instruction}"
            stepIndex < path.steps.size ->
                path.steps[stepIndex].instruction
            stepIndex == path.steps.size ->
                "You have arrived at your destination!"
            else -> null
        }
    }

    /**
     * Get next step instruction for TTS preloading
     */
    fun getNextInstruction(): String? {
        val path = currentPath ?: return null
        val stepIndex = _currentStep.value + 1
        
        return if (stepIndex < path.steps.size) {
            path.steps[stepIndex].instruction
        } else if (stepIndex == path.steps.size) {
            "You have arrived at your destination!"
        } else {
            null
        }
    }

    /**
     * Get path for drawing on floor plan
     */
    fun getPathForFloor(floorId: Int): List<IndoorGraph.Node> {
        return currentPath?.nodes?.filter { it.floor == floorId } ?: emptyList()
    }

    /**
     * Get current position
     */
    fun getCurrentPosition(): IndoorGraph.Node? = currentPosition

    /**
     * Get total progress (0.0 to 1.0)
     */
    fun getProgress(): Float {
        val path = currentPath ?: return 0f
        val totalSteps = path.nodes.size
        return if (totalSteps > 0) _currentStep.value.toFloat() / totalSteps else 0f
    }

    /**
     * Get remaining distance estimate
     */
    fun getRemainingDistance(): Double {
        val path = currentPath ?: return 0.0
        val stepIndex = _currentStep.value
        
        return path.steps.drop(stepIndex).sumOf { it.distance }
    }

    /**
     * Reset navigation
     */
    fun reset() {
        currentPath = null
        currentPosition = null
        _currentStep.value = 0
        _currentFloor.value = 1
        _state.value = IndoorNavState.Idle
        Log.d(TAG, "Navigation reset")
    }

    /**
     * Check if currently navigating
     */
    fun isNavigating(): Boolean {
        return currentPath != null && _state.value != IndoorNavState.Idle
    }

    // Navigation state sealed class
    sealed class IndoorNavState {
        object Idle : IndoorNavState()
        object NavigatingToFirst : IndoorNavState()
        object AtStart : IndoorNavState()
        object InProgress : IndoorNavState()
        object Completed : IndoorNavState()
        data class Error(val message: String) : IndoorNavState()
    }
}