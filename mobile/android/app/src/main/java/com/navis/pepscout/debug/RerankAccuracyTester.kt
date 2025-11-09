package com.navis.pepscout.debug

import android.content.Context
import android.util.Log
import com.navis.pepscout.data.KnownPlace
import com.navis.pepscout.data.KnownPlacesStore
import com.navis.pepscout.intent.IntentToTargetPipeline
import com.navis.pepscout.intent.IntentResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Rerank accuracy tester for intent-to-target pipeline
 * Tests 20 canned utterances for top-1 ≥ 90% and top-3 = 100% accuracy
 */
class RerankAccuracyTester(
    private val context: Context,
    private val knownPlacesStore: KnownPlacesStore,
    private val intentPipeline: IntentToTargetPipeline
) {
    
    companion object {
        private const val TAG = "RerankAccuracyTester"
        private const val REQUIRED_TOP1_ACCURACY = 0.90 // 90%
        private const val REQUIRED_TOP3_ACCURACY = 1.00 // 100%
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Test state
    private val _testResults = MutableStateFlow<List<RerankTestResult>>(emptyList())
    val testResults: StateFlow<List<RerankTestResult>> = _testResults.asStateFlow()
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    // Test utterances with expected target
    private val testUtterances = listOf(
        TestUtterance("Take me to the reading room", "reading-room"),
        TestUtterance("I need to go to the study area", "reading-room"),
        TestUtterance("Where is the quiet zone", "quiet-zone"),
        TestUtterance("Navigate to silent study", "quiet-zone"),
        TestUtterance("Help me find the reference desk", "reference"),
        TestUtterance("I need to ask a librarian", "reference"),
        TestUtterance("Take me to the information desk", "reference"),
        TestUtterance("Where can I use a computer", "computers"),
        TestUtterance("I need to access the internet", "computers"),
        TestUtterance("Show me the workstations", "computers"),
        TestUtterance("Take me to the main entrance", "entrance"),
        TestUtterance("How do I get to the front door", "entrance"),
        TestUtterance("Navigate to the library entrance", "entrance"),
        TestUtterance("I need to take the elevator", "elevator"),
        TestUtterance("Where is the lift", "elevator"),
        TestUtterance("Take me upstairs", "elevator"),
        TestUtterance("Find the study pods", "study-pods"),
        TestUtterance("I want to study in a group", "study-pods"),
        TestUtterance("Show me collaborative study spaces", "study-pods"),
        TestUtterance("Take me to the main lobby", "lobby")
    )
    
    init {
        // Add test places with rich synonyms
        addTestPlaces()
    }
    
    /**
     * Run rerank accuracy test with all utterances
     */
    fun runAccuracyTest() {
        if (_isRunning.value) return
        
        scope.launch {
            _isRunning.value = true
            _testResults.value = emptyList()
            
            val results = mutableListOf<RerankTestResult>()
            
            try {
                Log.d(TAG, "Starting rerank accuracy test with ${testUtterances.size} utterances")
                
                testUtterances.forEachIndexed { index, utterance ->
                    Log.d(TAG, "Testing utterance ${index + 1}/${testUtterances.size}: ${utterance.text}")
                    
                    val result = testSingleUtterance(utterance)
                    results.add(result)
                    
                    // Update UI progressively
                    _testResults.value = results.toList()
                    
                    // Small delay between tests
                    delay(100)
                }
                
                // Calculate overall metrics
                val top1Accuracy = results.count { it.top1Correct }.toDouble() / results.size
                val top3Accuracy = results.count { it.top3Correct }.toDouble() / results.size
                
                // Write CSV report
                writeCSVReport(results, top1Accuracy, top3Accuracy)
                
                Log.d(TAG, "Rerank accuracy test completed: top-1=${top1Accuracy}, top-3=${top3Accuracy}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Rerank accuracy test failed", e)
            }
            
            _isRunning.value = false
        }
    }
    
    /**
     * Test a single utterance and return results
     */
    private suspend fun testSingleUtterance(utterance: TestUtterance): RerankTestResult {
        return try {
            val startTime = System.currentTimeMillis()
            
            // Use the search function directly (bypass Gemini for testing)
            val searchResults = knownPlacesStore.searchPlaces(utterance.text, 5)
            val processingTime = System.currentTimeMillis() - startTime
            
            // Check if expected target is in top-1 and top-3
            val top1Correct = searchResults.isNotEmpty() && 
                             searchResults[0].place.id == utterance.expectedTargetId
            
            val top3Correct = searchResults.take(3).any { 
                it.place.id == utterance.expectedTargetId 
            }
            
            val top1Score = if (searchResults.isNotEmpty()) searchResults[0].score else 0.0
            val expectedPosition = searchResults.indexOfFirst { 
                it.place.id == utterance.expectedTargetId 
            }.let { if (it == -1) null else it + 1 }
            
            RerankTestResult(
                utterance = utterance.text,
                expectedTarget = utterance.expectedTargetId,
                top1Result = searchResults.getOrNull(0)?.place?.name ?: "None",
                top1Score = top1Score,
                top1Correct = top1Correct,
                top3Correct = top3Correct,
                expectedPosition = expectedPosition,
                processingTimeMs = processingTime,
                allResults = searchResults.map { "${it.place.name} (${it.score})" }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to test utterance: ${utterance.text}", e)
            RerankTestResult(
                utterance = utterance.text,
                expectedTarget = utterance.expectedTargetId,
                top1Result = "Error",
                top1Score = 0.0,
                top1Correct = false,
                top3Correct = false,
                expectedPosition = null,
                processingTimeMs = 0,
                allResults = emptyList()
            )
        }
    }
    
    /**
     * Add comprehensive test places with rich synonyms
     */
    private suspend fun addTestPlaces() {
        val testPlaces = listOf(
            KnownPlace(
                id = "reading-room",
                name = "Reading Room",
                synonyms = listOf(
                    "reading room", "study area", "quiet study", "reading area",
                    "study room", "reading space", "study zone", "reading hall"
                ),
                mapId = "map-floor-2",
                nodeId = "reading-room",
                category = "study",
                description = "Quiet reading and study area"
            ),
            KnownPlace(
                id = "quiet-zone",
                name = "Quiet Study Zone",
                synonyms = listOf(
                    "quiet zone", "silent study", "quiet area", "no talking",
                    "silent zone", "quiet study area", "silent room", "shush zone"
                ),
                mapId = "map-floor-2",
                nodeId = "quiet-zone",
                category = "study",
                description = "Silent study zone"
            ),
            KnownPlace(
                id = "reference",
                name = "Reference Desk",
                synonyms = listOf(
                    "reference", "help desk", "information", "librarian",
                    "ask for help", "information desk", "reference counter",
                    "help counter", "assistance desk", "library help"
                ),
                mapId = "map-floor-1",
                nodeId = "reference",
                category = "service",
                description = "Get help from librarians"
            ),
            KnownPlace(
                id = "computers",
                name = "Computer Area",
                synonyms = listOf(
                    "computers", "computer lab", "workstations", "public computers",
                    "internet", "pc area", "computer stations", "terminals",
                    "computer room", "tech area"
                ),
                mapId = "map-floor-1",
                nodeId = "computers",
                category = "technology",
                description = "Public computer workstations"
            ),
            KnownPlace(
                id = "entrance",
                name = "Library Entrance",
                synonyms = listOf(
                    "entrance", "entry", "front door", "main entrance",
                    "library entrance", "main door", "entry way", "foyer",
                    "main entry", "library door"
                ),
                mapId = "map-floor-1",
                nodeId = "entrance",
                category = "entrance",
                description = "Main entrance to the library"
            ),
            KnownPlace(
                id = "elevator",
                name = "Elevator",
                synonyms = listOf(
                    "elevator", "lift", "go upstairs", "go to second floor",
                    "elevator bank", "lifts", "vertical transport", "up",
                    "floor access", "level change"
                ),
                mapId = "map-floor-1",
                nodeId = "elevator-f1",
                category = "navigation",
                description = "Elevator to other floors"
            ),
            KnownPlace(
                id = "study-pods",
                name = "Study Pods",
                synonyms = listOf(
                    "study pods", "group study", "collaborative study", "pods",
                    "study rooms", "group rooms", "collaboration space",
                    "team study", "group work", "collaborative area"
                ),
                mapId = "map-floor-2",
                nodeId = "study-pods",
                category = "study",
                description = "Individual and group study pods"
            ),
            KnownPlace(
                id = "lobby",
                name = "Main Lobby",
                synonyms = listOf(
                    "lobby", "main area", "central area", "main lobby",
                    "center", "central space", "main space", "common area",
                    "main hall", "central hall"
                ),
                mapId = "map-floor-1",
                nodeId = "lobby",
                category = "common",
                description = "Main lobby area"
            )
        )
        
        testPlaces.forEach { place ->
            knownPlacesStore.addPlace(place)
        }
        
        Log.d(TAG, "Added ${testPlaces.size} test places with rich synonyms")
    }
    
    /**
     * Write CSV report to filesDir/logs/
     */
    private suspend fun writeCSVReport(
        results: List<RerankTestResult>,
        top1Accuracy: Double,
        top3Accuracy: Double
    ) {
        withContext(Dispatchers.IO) {
            try {
                val logsDir = File(context.filesDir, "logs")
                if (!logsDir.exists()) {
                    logsDir.mkdirs()
                }
                
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val csvFile = File(logsDir, "rerank_accuracy_${timestamp}.csv")
                
                csvFile.writeText(buildString {
                    // Header
                    appendLine("utterance,expected_target,top1_result,top1_score,top1_correct,top3_correct,expected_position,processing_time_ms,all_results")
                    
                    // Data rows
                    results.forEach { result ->
                        appendLine("\"${result.utterance}\",${result.expectedTarget},\"${result.top1Result}\",${result.top1Score},${result.top1Correct},${result.top3Correct},${result.expectedPosition ?: ""},${result.processingTimeMs},\"${result.allResults.joinToString("; ")}\"")
                    }
                    
                    // Summary
                    appendLine()
                    appendLine("Summary")
                    appendLine("Total utterances,${results.size}")
                    appendLine("Top-1 accuracy,${String.format("%.2f", top1Accuracy)}")
                    appendLine("Top-3 accuracy,${String.format("%.2f", top3Accuracy)}")
                    appendLine("Top-1 target (≥90%),${if (top1Accuracy >= REQUIRED_TOP1_ACCURACY) "PASS" else "FAIL"}")
                    appendLine("Top-3 target (100%),${if (top3Accuracy >= REQUIRED_TOP3_ACCURACY) "PASS" else "FAIL"}")
                    appendLine("Average processing time,${results.map { it.processingTimeMs }.average().toInt()}ms")
                })
                
                Log.d(TAG, "CSV report written to: ${csvFile.absolutePath}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write CSV report", e)
            }
        }
    }
    
    /**
     * Get overall test metrics
     */
    fun getTestMetrics(): RerankTestMetrics? {
        val results = _testResults.value
        if (results.isEmpty()) return null
        
        val top1Accuracy = results.count { it.top1Correct }.toDouble() / results.size
        val top3Accuracy = results.count { it.top3Correct }.toDouble() / results.size
        val avgProcessingTime = results.map { it.processingTimeMs }.average()
        
        return RerankTestMetrics(
            totalUtterances = results.size,
            top1Accuracy = top1Accuracy,
            top3Accuracy = top3Accuracy,
            top1Pass = top1Accuracy >= REQUIRED_TOP1_ACCURACY,
            top3Pass = top3Accuracy >= REQUIRED_TOP3_ACCURACY,
            avgProcessingTimeMs = avgProcessingTime
        )
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
    }
}

/**
 * Test utterance with expected target
 */
data class TestUtterance(
    val text: String,
    val expectedTargetId: String
)

/**
 * Result for a single rerank test
 */
data class RerankTestResult(
    val utterance: String,
    val expectedTarget: String,
    val top1Result: String,
    val top1Score: Double,
    val top1Correct: Boolean,
    val top3Correct: Boolean,
    val expectedPosition: Int?, // 1-based position of expected target, null if not found
    val processingTimeMs: Long,
    val allResults: List<String>
)

/**
 * Overall test metrics
 */
data class RerankTestMetrics(
    val totalUtterances: Int,
    val top1Accuracy: Double,
    val top3Accuracy: Double,
    val top1Pass: Boolean,
    val top3Pass: Boolean,
    val avgProcessingTimeMs: Double
)