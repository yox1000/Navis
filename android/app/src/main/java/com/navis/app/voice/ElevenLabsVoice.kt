package com.navis.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import com.navis.app.util.NavisLog

private const val ELEVEN_LABS_BASE_URL =
    "https://api.elevenlabs.io/v1/text-to-speech"
private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel (fast responding)
private const val TTS_MODEL_ID = "eleven_monolingual_v1"
private const val TAG = "ElevenLabsVoice"

class ElevenLabsVoice(
    private val appContext: Context,
    private val apiKey: String
) {
    private val okHttp = OkHttpClient()
    private var mediaPlayer: MediaPlayer? = null

    suspend fun speak(
        text: String,
        voiceId: String = DEFAULT_VOICE_ID
    ): VoicePlaybackOutcome {
        if (apiKey.isBlank()) return VoicePlaybackOutcome.Failure("Missing ElevenLabs API key")
        if (text.isBlank()) return VoicePlaybackOutcome.Failure("Empty text, nothing to say")
        val audioBytes = fetchAudioBytes(text, voiceId).getOrElse {
            return VoicePlaybackOutcome.Failure(it.message ?: "Unable to fetch audio")
        }
        return playAudio(audioBytes).fold(
            onSuccess = { VoicePlaybackOutcome.Success },
            onFailure = { VoicePlaybackOutcome.Failure(it.message ?: "Playback failed") }
        )
    }

    fun stop() {
        mediaPlayer?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        mediaPlayer = null
    }

    fun shutdown() {
        stop()
    }

    private suspend fun fetchAudioBytes(
        text: String,
        voiceId: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("text", text)
            put("model_id", TTS_MODEL_ID)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.4)
                put("similarity_boost", 0.85)
                put("style", 0.2)
                put("use_speaker_boost", true)
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$ELEVEN_LABS_BASE_URL/$voiceId/stream")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(requestBody)
            .build()

        try {
            okHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = "TTS request failed ${response.code} ${response.message}"
                    NavisLog.e(TAG, message)
                    return@withContext Result.failure(IllegalStateException(message))
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    val message = "TTS response body empty"
                    NavisLog.e(TAG, message)
                    return@withContext Result.failure(IllegalStateException(message))
                }
                return@withContext Result.success(bytes)
            }
        } catch (t: Throwable) {
            NavisLog.e(TAG, "TTS request error", t)
            return@withContext Result.failure(t)
        }
    }

    private suspend fun playAudio(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.Main) {
        val cacheDir = File(appContext.cacheDir, "tts").apply { mkdirs() }
        val audioFile = File.createTempFile("navis_elabs_", ".mp3", cacheDir)
        try {
            audioFile.writeBytes(bytes)
        } catch (t: Throwable) {
            NavisLog.e(TAG, "Failed writing temp audio file", t)
            audioFile.delete()
            return@withContext Result.failure(t)
        }

        stop()
        val fis = FileInputStream(audioFile)
        val descriptor = fis.fd
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(descriptor)
        player.setOnCompletionListener {
            audioFile.delete()
            stop()
        }
        player.setOnErrorListener { _, what, extra ->
            NavisLog.e(TAG, "MediaPlayer error what=$what extra=$extra")
            audioFile.delete()
            stop()
            true
        }
        try {
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (t: Throwable) {
            NavisLog.e(TAG, "Failed to start MediaPlayer", t)
            audioFile.delete()
            player.release()
            fis.close()
            return@withContext Result.failure(t)
        }
        fis.close()
        Result.success(Unit)
    }
}

sealed interface VoicePlaybackOutcome {
    data object Success : VoicePlaybackOutcome
    data class Failure(val reason: String) : VoicePlaybackOutcome
}
