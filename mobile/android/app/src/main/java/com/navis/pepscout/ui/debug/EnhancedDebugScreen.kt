package com.navis.pepscout.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.pepscout.debug.DebugLogger
import com.navis.pepscout.debug.SafetyCVTestRunner
import com.navis.pepscout.nav.SafetyFusionState
import com.navis.pepscout.util.Events
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced debug screen with test harness features
 * Supports live camera, video replay, synthetic injection, and export
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedDebugScreen(
    debugLogger: DebugLogger,
    currentSafetyState: SafetyFusionState = SafetyFusionState.Clear,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Event data
    val hazardEvents by debugLogger.hazardEvents.collectAsState()
    val freeSpaceEvents by debugLogger.freeSpaceEvents.collectAsState()
    val wallEvents by debugLogger.wallEvents.collectAsState()
    val voiceEvents by debugLogger.voiceEvents.collectAsState()
    
    // Test harness state
    var selectedTab by remember { mutableIntStateOf(0) }
    var inputMode by remember { mutableStateOf(InputMode.LIVE_CAMERA) }
    var showOverlays by remember { mutableStateOf(true) }
    var fps by remember { mutableFloatStateOf(0f) }
    var isRecording by remember { mutableStateOf(false) }
    
    val tabs = listOf("Events", "Test Harness", "Auto Tests", "Overlays", "Injection", "Export")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with FPS and state
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Debug Console",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Row {
                    Text(
                        text = "FPS: ${fps.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Text(
                        text = "State: ${currentSafetyState.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = getSafetyStateColor(currentSafetyState)
                    )
                }
            }
            
            Row {
                Button(
                    onClick = { debugLogger.clearEventBuffers() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear")
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Button(onClick = onBack) {
                    Text("Back")
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Status indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip("Events: ${hazardEvents.size + freeSpaceEvents.size + wallEvents.size + voiceEvents.size}")
            StatusChip("Input: ${inputMode.displayName}")
            if (showOverlays) StatusChip("Overlays: ON", Color.Green)
            if (isRecording) StatusChip("Recording", Color.Red)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Tabs
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Content based on selected tab
        when (selectedTab) {
            0 -> EventsTab(hazardEvents, freeSpaceEvents, wallEvents, voiceEvents)
            1 -> TestHarnessTab(
                inputMode = inputMode,
                onInputModeChange = { inputMode = it },
                fps = fps,
                onFpsUpdate = { fps = it },
                isRecording = isRecording,
                onRecordingToggle = { isRecording = !isRecording }
            )
            2 -> AutoTestsTab(context)
            3 -> OverlaysTab(
                showOverlays = showOverlays,
                onToggleOverlays = { showOverlays = !showOverlays },
                hazardEvents = hazardEvents,
                freeSpaceEvents = freeSpaceEvents
            )
            4 -> InjectionTab()
            5 -> ExportTab(debugLogger)
        }
    }
}

@Composable
private fun StatusChip(
    text: String,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer
) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun EventsTab(
    hazardEvents: List<DebugLogger.DebugHazardEvent>,
    freeSpaceEvents: List<DebugLogger.DebugFreeSpaceEvent>,
    wallEvents: List<DebugLogger.DebugWallEvent>,
    voiceEvents: List<DebugLogger.DebugVoiceEvent>
) {
    // Combine and sort all events by timestamp
    val allEvents = (
        hazardEvents.map { EventWrapper.Hazard(it) } +
        freeSpaceEvents.map { EventWrapper.FreeSpace(it) } +
        wallEvents.map { EventWrapper.Wall(it) } +
        voiceEvents.map { EventWrapper.Voice(it) }
    ).sortedByDescending { it.timestamp }
    
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Recent Events (${allEvents.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        items(allEvents.take(50)) { event ->
            EventCard(event)
        }
    }
}

@Composable
private fun TestHarnessTab(
    inputMode: InputMode,
    onInputModeChange: (InputMode) -> Unit,
    fps: Float,
    onFpsUpdate: (Float) -> Unit,
    isRecording: Boolean,
    onRecordingToggle: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Input Mode",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        // Input mode selector
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InputMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = inputMode == mode,
                            onClick = { onInputModeChange(mode) }
                        )
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        Column {
                            Text(
                                text = mode.displayName,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }
        
        // Control buttons
        Text(
            text = "Controls",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { /* Start CV */ },
                modifier = Modifier.weight(1f)
            ) {
                Text("Start CV")
            }
            
            Button(
                onClick = { /* Stop CV */ },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Stop CV")
            }
        }
        
        // Recording control
        Button(
            onClick = onRecordingToggle,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(if (isRecording) "Stop Recording" else "Start Recording")
        }
        
        // FPS monitor
        Text(
            text = "Performance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Card {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("FPS: ${fps.toInt()}")
                LinearProgressIndicator(
                    progress = (fps / 30f).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AutoTestsTab(context: Context) {
    val testRunner = remember { SafetyCVTestRunner(context) }
    val testResults by testRunner.testResults.collectAsState()
    val isRunning by testRunner.isRunning.collectAsState()
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Safety CV Automated Tests",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Button(
                onClick = { testRunner.runAllTests() },
                enabled = !isRunning
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isRunning) "Running..." else "Run All Tests")
            }
        }
        
        Text(
            text = "Automated pass/fail tests for safety system behavior",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        // Test descriptions
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Test Scenarios:", fontWeight = FontWeight.Bold)
                TestDescription("1. Couch-in-path", "Hazard then side hint, clears within 1.2s")
                TestDescription("2. Wall-at-1m", "Single 'Stop. Wall ahead.', 2s deadman")
                TestDescription("3. Empty corridor", "Zero hazards, free-space near 0°")
                TestDescription("4. Persistent block", "After 2s, one-edge detour indoors")
            }
        }
        
        // Test results
        if (testResults.isNotEmpty()) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Test Results",
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    testResults.forEach { result ->
                        TestResultCard(result)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Overall summary
                    val passedCount = testResults.count { it.passed }
                    val totalCount = testResults.size
                    
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Overall Result:",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$passedCount/$totalCount PASSED",
                            color = if (passedCount == totalCount) Color.Green else Color.Red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
    
    // Cleanup on dispose
    DisposableEffect(testRunner) {
        onDispose {
            testRunner.cleanup()
        }
    }
}

@Composable
private fun TestDescription(title: String, description: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}

@Composable 
private fun TestResultCard(result: com.navis.pepscout.debug.SafetyTestResult) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (result.passed) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = result.testName,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (result.passed) "PASS" else "FAIL",
                    color = if (result.passed) Color.Green else Color.Red,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            
            if (result.metrics.isNotEmpty()) {
                Text(
                    text = "Metrics: ${result.metrics.entries.joinToString(", ") { "${it.key}=${it.value}" }}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun OverlaysTab(
    showOverlays: Boolean,
    onToggleOverlays: () -> Unit,
    hazardEvents: List<DebugLogger.DebugHazardEvent>,
    freeSpaceEvents: List<DebugLogger.DebugFreeSpaceEvent>
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Overlay controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Show Overlays",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Switch(
                checked = showOverlays,
                onCheckedChange = { onToggleOverlays() }
            )
        }
        
        if (showOverlays) {
            // Visual representation of detections
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Detection Overlay",
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Mock camera view with overlays
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(Color.Black)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawDetectionOverlay(
                                hazardEvents = hazardEvents,
                                freeSpaceEvents = freeSpaceEvents
                            )
                        }
                        
                        // Overlay labels
                        Text(
                            text = "Camera Feed with CV Overlays",
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            // Legend
            Card {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Overlay Legend",
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    LegendItem("🔴 Bounding Boxes", "Object detections")
                    LegendItem("🔵 Forward Cone", "20° heading cone")
                    LegendItem("📊 Free-space Bins", "7-bin distribution")
                    LegendItem("➡️ Free-space Arrow", "Recommended direction")
                    LegendItem("🧱 Wall Badge", "Wall proximity indicator")
                }
            }
        }
    }
}

