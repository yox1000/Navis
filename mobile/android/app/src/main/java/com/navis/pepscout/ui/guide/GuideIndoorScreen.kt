package com.navis.pepscout.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.navis.pepscout.mappedin.*
import com.navis.pepscout.nav.RouteGuidance
import com.navis.pepscout.nav.SafetyFusionState
import kotlin.math.max
import kotlin.math.min

/**
 * Indoor navigation screen with Mappedin map view
 * Shows floor plan, route, position, and navigation guidance
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideIndoorScreen(
    currentMap: MappedinMap?,
    currentPosition: MappedinLocator.Position?,
    currentRoute: MappedinRoute?,
    routeGuidance: RouteGuidance?,
    safetyState: SafetyFusionState,
    onSelectDestination: () -> Unit,
    onStopNavigation: () -> Unit,
    onBack: () -> Unit
) {
    val density = LocalDensity.current
    
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header
        TopAppBar(
            title = {
                Text(
                    text = currentMap?.name ?: "Indoor Navigation",
                    fontWeight = FontWeight.Bold
                )
            },
            navigationIcon = {
                TextButton(onClick = onBack) {
                    Text("Back")
                }
            },
            actions = {
                if (currentRoute != null) {
                    TextButton(onClick = onStopNavigation) {
                        Text("Stop", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(onClick = onSelectDestination) {
                        Text("Navigate")
                    }
                }
            }
        )
        
        // Safety status banner
        if (safetyState != SafetyFusionState.Clear && safetyState != SafetyFusionState.Navigating) {
            SafetyBanner(safetyState)
        }
        
        // Map view
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                if (currentMap != null) {
                    MapView(
                        map = currentMap,
                        position = currentPosition,
                        route = currentRoute,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Loading or no map
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Loading map...")
                        }
                    }
                }
                
                // Floor indicator
                if (currentMap != null) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        )
                    ) {
                        Text(
                            text = "Floor ${currentMap.floor}",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                // Position confidence indicator
                if (currentPosition != null) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = getConfidenceColor(currentPosition.confidence).copy(alpha = 0.9f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(getConfidenceColor(currentPosition.confidence))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = currentPosition.method,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
        
        // Navigation guidance
        if (routeGuidance != null) {
            NavigationGuidanceCard(routeGuidance)
        }
    }
}

@Composable
private fun SafetyBanner(safetyState: SafetyFusionState) {
    val (message, color) = when (safetyState) {
        SafetyFusionState.ObstacleDetected -> "Obstacle detected" to MaterialTheme.colorScheme.error
        SafetyFusionState.GuidingAroundObstacle -> "Guiding around obstacle" to MaterialTheme.colorScheme.tertiary
        SafetyFusionState.WallDetected -> "Wall ahead - stop" to MaterialTheme.colorScheme.error
        SafetyFusionState.DetourActive -> "Taking alternate route" to MaterialTheme.colorScheme.primary
        SafetyFusionState.NoDetourAvailable -> "Path blocked - please wait" to MaterialTheme.colorScheme.error
        SafetyFusionState.Completed -> "Navigation completed" to MaterialTheme.colorScheme.primary
        else -> "" to Color.Transparent
    }
    
    if (message.isNotEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
        ) {
            Text(
                text = message,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                textAlign = TextAlign.Center,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MapView(
    map: MappedinMap,
    position: MappedinLocator.Position?,
    route: MappedinRoute?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        
        // Calculate map bounds and scaling
        val nodes = map.nodes
        if (nodes.isEmpty()) return@Canvas
        
        val minX = nodes.minOf { it.x }
        val maxX = nodes.maxOf { it.x }
        val minY = nodes.minOf { it.y }
        val maxY = nodes.maxOf { it.y }
        
        val mapWidth = maxX - minX
        val mapHeight = maxY - minY
        
        val scaleX = canvasWidth / mapWidth.toFloat()
        val scaleY = canvasHeight / mapHeight.toFloat()
        val scale = min(scaleX, scaleY) * 0.8f // Add some padding
        
        val offsetX = (canvasWidth - mapWidth * scale) / 2f - minX * scale
        val offsetY = (canvasHeight - mapHeight * scale) / 2f - minY * scale
        
        // Helper function to convert map coordinates to canvas coordinates
        fun mapToCanvas(x: Double, y: Double): Offset {
            return Offset(
                x = (x * scale + offsetX).toFloat(),
                y = (y * scale + offsetY).toFloat()
            )
        }
        
        // Draw edges (hallways/connections)
        map.edges.forEach { edge ->
            val fromNode = nodes.find { it.id == edge.fromNodeId }
            val toNode = nodes.find { it.id == edge.toNodeId }
            
            if (fromNode != null && toNode != null) {
                val from = mapToCanvas(fromNode.x, fromNode.y)
                val to = mapToCanvas(toNode.x, toNode.y)
                
                drawLine(
                    color = Color.Gray,
                    start = from,
                    end = to,
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
        
        // Draw route polyline
        if (route != null && route.geometry.isNotEmpty()) {
            val routePath = Path()
            route.geometry.forEachIndexed { index, coord ->
                val point = mapToCanvas(coord.x, coord.y)
                if (index == 0) {
                    routePath.moveTo(point.x, point.y)
                } else {
                    routePath.lineTo(point.x, point.y)
                }
            }
            
            drawPath(
                path = routePath,
                color = Color.Blue,
                style = Stroke(width = 4.dp.toPx())
            )
        }
        
        // Draw nodes
        nodes.forEach { node ->
            val center = mapToCanvas(node.x, node.y)
            
            // Node background
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = center
            )
            
            // Node border
            drawCircle(
                color = Color.DarkGray,
                radius = 8.dp.toPx(),
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            
            // Highlight special nodes
            when {
                node.name.contains("Elevator", ignoreCase = true) -> {
                    drawCircle(
                        color = Color.Magenta,
                        radius = 6.dp.toPx(),
                        center = center
                    )
                }
                node.name.contains("Entrance", ignoreCase = true) -> {
                    drawCircle(
                        color = Color.Green,
                        radius = 6.dp.toPx(),
                        center = center
                    )
                }
                else -> {
                    drawCircle(
                        color = Color.LightGray,
                        radius = 4.dp.toPx(),
                        center = center
                    )
                }
            }
        }
        
        // Draw current position
        if (position != null) {
            val posCenter = mapToCanvas(position.x, position.y)
            
            // Position accuracy circle
            drawCircle(
                color = Color.Blue.copy(alpha = 0.2f),
                radius = 20.dp.toPx(),
                center = posCenter
            )
            
            // Position marker
            drawCircle(
                color = Color.Blue,
                radius = 8.dp.toPx(),
                center = posCenter
            )
            
            // Position center dot
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = posCenter
            )
        }
        
        // Draw route step indicator
        if (route != null && route.steps.isNotEmpty() && position != null) {
            // Find current step target
            val currentStep = route.steps.firstOrNull()
            if (currentStep != null) {
                val targetNode = nodes.find { it.id == currentStep.nodeId }
                if (targetNode != null) {
                    val targetCenter = mapToCanvas(targetNode.x, targetNode.y)
                    
                    // Pulsing target indicator
                    drawCircle(
                        color = Color.Red.copy(alpha = 0.5f),
                        radius = 12.dp.toPx(),
                        center = targetCenter
                    )
                    
                    drawCircle(
                        color = Color.Red,
                        radius = 8.dp.toPx(),
                        center = targetCenter,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationGuidanceCard(guidance: RouteGuidance) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Progress bar
            if (!guidance.isComplete) {
                LinearProgressIndicator(
                    progress = guidance.stepIndex.toFloat() / guidance.totalSteps,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Step ${guidance.stepIndex + 1} of ${guidance.totalSteps}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Step description
            Text(
                text = guidance.stepDescription,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            
            if (!guidance.isComplete && guidance.remainingDistance > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${guidance.remainingDistance.toInt()}m remaining",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    
                    Text(
                        text = "${(guidance.estimatedTimeRemaining / 60).toInt()}min",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

private fun getConfidenceColor(confidence: Float): Color {
    return when {
        confidence >= 0.8f -> Color.Green
        confidence >= 0.5f -> Color(0xFFFF9800) // Orange
        else -> Color.Red
    }
}