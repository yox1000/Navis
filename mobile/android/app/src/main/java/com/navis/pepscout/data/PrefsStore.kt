package com.navis.pepscout.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PrefsStore(private val context: Context) {
    
    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")
        
        private val STAIRS_DISABLED = booleanPreferencesKey("stairs_disabled")
        private val AVOID_HILLS = booleanPreferencesKey("avoid_hills") 
        private val VOICE_ID = stringPreferencesKey("voice_id")
        private val VOICE_SPEED = floatPreferencesKey("voice_speed")
        private val CV_ENABLED = booleanPreferencesKey("cv_enabled")
        private val FIRST_RUN = booleanPreferencesKey("first_run")
        
        // Safety configuration keys
        private val SAFETY_CONE_DEG = floatPreferencesKey("safety_cone_deg")
        private val SAFETY_MIN_BOX_AREA_RATIO = floatPreferencesKey("safety_min_box_area_ratio")
        private val SAFETY_MIN_BLOCK_MS = intPreferencesKey("safety_min_block_ms")
        private val SAFETY_COOLDOWN_MS = intPreferencesKey("safety_cooldown_ms")
        private val SAFETY_WALL_TRIGGER_FRAMES = intPreferencesKey("safety_wall_trigger_frames")
        
        // Default values
        private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Default ElevenLabs voice
        private const val DEFAULT_VOICE_SPEED = 1.0f
        
        // Safety defaults
        private const val DEFAULT_SAFETY_CONE_DEG = 20f
        private const val DEFAULT_SAFETY_MIN_BOX_AREA_RATIO = 0.04f
        private const val DEFAULT_SAFETY_MIN_BLOCK_MS = 300
        private const val DEFAULT_SAFETY_COOLDOWN_MS = 2000
        private const val DEFAULT_SAFETY_WALL_TRIGGER_FRAMES = 10
    }

    val stairsDisabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[STAIRS_DISABLED] ?: false }

    val avoidHills: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[AVOID_HILLS] ?: false }

    val voiceId: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[VOICE_ID] ?: DEFAULT_VOICE_ID }

    val voiceSpeed: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[VOICE_SPEED] ?: DEFAULT_VOICE_SPEED }
        
    val cvEnabled: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[CV_ENABLED] ?: false }
        
    val isFirstRun: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[FIRST_RUN] ?: true }

    // Safety configuration flows
    val safetyConeDegrees: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[SAFETY_CONE_DEG] ?: DEFAULT_SAFETY_CONE_DEG }

    val safetyMinBoxAreaRatio: Flow<Float> = context.dataStore.data
        .map { preferences -> preferences[SAFETY_MIN_BOX_AREA_RATIO] ?: DEFAULT_SAFETY_MIN_BOX_AREA_RATIO }

    val safetyMinBlockMs: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[SAFETY_MIN_BLOCK_MS] ?: DEFAULT_SAFETY_MIN_BLOCK_MS }

    val safetyCooldownMs: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[SAFETY_COOLDOWN_MS] ?: DEFAULT_SAFETY_COOLDOWN_MS }

    val safetyWallTriggerFrames: Flow<Int> = context.dataStore.data
        .map { preferences -> preferences[SAFETY_WALL_TRIGGER_FRAMES] ?: DEFAULT_SAFETY_WALL_TRIGGER_FRAMES }

    suspend fun setStairsDisabled(disabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[STAIRS_DISABLED] = disabled
        }
    }

    suspend fun setAvoidHills(avoid: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AVOID_HILLS] = avoid
        }
    }

    suspend fun setVoiceId(id: String) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_ID] = id
        }
    }

    suspend fun setVoiceSpeed(speed: Float) {
        context.dataStore.edit { preferences ->
            preferences[VOICE_SPEED] = speed
        }
    }
    
    suspend fun setCvEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CV_ENABLED] = enabled
        }
    }
    
    suspend fun setFirstRunCompleted() {
        context.dataStore.edit { preferences ->
            preferences[FIRST_RUN] = false
        }
    }

    // Quick access to current values (for non-reactive use)
    suspend fun getCurrentStairsDisabled(): Boolean {
        return context.dataStore.data.map { it[STAIRS_DISABLED] ?: false }.let { 
            kotlinx.coroutines.flow.first(it)
        }
    }

    suspend fun getCurrentAvoidHills(): Boolean {
        return context.dataStore.data.map { it[AVOID_HILLS] ?: false }.let {
            kotlinx.coroutines.flow.first(it)
        }
    }

    // Safety configuration setters
    suspend fun setSafetyConeDegrees(degrees: Float) {
        context.dataStore.edit { preferences ->
            preferences[SAFETY_CONE_DEG] = degrees
        }
    }

    suspend fun setSafetyMinBoxAreaRatio(ratio: Float) {
        context.dataStore.edit { preferences ->
            preferences[SAFETY_MIN_BOX_AREA_RATIO] = ratio
        }
    }

    suspend fun setSafetyMinBlockMs(ms: Int) {
        context.dataStore.edit { preferences ->
            preferences[SAFETY_MIN_BLOCK_MS] = ms
        }
    }

    suspend fun setSafetyCooldownMs(ms: Int) {
        context.dataStore.edit { preferences ->
            preferences[SAFETY_COOLDOWN_MS] = ms
        }
    }

    suspend fun setSafetyWallTriggerFrames(frames: Int) {
        context.dataStore.edit { preferences ->
            preferences[SAFETY_WALL_TRIGGER_FRAMES] = frames
        }
    }
}