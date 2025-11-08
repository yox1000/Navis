package com.navis.pepscout.net

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.navis.pepscout.data.Cache
import com.navis.pepscout.data.Keystore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ElevenLabsClient(
    private val context: Context,
    private val keystore: Keystore,
    private val cache: Cache
) {
    
    companion object {
        private const val TAG = "ElevenLabsClient"
        private const val BASE_URL = "https://api.elevenlabs.io/v1"
        private const val TIMEOUT_SECONDS = 10L
        
        // Voice models
        private const val DEFAULT_MODEL = "eleven_monolingual_v1"
        private const val TURBO_MODEL = "eleven_turbo_v2"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()

    /**
     * Convert text to speech and return local file path
     * Uses cache to avoid repeated API calls
     */
    suspend fun textToSpeech(
        text: String,
        voiceId: String,
        useCache: Boolean = true
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Check cache first
            if (useCache) {
                val cachedFile = cache.getCachedTtsFile(text)
                if (cachedFile != null) {
                    Log.d(TAG, "Using cached TTS file")
                    return@withContext cachedFile
                }
            }
            
            // Get API key
            val apiKey = keystore.getElevenLabsKey()
            if (apiKey == null) {
                Log.e(TAG, "ElevenLabs API key not found")
                return@withContext null
            }
            
            // Prepare request
            val requestBody = TtsRequest(
                text = text.take(1000), // Limit to 1000 chars to avoid quota issues
                model_id = TURBO_MODEL,
                voice_settings = VoiceSettings(
                    stability = 0.5,
                    similarity_boost = 0.5
                )
            )
            
            val jsonBody = gson.toJson(requestBody)
            val mediaType = "application/json".toMediaType()
            
            val request = Request.Builder()
                .url("$BASE_URL/text-to-speech/$voiceId")
                .addHeader("Accept", "audio/mpeg")
                .addHeader("xi-api-key", apiKey)
                .post(jsonBody.toRequestBody(mediaType))
                .build()
            
            Log.d(TAG, "Requesting TTS for: ${text.take(50)}...")
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val audioBytes = response.body?.bytes()
                if (audioBytes != null) {
                    // Save to cache file
                    val cacheFilePath = cache.getTtsCacheFilePath(text)
                    val file = File(cacheFilePath)
                    
                    FileOutputStream(file).use { fos ->
                        fos.write(audioBytes)
                    }
                    
                    // Update cache
                    cache.cacheTtsFile(text, cacheFilePath)
                    
                    Log.d(TAG, "TTS audio saved to: $cacheFilePath")
                    cacheFilePath
                } else {
                    Log.w(TAG, "Empty audio response")
                    null
                }
            } else {
                val errorBody = response.body?.string()
                Log.w(TAG, "TTS API error ${response.code}: $errorBody")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get TTS audio", e)
            null
        }
    }

    /**
     * Get available voices from ElevenLabs
     */
    suspend fun getVoices(): List<Voice>? = withContext(Dispatchers.IO) {
        try {
            val apiKey = keystore.getElevenLabsKey()
            if (apiKey == null) {
                Log.e(TAG, "ElevenLabs API key not found")
                return@withContext null
            }
            
            val request = Request.Builder()
                .url("$BASE_URL/voices")
                .addHeader("xi-api-key", apiKey)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val voicesResponse = gson.fromJson(responseBody, VoicesResponse::class.java)
                    Log.d(TAG, "Received ${voicesResponse.voices.size} voices")
                    voicesResponse.voices
                } else {
                    Log.w(TAG, "Empty voices response")
                    null
                }
            } else {
                Log.w(TAG, "Voices API error: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get voices", e)
            null
        }
    }

    // Data classes for API requests/responses
    private data class TtsRequest(
        val text: String,
        val model_id: String,
        val voice_settings: VoiceSettings
    )

    private data class VoiceSettings(
        val stability: Double,
        val similarity_boost: Double
    )

    private data class VoicesResponse(
        val voices: List<Voice>
    )

    data class Voice(
        val voice_id: String,
        val name: String,
        val category: String,
        val description: String?
    )
}