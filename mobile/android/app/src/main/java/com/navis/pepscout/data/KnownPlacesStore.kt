package com.navis.pepscout.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Storage and management for known places in the venue
 * Supports search, synonyms, and ranking for intent-to-target
 */
class KnownPlacesStore(private val context: Context) {
    
    companion object {
        private const val TAG = "KnownPlacesStore"
        private const val PLACES_FILE = "known_places.json"
    }
    
    private val gson = Gson()
    private val placesFile = File(context.filesDir, PLACES_FILE)
    private var places = mutableListOf<KnownPlace>()
    
    init {
        loadPlaces()
    }
    
    /**
     * Load places from storage
     */
    private fun loadPlaces() {
        try {
            if (placesFile.exists()) {
                val json = placesFile.readText()
                val type = object : TypeToken<List<KnownPlace>>() {}.type
                places = gson.fromJson<List<KnownPlace>>(json, type).toMutableList()
                Log.d(TAG, "Loaded ${places.size} known places")
            } else {
                // Initialize with default places
                initializeDefaultPlaces()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading places", e)
            initializeDefaultPlaces()
        }
    }
    
    /**
     * Save places to storage
     */
    private suspend fun savePlaces() {
        withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(places)
                placesFile.writeText(json)
                Log.d(TAG, "Saved ${places.size} places to storage")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving places", e)
            }
        }
    }
    
    /**
     * Initialize with default library places
     */
    private fun initializeDefaultPlaces() {
        places = mutableListOf(
            // Floor 1 places
            KnownPlace(
                id = "entrance",
                name = "Library Entrance",
                synonyms = listOf("entrance", "entry", "front door", "main entrance"),
                mapId = "map-floor-1",
                nodeId = "entrance",
                category = "entrance",
                description = "Main entrance to the library"
            ),
            KnownPlace(
                id = "reference",
                name = "Reference Desk",
                synonyms = listOf("reference", "help desk", "information", "librarian", "ask for help"),
                mapId = "map-floor-1",
                nodeId = "reference",
                category = "service",
                description = "Get help from librarians"
            ),
            KnownPlace(
                id = "computers",
                name = "Computer Area",
                synonyms = listOf("computers", "computer lab", "workstations", "public computers"),
                mapId = "map-floor-1",
                nodeId = "computers",
                category = "technology",
                description = "Public computer workstations"
            ),
            KnownPlace(
                id = "lobby",
                name = "Main Lobby",
                synonyms = listOf("lobby", "main area", "central area"),
                mapId = "map-floor-1",
                nodeId = "lobby",
                category = "common",
                description = "Main lobby area"
            ),
            
            // Floor 2 places
            KnownPlace(
                id = "reading-room",
                name = "Reading Room",
                synonyms = listOf("reading room", "quiet study", "study area", "reading area"),
                mapId = "map-floor-2", 
                nodeId = "reading-room",
                category = "study",
                description = "Quiet reading and study area"
            ),
            KnownPlace(
                id = "study-pods",
                name = "Study Pods",
                synonyms = listOf("study pods", "group study", "collaborative study", "pods"),
                mapId = "map-floor-2",
                nodeId = "study-pods", 
                category = "study",
                description = "Individual and group study pods"
            ),
            KnownPlace(
                id = "quiet-zone",
                name = "Quiet Study Zone",
                synonyms = listOf("quiet zone", "silent study", "quiet area", "no talking"),
                mapId = "map-floor-2",
                nodeId = "quiet-zone",
                category = "study",
                description = "Silent study zone"
            ),
            
            // Shared facilities
            KnownPlace(
                id = "elevator",
                name = "Elevator",
                synonyms = listOf("elevator", "lift", "go upstairs", "go to second floor"),
                mapId = "map-floor-1", // Default to floor 1
                nodeId = "elevator-f1",
                category = "navigation",
                description = "Elevator to other floors"
            )
        )
        
        // Save default places
        kotlin.runCatching {
            kotlin.runBlocking { savePlaces() }
        }
        
        Log.d(TAG, "Initialized with ${places.size} default places")
    }
    
    /**
     * Search places by text query
     */
    fun searchPlaces(query: String, limit: Int = 10): List<ScoredPlace> {
        if (query.isBlank()) return emptyList()
        
        val queryLower = query.lowercase().trim()
        val scored = places.mapNotNull { place ->
            val score = calculateScore(place, queryLower)
            if (score > 0.0) ScoredPlace(place, score) else null
        }
        
        return scored.sortedByDescending { it.score }.take(limit)
    }
    
    /**
     * Calculate relevance score for a place against query
     */
    private fun calculateScore(place: KnownPlace, queryLower: String): Double {
        var score = 0.0
        
        // Exact name match
        if (place.name.lowercase() == queryLower) {
            score += 100.0
        }
        
        // Name contains query
        if (place.name.lowercase().contains(queryLower)) {
            score += 50.0
        }
        
        // Exact synonym match
        place.synonyms.forEach { synonym ->
            if (synonym.lowercase() == queryLower) {
                score += 80.0
            } else if (synonym.lowercase().contains(queryLower)) {
                score += 30.0
            }
        }
        
        // Category match
        if (place.category.lowercase().contains(queryLower)) {
            score += 20.0
        }
        
        // Description match
        if (place.description.lowercase().contains(queryLower)) {
            score += 10.0
        }
        
        // Word-level matching for multi-word queries
        val queryWords = queryLower.split(" ").filter { it.isNotBlank() }
        if (queryWords.size > 1) {
            val allText = "${place.name} ${place.synonyms.joinToString(" ")} ${place.description}".lowercase()
            val matchedWords = queryWords.count { word -> allText.contains(word) }
            val wordMatchRatio = matchedWords.toDouble() / queryWords.size
            score += wordMatchRatio * 25.0
        }
        
        return score
    }
    
    /**
     * Get place by ID
     */
    fun getPlace(id: String): KnownPlace? {
        return places.find { it.id == id }
    }
    
    /**
     * Get place by node ID
     */
    fun getPlaceByNode(nodeId: String, mapId: String? = null): KnownPlace? {
        return places.find { place ->
            place.nodeId == nodeId && (mapId == null || place.mapId == mapId)
        }
    }
    
    /**
     * Get all places
     */
    fun getAllPlaces(): List<KnownPlace> = places.toList()
    
    /**
     * Get places by category
     */
    fun getPlacesByCategory(category: String): List<KnownPlace> {
        return places.filter { it.category.equals(category, ignoreCase = true) }
    }
    
    /**
     * Get places on specific map/floor
     */
    fun getPlacesByMap(mapId: String): List<KnownPlace> {
        return places.filter { it.mapId == mapId }
    }
    
    /**
     * Add or update a place
     */
    suspend fun addPlace(place: KnownPlace) {
        val existingIndex = places.indexOfFirst { it.id == place.id }
        if (existingIndex >= 0) {
            places[existingIndex] = place
            Log.d(TAG, "Updated place: ${place.name}")
        } else {
            places.add(place)
            Log.d(TAG, "Added place: ${place.name}")
        }
        savePlaces()
    }
    
    /**
     * Remove a place
     */
    suspend fun removePlace(id: String) {
        val removed = places.removeIf { it.id == id }
        if (removed) {
            Log.d(TAG, "Removed place: $id")
            savePlaces()
        }
    }
    
    /**
     * Update places from Mappedin venue data
     */
    suspend fun syncWithMappedin(venue: com.navis.pepscout.mappedin.MappedinVenue) {
        try {
            // Add nodes that aren't already known as places
            venue.maps.forEach { map ->
                map.nodes.forEach { node ->
                    val existing = getPlaceByNode(node.id, map.id)
                    if (existing == null && node.name.isNotBlank()) {
                        val place = KnownPlace(
                            id = "auto_${node.id}",
                            name = node.name,
                            synonyms = generateSynonyms(node.name),
                            mapId = map.id,
                            nodeId = node.id,
                            category = inferCategory(node.name),
                            description = "Auto-generated from Mappedin data"
                        )
                        places.add(place)
                    }
                }
            }
            
            savePlaces()
            Log.d(TAG, "Synced places with Mappedin venue data")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing with Mappedin", e)
        }
    }
    
    /**
     * Generate synonyms for a place name
     */
    private fun generateSynonyms(name: String): List<String> {
        val synonyms = mutableListOf<String>()
        val nameLower = name.lowercase()
        
        // Add variations
        synonyms.add(nameLower)
        
        // Add abbreviated forms
        if (nameLower.contains("room")) {
            synonyms.add(nameLower.replace("room", "").trim())
        }
        if (nameLower.contains("area")) {
            synonyms.add(nameLower.replace("area", "").trim())
        }
        
        // Add common variations
        when {
            nameLower.contains("elevator") -> synonyms.addAll(listOf("lift", "elevator"))
            nameLower.contains("stair") -> synonyms.addAll(listOf("stairs", "stairway", "steps"))
            nameLower.contains("restroom") || nameLower.contains("bathroom") -> 
                synonyms.addAll(listOf("bathroom", "restroom", "toilet", "washroom"))
            nameLower.contains("entrance") -> synonyms.addAll(listOf("entrance", "entry", "door"))
        }
        
        return synonyms.distinct().filter { it.isNotBlank() }
    }
    
    /**
     * Infer category from place name
     */
    private fun inferCategory(name: String): String {
        val nameLower = name.lowercase()
        return when {
            nameLower.contains("entrance") || nameLower.contains("exit") -> "entrance"
            nameLower.contains("elevator") || nameLower.contains("stair") -> "navigation"
            nameLower.contains("desk") || nameLower.contains("counter") -> "service"
            nameLower.contains("study") || nameLower.contains("reading") -> "study"
            nameLower.contains("computer") || nameLower.contains("tech") -> "technology"
            nameLower.contains("restroom") || nameLower.contains("bathroom") -> "facilities"
            nameLower.contains("cafe") || nameLower.contains("food") -> "dining"
            else -> "general"
        }
    }
}

/**
 * Known place data class
 */
data class KnownPlace(
    val id: String,
    val name: String,
    val synonyms: List<String>,
    val mapId: String,
    val nodeId: String,
    val category: String,
    val description: String
)

/**
 * Scored place for search results
 */
data class ScoredPlace(
    val place: KnownPlace,
    val score: Double
)