package com.navis.pepscout.debug

import android.content.Context
import android.util.Log

/**
 * Debug manager for initializing and managing debug logging
 * Provides centralized access to debug functionality
 */
class DebugManager(private val context: Context) {
    
    companion object {
        private const val TAG = "DebugManager"
        
        @Volatile
        private var INSTANCE: DebugManager? = null
        
        fun getInstance(context: Context): DebugManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DebugManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private var _debugLogger: DebugLogger? = null
    val debugLogger: DebugLogger
        get() = _debugLogger ?: throw IllegalStateException("DebugManager not initialized")
    
    private var isInitialized = false
    
    /**
     * Initialize debug logging system
     */
    fun initialize() {
        if (isInitialized) {
            Log.w(TAG, "DebugManager already initialized")
            return
        }
        
        try {
            _debugLogger = DebugLogger(context)
            isInitialized = true
            
            // Clean up old logs on startup
            _debugLogger?.cleanupOldLogs()
            
            Log.d(TAG, "DebugManager initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DebugManager", e)
        }
    }
    
    /**
     * Check if debug logging is enabled
     */
    fun isDebugEnabled(): Boolean = isInitialized && _debugLogger != null
    
    /**
     * Get debug logger instance (null if not initialized)
     */
    fun getDebugLoggerOrNull(): DebugLogger? = _debugLogger
    
    /**
     * Clean up debug resources
     */
    fun destroy() {
        _debugLogger?.destroy()
        _debugLogger = null
        isInitialized = false
        INSTANCE = null
        Log.d(TAG, "DebugManager destroyed")
    }
}