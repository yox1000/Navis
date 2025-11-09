package com.navis.app.routing

import com.navis.app.util.NavisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

private const val TAG = "OsrmClient"

data class OsrmRoute(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val geometry: List<GeoPoint>,
    val instructions: List<String>
)

class OsrmClient(
    private val baseUrl: String = "https://routing.openstreetmap.de/routed-foot",
    private val profile: String = "foot"
) {
    private val client = OkHttpClient()

    suspend fun requestRoute(
        origin: GeoPoint,
        destination: GeoPoint
    ): Result<OsrmRoute> = withContext(Dispatchers.IO) {
        val coords = "${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}"
        val url =
            "$baseUrl/route/v1/$profile/$coords?overview=full&steps=true&geometries=geojson&annotations=distance"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "OSRM HTTP ${response.code}"
                    NavisLog.e(TAG, message)
                    return@withContext Result.failure(IllegalStateException(message))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("OSRM empty response"))
                }
                NavisLog.d(TAG, "OSRM response: $body")
                return@withContext parseRoute(body)
            }
        } catch (t: Throwable) {
            NavisLog.e(TAG, "OSRM request failed", t)
            Result.failure(t)
        }
    }

    private fun parseRoute(payload: String): Result<OsrmRoute> = runCatching {
        val json = JSONObject(payload)
        val code = json.optString("code", "Unknown")
        if (code != "Ok") {
            throw IllegalStateException("OSRM code=$code")
        }
        val routes = json.optJSONArray("routes")
            ?: throw IllegalStateException("OSRM routes missing")
        val firstRoute = routes.optJSONObject(0)
            ?: throw IllegalStateException("OSRM route missing")
        val distance = firstRoute.optDouble("distance", Double.NaN)
        val duration = firstRoute.optDouble("duration", Double.NaN)
        val geometry = firstRoute.optJSONObject("geometry")
            ?: throw IllegalStateException("OSRM geometry missing")
        val geoPoints = parseCoordinates(geometry.optJSONArray("coordinates"))
        val instructions = parseLegInstructions(firstRoute.optJSONArray("legs"))
        OsrmRoute(
            distanceMeters = distance,
            durationSeconds = duration,
            geometry = geoPoints,
            instructions = instructions
        )
    }

    private fun parseCoordinates(coordsArray: JSONArray?): List<GeoPoint> {
        if (coordsArray == null) return emptyList()
        val points = mutableListOf<GeoPoint>()
        for (i in 0 until coordsArray.length()) {
            val pair = coordsArray.optJSONArray(i) ?: continue
            val lon = pair.optDouble(0, Double.NaN)
            val lat = pair.optDouble(1, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                points.add(GeoPoint(lat, lon))
            }
        }
        return points
    }

    private fun parseLegInstructions(legsArray: JSONArray?): List<String> {
        if (legsArray == null) return emptyList()
        val stepsText = mutableListOf<String>()
        val firstLeg = legsArray.optJSONObject(0) ?: return emptyList()
        val steps = firstLeg.optJSONArray("steps") ?: return emptyList()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val maneuver = step.optJSONObject("maneuver")
            val instruction = maneuver?.optString("instruction").orEmpty()
            val name = step.optString("name")
            val text = when {
                instruction.isNotBlank() -> instruction
                name.isNotBlank() -> "Continue on $name"
                else -> ""
            }
            if (text.isNotBlank()) {
                stepsText.add(text)
            }
        }
        return stepsText
    }
}
