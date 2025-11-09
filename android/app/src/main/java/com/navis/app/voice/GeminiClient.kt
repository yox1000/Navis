package com.navis.app.voice

import com.navis.app.location.LocalPoiResolver
import com.navis.app.util.NavisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONArray
import org.json.JSONObject

private const val GEMINI_TAG = "GeminiClient"
private val GEMINI_MODELS = listOf(
    "gemini-2.5-flash",
    "gemini-2.0-flash-exp"
)
private const val GEMINI_BASE =
    "https://generativelanguage.googleapis.com/v1beta/models"
private const val GEMINI_GROUNDING_MODEL = "gemini-2.5-flash"

class GeminiClient(
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor { message ->
            NavisLog.d(GEMINI_TAG, message)
        }.apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun extractIntent(rawSnippet: String): Result<NeuralSeekIntent> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Gemini API key missing"))
            }
            if (rawSnippet.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Empty snippet"))
            }

            val prompt = buildPrompt(rawSnippet)
            val body = buildRequestBody(prompt)

            var lastError: Throwable? = null
            for (model in GEMINI_MODELS) {
                val request = Request.Builder()
                    .url("$GEMINI_BASE/$model:generateContent?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val msg = "Gemini HTTP ${response.code} model=$model"
                            NavisLog.e(GEMINI_TAG, msg)
                            lastError = IllegalStateException(msg)
                            return@use
                        }
                        val respBody = response.body?.string().orEmpty()
                        if (respBody.isBlank()) {
                            lastError = IllegalStateException("Empty Gemini response")
                            return@use
                        }
                        NavisLog.d(GEMINI_TAG, "Raw response: $respBody")
                        return@withContext parseIntentFromResponse(respBody)
                    }
                } catch (t: Throwable) {
                    NavisLog.e(GEMINI_TAG, "Gemini request failed", t)
                    lastError = t
                }
            }
            Result.failure(lastError ?: IllegalStateException("Gemini request failed"))
        }

    suspend fun classifyIntent(query: String): Result<NeuralSeekIntent> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Gemini API key missing"))
            }
            if (query.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Empty query"))
            }
            val prompt = buildIntentClassificationPrompt(query)
            val body = buildRequestBody(prompt)
            var lastError: Throwable? = null
            for (model in GEMINI_MODELS) {
                val request = Request.Builder()
                    .url("$GEMINI_BASE/$model:generateContent?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            val msg = "Gemini intent HTTP ${response.code} model=$model"
                            NavisLog.e(GEMINI_TAG, msg)
                            lastError = IllegalStateException(msg)
                            return@use
                        }
                        val respBody = response.body?.string().orEmpty()
                        if (respBody.isBlank()) {
                            lastError = IllegalStateException("Empty Gemini response")
                            return@use
                        }
                        NavisLog.d(GEMINI_TAG, "Intent response: $respBody")
                        return@withContext parseIntentFromResponse(respBody)
                    }
                } catch (t: Throwable) {
                    NavisLog.e(GEMINI_TAG, "Gemini intent request failed", t)
                    lastError = t
                }
            }
            Result.failure(lastError ?: IllegalStateException("Gemini intent request failed"))
        }

    data class Destination(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val address: String?,
        val confidence: Double?,
        val poiKey: String? = null
    )

    suspend fun resolveDestination(
        userQuery: String,
        originLat: Double,
        originLon: Double,
        locationHint: String? = null
    ): Result<Destination> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key missing"))
        }
        if (userQuery.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Empty user query"))
        }

        val prompt = buildDestinationPrompt(userQuery, originLat, originLon, locationHint)
        val body = buildRequestBody(prompt)
        val request = Request.Builder()
            .url("$GEMINI_BASE/$GEMINI_GROUNDING_MODEL:generateContent?key=$apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "Gemini destination HTTP ${response.code}"
                    NavisLog.e(GEMINI_TAG, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val respBody = response.body?.string().orEmpty()
                if (respBody.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Gemini destination body empty"))
                }
                NavisLog.d(GEMINI_TAG, "Destination response: $respBody")
                return@withContext parseDestinationResponse(respBody)
            }
        } catch (t: Throwable) {
            NavisLog.e(GEMINI_TAG, "Gemini destination request failed", t)
            Result.failure(t)
        }
    }

    private fun buildRequestBody(prompt: String) = JSONObject().apply {
        put(
            "contents",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put(
                            "parts",
                            JSONArray().apply {
                                put(JSONObject().apply { put("text", prompt) })
                            }
                        )
                    }
                )
            }
        )
    }.toString().toRequestBody(jsonMediaType)

    private fun buildDestinationPrompt(
        userQuery: String,
        originLat: Double,
        originLon: Double,
        locationHint: String?
    ): String = """
        You are a navigation grounding service for Stony Brook University.
        Determine the best matching destination for the user's request using the curated POI list when possible.
        If a POI from the list matches (exact name or sensible alias), return its coordinates exactly as provided.
        Only fall back to other locations if nothing in the list matches.
        
        Known POIs (JSON array):
        ${LocalPoiResolver.exportForPrompt()}
        
        Respond strictly with JSON:
        {
          "name": "Destination name",
          "poi_key": "POI key from the list or null",
          "lat": number,
          "lon": number,
          "confidence": number between 0 and 1
        }
        
        If you cannot determine coordinates, set "lat" and "lon" to null.
        
        User request: "$userQuery"
        User location: lat=$originLat, lon=$originLon
        ${locationHint?.let { "Context: $it" } ?: ""}
    """.trimIndent()

    private fun parseDestinationResponse(body: String): Result<Destination> {
        return runCatching {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini missing candidates")
            val first = candidates.optJSONObject(0)
                ?: throw IllegalStateException("Gemini empty candidate")
            val content = first.optJSONObject("content")
                ?: throw IllegalStateException("Gemini content missing")
            val parts = content.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini parts missing")
            val text = parts.optJSONObject(0)?.optString("text").orEmpty()
            if (text.isBlank()) {
                throw IllegalStateException("Gemini destination text empty")
            }
            val candidateJson = sanitizeJsonBlock(text)
            NavisLog.d(GEMINI_TAG, "Parsed destination JSON=$candidateJson")
            val destJson = JSONObject(candidateJson)
            var name = destJson.optString("name").ifBlank { "Destination" }
            var lat = destJson.optDouble("lat", Double.NaN)
            var lon = destJson.optDouble("lon", Double.NaN)
            val address = destJson.optString("address").takeIf { it.isNotBlank() }
            val poiKey = destJson.optString("poi_key").takeIf { it.isNotBlank() }
            if ((lat.isNaN() || lon.isNaN()) && poiKey != null) {
                LocalPoiResolver.resolveByKey(poiKey)?.let { dest ->
                    name = dest.name
                    lat = dest.latitude
                    lon = dest.longitude
                }
            }
            if (lat.isNaN() || lon.isNaN()) {
                throw IllegalStateException("Gemini destination missing coordinates: $candidateJson")
            }
            Destination(
                name = name,
                latitude = lat,
                longitude = lon,
                address = address,
                confidence = destJson.optDouble("confidence").takeIf { !it.isNaN() },
                poiKey = poiKey
            )
        }
    }

    private val fencedJsonRegex = Regex(
        pattern = """```(?:json)?\s*([\s\S]*?)```""",
        option = RegexOption.IGNORE_CASE
    )

    private fun sanitizeJsonBlock(text: String): String {
        val trimmed = text.trim()
        val defenced = fencedJsonRegex.find(trimmed)?.groupValues?.get(1)?.trim()
            ?: trimmed.trim('`', '\n', '\r', ' ')
        val unquoted = defenced.removeSurrounding("\"").trim()
        return extractFirstJsonObject(unquoted) ?: unquoted
    }

    private fun extractFirstJsonObject(block: String): String? {
        var depth = 0
        var inQuotes = false
        var escape = false
        var start = -1
        block.forEachIndexed { index, c ->
            if (escape) {
                escape = false
                return@forEachIndexed
            }
            when (c) {
                '\\' -> if (inQuotes) escape = true
                '"' -> inQuotes = !inQuotes
            }
            if (inQuotes) return@forEachIndexed
            if (c == '{') {
                if (depth == 0) start = index
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && start != -1) {
                    return block.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun parseIntentFromResponse(respBody: String): Result<NeuralSeekIntent> {
        val json = JSONObject(respBody)
        val candidates = json.optJSONArray("candidates")
            ?: return Result.failure(IllegalStateException("Gemini missing candidates"))
        val first = candidates.optJSONObject(0)
            ?: return Result.failure(IllegalStateException("Gemini empty candidate"))
        val content = first.optJSONObject("content")
            ?: return Result.failure(IllegalStateException("Gemini content missing"))
        val parts = content.optJSONArray("parts")
            ?: return Result.failure(IllegalStateException("Gemini parts missing"))
        val text = parts.optJSONObject(0)?.optString("text").orEmpty()
        if (text.isBlank()) {
            return Result.failure(IllegalStateException("Gemini text empty"))
        }
        return parseIntentJson(text)
    }

    private fun buildPrompt(rawSnippet: String): String = """
        You are a strict JSON parser for navigation intent classification.
        The input might contain markdown, triple backticks, escaped JSON, or nested strings.
        Extract the JSON object describing the intent and entity.
        Output exactly:
        {"intent": number, "entity": "string"}
        Intent codes:
        1 = navigate to destination
        2 = route check
        3 = explore nearby
        4 = not navigation
        
        Raw snippet:
        $rawSnippet
    """.trimIndent()

    private fun buildIntentClassificationPrompt(query: String): String = """
        You are a navigation intent classifier. Output JSON only. Classify user query into:
        1 = navigate to destination
        2 = route check
        3 = explore nearby
        4 = not navigation

        Extract the destination or category in "entity" if present; use an empty string when none.

        User query: "$query"

        Return JSON exactly like:
        {"intent": number, "entity": "string"}
    """.trimIndent()

    private fun parseIntentJson(text: String): Result<NeuralSeekIntent> = runCatching {
        val cleaned = sanitizeJsonBlock(text)
        val intentJson = JSONObject(cleaned)
        val intentValue = intentJson.optInt("intent", -1)
        val entity = intentJson.optString("entity", "")
        if (intentValue == -1) {
            throw IllegalStateException("Gemini returned invalid JSON: $text")
        }
        NeuralSeekIntent(intentValue, entity)
    }
}
