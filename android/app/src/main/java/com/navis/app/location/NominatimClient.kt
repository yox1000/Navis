package com.navis.app.location

import com.navis.app.util.NavisLog
import com.navis.app.voice.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val NOMINATIM_TAG = "NominatimClient"
private const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/search"
private const val NOMINATIM_USER_AGENT = "NavisApp/0.1 (+https://github.com/NavisApp)"

class NominatimClient {
    private val client = OkHttpClient()

    suspend fun search(
        query: String,
        origin: GeoPoint?
    ): Result<GeminiClient.Destination?> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(null)

        val url = buildUrl(
            query = query,
            origin = origin,
            limit = 3,
            radiusMeters = 1_000.0,
            bounded = false
        )
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", NOMINATIM_USER_AGENT)
            .addHeader("Accept", "application/json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "Nominatim HTTP ${response.code}"
                    NavisLog.e(NOMINATIM_TAG, message)
                    return@withContext Result.failure(IllegalStateException(message))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Nominatim empty response"))
                }
                return@withContext Result.success(parseFirst(body))
            }
        } catch (t: Throwable) {
            NavisLog.e(NOMINATIM_TAG, "Nominatim request failed", t)
            Result.failure(t)
        }
    }

    suspend fun searchNearby(
        query: String,
        origin: GeoPoint,
        limit: Int = 5,
        radiusMeters: Double = 1_200.0
    ): Result<List<GeminiClient.Destination>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())
        val url = buildUrl(
            query = query,
            origin = origin,
            limit = limit,
            radiusMeters = radiusMeters,
            bounded = true
        )
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", NOMINATIM_USER_AGENT)
            .addHeader("Accept", "application/json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "Nominatim HTTP ${response.code}"
                    NavisLog.e(NOMINATIM_TAG, message)
                    return@withContext Result.failure(IllegalStateException(message))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Nominatim empty response"))
                }
                return@withContext Result.success(parseList(body, limit))
            }
        } catch (t: Throwable) {
            NavisLog.e(NOMINATIM_TAG, "Nominatim nearby request failed", t)
            Result.failure(t)
        }
    }

    private fun parseFirst(payload: String): GeminiClient.Destination? =
        parseList(payload, limit = 1).firstOrNull()

    private fun parseList(payload: String, limit: Int): List<GeminiClient.Destination> {
        val results = JSONArray(payload)
        if (results.length() == 0) {
            NavisLog.d(NOMINATIM_TAG, "Nominatim returned no matches")
            return emptyList()
        }
        val destinations = mutableListOf<GeminiClient.Destination>()
        for (i in 0 until results.length()) {
            if (destinations.size >= limit) break
            val obj = results.optJSONObject(i) ?: continue
            val lat = obj.optString("lat").toDoubleOrNull() ?: continue
            val lon = obj.optString("lon").toDoubleOrNull() ?: continue
            val name = obj.optString("name").ifBlank {
                obj.optString("display_name").substringBefore(",").ifBlank { "Destination" }
            }
            val address = obj.optString("display_name").takeIf { it.isNotBlank() }
            val confidence = obj.optDouble("importance", Double.NaN).takeIf { !it.isNaN() }
            destinations.add(
                GeminiClient.Destination(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    address = address,
                    confidence = confidence
                )
            )
        }
        return destinations
    }

    private fun buildUrl(
        query: String,
        origin: GeoPoint?,
        limit: Int,
        radiusMeters: Double,
        bounded: Boolean
    ): String {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val builder = StringBuilder(
            "$NOMINATIM_BASE_URL?format=jsonv2&limit=$limit&addressdetails=0&namedetails=0&q=$encodedQuery"
        )
        origin?.let {
            val latDelta = radiusMeters / 111_000.0
            val cosLat = cos(Math.toRadians(it.latitude)).coerceIn(0.2, 1.0)
            val lonDelta = radiusMeters / (111_000.0 * cosLat)
            val minLat = it.latitude - latDelta
            val maxLat = it.latitude + latDelta
            val minLon = it.longitude - lonDelta
            val maxLon = it.longitude + lonDelta
            builder.append("&viewbox=$minLon,$maxLat,$maxLon,$minLat")
        }
        builder.append("&bounded=").append(if (bounded) "1" else "0")
        builder.append("&accept-language=en")
        return builder.toString()
    }
}
