package com.navis.pepscout.ui.secrets

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * API key validation utilities
 * Tests keys against their respective APIs to ensure they're valid
 */
object ApiKeyValidator {
    
    private const val TAG = "ApiKeyValidator"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    /**
     * Validate ElevenLabs API key
     */
    suspend fun validateElevenLabsKey(apiKey: String): ValidationResult = withContext(Dispatchers.IO) {
        if (!apiKey.startsWith("sk-")) {
            return@withContext ValidationResult.Invalid("ElevenLabs keys should start with 'sk-'")
        }
        
        try {
            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/user")
                .addHeader("xi-api-key", apiKey)
                .build()
            
            val response = client.newCall(request).execute()
            
            when (response.code) {
                200 -> ValidationResult.Valid
                401 -> ValidationResult.Invalid("Invalid API key")
                else -> ValidationResult.Invalid("API returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ElevenLabs validation failed", e)
            ValidationResult.Unknown("Cannot reach ElevenLabs API")
        }
    }
    
    /**
     * Validate Gemini API key
     */
    suspend fun validateGeminiKey(apiKey: String): ValidationResult = withContext(Dispatchers.IO) {
        if (!apiKey.startsWith("AIza")) {
            return@withContext ValidationResult.Invalid("Gemini keys should start with 'AIza'")
        }
        
        try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
                .build()
            
            val response = client.newCall(request).execute()
            
            when (response.code) {
                200 -> ValidationResult.Valid
                400, 403 -> ValidationResult.Invalid("Invalid API key")
                else -> ValidationResult.Invalid("API returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini validation failed", e)
            ValidationResult.Unknown("Cannot reach Gemini API")
        }
    }
    
    /**
     * Validate Mappedin credentials
     */
    suspend fun validateMappedinCredentials(apiKey: String, secret: String): ValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secret.isBlank()) {
            return@withContext ValidationResult.Invalid("Both API key and secret are required")
        }
        
        // For now, just check format since we don't have real Mappedin validation endpoint
        // In real implementation, this would test against Mappedin's auth endpoint
        if (apiKey.length < 10 || secret.length < 10) {
            return@withContext ValidationResult.Invalid("API key and secret seem too short")
        }
        
        ValidationResult.Valid
    }
    
    /**
     * Validate NeuralSeek API key
     */
    suspend fun validateNeuralSeekKey(apiKey: String): ValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ValidationResult.Invalid("API key cannot be empty")
        }
        
        // Basic format validation - adjust based on actual NeuralSeek key format
        if (apiKey.length < 20) {
            return@withContext ValidationResult.Invalid("API key seems too short")
        }
        
        ValidationResult.Valid
    }
    
    /**
     * Validate Cohere API key
     */
    suspend fun validateCohereKey(apiKey: String): ValidationResult = withContext(Dispatchers.IO) {
        if (!apiKey.startsWith("co-")) {
            return@withContext ValidationResult.Invalid("Cohere keys should start with 'co-'")
        }
        
        try {
            val request = Request.Builder()
                .url("https://api.cohere.ai/v1/check-api-key")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            
            val response = client.newCall(request).execute()
            
            when (response.code) {
                200 -> ValidationResult.Valid
                401 -> ValidationResult.Invalid("Invalid API key")
                else -> ValidationResult.Invalid("API returned ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cohere validation failed", e)
            ValidationResult.Unknown("Cannot reach Cohere API")
        }
    }
}

/**
 * Validation result sealed class
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val message: String) : ValidationResult()
    data class Unknown(val message: String) : ValidationResult()
}