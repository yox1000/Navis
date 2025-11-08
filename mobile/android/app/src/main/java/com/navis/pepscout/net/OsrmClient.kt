package com.navis.pepscout.net

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class OsrmClient {
    
    companion object {
        private const val TAG = "OsrmClient"
        private const val BASE_URL = "https://router.project-osrm.org"
        private const val TIMEOUT_SECONDS = 5L
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()

    /**
     * Get walking route between two points
     * @param fromLon Starting longitude
     * @param fromLat Starting latitude  
     * @param toLon Destination longitude
     * @param toLat Destination latitude
     * @return RouteResponse with geometry and steps, or null if failed
     */
    suspend fun getRoute(
        fromLon: Double,
        fromLat: Double,
        toLon: Double,
        toLat: Double
    ): RouteResponse? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/route/v1/foot/$fromLon,$fromLat;$toLon,$toLat" +
                     "?steps=true&geometries=polyline6&overview=full"
            
            Log.d(TAG, "Requesting route: $url")
            
            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val osrmResponse = gson.fromJson(responseBody, OsrmResponse::class.java)
                    
                    if (osrmResponse.code == "Ok" && osrmResponse.routes.isNotEmpty()) {
                        val route = osrmResponse.routes[0]
                        Log.d(TAG, "Route received: ${route.distance}m, ${route.duration}s")
                        
                        RouteResponse(
                            distance = route.distance,
                            duration = route.duration,
                            geometry = route.geometry,
                            steps = route.legs.flatMap { it.steps }
                        )
                    } else {
                        Log.w(TAG, "OSRM returned error: ${osrmResponse.code}")
                        null
                    }
                } else {
                    Log.w(TAG, "Empty response body")
                    null
                }
            } else {
                Log.w(TAG, "HTTP error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get route", e)
            null
        }
    }

    /**
     * Decode polyline string to list of lat/lng points
     * Uses polyline6 precision (6 decimal places)
     */
    fun decodePolyline(polyline: String, precision: Int = 6): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var index = 0
        val len = polyline.length
        var lat = 0
        var lng = 0
        val factor = Math.pow(10.0, precision.toDouble()).toInt()

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = polyline[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                b = polyline[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            points.add(Pair(lat.toDouble() / factor, lng.toDouble() / factor))
        }

        return points
    }

    // Data classes for OSRM API response
    private data class OsrmResponse(
        val code: String,
        val routes: List<OsrmRoute>
    )

    private data class OsrmRoute(
        val geometry: String,
        val legs: List<OsrmLeg>,
        val distance: Double,
        val duration: Double
    )

    private data class OsrmLeg(
        val steps: List<OsrmStep>
    )

    private data class OsrmStep(
        val geometry: String,
        val maneuver: OsrmManeuver,
        val name: String?,
        val duration: Double,
        val distance: Double
    )

    private data class OsrmManeuver(
        val location: List<Double>,
        val type: String,
        @SerializedName("modifier") val modifier: String?
    )

    // Public response data classes
    data class RouteResponse(
        val distance: Double, // meters
        val duration: Double, // seconds
        val geometry: String, // polyline6 encoded
        val steps: List<OsrmStep>
    )
}