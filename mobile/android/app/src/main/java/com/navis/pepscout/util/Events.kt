package com.navis.pepscout.util

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * Simple event bus for decoupled communication between components
 * Handles hazard, location, heading, free_space, and wall events
 */
object Events {
    
    companion object {
        private const val TAG = "Events"
    }
    
    // Shared event flow
    private val _eventFlow = MutableSharedFlow<PepEvent>(
        replay = 0,
        extraBufferCapacity = 50
    )
    val eventFlow: Flow<PepEvent> = _eventFlow.asSharedFlow()
    
    /**
     * Emit an event to all subscribers
     */
    fun emit(event: PepEvent) {
        val success = _eventFlow.tryEmit(event)
        if (!success) {
            Log.w(TAG, "Failed to emit event: $event")
        } else {
            Log.d(TAG, "Emitted: ${event.type} at ${event.timestamp}")
        }
    }
    
    /**
     * Get a flow of specific event types
     */
    inline fun <reified T : PepEvent> flowOf(): Flow<T> {
        return eventFlow.filter { it is T }.map { it as T }
    }
    
    /**
     * Get flow of hazard events
     */
    fun hazardEvents(): Flow<HazardEvent> = flowOf()
    
    /**
     * Get flow of location events  
     */
    fun locationEvents(): Flow<LocationEvent> = flowOf()
    
    /**
     * Get flow of heading events
     */
    fun headingEvents(): Flow<HeadingEvent> = flowOf()
    
    /**
     * Get flow of free space events
     */
    fun freeSpaceEvents(): Flow<FreeSpaceEvent> = flowOf()
    
    /**
     * Get flow of wall events
     */
    fun wallEvents(): Flow<WallEvent> = flowOf()
    
    // Base event class
    sealed class PepEvent(
        val type: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Hazard event from CV
    data class HazardEvent(
        val id: String,
        val where: Where,
        val geo: Geo?,
        val kind: String, // "obstacle", "moving_object", "stair", "curb", "door"
        val label: String, // "person", "chair", "bike", "unknown"
        val severity: String, // "info", "warn", "danger"
        val confidence: Float, // 0.0 to 1.0
        val ttlSeconds: Int
    ) : PepEvent("hazard") {
        
        data class Where(
            val locationType: String, // "indoor" or "outdoor"
            val building: String? = null,
            val nodeId: String? = null
        )
        
        data class Geo(
            val lat: Double,
            val lon: Double,
            val accuracyM: Double
        )
    }
    
    // Location event from GPS
    data class LocationEvent(
        val lat: Double,
        val lon: Double,
        val accuracyM: Double,
        val bearingDeg: Double
    ) : PepEvent("location")
    
    // Heading event from sensors
    data class HeadingEvent(
        val azimuthDeg: Double
    ) : PepEvent("heading")
    
    // Free space event from CV
    data class FreeSpaceEvent(
        val angleDeg: Double, // positive = right, negative = left
        val confidence: Float, // 0.0 to 1.0
        val binDistribution: FloatArray? = null // distribution across 7 bins
    ) : PepEvent("free_space")
    
    // Wall proximity event from CV
    data class WallEvent(
        val detected: Boolean,
        val edgeDensity: Float,
        val foeConfidence: Float,
        val distanceM: Double = 0.0 // estimated distance to wall
    ) : PepEvent("wall")
    
    // Navigation state events
    data class NavigationStateEvent(
        val state: String, // "idle", "planning", "outdoor", "indoor", "completed"
        val currentStep: Int = 0,
        val totalSteps: Int = 0
    ) : PepEvent("nav_state")
    
    // TTS events
    data class VoiceEvent(
        val action: String, // "started", "completed", "error"
        val text: String,
        val priority: String = "normal" // "low", "normal", "urgent"
    ) : PepEvent("voice")
}

/**
 * Extension functions for easier event emission from Capacitor plugins
 */
fun com.getcapacitor.JSObject.toHazardEvent(): Events.HazardEvent? {
    return try {
        val whereJson = this.getJSObject("where")
        val geoJson = this.getJSObject("geo")
        
        Events.HazardEvent(
            id = this.getString("id") ?: "unknown",
            where = Events.HazardEvent.Where(
                locationType = whereJson?.getString("type") ?: "unknown",
                building = whereJson?.getString("building"),
                nodeId = whereJson?.getString("nodeId")
            ),
            geo = geoJson?.let {
                Events.HazardEvent.Geo(
                    lat = it.getDouble("lat") ?: 0.0,
                    lon = it.getDouble("lon") ?: 0.0,
                    accuracyM = it.getDouble("accuracy_m") ?: 0.0
                )
            },
            kind = this.getString("kind") ?: "unknown",
            label = this.getString("label") ?: "unknown",
            severity = this.getString("severity") ?: "info",
            confidence = (this.getDouble("confidence") ?: 0.0).toFloat(),
            ttlSeconds = this.getInteger("ttl_s") ?: 3
        )
    } catch (e: Exception) {
        Log.w("Events", "Failed to parse hazard event", e)
        null
    }
}

fun com.getcapacitor.JSObject.toLocationEvent(): Events.LocationEvent? {
    return try {
        Events.LocationEvent(
            lat = this.getDouble("lat") ?: 0.0,
            lon = this.getDouble("lon") ?: 0.0,
            accuracyM = this.getDouble("accuracy_m") ?: 0.0,
            bearingDeg = this.getDouble("bearing_deg") ?: 0.0
        )
    } catch (e: Exception) {
        Log.w("Events", "Failed to parse location event", e)
        null
    }
}

fun com.getcapacitor.JSObject.toHeadingEvent(): Events.HeadingEvent? {
    return try {
        Events.HeadingEvent(
            azimuthDeg = this.getDouble("azimuth") ?: 0.0
        )
    } catch (e: Exception) {
        Log.w("Events", "Failed to parse heading event", e)
        null
    }
}

fun com.getcapacitor.JSObject.toFreeSpaceEvent(): Events.FreeSpaceEvent? {
    return try {
        val binArray = this.getJSArray("bin_distribution")
        val binDistribution = if (binArray != null) {
            FloatArray(binArray.length()) { i ->
                (binArray.getDouble(i) ?: 0.0).toFloat()
            }
        } else null
        
        Events.FreeSpaceEvent(
            angleDeg = this.getDouble("angle_deg") ?: 0.0,
            confidence = (this.getDouble("confidence") ?: 0.0).toFloat(),
            binDistribution = binDistribution
        )
    } catch (e: Exception) {
        Log.w("Events", "Failed to parse free space event", e)
        null
    }
}

fun com.getcapacitor.JSObject.toWallEvent(): Events.WallEvent? {
    return try {
        Events.WallEvent(
            detected = this.getBool("detected") ?: false,
            edgeDensity = (this.getDouble("edge_density") ?: 0.0).toFloat(),
            foeConfidence = (this.getDouble("foe_confidence") ?: 0.0).toFloat(),
            distanceM = this.getDouble("distance_m") ?: 0.0
        )
    } catch (e: Exception) {
        Log.w("Events", "Failed to parse wall event", e)
        null
    }
}