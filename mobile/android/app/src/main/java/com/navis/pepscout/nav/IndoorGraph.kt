package com.navis.pepscout.nav

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.IOException
import kotlin.math.sqrt

/**
 * Indoor navigation graph loader and pathfinding engine
 * Inspired by PathSense graph processing patterns
 */
class IndoorGraph(private val context: Context) {
    
    companion object {
        private const val TAG = "IndoorGraph"
        private const val GRAPH_FILE = "indoor/library_demo.json"
    }
    
    private var buildingData: BuildingData? = null
    private var nodeMap: Map<String, Node> = emptyMap()
    private var edgeMap: Map<String, List<Edge>> = emptyMap()
    
    private val gson = Gson()

    /**
     * Load the indoor graph from assets
     */
    suspend fun loadGraph(): Boolean {
        return try {
            val jsonString = context.assets.open(GRAPH_FILE).bufferedReader().use { it.readText() }
            buildingData = gson.fromJson(jsonString, BuildingData::class.java)
            
            buildingData?.let { data ->
                // Build node lookup map
                val nodes = mutableMapOf<String, Node>()
                data.floors.forEach { floor ->
                    floor.nodes.values.forEach { node ->
                        nodes[node.id] = node
                    }
                }
                nodeMap = nodes
                
                // Build edge adjacency map
                val adjacency = mutableMapOf<String, MutableList<Edge>>()
                data.edges.forEach { edge ->
                    adjacency.getOrPut(edge.from) { mutableListOf() }.add(edge)
                    // Add reverse edge for bidirectional navigation
                    val reverseEdge = edge.copy(from = edge.to, to = edge.from)
                    adjacency.getOrPut(edge.to) { mutableListOf() }.add(reverseEdge)
                }
                edgeMap = adjacency
                
                Log.d(TAG, "Loaded graph: ${nodeMap.size} nodes, ${data.edges.size} edges")
                true
            } ?: false
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load indoor graph", e)
            false
        }
    }

    /**
     * Find shortest path between two nodes using Dijkstra's algorithm
     * Supports constraints like avoiding stairs
     */
    fun findPath(
        fromNodeId: String,
        toNodeId: String,
        constraints: PathConstraints = PathConstraints()
    ): PathResult? {
        val startNode = nodeMap[fromNodeId]
        val endNode = nodeMap[toNodeId]
        
        if (startNode == null || endNode == null) {
            Log.w(TAG, "Start or end node not found: $fromNodeId -> $toNodeId")
            return null
        }
        
        // Dijkstra's algorithm
        val distances = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
        val previous = mutableMapOf<String, String>()
        val visited = mutableSetOf<String>()
        val queue = mutableListOf<String>()
        
        // Initialize
        distances[fromNodeId] = 0.0
        queue.add(fromNodeId)
        
        while (queue.isNotEmpty()) {
            // Find unvisited node with minimum distance
            val current = queue.minByOrNull { distances.getValue(it) } ?: break
            queue.remove(current)
            
            if (current in visited) continue
            visited.add(current)
            
            if (current == toNodeId) break
            
            // Check all neighbors
            edgeMap[current]?.forEach { edge ->
                if (edge.to !in visited && isEdgeAllowed(edge, constraints)) {
                    val newDistance = distances.getValue(current) + edge.distance
                    
                    if (newDistance < distances.getValue(edge.to)) {
                        distances[edge.to] = newDistance
                        previous[edge.to] = current
                        if (edge.to !in queue) {
                            queue.add(edge.to)
                        }
                    }
                }
            }
        }
        
        // Reconstruct path
        if (toNodeId !in previous && fromNodeId != toNodeId) {
            Log.w(TAG, "No path found from $fromNodeId to $toNodeId")
            return null
        }
        
        val path = mutableListOf<String>()
        var current = toNodeId
        while (current != fromNodeId) {
            path.add(0, current)
            current = previous[current] ?: break
        }
        path.add(0, fromNodeId)
        
        // Build step instructions
        val steps = buildStepInstructions(path)
        val totalDistance = distances.getValue(toNodeId)
        
        Log.d(TAG, "Found path: ${path.size} nodes, ${totalDistance}m")
        
        return PathResult(
            nodes = path.mapNotNull { nodeMap[it] },
            steps = steps,
            totalDistance = totalDistance,
            floorChanges = steps.count { it.floorChange }
        )
    }
    
