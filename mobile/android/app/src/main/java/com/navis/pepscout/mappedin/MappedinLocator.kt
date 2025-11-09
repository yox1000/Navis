package com.navis.pepscout.mappedin

import android.util.Log
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mappedin localization service
 * Handles position tracking and map matching for indoor navigation
 */
class MappedinLocator(private val router: MappedinRouter) {
    
    companion object {
        private const val TAG = "MappedinLocator"
        private const val SNAP_THRESHOLD_METERS = 5.0 // Snap to node if within 5m
    }
    
    data class Position(
        val nodeId: String?,
        val mapId: String,
        val x: Double,
        val y: Double,
        val confidence: Float = 1.0f,
        val method: String = "unknown" // "qr", "gps", "dead_reckoning", "manual"
    )
    
    private var currentPosition: Position? = null
    private var lastHeading: Double = 0.0
    
    /**
     * Update position from QR code scan
     */
    fun updateFromQrCode(qrPayload: String, qrAnchors: Map<String, QrAnchor>): Position? {
        val anchor = qrAnchors[qrPayload]
        if (anchor == null) {
            Log.w(TAG, "Unknown QR code: $qrPayload")
            return null
        }
        
        val position = Position(
            nodeId = anchor.nodeId,
            mapId = anchor.mapId,
            x = anchor.x,
            y = anchor.y,
            confidence = 1.0f,
            method = "qr"
        )
        
        currentPosition = position
        Log.d(TAG, "Position updated from QR: ${anchor.nodeId} on ${anchor.mapId}")
        return position
    }
    
    /**
     * Update position manually (e.g., user selection)
     */
    fun updateManualPosition(nodeId: String, mapId: String): Position? {
        val map = router.getMap(mapId)
        val node = map?.nodes?.find { it.id == nodeId }
        
        if (node == null) {
            Log.w(TAG, "Node not found: $nodeId on $mapId")
            return null
        }
        
        val position = Position(
            nodeId = nodeId,
            mapId = mapId,
            x = node.x,
            y = node.y,
            confidence = 0.8f,
            method = "manual"
        )
        
        currentPosition = position
        Log.d(TAG, "Position updated manually: $nodeId on $mapId")
        return position
    }
    
    /**
     * Optional GPS-based map matching (for venues with outdoor GPS coordinates)
     */
    fun mapMatching(lat: Double, lon: Double, heading: Double): Position? {
        // For indoor use, this is typically not reliable
        // Most indoor venues don't have accurate GPS mapping
        // This is kept as a stub for potential future use
        
        Log.d(TAG, "GPS map matching not implemented for indoor venues")
        return currentPosition
    }
    
    /**
     * Dead reckoning position update based on movement
     */
    fun updatePositionWithMovement(
        stepCount: Int, 
        heading: Double, 
        stepLength: Double = 0.7 // Average step length in meters
    ): Position? {
        val current = currentPosition ?: return null
        
        if (stepCount == 0) return current
        
        // Calculate displacement
        val distance = stepCount * stepLength
        val headingRad = Math.toRadians(heading)
        
        val deltaX = distance * sin(headingRad)
        val deltaY = distance * cos(headingRad)
        
        val newX = current.x + deltaX
        val newY = current.y + deltaY
        
        // Try to snap to nearest node
        val closestNode = router.findClosestNode(newX, newY, current.mapId)
        val snapDistance = closestNode?.let { node ->
            val dx = node.x - newX
            val dy = node.y - newY
            sqrt(dx * dx + dy * dy)
        }
        
        val position = if (snapDistance != null && snapDistance <= SNAP_THRESHOLD_METERS) {
            // Snap to node
            Position(
                nodeId = closestNode!!.id,
                mapId = current.mapId,
                x = closestNode.x,
                y = closestNode.y,
                confidence = 0.7f,
                method = "dead_reckoning_snapped"
            )
        } else {
            // Free position
            Position(
                nodeId = null,
                mapId = current.mapId,
                x = newX,
                y = newY,
                confidence = 0.5f,
                method = "dead_reckoning"
            )
        }
        
        currentPosition = position
        lastHeading = heading
        
        Log.d(TAG, "Position updated via dead reckoning: (${position.x}, ${position.y})")
        return position
    }
    
    /**
     * Handle floor change (e.g., after taking elevator)
     */
    fun changeFloor(newMapId: String, nodeId: String? = null): Position? {
        val map = router.getMap(newMapId)
        if (map == null) {
            Log.w(TAG, "Map not found: $newMapId")
            return null
        }
        
        // If nodeId specified, use that position
        if (nodeId != null) {
            val node = map.nodes.find { it.id == nodeId }
            if (node != null) {
                val position = Position(
                    nodeId = nodeId,
                    mapId = newMapId,
                    x = node.x,
                    y = node.y,
                    confidence = 0.9f,
                    method = "floor_change"
                )
                currentPosition = position
                Log.d(TAG, "Floor changed to $newMapId at node $nodeId")
                return position
            }
        }
        
        // Try to find equivalent position on new floor (e.g., elevator)
        val current = currentPosition
        if (current != null) {
            val equivalentNode = map.nodes.find { node ->
                node.name.contains("Elevator", ignoreCase = true) || 
                node.name.contains("Stair", ignoreCase = true)
            }
            
            if (equivalentNode != null) {
                val position = Position(
                    nodeId = equivalentNode.id,
                    mapId = newMapId,
                    x = equivalentNode.x,
                    y = equivalentNode.y,
                    confidence = 0.8f,
                    method = "floor_change"
                )
                currentPosition = position
                Log.d(TAG, "Floor changed to $newMapId, positioned at ${equivalentNode.id}")
                return position
            }
        }
        
        Log.w(TAG, "Could not determine position on floor $newMapId")
        return null
    }
    
    /**
     * Get current position
     */
    fun getCurrentPosition(): Position? = currentPosition
    
    /**
     * Check if position is available
     */
    fun hasPosition(): Boolean = currentPosition != null
    
    /**
     * Get position confidence level
     */
    fun getPositionConfidence(): Float = currentPosition?.confidence ?: 0.0f
    
    /**
     * Reset position (e.g., when starting new navigation)
     */
    fun resetPosition() {
        currentPosition = null
        lastHeading = 0.0
        Log.d(TAG, "Position reset")
    }
    
    /**
     * Get distance to a target node
     */
    fun getDistanceToNode(nodeId: String, mapId: String): Double? {
        val current = currentPosition ?: return null
        if (current.mapId != mapId) return null
        
        val map = router.getMap(mapId)
        val targetNode = map?.nodes?.find { it.id == nodeId } ?: return null
        
        val dx = targetNode.x - current.x
        val dy = targetNode.y - current.y
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * QR anchor data class
 */
data class QrAnchor(
    val nodeId: String,
    val mapId: String,
    val x: Double,
    val y: Double,
    val name: String
)