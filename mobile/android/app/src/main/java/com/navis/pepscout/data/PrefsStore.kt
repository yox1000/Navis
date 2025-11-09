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
        
        // Default values
        private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Default ElevenLabs voice
        private const val DEFAULT_VOICE_SPEED = 1.0f
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
}