    /**
     * Check if an edge is allowed given the constraints
     */
    private fun isEdgeAllowed(edge: Edge, constraints: PathConstraints): Boolean {
        return !(constraints.avoidStairs && edge.stairs)
    }
    
    /**
     * Build step-by-step instructions for the path
     */
    private fun buildStepInstructions(nodePath: List<String>): List<PathStep> {
        val steps = mutableListOf<PathStep>()
        
        for (i in 0 until nodePath.size - 1) {
            val fromId = nodePath[i]
            val toId = nodePath[i + 1]
            val fromNode = nodeMap[fromId]
            val toNode = nodeMap[toId]
            
            // Find the edge between these nodes
            val edge = edgeMap[fromId]?.find { it.to == toId }
            
            if (fromNode != null && toNode != null && edge != null) {
                steps.add(PathStep(
                    fromNode = fromNode,
                    toNode = toNode,
                    instruction = edge.description,
                    distance = edge.distance,
                    floorChange = edge.floor_change ?: false,
                    requiresStairs = edge.stairs
                ))
            }
        }
        
        return steps
    }
    
    /**
     * Get all nodes on a specific floor
     */
    fun getNodesForFloor(floorId: String): List<Node> {
        return nodeMap.values.filter { it.floor.toString() == floorId }
    }
    
    /**
     * Get node by QR payload
     */
    fun getNodeByQrPayload(payload: String): Node? {
        return nodeMap.values.find { it.qr_payload == payload }
    }
    
    /**
     * Get floor information
     */
    fun getFloor(floorId: String): Floor? {
        return buildingData?.floors?.find { it.id == floorId }
    }
    
    /**
     * Get all floors
     */
    fun getFloors(): List<Floor> {
        return buildingData?.floors ?: emptyList()
    }
    
    /**
     * Calculate Euclidean distance between two nodes (for drawing)
     */
    fun getPixelDistance(node1: Node, node2: Node): Double {
        if (node1.floor != node2.floor) return Double.MAX_VALUE
        
        val dx = node1.x - node2.x
        val dy = node1.y - node2.y
        return sqrt((dx * dx + dy * dy).toDouble())
    }

    // Data classes for JSON structure
    data class BuildingData(
        val building: String,
        val floors: List<Floor>,
        val edges: List<Edge>,
        val routes: Map<String, Route>?
    )

    data class Floor(
        val id: String,
        val name: String,
        val image: String,
        val width: Int,
        val height: Int,
        val nodes: Map<String, Node>
    )

    data class Node(
        val id: String,
        val name: String,
        val x: Int,
        val y: Int,
        val floor: Int,
        val type: String,
        val qr_payload: String?,
        val connects_to: String? // For stairs/elevator connections
    )

    data class Edge(
        val from: String,
        val to: String,
        val distance: Double,
        val stairs: Boolean,
        val description: String,
        val floor_change: Boolean? = false
    )

    data class Route(
        val name: String,
        val description: String,
        val start: String,
        val end: String,
        val constraints: RouteConstraints?
    )

    data class RouteConstraints(
        val stairs: Boolean?
    )

    // Pathfinding classes
    data class PathConstraints(
        val avoidStairs: Boolean = false
    )

    data class PathResult(
        val nodes: List<Node>,
        val steps: List<PathStep>,
        val totalDistance: Double,
        val floorChanges: Int
    )

    data class PathStep(
        val fromNode: Node,
        val toNode: Node,
        val instruction: String,
        val distance: Double,
        val floorChange: Boolean,
        val requiresStairs: Boolean
    )
}