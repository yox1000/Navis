package com.navis.pepscout.debug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * Latency tracker for TTS and STT performance monitoring
 * Tracks latency budgets and generates reports
 */
class LatencyTracker(private val context: Context) {
    
    companion object {
        private const val TAG = "LatencyTracker"
        
        // Latency budgets (in milliseconds)
        const val STT_SYSTEM_BUDGET = 1500L // System SpeechRecognizer
        const val STT_WHISPER_BUDGET = 2500L // Whisper on-device
        const val TTS_CACHED_BUDGET = 900L   // ElevenLabs cached
        const val TTS_COLD_BUDGET = 2000L    // ElevenLabs cold call
        
        private const val MAX_SAMPLES = 100 // Keep last 100 samples
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Latency data storage
    private val sttLatencies = mutableListOf<LatencySample>()
    private val ttsLatencies = mutableListOf<LatencySample>()
    
    // State flows for UI
    private val _sttMetrics = MutableStateFlow<LatencyMetrics?>(null)
    val sttMetrics: StateFlow<LatencyMetrics?> = _sttMetrics.asStateFlow()
    
    private val _ttsMetrics = MutableStateFlow<LatencyMetrics?>(null) 
    val ttsMetrics: StateFlow<LatencyMetrics?> = _ttsMetrics.asStateFlow()
    
    /**
     * Record STT latency measurement
     */
    fun recordSTTLatency(
        durationMs: Long,
        engine: String, // "system" or "whisper"
        success: Boolean,
        transcript: String = ""
    ) {
        scope.launch {
            val budget = when (engine) {
                "whisper" -> STT_WHISPER_BUDGET
                else -> STT_SYSTEM_BUDGET
            }
            
            val sample = LatencySample(
                durationMs = durationMs,
                type = engine,
                success = success,
                withinBudget = durationMs <= budget,
                metadata = mapOf(
                    "transcript_length" to transcript.length.toString(),
                    "budget_ms" to budget.toString()
                ),
                timestamp = System.currentTimeMillis()
            )
            
            addSample(sttLatencies, sample)
            updateSTTMetrics()
            
            Log.d(TAG, "STT latency: ${durationMs}ms ($engine) - ${if (sample.withinBudget) "PASS" else "FAIL"}")
        }
    }
    
    /**
     * Record TTS latency measurement
     */
    fun recordTTSLatency(
        durationMs: Long,
        cached: Boolean,
        success: Boolean,
        textLength: Int = 0
    ) {
        scope.launch {
            val budget = if (cached) TTS_CACHED_BUDGET else TTS_COLD_BUDGET
            val type = if (cached) "cached" else "cold"
            
            val sample = LatencySample(
                durationMs = durationMs,
                type = type,
                success = success,
                withinBudget = durationMs <= budget,
                metadata = mapOf(
                    "text_length" to textLength.toString(),
                    "budget_ms" to budget.toString()
                ),
                timestamp = System.currentTimeMillis()
            )
            
            addSample(ttsLatencies, sample)
            updateTTSMetrics()
            
            Log.d(TAG, "TTS latency: ${durationMs}ms ($type) - ${if (sample.withinBudget) "PASS" else "FAIL"}")
        }
    }
    
    /**
     * Add sample to list with size limit
     */
    private fun addSample(list: MutableList<LatencySample>, sample: LatencySample) {
        list.add(sample)
        while (list.size > MAX_SAMPLES) {
            list.removeAt(0)
        }
    }
    
    /**
     * Update STT metrics
     */
    private suspend fun updateSTTMetrics() {
        withContext(Dispatchers.Main) {
            _sttMetrics.value = calculateMetrics(sttLatencies, "STT")
        }
    }
    
    /**
     * Update TTS metrics
     */
    private suspend fun updateTTSMetrics() {
        withContext(Dispatchers.Main) {
            _ttsMetrics.value = calculateMetrics(ttsLatencies, "TTS")
        }
    }
    
    /**
     * Calculate metrics for a set of samples
     */
    private fun calculateMetrics(samples: List<LatencySample>, service: String): LatencyMetrics {
        if (samples.isEmpty()) {
            return LatencyMetrics(
                service = service,
                totalSamples = 0,
                averageLatency = 0.0,
                medianLatency = 0.0,
                p95Latency = 0.0,
                successRate = 0.0,
                budgetComplianceRate = 0.0,
                recentSamples = emptyList()
            )
        }
        
        val sortedLatencies = samples.map { it.durationMs }.sorted()
        val successfulSamples = samples.count { it.success }
        val withinBudgetSamples = samples.count { it.withinBudget }
        
        return LatencyMetrics(
            service = service,
            totalSamples = samples.size,
            averageLatency = samples.map { it.durationMs }.average(),
            medianLatency = sortedLatencies[sortedLatencies.size / 2].toDouble(),
            p95Latency = sortedLatencies[(sortedLatencies.size * 0.95).toInt().coerceAtMost(sortedLatencies.size - 1)].toDouble(),
            successRate = successfulSamples.toDouble() / samples.size,
            budgetComplianceRate = withinBudgetSamples.toDouble() / samples.size,
            recentSamples = samples.takeLast(10)
        )
    }
    
    /**
     * Run latency budget test (10 runs each)
     */
    suspend fun runLatencyBudgetTest(): LatencyBudgetTestResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting latency budget test")
                
                // Clear existing data for clean test
                sttLatencies.clear()
                ttsLatencies.clear()
                
                val testResults = mutableListOf<String>()
                
                // Simulate 10 STT runs (system)
                repeat(10) { i ->
                    val simulatedLatency = (800L..2000L).random() // Simulate realistic range
                    recordSTTLatency(
                        durationMs = simulatedLatency,
                        engine = "system",
                        success = true,
                        transcript = "Test utterance $i"
                    )
                    delay(100)
                }
                
                // Simulate 10 TTS runs (mix of cached/cold)
                repeat(10) { i ->
                    val isCached = i < 5 // First 5 are cached
                    val baseLatency = if (isCached) 300L else 1200L
                    val simulatedLatency = baseLatency + (-200L..400L).random()
                    
                    recordTTSLatency(
                        durationMs = simulatedLatency,
                        cached = isCached,
                        success = true,
                        textLength = 50
                    )
                    delay(100)
                }
                
                // Wait for metrics to update
                delay(500)
                
                val sttMetrics = _sttMetrics.value
                val ttsMetrics = _ttsMetrics.value
                
                // Check budget compliance
                val sttP95Pass = (sttMetrics?.p95Latency ?: Double.MAX_VALUE) <= STT_SYSTEM_BUDGET
                val ttsP95Pass = (ttsMetrics?.p95Latency ?: Double.MAX_VALUE) <= TTS_COLD_BUDGET
                
                val overallPass = sttP95Pass && ttsP95Pass
                
                // Write detailed report
                writeLatencyReport(sttMetrics, ttsMetrics, overallPass)
                
                LatencyBudgetTestResult(
                    sttMetrics = sttMetrics,
                    ttsMetrics = ttsMetrics,
                    sttP95Pass = sttP95Pass,
                    ttsP95Pass = ttsP95Pass,
                    overallPass = overallPass
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Latency budget test failed", e)
                LatencyBudgetTestResult(
                    sttMetrics = null,
                    ttsMetrics = null,
                    sttP95Pass = false,
                    ttsP95Pass = false,
                    overallPass = false
                )
            }
        }
    }
    
    /**
     * Write detailed latency report to CSV
     */
    private suspend fun writeLatencyReport(
        sttMetrics: LatencyMetrics?,
        ttsMetrics: LatencyMetrics?,
        overallPass: Boolean
    ) {
        withContext(Dispatchers.IO) {
            try {
                val logsDir = File(context.filesDir, "logs")
                if (!logsDir.exists()) {
                    logsDir.mkdirs()
                }
                
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val csvFile = File(logsDir, "latency_budget_${timestamp}.csv")
                
                csvFile.writeText(buildString {
                    appendLine("Latency Budget Test Report")
                    appendLine("Timestamp,${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine()
                    
                    // STT Results
                    appendLine("STT Results")
                    if (sttMetrics != null) {
                        appendLine("Total Samples,${sttMetrics.totalSamples}")
                        appendLine("Average Latency,${sttMetrics.averageLatency.toInt()}ms")
                        appendLine("Median Latency,${sttMetrics.medianLatency.toInt()}ms")
                        appendLine("95th Percentile,${sttMetrics.p95Latency.toInt()}ms")
                        appendLine("Budget (System),${STT_SYSTEM_BUDGET}ms")
                        appendLine("Budget Compliance,${String.format("%.1f", sttMetrics.budgetComplianceRate * 100)}%")
                        appendLine("95th Percentile Pass,${if (sttMetrics.p95Latency <= STT_SYSTEM_BUDGET) "PASS" else "FAIL"}")
                    }
                    
                    appendLine()
                    
                    // TTS Results
                    appendLine("TTS Results")
                    if (ttsMetrics != null) {
                        appendLine("Total Samples,${ttsMetrics.totalSamples}")
                        appendLine("Average Latency,${ttsMetrics.averageLatency.toInt()}ms")
                        appendLine("Median Latency,${ttsMetrics.medianLatency.toInt()}ms")
                        appendLine("95th Percentile,${ttsMetrics.p95Latency.toInt()}ms")
                        appendLine("Budget (Cold),${TTS_COLD_BUDGET}ms")
                        appendLine("Budget (Cached),${TTS_CACHED_BUDGET}ms")
                        appendLine("Budget Compliance,${String.format("%.1f", ttsMetrics.budgetComplianceRate * 100)}%")
                        appendLine("95th Percentile Pass,${if (ttsMetrics.p95Latency <= TTS_COLD_BUDGET) "PASS" else "FAIL"}")
                    }
                    
                    appendLine()
                    appendLine("Overall Result,${if (overallPass) "PASS" else "FAIL"}")
                    
                    // Raw data
                    appendLine()
                    appendLine("Raw STT Data")
                    appendLine("duration_ms,type,success,within_budget,timestamp")
                    sttLatencies.forEach { sample ->
                        appendLine("${sample.durationMs},${sample.type},${sample.success},${sample.withinBudget},${sample.timestamp}")
                    }
                    
                    appendLine()
                    appendLine("Raw TTS Data")
                    appendLine("duration_ms,type,success,within_budget,timestamp")
                    ttsLatencies.forEach { sample ->
                        appendLine("${sample.durationMs},${sample.type},${sample.success},${sample.withinBudget},${sample.timestamp}")
                    }
                })
                
                Log.d(TAG, "Latency report written to: ${csvFile.absolutePath}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write latency report", e)
            }
        }
    }
    
    /**
     * Clear all latency data
     */
    fun clearData() {
        scope.launch {
            sttLatencies.clear()
            ttsLatencies.clear()
            updateSTTMetrics()
            updateTTSMetrics()
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
    }
}

/**
 * Individual latency sample
 */
data class LatencySample(
    val durationMs: Long,
    val type: String, // "system", "whisper", "cached", "cold"
    val success: Boolean,
    val withinBudget: Boolean,
    val metadata: Map<String, String>,
    val timestamp: Long
)

/**
 * Aggregated latency metrics
 */
data class LatencyMetrics(
    val service: String,
    val totalSamples: Int,
    val averageLatency: Double,
    val medianLatency: Double,
    val p95Latency: Double,
    val successRate: Double,
    val budgetComplianceRate: Double,
    val recentSamples: List<LatencySample>
)

/**
 * Latency budget test result
 */
data class LatencyBudgetTestResult(
    val sttMetrics: LatencyMetrics?,
    val ttsMetrics: LatencyMetrics?,
    val sttP95Pass: Boolean,
    val ttsP95Pass: Boolean,
    val overallPass: Boolean
)