@Composable
private fun InjectionTab() {
    val scope = rememberCoroutineScope()
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Synthetic Event Injection",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Inject test events to verify safety system behavior",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        // Hazard injection
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Inject Hazard", fontWeight = FontWeight.Bold)
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                injectHazardEvent("person", "danger")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Person")
                    }
                    
                    Button(
                        onClick = {
                            scope.launch {
                                injectHazardEvent("chair", "warn")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Chair")
                    }
                }
            }
        }
        
        // Free-space injection
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Inject Free-space", fontWeight = FontWeight.Bold)
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                injectFreeSpaceEvent(-15.0, 0.8f)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Left Clear")
                    }
                    
                    Button(
                        onClick = {
                            scope.launch {
                                injectFreeSpaceEvent(15.0, 0.8f)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Right Clear")
                    }
                }
            }
        }
        
        // Wall injection
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Inject Wall", fontWeight = FontWeight.Bold)
                
                Button(
                    onClick = {
                        scope.launch {
                            injectWallEvent(true, 0.8f, 0.9f)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Wall at 1.0m")
                }
            }
        }
    }
}

@Composable
private fun ExportTab(debugLogger: DebugLogger) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Export & Analysis",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        // Session stats
        val stats = remember { debugLogger.getSessionStats() }
        
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Session Statistics", fontWeight = FontWeight.Bold)
                
                StatRow("Duration", formatDuration(stats.sessionDuration))
                StatRow("Hazard Events", stats.totalHazardEvents.toString())
                StatRow("Free Space Events", stats.totalFreeSpaceEvents.toString())
                StatRow("Wall Events", stats.totalWallEvents.toString())
                StatRow("Voice Events", stats.totalVoiceEvents.toString())
            }
        }
        
        // Export options
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Export Options", fontWeight = FontWeight.Bold)
                
                Button(
                    onClick = {
                        scope.launch {
                            exportLogsZip(context, debugLogger)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export Logs ZIP")
                }
                
                if (stats.sessionFilePath != null) {
                    Text(
                        text = "Current log: ${stats.sessionFilePath}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

// Helper functions and data classes

private fun DrawScope.drawDetectionOverlay(
    hazardEvents: List<DebugLogger.DebugHazardEvent>,
    freeSpaceEvents: List<DebugLogger.DebugFreeSpaceEvent>
) {
    val width = size.width
    val height = size.height
    
    // Draw forward cone (20 degrees)
    val coneAngle = 20.0
    val coneRadius = height * 0.3f
    val centerX = width / 2f
    val bottomY = height
    
    drawLine(
        color = Color.Blue,
        start = Offset(centerX, bottomY),
        end = Offset(centerX - coneRadius * 0.3f, bottomY - coneRadius),
        strokeWidth = 2.dp.toPx()
    )
    
    drawLine(
        color = Color.Blue,
        start = Offset(centerX, bottomY),
        end = Offset(centerX + coneRadius * 0.3f, bottomY - coneRadius),
        strokeWidth = 2.dp.toPx()
    )
    
    // Draw free-space bins
    val binWidth = width / 7f
    freeSpaceEvents.lastOrNull()?.binDistribution?.forEachIndexed { index, score ->
        val alpha = score.coerceIn(0f, 1f)
        val x = index * binWidth
        
        drawRect(
            color = Color.Green.copy(alpha = alpha),
            topLeft = Offset(x, height * 0.8f),
            size = androidx.compose.ui.geometry.Size(binWidth, height * 0.2f)
        )
    }
    
    // Draw recent hazards as bounding boxes
    hazardEvents.take(3).forEachIndexed { index, event ->
        val boxSize = when (event.severity) {
            "danger" -> 80.dp.toPx()
            "warn" -> 60.dp.toPx()
            else -> 40.dp.toPx()
        }
        
        val x = width * (0.2f + index * 0.3f) - boxSize / 2f
        val y = height * 0.4f - boxSize / 2f
        
        drawRect(
            color = when (event.severity) {
                "danger" -> Color.Red
                "warn" -> Color.Yellow
                else -> Color.Gray
            },
            topLeft = Offset(x, y),
            size = androidx.compose.ui.geometry.Size(boxSize, boxSize),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
        )
    }
}

@Composable
private fun LegendItem(icon: String, description: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

private suspend fun injectHazardEvent(label: String, severity: String) {
    Events.emit(Events.HazardEvent(
        id = "test_${System.currentTimeMillis()}",
        where = Events.HazardEvent.Where("indoor", "test_building", "test_node"),
        geo = null,
        kind = "obstacle",
        label = label,
        severity = severity,
        confidence = 0.9f,
        ttlSeconds = 3
    ))
}

private suspend fun injectFreeSpaceEvent(angle: Double, confidence: Float) {
    Events.emit(Events.FreeSpaceEvent(
        angleDeg = angle,
        confidence = confidence,
        binDistribution = FloatArray(7) { if (it == 3) confidence else confidence * 0.5f }
    ))
}

private suspend fun injectWallEvent(detected: Boolean, edgeDensity: Float, foeConfidence: Float) {
    Events.emit(Events.WallEvent(
        detected = detected,
        edgeDensity = edgeDensity,
        foeConfidence = foeConfidence,
        distanceM = 1.0
    ))
}

private suspend fun exportLogsZip(context: android.content.Context, debugLogger: DebugLogger) {
    // TODO: Implement ZIP export functionality
    // This would package CSV logs, event data, and session metadata
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private fun getSafetyStateColor(state: SafetyFusionState): Color {
    return when (state) {
        SafetyFusionState.Clear, SafetyFusionState.Navigating -> Color.Green
        SafetyFusionState.GuidingAroundObstacle, SafetyFusionState.DetourActive -> Color(0xFFFF9800)
        SafetyFusionState.ObstacleDetected, SafetyFusionState.WallDetected, SafetyFusionState.NoDetourAvailable -> Color.Red
        SafetyFusionState.Completed -> Color.Blue
    }
}

// Enums and data classes

enum class InputMode(val displayName: String, val description: String) {
    LIVE_CAMERA("Live Camera", "Real-time camera feed with CV processing"),
    VIDEO_REPLAY("Video Replay", "Replay from assets/CouchCenter.mp4"),
    SYNTHETIC_INJECTION("Synthetic", "Manual event injection for testing")
}

sealed class EventWrapper(val timestamp: Long) {
    data class Hazard(val event: DebugLogger.DebugHazardEvent) : EventWrapper(event.timestamp)
    data class FreeSpace(val event: DebugLogger.DebugFreeSpaceEvent) : EventWrapper(event.timestamp)
    data class Wall(val event: DebugLogger.DebugWallEvent) : EventWrapper(event.timestamp)
    data class Voice(val event: DebugLogger.DebugVoiceEvent) : EventWrapper(event.timestamp)
}

@Composable
private fun EventCard(event: EventWrapper) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            when (event) {
                is EventWrapper.Hazard -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🚨 ${event.event.label}",
                            fontWeight = FontWeight.Bold,
                            color = when (event.event.severity) {
                                "danger" -> Color.Red
                                "warn" -> Color(0xFFFF9800)
                                else -> Color.Gray
                            }
                        )
                        Text(
                            text = formatTimestamp(event.event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                is EventWrapper.FreeSpace -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "📊 Free-space ${event.event.angleDeg.toInt()}°",
                            fontWeight = FontWeight.Bold,
                            color = Color.Blue
                        )
                        Text(
                            text = formatTimestamp(event.event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                is EventWrapper.Wall -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (event.event.detected) "🧱 Wall detected" else "👁️ No wall",
                            fontWeight = FontWeight.Bold,
                            color = if (event.event.detected) Color.Red else Color.Green
                        )
                        Text(
                            text = formatTimestamp(event.event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                is EventWrapper.Voice -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🔊 ${event.event.action}",
                            fontWeight = FontWeight.Bold,
                            color = when (event.event.priority) {
                                "urgent" -> Color.Red
                                "normal" -> Color.Blue
                                else -> Color.Gray
                            }
                        )
                        Text(
                            text = formatTimestamp(event.event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Text(
                        text = event.event.text,
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    return sdf.format(Date(timestamp))
}