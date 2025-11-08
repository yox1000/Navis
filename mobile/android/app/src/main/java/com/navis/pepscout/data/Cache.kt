package com.navis.pepscout.data

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class Cache(private val context: Context) {
    
    companion object {
        private const val TAG = "Cache"
        private const val TTS_CACHE_DIR = "tts"
        private const val MAX_TTS_CACHE_SIZE = 10 // Keep last 10 TTS clips
        private const val MAX_ROUTE_CACHE_AGE = 3600000L // 1 hour in milliseconds
    }
    
    // In-memory cache for TTS file paths
    private val ttsCache = ConcurrentHashMap<String, String>()
    
    // In-memory cache for last route
    private var lastRoute: RouteCache? = null
    
    // TTS cache directory
    private val ttsCacheDir: File by lazy {
        File(context.filesDir, TTS_CACHE_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Generate a hash for text to use as cache key
     */
    fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(text.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Store TTS audio file path in cache
     */
    fun cacheTtsFile(text: String, filePath: String) {
        val hash = hashText(text)
        ttsCache[hash] = filePath
        
        // Cleanup old files if cache is too large
        cleanupTtsCache()
        
        Log.d(TAG, "Cached TTS file for hash: $hash")
    }

    /**
     * Get cached TTS file path if exists
     */
    fun getCachedTtsFile(text: String): String? {
        val hash = hashText(text)
        val filePath = ttsCache[hash]
        
        return if (filePath != null && File(filePath).exists()) {
            Log.d(TAG, "Found cached TTS file for hash: $hash")
            filePath
        } else {
            // Remove from cache if file doesn't exist
            ttsCache.remove(hash)
            null
        }
    }

    /**
     * Get TTS cache file path for a given text
     */
    fun getTtsCacheFilePath(text: String): String {
        val hash = hashText(text)
        return File(ttsCacheDir, "$hash.mp3").absolutePath
    }

    /**
     * Clean up old TTS files to maintain cache size limit
     */
    private fun cleanupTtsCache() {
        try {
            val files = ttsCacheDir.listFiles() ?: return
            
            if (files.size > MAX_TTS_CACHE_SIZE) {
                // Sort by last modified time (oldest first)
                val sortedFiles = files.sortedBy { it.lastModified() }
                
                // Delete oldest files beyond the limit
                val filesToDelete = sortedFiles.take(files.size - MAX_TTS_CACHE_SIZE)
                
                filesToDelete.forEach { file ->
                    if (file.delete()) {
                        Log.d(TAG, "Deleted old TTS cache file: ${file.name}")
                        // Remove from in-memory cache
                        ttsCache.values.removeAll { it == file.absolutePath }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TTS cache", e)
        }
    }

    /**
     * Cache the last route for offline access
     */
    fun cacheRoute(from: Pair<Double, Double>, to: Pair<Double, Double>, routeData: String) {
        lastRoute = RouteCache(
            from = from,
            to = to,
            routeData = routeData,
            timestamp = System.currentTimeMillis()
        )
        Log.d(TAG, "Cached route from ${from} to ${to}")
    }

    /**
     * Get cached route if still valid
     */
    fun getCachedRoute(from: Pair<Double, Double>, to: Pair<Double, Double>): String? {
        val cached = lastRoute ?: return null
        
        val age = System.currentTimeMillis() - cached.timestamp
        if (age > MAX_ROUTE_CACHE_AGE) {
            Log.d(TAG, "Cached route expired")
            lastRoute = null
            return null
        }
        
        // Check if coordinates match (with small tolerance)
        val tolerance = 0.001 // ~100m tolerance
        val fromMatch = kotlin.math.abs(cached.from.first - from.first) < tolerance &&
                       kotlin.math.abs(cached.from.second - from.second) < tolerance
        val toMatch = kotlin.math.abs(cached.to.first - to.first) < tolerance &&
                     kotlin.math.abs(cached.to.second - to.second) < tolerance
        
        return if (fromMatch && toMatch) {
            Log.d(TAG, "Found valid cached route")
            cached.routeData
        } else {
            Log.d(TAG, "Cached route coordinates don't match")
            null
        }
    }

    /**
     * Clear all caches
     */
    fun clearAll() {
        // Clear TTS cache
        ttsCache.clear()
        ttsCacheDir.listFiles()?.forEach { it.delete() }
        
        // Clear route cache
        lastRoute = null
        
        Log.d(TAG, "Cleared all caches")
    }

    /**
     * Get cache status for debugging
     */
    fun getCacheStatus(): String {
        val ttsFiles = ttsCacheDir.listFiles()?.size ?: 0
        val routeCached = lastRoute != null
        return "TTS files: $ttsFiles, Route cached: $routeCached"
    }
    
    data class RouteCache(
        val from: Pair<Double, Double>,
        val to: Pair<Double, Double>, 
        val routeData: String,
        val timestamp: Long
    )
}