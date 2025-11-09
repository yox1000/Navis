package com.navis.pepscout.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import kotlin.text.Charsets

class Keystore(private val context: Context) {
    
    companion object {
        private const val TAG = "Keystore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS_PREFIX = "pepscout_"
        
        // API key identifiers
        const val ELEVENLABS_API_KEY = "elevenlabs_api_key"
        const val GEMINI_API_KEY = "gemini_api_key" 
        const val NEURALSEEK_API_KEY = "neuralseek_api_key"
        
        private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
    }
    
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }
    
    private val sharedPrefs = context.getSharedPreferences("encrypted_keys", Context.MODE_PRIVATE)

    fun setApiKey(keyType: String, apiKey: String): Boolean {
        return try {
            val keyAlias = KEY_ALIAS_PREFIX + keyType
            
            // Generate or retrieve secret key
            val secretKey = getOrCreateSecretKey(keyAlias)
            
            // Encrypt the API key
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val encryptedKey = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            
            // Store encrypted data and IV in SharedPreferences
            sharedPrefs.edit()
                .putString("${keyType}_encrypted", android.util.Base64.encodeToString(encryptedKey, android.util.Base64.DEFAULT))
                .putString("${keyType}_iv", android.util.Base64.encodeToString(iv, android.util.Base64.DEFAULT))
                .apply()
            
            Log.d(TAG, "API key stored securely for $keyType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store API key for $keyType", e)
            false
        }
    }

    fun getApiKey(keyType: String): String? {
        return try {
            val keyAlias = KEY_ALIAS_PREFIX + keyType
            
            // Get encrypted data from SharedPreferences
            val encryptedKeyString = sharedPrefs.getString("${keyType}_encrypted", null)
            val ivString = sharedPrefs.getString("${keyType}_iv", null)
            
            if (encryptedKeyString == null || ivString == null) {
                Log.d(TAG, "No stored API key found for $keyType")
                return null
            }
            
            val encryptedKey = android.util.Base64.decode(encryptedKeyString, android.util.Base64.DEFAULT)
            val iv = android.util.Base64.decode(ivString, android.util.Base64.DEFAULT)
            
            // Get secret key from Android Keystore
            val secretKey = keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw Exception("Secret key not found")
            
            // Decrypt the API key
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            
            val decryptedKey = cipher.doFinal(encryptedKey)
            String(decryptedKey, Charsets.UTF_8)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve API key for $keyType", e)
            null
        }
    }

    fun hasApiKey(keyType: String): Boolean {
        return sharedPrefs.contains("${keyType}_encrypted")
    }

    fun removeApiKey(keyType: String): Boolean {
        return try {
            val keyAlias = KEY_ALIAS_PREFIX + keyType
            
            // Remove from SharedPreferences
            sharedPrefs.edit()
                .remove("${keyType}_encrypted")
                .remove("${keyType}_iv")
                .apply()
            
            // Remove key from keystore if it exists
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
            
            Log.d(TAG, "API key removed for $keyType")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove API key for $keyType", e)
            false
        }
    }
    
    private fun getOrCreateSecretKey(keyAlias: String): SecretKey {
        return if (keyStore.containsAlias(keyAlias)) {
            keyStore.getKey(keyAlias, null) as SecretKey
        } else {
            generateSecretKey(keyAlias)
        }
    }
    
    private fun generateSecretKey(keyAlias: String): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
    
    // Convenience methods for specific API keys
    fun setElevenLabsKey(apiKey: String) = setApiKey(ELEVENLABS_API_KEY, apiKey)
    fun setGeminiKey(apiKey: String) = setApiKey(GEMINI_API_KEY, apiKey)  
    fun setNeuralSeekKey(apiKey: String) = setApiKey(NEURALSEEK_API_KEY, apiKey)
    
    fun getElevenLabsKey() = getApiKey(ELEVENLABS_API_KEY)
    fun getGeminiKey() = getApiKey(GEMINI_API_KEY)
    fun getNeuralSeekKey() = getApiKey(NEURALSEEK_API_KEY)
    
    fun hasAllKeys(): Boolean {
        return hasApiKey(ELEVENLABS_API_KEY) && 
               hasApiKey(GEMINI_API_KEY) && 
               hasApiKey(NEURALSEEK_API_KEY)
    }
}