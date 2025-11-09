package com.navis.app.voice

import com.navis.app.util.NavisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val TAG = "NeuralSeekClient"

class NeuralSeekClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultAgent: String = "interpreter"
) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun classifyQuery(query: String): Result<NeuralSeekResult> =
        executeAgentCall(
            agent = defaultAgent,
            params = listOf("queryText" to query)
        )

    suspend fun queryWithLocation(
        agent: String,
        queryText: String,
        latitude: Double,
        longitude: Double
    ): Result<NeuralSeekResult> = executeAgentCall(
        agent = agent,
        params = listOf(
            "queryText" to queryText,
            "lon" to formatCoordinate(longitude),
            "lat" to formatCoordinate(latitude)
        )
    )

    private suspend fun executeAgentCall(
        agent: String,
        params: List<Pair<String, String>>
    ): Result<NeuralSeekResult> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("NeuralSeek config missing"))
        }

        val paramsArray = JSONArray().apply {
            params.forEach { (name, value) ->
                put(JSONObject().apply {
                    put("name", name)
                    put("value", value)
                })
            }
        }

        val payload = JSONObject().apply {
            put("agent", agent)
            put("params", paramsArray)
        }

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("apikey", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "NeuralSeek HTTP ${response.code}"
                    NavisLog.e(TAG, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                val body = response.body?.string().orEmpty()
                NavisLog.d(TAG, "NeuralSeek raw response: $body")
                if (body.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Empty NeuralSeek response"))
                }
                val result = parseResponse(body)
                    ?: return@withContext Result.failure(IllegalStateException("Invalid NeuralSeek response: $body"))
                Result.success(result)
            }
        } catch (t: Throwable) {
            NavisLog.e(TAG, "NeuralSeek request failed", t)
            Result.failure(t)
        }
    }

    private fun parseResponse(body: String): NeuralSeekResult? {
        return try {
            val root = JSONObject(body)
            val resultObj = root.optJSONObject("result")
            val outputRaw = when {
                resultObj != null -> resultObj.opt("textOutput")
                root.has("answer") -> root.opt("answer")
                else -> null
            }
            val output = when (outputRaw) {
                is JSONObject -> outputRaw.toString()
                is JSONArray -> outputRaw.toString()
                null -> ""
                else -> outputRaw.toString()
            }.also { NavisLog.d(TAG, "NeuralSeek textOutput: $it") }
            if (output.isBlank()) return null
            NeuralSeekResult(output)
        } catch (t: Throwable) {
            NavisLog.e(TAG, "Failed to parse NeuralSeek response", t)
            null
        }
    }
}

data class NeuralSeekResult(
    val rawText: String
)

private fun formatCoordinate(value: Double): String =
    String.format(Locale.US, "%.6f", value)
