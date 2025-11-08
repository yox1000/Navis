package com.navis.pepscout.net

import android.util.Log
import com.google.gson.Gson
import com.navis.pepscout.data.Keystore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class NeuralSeekClient(private val keystore: Keystore) {
    
    companion object {
        private const val TAG = "NeuralSeekClient"
        // Note: Replace with actual NeuralSeek workspace URL
        private const val BASE_URL = "https://your-workspace.neuralseek.com/v1"
        private const val TIMEOUT_SECONDS = 10L
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()

    /**
     * Ask a question and get a short answer with source
     * @param question User's question about accessibility or campus policies
     * @return AskResponse with answer and source hint, or null if failed
     */
    suspend fun askQuestion(question: String): AskResponse? = withContext(Dispatchers.IO) {
        try {
            val apiKey = keystore.getNeuralSeekKey()
            if (apiKey == null) {
                Log.e(TAG, "NeuralSeek API key not found")
                return@withContext null
            }
            
            val requestBody = AskRequest(
                question = question.take(500), // Limit question length
                language = "en",
                confidence_threshold = 0.3 // Lower threshold for more responses
            )
            
            val jsonBody = gson.toJson(requestBody)
            val mediaType = "application/json".toMediaType()
            
            val request = Request.Builder()
                .url("$BASE_URL/ask")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toRequestBody(mediaType))
                .build()
            
            Log.d(TAG, "Asking question: \"$question\"")
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val neuralSeekResponse = gson.fromJson(responseBody, NeuralSeekResponse::class.java)
                    
                    if (neuralSeekResponse.success && neuralSeekResponse.answer.isNotEmpty()) {
                        Log.d(TAG, "Received answer: ${neuralSeekResponse.answer.take(100)}...")
                        
                        AskResponse(
                            answer = neuralSeekResponse.answer,
                            source_hint = neuralSeekResponse.source_documents?.firstOrNull()?.title 
                                ?: neuralSeekResponse.source ?: "Campus Information",
                            confidence = neuralSeekResponse.confidence
                        )
                    } else {
                        Log.w(TAG, "NeuralSeek returned no answer or failed")
                        // Return fallback response
                        AskResponse(
                            answer = "I don't have specific information about that. Please contact campus accessibility services for detailed assistance.",
                            source_hint = "General Information",
                            confidence = 0.0
                        )
                    }
                } else {
                    Log.w(TAG, "Empty NeuralSeek response")
                    null
                }
            } else {
                val errorBody = response.body?.string()
                Log.w(TAG, "NeuralSeek API error ${response.code}: $errorBody")
                
                // Return fallback for common errors
                if (response.code == 429) {
                    AskResponse(
                        answer = "I'm getting too many requests right now. Please try asking again in a moment.",
                        source_hint = "Rate Limit",
                        confidence = 0.0
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ask question", e)
            
            // Return offline fallback for connectivity issues
            AskResponse(
                answer = getOfflineFallbackAnswer(question),
                source_hint = "Cached Information",
                confidence = 0.0
            )
        }
    }

    /**
     * Get a basic fallback answer for common accessibility questions
     */
    private fun getOfflineFallbackAnswer(question: String): String {
        val lowerQuestion = question.lowercase()
        
        return when {
            "elevator" in lowerQuestion || "lift" in lowerQuestion ->
                "Elevators are available in most campus buildings. Check building directories for specific locations."
            
            "wheelchair" in lowerQuestion || "accessible" in lowerQuestion ->
                "Campus provides wheelchair accessible routes and facilities. Contact accessibility services for specific building information."
            
            "stairs" in lowerQuestion && "avoid" in lowerQuestion ->
                "Alternative accessible routes are available. Use elevator options in building navigation."
            
            "parking" in lowerQuestion && ("accessible" in lowerQuestion || "disabled" in lowerQuestion) ->
                "Accessible parking spaces are marked and located near building entrances. Permits required."
            
            "transit" in lowerQuestion || "bus" in lowerQuestion ->
                "Campus transit provides accessible transportation. Check schedules at bus stops."
            
            else ->
                "For specific accessibility information, please contact campus accessibility services or check the campus website."
        }
    }

    // Data classes for NeuralSeek API
    private data class AskRequest(
        val question: String,
        val language: String,
        val confidence_threshold: Double
    )

    private data class NeuralSeekResponse(
        val success: Boolean,
        val answer: String,
        val confidence: Double,
        val source: String?,
        val source_documents: List<SourceDocument>?
    )

    private data class SourceDocument(
        val title: String,
        val content: String,
        val score: Double
    )

    // Public response data class
    data class AskResponse(
        val answer: String,
        val source_hint: String,
        val confidence: Double
    )
}