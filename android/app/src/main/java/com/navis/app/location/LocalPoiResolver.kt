package com.navis.app.location

import android.content.Context
import com.navis.app.util.NavisLog
import com.navis.app.voice.GeminiClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

private data class LocalPoi(
    val key: String,
    val displayName: String,
    val aliases: List<String>,
    val searchTerms: List<String>,
    val latitude: Double,
    val longitude: Double
) {
    fun toDestination(): GeminiClient.Destination = GeminiClient.Destination(
        name = displayName,
        latitude = latitude,
        longitude = longitude,
        address = null,
        confidence = 0.98,
        poiKey = key
    )
}

object LocalPoiResolver {
    private const val TAG = "LocalPoiResolver"
    private const val ASSET_FILE = "pois.json"
    private const val MATCH_THRESHOLD = 0.62
    @Volatile
    private var pois: List<LocalPoi> = defaultPois()
    @Volatile
    private var poiMap: Map<String, LocalPoi> = pois.associateBy { it.key }

    fun loadFromAssets(context: Context) {
        runCatching {
            val json = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            parsePois(json)
        }.onSuccess { loaded ->
            if (loaded.isNotEmpty()) {
                pois = loaded
                poiMap = loaded.associateBy { it.key }
                NavisLog.i(TAG, "Loaded ${loaded.size} POIs from assets")
            }
        }.onFailure { err ->
            NavisLog.w(TAG, "Failed to load POIs from assets: ${err.message}")
        }
    }

    fun resolve(query: String): GeminiClient.Destination? {
        if (query.isBlank()) return null
        val normalizedQuery = normalize(query)
        val candidates = pois.mapNotNull { poi ->
            val score = scorePoi(normalizedQuery, poi.searchTerms)
            if (score >= MATCH_THRESHOLD) poi to score else null
        }
        val best = candidates.maxByOrNull { it.second }?.first ?: return null
        return best.toDestination()
    }

    fun resolveByKey(key: String?): GeminiClient.Destination? {
        if (key.isNullOrBlank()) return null
        return poiMap[key.trim().lowercase()]?.toDestination()
    }

    fun exportForPrompt(limit: Int = 30): String {
        val array = JSONArray()
        pois.take(limit).forEach { poi ->
            array.put(
                JSONObject().apply {
                    put("poi_key", poi.key)
                    put("name", poi.displayName)
                    put("aliases", JSONArray(poi.aliases))
                    put("lat", poi.latitude)
                    put("lon", poi.longitude)
                }
            )
        }
        return array.toString()
    }

    private fun scorePoi(query: String, terms: List<String>): Double {
        return terms.maxOfOrNull { term -> matchScore(query, term) } ?: 0.0
    }

    private fun matchScore(query: String, alias: String): Double {
        if (alias.isBlank() || query.isBlank()) return 0.0
        if (alias == query) return 1.0
        if (alias.contains(query) || query.contains(alias)) return 0.92
        val distance = levenshteinDistance(alias, query)
        val maxLen = max(alias.length, query.length).coerceAtLeast(1)
        return 1.0 - distance.toDouble() / maxLen
    }

    private fun normalize(text: String): String =
        text.lowercase()
            .replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) {
                    prev
                } else {
                    minOf(prev, dp[j - 1], dp[j]) + 1
                }
                prev = temp
            }
        }
        return dp[b.length]
    }

    private fun parsePois(rawJson: String): List<LocalPoi> {
        if (rawJson.isBlank()) return emptyList()
        val root = JSONObject(rawJson)
        val list = mutableListOf<LocalPoi>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = root.optJSONObject(key) ?: continue
            val aliasesArray = obj.optJSONArray("aliases")
            val aliases = mutableListOf<String>()
            if (aliasesArray != null) {
                for (i in 0 until aliasesArray.length()) {
                    aliasesArray.optString(i)?.takeIf { it.isNotBlank() }?.let { aliases.add(it) }
                }
            }
            val canonical = humanizeKey(key)
            val searchTerms = (listOf(canonical) + aliases)
                .map { normalize(it) }
                .filter { it.isNotBlank() }
                .distinct()
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (searchTerms.isNotEmpty() && !lat.isNaN() && !lon.isNaN()) {
                list.add(
                    LocalPoi(
                        key = key.lowercase(),
                        displayName = canonical.replaceFirstChar { ch ->
                            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                        },
                        aliases = aliases,
                        searchTerms = searchTerms,
                        latitude = lat,
                        longitude = lon
                    )
                )
            }
        }
        return list
    }

    private fun defaultPois(): List<LocalPoi> = listOf(
        LocalPoi(
            key = "melville_library",
            displayName = "Melville Library",
            aliases = listOf("Frank Melville Jr. Memorial Library", "Main Library"),
            searchTerms = listOf(
                normalize("Melville Library"),
                normalize("Frank Melville Jr. Memorial Library"),
                normalize("Main Library")
            ),
            latitude = 40.92529,
            longitude = -73.12754
        ),
        LocalPoi(
            key = "student_activities_center",
            displayName = "Student Activities Center",
            aliases = listOf("SAC", "Student Activity Center"),
            searchTerms = listOf(
                normalize("Student Activities Center"),
                normalize("SAC"),
                normalize("Student Activity Center")
            ),
            latitude = 40.91438161518008,
            longitude = -73.12424580525091
        ),
        LocalPoi(
            key = "staller_center",
            displayName = "Staller Center",
            aliases = listOf("Staller Center for the Arts"),
            searchTerms = listOf(
                normalize("Staller Center"),
                normalize("Staller Center for the Arts")
            ),
            latitude = 40.91993,
            longitude = -73.12358
        )
    ).also { poiMap = it.associateBy { poi -> poi.key } }

    private fun humanizeKey(key: String): String =
        key.replace('_', ' ').trim()
}
