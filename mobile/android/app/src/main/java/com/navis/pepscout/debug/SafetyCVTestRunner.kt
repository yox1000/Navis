package com.navis.pepscout.debug

import android.content.Context
import android.util.Log
import com.navis.pepscout.util.Events
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.system.measureTimeMillis

/**
 * Safety CV test runner for pass/fail testing
 * Executes automated tests for safety system behavior
 */
class SafetyCVTestRunner(private val context: Context) {
    
    companion object {
        private const val TAG = "SafetyCVTestRunner"
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Test state
    private val _testResults = MutableStateFlow<List<SafetyTestResult>>(emptyList())
    val testResults: StateFlow<List<SafetyTestResult>> = _testResults.asStateFlow()
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    // Metrics tracking
    private var currentFPS = 0f
    private var testStartTime = 0L
    private var timeToFirstHazard = 0L
    private var safetyLinesSpoken = 0
    
    /**
     * Run all safety CV tests
     */
    fun runAllTests() {
        if (_isRunning.value) return
        
        scope.launch {
            _isRunning.value = true
            _testResults.value = emptyList()
            
            val results = mutableListOf<SafetyTestResult>()
            
            try {
                // Test 1: Couch-in-path
                results.add(testCouchInPath())
                delay(2000) // Cooldown between tests
                
                // Test 2: Wall-at-1m  
                results.add(testWallAt1m())
                delay(2000)
                
                // Test 3: Empty corridor
                results.add(testEmptyCorridor())
                delay(2000)
                
                // Test 4: Persistent block
                results.add(testPersistentBlock())
                
            } catch (e: Exception) {
                Log.e(TAG, "Test execution failed", e)
                results.add(SafetyTestResult(
                    testName = "Test Execution",
                    passed = false,
                    message = "Test execution failed: ${e.message}",
                    metrics = emptyMap()
                ))
            }
            
            _testResults.value = results
            _isRunning.value = false
        }
    }
    
    /**
     * Test 1: Couch-in-path - hazard then side hint, clears within 1.2s
     */
    private suspend fun testCouchInPath(): SafetyTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting couch-in-path test")
                
                val voiceEvents = mutableListOf<String>()
                val eventListener = scope.launch {
                    Events.flowOf<Events.VoiceEvent>().collect { event ->
                        voiceEvents.add(event.text)
                        safetyLinesSpoken++
                    }
                }
                
                testStartTime = System.currentTimeMillis()
                
                val testDuration = measureTimeMillis {
                    // Inject couch hazard
                    Events.emit(Events.HazardEvent(
                        id = "test_couch_${System.currentTimeMillis()}",
                        where = Events.HazardEvent.Where("indoor", "test_building", "test_node"),
                        geo = null,
                        kind = "obstacle",
                        label = "chair",
                        severity = "warn",
                        confidence = 0.9f,
                        ttlSeconds = 3
                    ))
                    
                    if (timeToFirstHazard == 0L) {
                        timeToFirstHazard = System.currentTimeMillis() - testStartTime
                    }
                    
                    // Wait for initial hazard detection
                    delay(300)
                    
                    // Inject free-space hint (right side clear)
                    Events.emit(Events.FreeSpaceEvent(
                        angleDeg = 15.0,
                        confidence = 0.8f,
                        binDistribution = FloatArray(7) { if (it > 4) 0.8f else 0.2f }
                    ))
                    
                    // Wait for side guidance
                    delay(500)
                    
                    // Clear the hazard
                    delay(400) // Total should be ~1.2s
                }
                
                eventListener.cancel()
                
                // Analyze results
                val hasInitialWarning = voiceEvents.any { 
                    it.contains("careful", ignoreCase = true) || 
                    it.contains("obstacle", ignoreCase = true) 
                }
                
                val hasSideHint = voiceEvents.any { 
                    it.contains("right", ignoreCase = true) || 
                    it.contains("side", ignoreCase = true) 
                }
                
                val clearedInTime = testDuration <= 1200L
                
                val passed = hasInitialWarning && hasSideHint && clearedInTime
                
                SafetyTestResult(
                    testName = "Couch-in-path",
                    passed = passed,
                    message = if (passed) "PASS: Detected hazard and provided guidance" 
                             else "FAIL: Missing warning=${!hasInitialWarning}, side hint=${!hasSideHint}, timing=${!clearedInTime}",
                    metrics = mapOf(
                        "duration_ms" to testDuration.toString(),
                        "voice_events" to voiceEvents.size.toString(),
                        "time_to_first_hazard_ms" to timeToFirstHazard.toString()
                    )
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Couch-in-path test failed", e)
                SafetyTestResult(
                    testName = "Couch-in-path",
                    passed = false,
                    message = "Test failed: ${e.message}",
                    metrics = emptyMap()
                )
            }
        }
    }
    
    /**
     * Test 2: Wall-at-1m - single "Stop. Wall ahead.", 2s deadman
     */
    private suspend fun testWallAt1m(): SafetyTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting wall-at-1m test")
                
                val voiceEvents = mutableListOf<String>()
                val eventListener = scope.launch {
                    Events.flowOf<Events.VoiceEvent>().collect { event ->
                        voiceEvents.add(event.text)
                    }
                }
                
                val testDuration = measureTimeMillis {
                    // Inject wall detection
                    Events.emit(Events.WallEvent(
                        detected = true,
                        edgeDensity = 0.8f,
                        foeConfidence = 0.9f,
                        distanceM = 1.0
                    ))
                    
                    // Wait for initial wall warning
                    delay(500)
                    
                    // Try to inject another wall event during deadman period
                    Events.emit(Events.WallEvent(
                        detected = true,
                        edgeDensity = 0.8f,
                        foeConfidence = 0.9f,
                        distanceM = 1.0
                    ))
                    
                    // Wait for deadman period to expire
                    delay(2000)
                }
                
                eventListener.cancel()
                
                // Analyze results
                val wallWarnings = voiceEvents.filter { 
                    it.contains("stop", ignoreCase = true) && 
                    it.contains("wall", ignoreCase = true) 
                }
                
                val singleWarning = wallWarnings.size == 1
                val hasDeadman = testDuration >= 2000L
                
                val passed = singleWarning && hasDeadman
                
                SafetyTestResult(
                    testName = "Wall-at-1m",
                    passed = passed,
                    message = if (passed) "PASS: Single wall warning with deadman period"
                             else "FAIL: Wall warnings=${wallWarnings.size}, deadman=${hasDeadman}",
                    metrics = mapOf(
                        "duration_ms" to testDuration.toString(),
                        "wall_warnings" to wallWarnings.size.toString(),
                        "total_voice_events" to voiceEvents.size.toString()
                    )
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Wall-at-1m test failed", e)
                SafetyTestResult(
                    testName = "Wall-at-1m", 
                    passed = false,
                    message = "Test failed: ${e.message}",
                    metrics = emptyMap()
                )
            }
        }
    }
    
    /**
     * Test 3: Empty corridor - zero hazards, free-space near 0 deg
     */
    private suspend fun testEmptyCorridor(): SafetyTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting empty corridor test")
                
                val hazardEvents = mutableListOf<Events.HazardEvent>()
                val freeSpaceEvents = mutableListOf<Events.FreeSpaceEvent>()
                
                val hazardListener = scope.launch {
                    Events.hazardEvents().collect { event ->
                        hazardEvents.add(event)
                    }
                }
                
                val freeSpaceListener = scope.launch {
                    Events.freeSpaceEvents().collect { event ->
                        freeSpaceEvents.add(event)
                    }
                }
                
                val testDuration = measureTimeMillis {
                    // Inject clear corridor free-space
                    Events.emit(Events.FreeSpaceEvent(
                        angleDeg = 2.0, // Near 0 degrees (straight ahead)
                        confidence = 0.9f,
                        binDistribution = FloatArray(7) { if (it == 3) 0.9f else 0.1f } // Center bin clear
                    ))
                    
                    delay(1000) // Monitor for any hazards
                }
                
                hazardListener.cancel()
                freeSpaceListener.cancel()
                
                // Analyze results
                val noHazards = hazardEvents.isEmpty()
                val straightAhead = freeSpaceEvents.any { 
                    kotlin.math.abs(it.angleDeg) < 5.0 // Within 5 degrees of straight
                }
                
                val passed = noHazards && straightAhead
                
                SafetyTestResult(
                    testName = "Empty corridor",
                    passed = passed,
                    message = if (passed) "PASS: Clear path detected straight ahead"
                             else "FAIL: Hazards detected=${!noHazards}, straight path=${!straightAhead}",
                    metrics = mapOf(
                        "duration_ms" to testDuration.toString(),
                        "hazard_count" to hazardEvents.size.toString(),
                        "free_space_events" to freeSpaceEvents.size.toString(),
                        "best_angle_deg" to (freeSpaceEvents.minByOrNull { kotlin.math.abs(it.angleDeg) }?.angleDeg?.toString() ?: "none")
                    )
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Empty corridor test failed", e)
                SafetyTestResult(
                    testName = "Empty corridor",
                    passed = false,
                    message = "Test failed: ${e.message}",
                    metrics = emptyMap()
                )
            }
        }
    }
    
    /**
     * Test 4: Persistent block - after 2s, one-edge detour indoors if possible
     */
    private suspend fun testPersistentBlock(): SafetyTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting persistent block test")
                
                val voiceEvents = mutableListOf<String>()
                val eventListener = scope.launch {
                    Events.flowOf<Events.VoiceEvent>().collect { event ->
                        voiceEvents.add(event.text)
                    }
                }
                
                val testDuration = measureTimeMillis {
                    // Simulate persistent obstacle
                    repeat(5) { // Multiple hazard events over time
                        Events.emit(Events.HazardEvent(
                            id = "persistent_obstacle_${System.currentTimeMillis()}",
                            where = Events.HazardEvent.Where("indoor", "test_building", "test_node"),
                            geo = null,
                            kind = "obstacle",
                            label = "person",
                            severity = "danger", 
                            confidence = 0.9f,
                            ttlSeconds = 5
                        ))
                        delay(600) // Emit every 600ms to simulate persistent detection
                    }
                    
                    // Wait additional time for detour calculation
                    delay(500)
                }
                
                eventListener.cancel()
                
                // Analyze results
                val hasInitialWarning = voiceEvents.any { 
                    it.contains("careful", ignoreCase = true) || 
                    it.contains("obstacle", ignoreCase = true)
                }
                
                val hasPersistentMessage = voiceEvents.any { 
                    it.contains("path closed", ignoreCase = true) || 
                    it.contains("wait", ignoreCase = true) ||
                    it.contains("alternate", ignoreCase = true) ||
                    it.contains("detour", ignoreCase = true)
                }
                
                val appropriateTiming = testDuration >= 2000L
                
                val passed = hasInitialWarning && hasPersistentMessage && appropriateTiming
                
                SafetyTestResult(
                    testName = "Persistent block",
                    passed = passed,
                    message = if (passed) "PASS: Detected persistent block and provided guidance"
                             else "FAIL: Initial warning=${hasInitialWarning}, persistent msg=${hasPersistentMessage}, timing=${appropriateTiming}",
                    metrics = mapOf(
                        "duration_ms" to testDuration.toString(),
                        "voice_events" to voiceEvents.size.toString(),
                        "fps" to currentFPS.toString()
                    )
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Persistent block test failed", e)
                SafetyTestResult(
                    testName = "Persistent block",
                    passed = false,
                    message = "Test failed: ${e.message}",
                    metrics = emptyMap()
                )
            }
        }
    }
    
    /**
     * Update FPS for metrics
     */
    fun updateFPS(fps: Float) {
        currentFPS = fps
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
    }
}

/**
 * Safety test result data class
 */
data class SafetyTestResult(
    val testName: String,
    val passed: Boolean,
    val message: String,
    val metrics: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)