package com.navis.pepscout.mappedin

import android.content.Context
import android.util.Log
import com.navis.pepscout.data.Keystore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mappedin routing service
 * Provides route calculation between indoor locations
 */
class MappedinRouter(
    private val context: Context,
    private val keystore: Keystore,
    private val adapter: MappedinAdapter = RealMappedinAdapter()
) {
    
    companion object {
        private const val TAG = "MappedinRouter"
        private const val DEFAULT_VENUE_SLUG = "demo-library"
    }
    
    private var currentVenue: MappedinVenue? = null
    private var isInitialized = false
    
    /**
     * Initialize the router with Mappedin credentials
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            if (isInitialized) return@withContext true
            
            try {
                val apiKey = keystore.getMappedinApiKey()
                val secret = keystore.getMappedinSecret()
                
                if (apiKey.isNullOrBlank() || secret.isNullOrBlank()) {
                    Log.w(TAG, "Mappedin credentials not found, using stub adapter")
                    // Still initialize with empty credentials for stub mode
                }
                
                val success = adapter.initialize(context, apiKey ?: "", secret ?: "")
                if (success) {
                    // Load default venue
                    currentVenue = adapter.loadVenue(DEFAULT_VENUE_SLUG)
                    isInitialized = currentVenue != null
                    Log.d(TAG, "MappedinRouter initialized: venue=${currentVenue?.name}")
                } else {
                    Log.e(TAG, "Failed to initialize Mappedin adapter")
                }
                
                success && isInitialized
                
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MappedinRouter", e)
                false
            }
        }
    }
    
    /**
     * Calculate route between two nodes
     */
    suspend fun route(fromNodeId: String, toNodeId: String, mapId: String? = null): MappedinRoute? {
        return withContext(Dispatchers.IO) {
            if (!isInitialized || currentVenue == null) {
                Log.w(TAG, "Router not initialized")
                return@withContext null
            }
            
            try {
                // Determine map ID if not provided
                val routeMapId = mapId ?: findNodeMap(fromNodeId) ?: run {
                    Log.w(TAG, "Could not determine map for node: $fromNodeId")
                    return@withContext null
                }
                
                // Check if cross-floor routing is needed
                val fromMap = findNodeMap(fromNodeId)
                val toMap = findNodeMap(toNodeId)
                
                if (fromMap != toMap && fromMap != null && toMap != null) {
                    // Handle cross-floor routing
                    return@withContext calculateCrossFloorRoute(fromNodeId, toNodeId, fromMap, toMap)
                }
                
                // Single floor routing
                adapter.getRoute(fromNodeId, toNodeId, routeMapId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating route", e)
                null
            }
        }
    }
    
    /**
     * Get available destinations for a given map
     */
    fun getDestinations(mapId: String): List<MappedinNode> {
        return currentVenue?.maps?.find { it.id == mapId }?.nodes ?: emptyList()
    }
    
    /**
     * Get all available destinations across all floors
     */
    fun getAllDestinations(): List<Pair<MappedinNode, String>> {
        return currentVenue?.maps?.flatMap { map ->
            map.nodes.map { node -> Pair(node, map.name) }
        } ?: emptyList()
    }
    
    /**
     * Find the closest node to given coordinates
     */
    fun findClosestNode(x: Double, y: Double, mapId: String): MappedinNode? {
        val map = currentVenue?.maps?.find { it.id == mapId }
        return map?.nodes?.minByOrNull { node ->
            val dx = node.x - x
            val dy = node.y - y
            dx * dx + dy * dy
        }
    }
    
    /**
     * Get venue information
     */
    fun getCurrentVenue(): MappedinVenue? = currentVenue
    
    /**
     * Get map by ID
     */
    fun getMap(mapId: String): MappedinMap? {
        return currentVenue?.maps?.find { it.id == mapId }
    }
    
    /**
     * Get all maps in venue
     */
    fun getAllMaps(): List<MappedinMap> {
        return currentVenue?.maps ?: emptyList()
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        adapter.cleanup()
        currentVenue = null
        isInitialized = false
        Log.d(TAG, "MappedinRouter cleaned up")
    }
    
    // Private helper methods
    
    private fun findNodeMap(nodeId: String): String? {
        return currentVenue?.maps?.find { map ->
            map.nodes.any { it.id == nodeId }
        }?.id
    }
    
    private suspend fun calculateCrossFloorRoute(
        fromNodeId: String,
        toNodeId: String,
        fromMapId: String,
        toMapId: String
    ): MappedinRoute? {
        try {
            // Find elevator/stair nodes for floor transition
            val fromMap = getMap(fromMapId) ?: return null
            val toMap = getMap(toMapId) ?: return null
            
            // Find elevator node on source floor
            val elevatorFromNode = fromMap.nodes.find { it.name.contains("Elevator", ignoreCase = true) }
            if (elevatorFromNode == null) {
                Log.w(TAG, "No elevator found on source floor")
                return null
            }
            
            // Find elevator node on destination floor  
            val elevatorToNode = toMap.nodes.find { it.name.contains("Elevator", ignoreCase = true) }
            if (elevatorToNode == null) {
                Log.w(TAG, "No elevator found on destination floor")
                return null
            }
            
            // Calculate route segments
            val segment1 = adapter.getRoute(fromNodeId, elevatorFromNode.id, fromMapId)
            val segment2 = adapter.getRoute(elevatorToNode.id, toNodeId, toMapId)
            
            if (segment1 == null || segment2 == null) {
                Log.w(TAG, "Could not calculate route segments")
                return null
            }
            
            // Create elevator transition step
            val elevatorStep = MappedinRouteStep(
                action = "elevator",
                description = "Take elevator to ${toMap.name}",
                distance = 0.0,
                duration = 15.0, // Assume 15 seconds for elevator
                nodeId = elevatorToNode.id,
                mapId = toMapId
            )
            
            // Combine route segments
            val combinedSteps = segment1.steps + elevatorStep + segment2.steps
            val combinedGeometry = segment1.geometry + segment2.geometry
            
            MappedinRoute(
                distance = segment1.distance + segment2.distance,
                duration = segment1.duration + 15.0 + segment2.duration,
                steps = combinedSteps,
                geometry = combinedGeometry
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating cross-floor route", e)
            null
        }
    }
}