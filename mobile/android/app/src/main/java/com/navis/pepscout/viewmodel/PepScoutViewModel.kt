package com.navis.pepscout.viewmodel

import android.app.Application
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.navis.pepscout.HeadingTracker
import com.navis.pepscout.data.Cache
import com.navis.pepscout.data.Keystore
import com.navis.pepscout.data.PrefsStore
import com.navis.pepscout.detector.HazardDetector
import com.navis.pepscout.net.ElevenLabsClient
import com.navis.pepscout.net.GeminiClient
import com.navis.pepscout.net.NeuralSeekClient
import com.navis.pepscout.net.OsrmClient
import com.navis.pepscout.nav.IndoorEngine
import com.navis.pepscout.nav.IndoorGraph
import com.navis.pepscout.nav.StepFormatter
import com.navis.pepscout.stt.SpeechCapture
import com.navis.pepscout.tts.VoicePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PepScoutViewModel"

class PepScoutViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    // Location & Heading
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
        .setMinUpdateIntervalMillis(2000L)
        .setWaitForAccurateLocation(false)
        .build()
    private var locationCallback: LocationCallback? = null
    private val headingTracker = HeadingTracker(context)

    // Data sources
    private val cache = Cache(context)
    private val keystore = Keystore(context)
    private val prefsStore = PrefsStore(context)
    private val elevenLabsClient = ElevenLabsClient(context, keystore, cache)
    private val voicePlayer = VoicePlayer(context, elevenLabsClient, prefsStore, cache)
    private val geminiClient = GeminiClient(keystore)
    private val neuralSeekClient = NeuralSeekClient(keystore)
    private val osrmClient = OsrmClient()
    private val indoorGraph = IndoorGraph(context)
    private val indoorEngine = IndoorEngine(indoorGraph)
    private val formatter = StepFormatter()
    private val speechCapture = SpeechCapture(context)

    init {
        headingTracker.start()
        viewModelScope.launch {
            val loaded = indoorGraph.loadGraph()
            _indoorGraphLoaded.value = loaded
            if (loaded) {
                Log.d(TAG, "Indoor graph ready")
            }
        }
        _apiKeyStatus.value = ApiKeyStatus(
            hasEleven = keystore.hasApiKey(Keystore.ELEVENLABS_API_KEY),
            hasGemini = keystore.hasApiKey(Keystore.GEMINI_API_KEY),
            hasNeural = keystore.hasApiKey(Keystore.NEURALSEEK_API_KEY)
        )
    }

    // Permissions
    private val _locationPermissionGranted = MutableStateFlow(false)
    private val _cameraPermissionGranted = MutableStateFlow(false)
    private val _microphonePermissionGranted = MutableStateFlow(false)
    val locationPermissionGranted: StateFlow<Boolean> = _locationPermissionGranted.asStateFlow()
    val cameraPermissionGranted: StateFlow<Boolean> = _cameraPermissionGranted.asStateFlow()
    val microphonePermissionGranted: StateFlow<Boolean> = _microphonePermissionGranted.asStateFlow()

    // Location state
    private val _isTrackingLocation = MutableStateFlow(false)
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val isTrackingLocation: StateFlow<Boolean> = _isTrackingLocation.asStateFlow()
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()
    val heading: StateFlow<Float?> = headingTracker.heading

    // Outdoor navigation
    private val _outdoorRouteSummary = MutableStateFlow<OutdoorRouteSummary?>(null)
    private val _outdoorInstructions = MutableStateFlow<List<String>>(emptyList())
    val outdoorRouteSummary: StateFlow<OutdoorRouteSummary?> = _outdoorRouteSummary.asStateFlow()
    val outdoorInstructions: StateFlow<List<String>> = _outdoorInstructions.asStateFlow()

    // Indoor navigation
    private val _indoorInstructions = MutableStateFlow<List<String>>(emptyList())
    private val _indoorProgress = MutableStateFlow(0f)
    private val _indoorGraphLoaded = MutableStateFlow(false)
    private val _indoorStatusMessage = MutableStateFlow("Idle")
    val indoorInstructions: StateFlow<List<String>> = _indoorInstructions.asStateFlow()
    val indoorProgress: StateFlow<Float> = _indoorProgress.asStateFlow()
    val indoorGraphLoaded: StateFlow<Boolean> = _indoorGraphLoaded.asStateFlow()
    val indoorStatusMessage: StateFlow<String> = _indoorStatusMessage.asStateFlow()
    val indoorState: StateFlow<IndoorEngine.IndoorNavState> = indoorEngine.state

    // Hazards
    private val _hazards = MutableStateFlow<List<HazardDetector.HazardEvent>>(emptyList())
    private val _hazardDetectionActive = MutableStateFlow(false)
    val hazards: StateFlow<List<HazardDetector.HazardEvent>> = _hazards.asStateFlow()
    val hazardDetectionActive: StateFlow<Boolean> = _hazardDetectionActive.asStateFlow()

    // Speech & voice
    val speechState: StateFlow<SpeechCapture.SpeechState> = speechCapture.state
    val partialTranscript: StateFlow<String> = speechCapture.partialResult
    val finalTranscript: StateFlow<String> = speechCapture.finalResult
    private val _assistantReply = MutableStateFlow("Ask Pep Scout about routes or accessibility.")
    val assistantReply: StateFlow<String> = _assistantReply.asStateFlow()

    // API keys
    private val _apiKeyStatus = MutableStateFlow(ApiKeyStatus(false, false, false))
    val apiKeyStatus: StateFlow<ApiKeyStatus> = _apiKeyStatus.asStateFlow()

    val cvEnabled: StateFlow<Boolean> = prefsStore.cvEnabled

    fun onLocationPermissionChanged(granted: Boolean) {
        _locationPermissionGranted.value = granted
        if (granted) {
            startLocationUpdates()
        } else {
            stopLocationUpdates()
        }
    }

    fun onCameraPermissionChanged(granted: Boolean) {
        _cameraPermissionGranted.value = granted
    }

    fun onMicrophonePermissionChanged(granted: Boolean) {
        _microphonePermissionGranted.value = granted
    }

    private fun startLocationUpdates() {
        if (locationCallback != null) return
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    _currentLocation.value = location
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
        _isTrackingLocation.value = true
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        _isTrackingLocation.value = false
    }

    fun setCvEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsStore.setCvEnabled(enabled)
        }
    }

    fun onHazardDetected(event: HazardDetector.HazardEvent) {
        val fresh = listOf(event) + _hazards.value.take(4)
        _hazards.value = fresh
        viewModelScope.launch {
            val message = formatter.formatSafetyWarning(event.label, event.severity)
            voicePlayer.speak(message, VoicePlayer.Priority.Urgent)
        }
    }

    fun onHazardDetectionStarted() {
        _hazardDetectionActive.value = true
    }

    fun onHazardDetectionStopped() {
        _hazardDetectionActive.value = false
    }

    fun startIndoorNavigation(avoidStairs: Boolean = false) {
        if (!_indoorGraphLoaded.value) {
            _indoorStatusMessage.value = "Loading indoor graph..."
            return
        }

        viewModelScope.launch {
            val success = indoorEngine.startNavigation(
                startNodeId = "ENTR",
                endNodeId = "DEST",
                avoidStairs = avoidStairs
            )
            if (success) {
                _indoorStatusMessage.value = "Route ready"
                refreshIndoorSteps()
                _indoorProgress.value = indoorEngine.getProgress()
                indoorEngine.getCurrentInstruction()?.let { firstInstruction ->
                    voicePlayer.speak(firstInstruction, VoicePlayer.Priority.Normal)
                }
            } else {
                _indoorStatusMessage.value = "Unable to find route"
                voicePlayer.speak("Unable to find inside path.", VoicePlayer.Priority.Low)
            }
        }
    }

    fun onIndoorQrScanned(payload: String) {
        indoorEngine.onQrScanned(payload)
        _indoorProgress.value = indoorEngine.getProgress()
        refreshIndoorSteps()
        indoorEngine.getCurrentInstruction()?.let { instruction ->
            voicePlayer.speak(instruction, VoicePlayer.Priority.Normal)
        }
        if (indoorEngine.state.value is IndoorEngine.IndoorNavState.Completed) {
            voicePlayer.speak(formatter.formatCompletionMessage("Destination"), VoicePlayer.Priority.Urgent)
        }
    }

    fun startListening() {
        if (!_microphonePermissionGranted.value) {
            _assistantReply.value = "Microphone access is required for voice commands."
            return
        }

        speechCapture.startListening(
            onResult = this::handleVoiceCommand,
            onError = { message -> _assistantReply.value = message }
        )
    }

    fun stopListening() {
        speechCapture.stopListening()
    }

    fun askQuestion(text: String) {
        viewModelScope.launch {
            if (text.isBlank()) return@launch
            val response = askNeuralSeek(text)
            _assistantReply.value = response
            voicePlayer.speak(response, VoicePlayer.Priority.Normal)
        }
    }

    private fun handleVoiceCommand(transcript: String) {
        viewModelScope.launch {
            _assistantReply.value = "Processing your voice command..."
            val intent = geminiClient.classifyIntent(transcript)
            if (intent == null) {
                _assistantReply.value = "I couldn't interpret that command."
                return@launch
            }

            when (intent.action) {
                "navigate" -> {
                    val destLat = intent.to?.lat
                    val destLon = intent.to?.lon
                    val destName = intent.to?.name ?: "SAC Bus Stop"
                    startOutdoorRoute(destName, destLat, destLon)
                }
                "qa" -> {
                    val question = intent.question ?: transcript
                    val answer = askNeuralSeek(question)
                    _assistantReply.value = answer
                    voicePlayer.speak(answer, VoicePlayer.Priority.Normal)
                }
                else -> {
                    _assistantReply.value = "I heard \"$transcript\" but could not act on it."
                }
            }
        }
    }

    private suspend fun askNeuralSeek(question: String): String {
        return withContext(Dispatchers.IO) {
            val answer = neuralSeekClient.askQuestion(question)
            answer?.let {
                "${it.answer} (source: ${it.source_hint})"
            } ?: "I'm not sure about that right now. Try again in a moment."
        }
    }

    fun startOutdoorRoute(
        destinationName: String = "SAC Bus Stop",
        lat: Double? = null,
        lon: Double? = null
    ) {
        val origin = _currentLocation.value
        if (origin == null) {
            _assistantReply.value = "Waiting for GPS fix before routing."
            return
        }

        val destination = lat?.let { latValue ->
            lon?.let { lonValue -> Pair(latValue, lonValue) }
        } ?: knownDestinations[destinationName.lowercase()]

        if (destination == null) {
            _assistantReply.value = "Unknown destination: $destinationName"
            return
        }

        viewModelScope.launch {
            val route = withContext(Dispatchers.IO) {
                osrmClient.getRoute(origin.longitude, origin.latitude, destination.second, destination.first)
            }
            if (route == null) {
                _assistantReply.value = "Route lookup failed. Try again."
                return@launch
            }

            val rawSteps = route.steps.mapNotNull { step ->
                step.maneuver?.let { maneuver ->
                    buildString {
                        append(maneuver.type)
                        maneuver.modifier?.let { mod -> append(" $mod") }
                        step.name?.let { name -> append(" onto $name") }
                    }
                }
            }

            val friendlySteps = geminiClient.rewriteSteps(rawSteps) ?: formatter.formatOutdoorSteps(rawSteps)
            _outdoorInstructions.value = friendlySteps
            val geometry = osrmClient.decodePolyline(route.geometry)
            _outdoorRouteSummary.value = OutdoorRouteSummary(
                distanceMeters = route.distance,
                durationSeconds = route.duration,
                points = geometry,
                friendlySteps = friendlySteps
            )

            voicePlayer.preload(friendlySteps)
            friendlySteps.firstOrNull()?.let { voicePlayer.speak(it, VoicePlayer.Priority.Normal) }
            _assistantReply.value = "Outdoor route ready for ${destinationName.replaceFirstChar { it.uppercaseChar() }}."
        }
    }

    fun storeApiKeys(eleven: String, gemini: String, neural: String) {
        viewModelScope.launch {
            keystore.setElevenLabsKey(eleven)
            keystore.setGeminiKey(gemini)
            keystore.setNeuralSeekKey(neural)
            _apiKeyStatus.value = ApiKeyStatus(
                hasEleven = keystore.hasApiKey(Keystore.ELEVENLABS_API_KEY),
                hasGemini = keystore.hasApiKey(Keystore.GEMINI_API_KEY),
                hasNeural = keystore.hasApiKey(Keystore.NEURALSEEK_API_KEY)
            )
        }
    }

    private fun refreshIndoorSteps() {
        val path = indoorEngine.getPathSnapshot()
        val steps = path?.steps ?: emptyList()
        _indoorInstructions.value = formatter.formatIndoorSteps(steps)
    }

    override fun onCleared() {
        stopLocationUpdates()
        headingTracker.stop()
        speechCapture.destroy()
        voicePlayer.destroy()
        super.onCleared()
    }

    fun cleanUp() {
        stopLocationUpdates()
        headingTracker.stop()
        speechCapture.destroy()
        voicePlayer.destroy()
    }

    companion object {
        private val knownDestinations = mapOf(
            "library" to Pair(43.4723, -80.5449),
            "sac bus stop" to Pair(43.4713, -80.5461)
        )
    }

    data class OutdoorRouteSummary(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<Pair<Double, Double>>,
        val friendlySteps: List<String>
    )

    data class ApiKeyStatus(
        val hasEleven: Boolean,
        val hasGemini: Boolean,
        val hasNeural: Boolean
    )
}
