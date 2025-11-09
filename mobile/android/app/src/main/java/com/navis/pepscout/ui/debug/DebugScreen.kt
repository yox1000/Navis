package com.navis.pepscout.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navis.pepscout.debug.DebugLogger
import java.text.SimpleDateFormat
import java.util.*

/**
 * Debug screen showing recent safety events and telemetry data
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    debugLogger: DebugLogger,
    onBack: () -> Unit
) {
    val hazardEvents by debugLogger.hazardEvents.collectAsState()
    val freeSpaceEvents by debugLogger.freeSpaceEvents.collectAsState()
    val wallEvents by debugLogger.wallEvents.collectAsState()
    val voiceEvents by debugLogger.voiceEvents.collectAsState()
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Hazards", "Free Space", "Walls", "Voice", "Stats")
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Debug Console",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
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
            0 -> HazardEventsTab(hazardEvents)
            1 -> FreeSpaceEventsTab(freeSpaceEvents)
            2 -> WallEventsTab(wallEvents)
            3 -> VoiceEventsTab(voiceEvents)
            4 -> StatsTab(debugLogger)
        }
    }
}

@Composable
private fun HazardEventsTab(events: List<DebugLogger.DebugHazardEvent>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Recent Hazard Events (${events.size}/20)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        items(events) { event ->
            DebugEventCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = event.label,
                            fontWeight = FontWeight.Bold,
                            color = getSeverityColor(event.severity)
                        )
                        Text(
                            text = formatTimestamp(event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    
                    Text(
                        text = "Kind: ${event.kind} | Severity: ${event.severity}",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Text(
                        text = "ID: ${event.id} | Confidence: ${"%.2f".format(event.confidence)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FreeSpaceEventsTab(events: List<DebugLogger.DebugFreeSpaceEvent>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Recent Free Space Vectors (${events.size}/10)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        items(events) { event ->
            DebugEventCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Angle: ${"%.1f".format(event.angleDeg)}°",
                            fontWeight = FontWeight.Bold,
                            color = getFreeSpaceColor(event.angleDeg)
                        )
                        Text(
                            text = formatTimestamp(event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    
                    Text(
                        text = "Confidence: ${"%.2f".format(event.confidence)}",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    if (event.binDistribution.isNotEmpty()) {
                        Text(
                            text = "Bins: ${event.binDistribution.joinToString(" ") { "%.2f".format(it) }}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WallEventsTab(events: List<DebugLogger.DebugWallEvent>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Recent Wall Detection (${events.size}/10)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        items(events) { event ->
            DebugEventCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (event.detected) "WALL DETECTED" else "No Wall",
                            fontWeight = FontWeight.Bold,
                            color = if (event.detected) Color.Red else Color.Green
                        )
                        Text(
                            text = formatTimestamp(event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    
                    Text(
                        text = "Edge Density: ${"%.3f".format(event.edgeDensity)}",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Text(
                        text = "FOE Confidence: ${"%.2f".format(event.foeConfidence)}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceEventsTab(events: List<DebugLogger.DebugVoiceEvent>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "Recent Voice Events (${events.size}/10)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        
        items(events) { event ->
            DebugEventCard {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = event.action.uppercase(),
                            fontWeight = FontWeight.Bold,
                            color = getPriorityColor(event.priority)
                        )
                        Text(
                            text = formatTimestamp(event.timestamp),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    
                    Text(
                        text = "Priority: ${event.priority}",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    Text(
                        text = event.text,
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsTab(debugLogger: DebugLogger) {
    val stats = remember { debugLogger.getSessionStats() }
    
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Session Statistics",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatRow("Session Duration", formatDuration(stats.sessionDuration))
                StatRow("Hazard Events", stats.totalHazardEvents.toString())
                StatRow("Free Space Events", stats.totalFreeSpaceEvents.toString())
                StatRow("Wall Events", stats.totalWallEvents.toString())
                StatRow("Voice Events", stats.totalVoiceEvents.toString())
                
                if (stats.sessionFilePath != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Log File:",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = stats.sessionFilePath,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun DebugEventCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Box(
            modifier = Modifier.padding(12.dp)
        ) {
            content()
        }
    }
}

private fun getSeverityColor(severity: String): Color {
    return when (severity.lowercase()) {
        "danger" -> Color.Red
        "warn" -> Color(0xFFFF9800) // Orange
        "info" -> Color.Blue
        else -> Color.Gray
    }
}

private fun getFreeSpaceColor(angle: Double): Color {
    return when {
        angle > 15.0 -> Color(0xFF4CAF50) // Green - clear right
        angle < -15.0 -> Color(0xFF4CAF50) // Green - clear left
        else -> Color(0xFFFF9800) // Orange - narrow path
    }
}

private fun getPriorityColor(priority: String): Color {
    return when (priority.lowercase()) {
        "urgent" -> Color.Red
        "normal" -> Color.Blue
        "low" -> Color.Gray
        else -> Color.Black
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    return sdf.format(Date(timestamp))
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    
    return when {
        hours > 0 -> "${hours}h ${minutes % 60}m ${seconds % 60}s"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}