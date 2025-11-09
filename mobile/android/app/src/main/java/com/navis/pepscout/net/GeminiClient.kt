package com.navis.pepscout.net

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.navis.pepscout.data.Keystore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiClient(private val keystore: Keystore) {
    
    companion object {
        private const val TAG = "GeminiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val TIMEOUT_SECONDS = 10L
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()

    /**
     * Classify user utterance into navigation request or Q&A
     * @param transcript User's spoken text
     * @return IntentResponse with classified action and extracted parameters
     */
    suspend fun classifyIntent(transcript: String): IntentResponse? = withContext(Dispatchers.IO) {
        try {
            val apiKey = keystore.getGeminiKey()
            if (apiKey == null) {
                Log.e(TAG, "Gemini API key not found")
                return@withContext null
            }
            
            val prompt = buildClassificationPrompt(transcript)
            
            val requestBody = GeminiRequest(
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt))
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = 0.1,
                    topK = 1,
                    topP = 0.1,
                    maxOutputTokens = 500
                )
            )
            
            val jsonBody = gson.toJson(requestBody)
            val mediaType = "application/json".toMediaType()
            
            val request = Request.Builder()
                .url("$BASE_URL/models/gemini-pro:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(mediaType))
                .build()
            
            Log.d(TAG, "Classifying intent for: \"$transcript\"")
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
                    
                    if (geminiResponse.candidates.isNotEmpty()) {
                        val responseText = geminiResponse.candidates[0].content.parts[0].text
                        Log.d(TAG, "Gemini response: $responseText")
                        
                        // Parse the JSON response from Gemini
                        try {
                            val intentResponse = gson.fromJson(responseText, IntentResponse::class.java)
                            Log.d(TAG, "Classified as: ${intentResponse.action}")
                            intentResponse
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse Gemini response as JSON", e)
                            null
                        }
                    } else {
                        Log.w(TAG, "No candidates in Gemini response")
                        null
                    }
                } else {
                    Log.w(TAG, "Empty Gemini response")
                    null
                }
            } else {
                val errorBody = response.body?.string()
                Log.w(TAG, "Gemini API error ${response.code}: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to classify intent", e)
            null
        }
    }
    
    /**
     * Rewrite OSRM step instructions into short pet-style phrases
     */
    suspend fun rewriteSteps(steps: List<String>): List<String>? = withContext(Dispatchers.IO) {
        try {
            val apiKey = keystore.getGeminiKey()
            if (apiKey == null) {
                Log.e(TAG, "Gemini API key not found")
                return@withContext null
            }
            
            val prompt = buildStepRewritePrompt(steps)
            
            val requestBody = GeminiRequest(
                contents = listOf(
                    Content(
                        parts = listOf(Part(text = prompt))
                    )
                ),
                generationConfig = GenerationConfig(
                    temperature = 0.3,
                    topK = 10,
                    topP = 0.8,
                    maxOutputTokens = 1000
                )
            )
            
            val jsonBody = gson.toJson(requestBody)
            val mediaType = "application/json".toMediaType()
            
            val request = Request.Builder()
                .url("$BASE_URL/models/gemini-pro:generateContent?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(mediaType))
                .build()
            
            Log.d(TAG, "Rewriting ${steps.size} navigation steps")
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val geminiResponse = gson.fromJson(responseBody, GeminiResponse::class.java)
                    
                    if (geminiResponse.candidates.isNotEmpty()) {
                        val responseText = geminiResponse.candidates[0].content.parts[0].text
                        
                        // Extract the rewritten steps (expecting one per line)
                        val rewrittenSteps = responseText.split("\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .take(steps.size) // Don't return more than input
                        
                        Log.d(TAG, "Rewritten to ${rewrittenSteps.size} pet-style steps")
                        rewrittenSteps
                    } else {
                        Log.w(TAG, "No candidates in Gemini response for step rewrite")
                        null
                    }
                } else {
                    Log.w(TAG, "Empty Gemini response for step rewrite")
                    null
                }
            } else {
                val errorBody = response.body?.string()
                Log.w(TAG, "Gemini API error for step rewrite ${response.code}: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rewrite steps", e)
            null
        }
    }

    private fun buildClassificationPrompt(transcript: String): String {
        return """
        You are Pep, a helpful navigation assistant. Analyze this user utterance and classify it as either:
        1. "navigate" - user wants directions/navigation 
        2. "qa" - user has a question about accessibility, campus policies, or other information

        Extract the following information and return ONLY valid JSON:

        For navigation requests:
        {
          "action": "navigate",
          "from": {"name": "location name or null", "lat": number or null, "lon": number or null},
          "to": {"name": "destination name", "lat": number or null, "lon": number or null},
          "constraints": {"stairs": false, "hills": false},
          "question": null
        }

        For questions:
        {
          "action": "qa", 
          "from": null,
          "to": null,
          "constraints": {"stairs": false, "hills": false},
          "question": "the user's question"
        }

        Known locations:
        - Library: {"name": "Library", "lat": 43.4723, "lon": -80.5449}
        - SAC Bus Stop: {"name": "SAC Bus Stop", "lat": 43.4713, "lon": -80.5461}

        User said: "$transcript"

        Return only JSON:
        """.trimIndent()
    }

    private fun buildStepRewritePrompt(steps: List<String>): String {
        return """
        You are Pep, a friendly pet-like navigation assistant. Rewrite these navigation instructions into short, cheerful phrases under 7 seconds when spoken. Make them sound like a helpful pet guiding someone.

        Original steps:
        ${steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n")}

        Rewrite each step as a short, friendly phrase (one per line):
        - Keep under 7 seconds when spoken
        - Use simple, encouraging language
        - Mention key landmarks and turns clearly
        - Sound like a helpful pet companion

        Example style: "Let's head straight to the main entrance!" or "Time to turn right at the big tree!"

        Rewritten steps:
        """.trimIndent()
    }

    // Data classes for Gemini API
    private data class GeminiRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig
    )

    private data class Content(
        val parts: List<Part>
    )

    private data class Part(
        val text: String
    )

    private data class GenerationConfig(
        val temperature: Double,
        val topK: Int,
        val topP: Double,
        val maxOutputTokens: Int
    )

    private data class GeminiResponse(
        val candidates: List<Candidate>
    )

    private data class Candidate(
        val content: Content
    )

    // Public response data classes
    data class IntentResponse(
        val action: String, // "navigate" or "qa"
        val from: Location?,
        val to: Location?,
        val constraints: Constraints,
        val question: String?
    )

    data class Location(
        val name: String?,
        val lat: Double?,
        val lon: Double?
    )

    data class Constraints(
        val stairs: Boolean,
        val hills: Boolean
    )
}