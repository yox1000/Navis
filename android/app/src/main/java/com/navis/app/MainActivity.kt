package com.navis.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.MotionEvent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.animateColorAsState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import org.osmdroid.util.BoundingBox
import android.graphics.Color as AndroidColor
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.navis.app.R
import com.navis.app.location.LocalPoiResolver
import com.navis.app.location.NominatimClient
import com.navis.app.routing.OsrmClient
import com.navis.app.routing.OsrmRoute
import com.navis.app.util.NavisLog
import com.navis.app.voice.ElevenLabsVoice
import com.navis.app.voice.GeminiClient
import com.navis.app.voice.NeuralSeekClient
import com.navis.app.voice.NeuralSeekIntent
import com.navis.app.voice.NeuralSeekResult
import com.navis.app.voice.VoicePlaybackOutcome
import com.navis.app.voice.parseNeuralSeekIntent
import com.navis.app.ui.theme.NavisAppTheme
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val TAG = "NavisMapDebug"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavisLog.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            NavisAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RealTimeDashboard()
                }
            }
        }
    }
}

@Composable
fun RealTimeDashboard() {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }
    val currentTime by produceState(initialValue = LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(1000)
        }
    }

    val context = LocalContext.current
    val requiredPermissions = remember {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }

    val permissionState = remember {
        mutableStateOf(resolvePermissionSnapshot(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantedMap ->
        val locationGranted = grantedMap[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grantedMap[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            permissionState.value.hasLocation
        val audioGranted = grantedMap[Manifest.permission.RECORD_AUDIO] == true ||
            permissionState.value.hasAudio
        permissionState.value = PermissionSnapshot(
            hasLocation = locationGranted,
            hasAudio = audioGranted
        )
    }

    LaunchedEffect(Unit) {
        val snapshot = permissionState.value
        if (!snapshot.hasLocation || !snapshot.hasAudio) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    val hasLocationPermission = permissionState.value.hasLocation
    val hasAudioPermission = permissionState.value.hasAudio
    val latestLocation by rememberLatestLocation(isActive = hasLocationPermission)
    val mapTilerKey = stringResource(R.string.maptiler_key)
    val mapView = rememberMapViewWithLifecycle(mapTilerKey)
    var isListening by remember { mutableStateOf(false) }
    var ttsStatus by remember { mutableStateOf<TtsStatus>(TtsStatus.Idle) }
    var intentStatus by remember { mutableStateOf<IntentStatus>(IntentStatus.Idle) }
    var lastAnnouncedText by remember { mutableStateOf("") }
    val elevenLabsKey = stringResource(R.string.elevenlabs_api_key)
    val elevenLabsVoice = rememberElevenLabsVoice(elevenLabsKey)
    val neuralSeekUrl = stringResource(R.string.neuralseek_url)
    val neuralSeekKey = stringResource(R.string.neuralseek_key)
    val neuralSeekClient = rememberNeuralSeekClient(neuralSeekUrl, neuralSeekKey)
    val geminiKey = stringResource(R.string.gemini_api_key)
    val geminiClient = rememberGeminiClient(geminiKey)
    val nominatimClient = rememberNominatimClient()
    val osrmClient = rememberOsrmClient()
    var routeState by remember { mutableStateOf<RouteState?>(null) }
    var driftState by remember { mutableStateOf(DriftState()) }
    val appContext = LocalContext.current.applicationContext

    LaunchedEffect(appContext) {
        LocalPoiResolver.loadFromAssets(appContext)
    }

    LaunchedEffect(hasAudioPermission) {
        if (!hasAudioPermission) {
            isListening = false
        }
    }

    val speechState by rememberSpeechRecognizerState(
        shouldListen = isListening && hasAudioPermission,
        isRecognizerEnabled = hasAudioPermission,
        locale = Locale.getDefault()
    )
    val voiceLevel = speechState.voiceLevel
    val userMarker = remember(mapView) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "You are here"
        }
    }
    val routePolyline = remember(mapView) {
        Polyline(mapView).apply {
            outlinePaint.color = AndroidColor.parseColor("#4A90E2")
            outlinePaint.strokeWidth = 8f
            outlinePaint.isAntiAlias = true
        }
    }
    val destinationMarker = remember(mapView) {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
    }

    LaunchedEffect(mapView, routeState) {
        routeState
            ?.takeIf { it.points.size > 1 }
            ?.let { current ->
                val bbox = BoundingBox.fromGeoPoints(current.points)
                mapView.post {
                    mapView.zoomToBoundingBox(bbox, true, 100)
                }
            }
    }

    LaunchedEffect(elevenLabsKey) {
        if (elevenLabsKey.isBlank()) {
            ttsStatus = TtsStatus.Error("Add ElevenLabs key to enable responses")
        } else {
            ttsStatus = TtsStatus.Idle
        }
    }

    LaunchedEffect(speechState.lastFinalText, neuralSeekClient, elevenLabsVoice, geminiClient) {
        val transcript = speechState.lastFinalText.trim()
        if (transcript.isBlank() || transcript == lastAnnouncedText) return@LaunchedEffect

        intentStatus = IntentStatus.Loading(transcript)

        val resolvedIntent = resolveIntentWithNeuralSeek(
            transcript = transcript,
            neuralSeekClient = neuralSeekClient,
            geminiClient = geminiClient
        ).getOrElse { err ->
            val msg = err.message ?: "Intent assistant unavailable"
            intentStatus = IntentStatus.Error(msg)
            ttsStatus = TtsStatus.Error(msg)
            lastAnnouncedText = ""
            return@LaunchedEffect
        }

        intentStatus = IntentStatus.Success(transcript, resolvedIntent)
        val assistant = elevenLabsVoice ?: run {
            ttsStatus = TtsStatus.Error("ElevenLabs unavailable")
            return@LaunchedEffect
        }

        suspend fun speakAndTrack(message: String): Boolean {
            ttsStatus = TtsStatus.Speaking(message)
            return when (val outcome = assistant.speak(message)) {
                is VoicePlaybackOutcome.Success -> {
                    lastAnnouncedText = transcript
                    ttsStatus = TtsStatus.Completed(message)
                    true
                }
                is VoicePlaybackOutcome.Failure -> {
                    lastAnnouncedText = ""
                    ttsStatus = TtsStatus.Error(outcome.reason)
                    false
                }
            }
        }

        when (resolvedIntent.intent) {
            1 -> {
                val origin = latestLocation
                if (origin == null) {
                    val msg = "I need your location before I can start navigation."
                    routeState = null
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }
                val originPoint = GeoPoint(origin.latitude, origin.longitude)
                val localCandidate = LocalPoiResolver.resolve(transcript)
                val needsExternalLookup = localCandidate == null
                val locationHint = buildLocationHint(originPoint)
                val osmDestination = if (needsExternalLookup && nominatimClient != null) {
                    nominatimClient.search(transcript, originPoint)
                        .onFailure { err ->
                            NavisLog.w(TAG, "Nominatim lookup failed: ${err.message}")
                        }
                        .getOrNull()
                        ?.also {
                            NavisLog.d(
                                TAG,
                                "Nominatim resolved ${it.name} lat=${it.latitude} lon=${it.longitude}"
                            )
                        }
                } else {
                    null
                }
                var geminiError: String? = null
                val geminiDestination = if (!needsExternalLookup) {
                    null
                } else {
                    val gemini = geminiClient ?: run {
                        val msg = "Destination assistant unavailable"
                        routeState = null
                        intentStatus = IntentStatus.Error(msg)
                        speakAndTrack(msg)
                        return@LaunchedEffect
                    }
                    gemini.resolveDestination(
                        userQuery = transcript,
                        originLat = origin.latitude,
                        originLon = origin.longitude,
                        locationHint = locationHint
                    ).getOrElse { err ->
                        geminiError = err.message ?: "Couldn't understand that destination"
                        null
                    }
                }

                val primaryCandidate = chooseExternalDestination(
                    origin = originPoint,
                    osmCandidate = osmDestination,
                    geminiCandidate = geminiDestination
                )
                val chosenDestination = selectPreferredDestination(
                    origin = originPoint,
                    primary = primaryCandidate,
                    localCandidate = localCandidate
                ) ?: run {
                    val msg = when {
                        localCandidate == null && primaryCandidate == null ->
                            geminiError ?: "Couldn't understand that destination"
                        else -> "Unable to resolve that destination nearby."
                    }
                    routeState = null
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }

                val destinationPoint = GeoPoint(chosenDestination.latitude, chosenDestination.longitude)
                val osrmResult = osrmClient.requestRoute(originPoint, destinationPoint).getOrElse { err ->
                    val msg = err.message ?: "Routing service unavailable"
                    routeState = null
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }

                val adjustedRoute = adjustRouteForShortWalks(
                    originPoint = originPoint,
                    destinationPoint = destinationPoint,
                    osrmRoute = osrmResult,
                    destinationName = chosenDestination.name
                )
                routeState = adjustedRoute.also { state ->
                    NavisLog.i(
                        TAG,
                        "Route established to ${state.destinationName} points=${state.points.size} distance=${state.distanceMeters}"
                    )
                }
                val summary = buildRouteSummary(adjustedRoute)
                driftState = DriftState()
                speakAndTrack(summary)
            }
            2 -> {
                val currentRoute = routeState
                if (currentRoute == null) {
                    val msg = "You’re not currently navigating anywhere. Ask me to start a route first."
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }
                val origin = latestLocation
                if (origin == null) {
                    val msg = "I need a fresh location fix to check your route."
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }
                val userPoint = GeoPoint(origin.latitude, origin.longitude)
                val progress = evaluateRouteProgress(currentRoute, userPoint)
                NavisLog.d(
                    TAG,
                    "Route check onRoute=${progress.onRoute} distToRoute=${progress.distanceToRouteMeters}m " +
                        "remaining=${progress.distanceRemainingMeters}m percent=${progress.percentComplete}"
                )
                val message = buildRouteCheckMessage(currentRoute, progress)
                speakAndTrack(message)
            }
            3 -> {
                val origin = latestLocation
                if (origin == null) {
                    val msg = "I need your location before I can look for nearby places."
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }
                val originPoint = GeoPoint(origin.latitude, origin.longitude)
                val category = resolvedIntent.entity.ifBlank { "interesting places" }
                val explorer = nominatimClient
                if (explorer == null) {
                    val msg = "Nearby search is unavailable right now."
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }
                val nearbyResults = explorer.searchNearby(
                    query = category,
                    origin = originPoint
                ).getOrElse { err ->
                    val msg = err.message ?: "Nearby search failed"
                    intentStatus = IntentStatus.Error(msg)
                    speakAndTrack(msg)
                    return@LaunchedEffect
                }
                val annotated = nearbyResults.mapNotNull { dest ->
                    val point = GeoPoint(dest.latitude, dest.longitude)
                    val distance = originPoint.distanceToAsDouble(point)
                    if (distance.isNaN() || distance > NEARBY_MAX_DISTANCE_METERS) {
                        null
                    } else {
                        NearbyPlace(dest, point, distance)
                    }
                }.sortedBy { it.distanceMeters }
                val message = buildNearbySummary(category, originPoint, annotated)
                NavisLog.d(
                    TAG,
                    "Nearby search '$category' returned ${annotated.size} matches"
                )
                val neuralMessage = neuralSeekClient?.let { client ->
                    client.queryWithLocation(
                        agent = NEURALSEEK_EXPLORE_AGENT,
                        queryText = transcript,
                        latitude = origin.latitude,
                        longitude = origin.longitude
                    ).mapCatching { result ->
                        parseNeuralSeekMessage(result.rawText)
                    }.getOrElse { err ->
                        NavisLog.w(TAG, "NeuralSeek explore insight failed: ${err.message}")
                        null
                    }
                }?.takeIf { !it.isNullOrBlank() }

                val spokenMessage = neuralMessage ?: message
                speakAndTrack(spokenMessage)
            }
            else -> {
                val spoken = resolvedIntent.friendlyDescription
                speakAndTrack(spoken)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { map ->
                if (!map.overlays.contains(userMarker)) {
                    map.overlays.add(userMarker)
                    NavisLog.d(TAG, "User marker overlay added to map")
                }
                val currentRoute = routeState
                if (currentRoute?.points?.isNotEmpty() == true) {
                    routePolyline.setPoints(currentRoute.points)
                    if (!map.overlays.contains(routePolyline)) {
                        map.overlays.add(routePolyline)
                        NavisLog.d(TAG, "Route polyline added to map (points=${currentRoute.points.size})")
                    }
                    destinationMarker.apply {
                        position = currentRoute.destinationPoint
                        title = currentRoute.destinationName
                        subDescription = "Lat %.5f, Lng %.5f".format(
                            position.latitude,
                            position.longitude
                        )
                    }
                    if (!map.overlays.contains(destinationMarker)) {
                        map.overlays.add(destinationMarker)
                        NavisLog.d(TAG, "Destination marker added to map")
                    }
                } else {
                    map.overlays.remove(routePolyline)
                    map.overlays.remove(destinationMarker)
                }

                val targetPoint = latestLocation?.let { GeoPoint(it.latitude, it.longitude) }
                if (targetPoint != null) {
                    userMarker.position = targetPoint
                    if (currentRoute == null) {
                        map.controller.setCenter(targetPoint)
                    }
                    NavisLog.d(
                        TAG,
                        "Updated map center to lat=${"%.5f".format(targetPoint.latitude)} lng=${"%.5f".format(targetPoint.longitude)}"
                    )
                }
                map.invalidate()
            }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            Text(
                text = "Time: ${timeFormatter.format(currentTime)}",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = latestLocation?.let {
                    "Lat: %.5f, Lng: %.5f".format(it.latitude, it.longitude)
                } ?: if (hasLocationPermission) "Fetching coordinates..." else "Waiting for permission...",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        PushToTalkControls(
            isListening = isListening,
            hasLocationPermission = hasLocationPermission,
            hasAudioPermission = hasAudioPermission,
            voiceLevel = voiceLevel,
            speechState = speechState,
            ttsStatus = ttsStatus,
            intentStatus = intentStatus,
            onPressChanged = { listening -> isListening = listening },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp)
        )
    }

    LaunchedEffect(routeState, latestLocation, ttsStatus, isListening) {
        val currentRoute = routeState ?: return@LaunchedEffect
        val currentLocation = latestLocation ?: return@LaunchedEffect
        if (ttsStatus is TtsStatus.Speaking || isListening) return@LaunchedEffect

        val now = System.currentTimeMillis()
        val progress = evaluateRouteProgress(currentRoute, GeoPoint(currentLocation.latitude, currentLocation.longitude))
        val offRoute = progress.distanceToRouteMeters > ROUTE_OFF_DISTANCE_METERS
        if (offRoute && now - driftState.lastAnnouncementAt >= DRIFT_COOLDOWN_MS) {
            val warning = buildDriftWarning(progress)
            elevenLabsVoice?.let { assistant ->
                ttsStatus = TtsStatus.Speaking(warning)
                when (val outcome = assistant.speak(warning)) {
                    is VoicePlaybackOutcome.Success -> {
                        ttsStatus = TtsStatus.Completed(warning)
                        driftState = driftState.copy(lastAnnouncementAt = System.currentTimeMillis())
                    }
                    is VoicePlaybackOutcome.Failure -> {
                        ttsStatus = TtsStatus.Error(outcome.reason)
                    }
                }
            }
        }
    }
}

@Composable
@SuppressLint("MissingPermission")
fun rememberLatestLocation(
    isActive: Boolean
): androidx.compose.runtime.State<Location?> {
    val context = LocalContext.current
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    val locationState = remember { mutableStateOf<Location?>(null) }

    DisposableEffect(isActive) {
        if (!isActive) {
            locationState.value = null
            return@DisposableEffect onDispose {}
        }

        fusedClient.lastLocation
            .addOnSuccessListener { last ->
                if (last != null) {
                    locationState.value = last
                    NavisLog.d(
                        TAG,
                        "Last known location lat=${last.latitude} lng=${last.longitude}"
                    )
                } else {
                    NavisLog.w(TAG, "No last known location available")
                }
            }
            .addOnFailureListener { err ->
                NavisLog.e(TAG, "Failed to fetch last known location", err)
            }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val latest = result.lastLocation
                if (latest != null) {
                    locationState.value = latest
                    NavisLog.v(
                        TAG,
                        "Fused location lat=${latest.latitude} lng=${latest.longitude} acc=${latest.accuracy}"
                    )
                } else {
                    locationState.value = null
                    NavisLog.w(TAG, "Fused location update returned null lastLocation")
                }
            }
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1_000L
        )
            .setMinUpdateDistanceMeters(0f)
            .build()

        fusedClient.requestLocationUpdates(
            request,
            callback,
            context.mainLooper
        )

        onDispose {
            fusedClient.removeLocationUpdates(callback)
        }
    }

    return locationState
}

@Composable
private fun rememberMapViewWithLifecycle(apiKey: String): MapView {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val tileSource = rememberMapTilerTileSource(apiKey)
    val prefs = remember {
        appContext.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
    }
    LaunchedEffect(appContext, prefs) {
        Configuration.getInstance().load(appContext, prefs)
        Configuration.getInstance().userAgentValue = appContext.packageName
        NavisLog.i(TAG, "OSMDroid configured userAgent=${Configuration.getInstance().userAgentValue}")
    }
    val mapView = remember(tileSource) {
        MapView(context).apply {
            setTileSource(tileSource)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            isTilesScaledToDpi = true
            controller.setZoom(17.0)
            controller.setCenter(DEFAULT_GEO_POINT)
            NavisLog.i(TAG, "MapView created; default center=$DEFAULT_GEO_POINT")
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                mapView.onResume()
                NavisLog.d(TAG, "MapView lifecycle onResume")
            }

            override fun onPause(owner: LifecycleOwner) {
                mapView.onPause()
                NavisLog.d(TAG, "MapView lifecycle onPause")
            }

            override fun onDestroy(owner: LifecycleOwner) {
                mapView.onDetach()
                NavisLog.d(TAG, "MapView lifecycle onDestroy")
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
            NavisLog.d(TAG, "MapView disposed via DisposableEffect")
        }
    }

    return mapView
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    NavisAppTheme {
        Text("Map preview unavailable in static preview")
    }
}

private val DEFAULT_GEO_POINT = GeoPoint(40.7128, -74.0060)

@Composable
private fun rememberMapTilerTileSource(apiKey: String): OnlineTileSourceBase {
    return remember(apiKey) {
        object : OnlineTileSourceBase(
            "MapTiler-StreetsV2",
            0,
            20,
            512,
            ".png",
            arrayOf("https://api.maptiler.com/maps/streets-v2/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val zoom = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                val url = buildString {
                    append(getBaseUrl())
                    append(zoom).append("/")
                    append(x).append("/")
                    append(y)
                    append("@2x")
                    append(mImageFilenameEnding)
                    append("?key=").append(apiKey)
                    append("&dpi=192")
                }
                NavisLog.v(TAG, "Requesting tile url=$url")
                return url
            }
        }
    }
}

@Composable
private fun rememberSpeechRecognizerState(
    shouldListen: Boolean,
    isRecognizerEnabled: Boolean,
    locale: Locale = Locale.getDefault()
): androidx.compose.runtime.State<SpeechUiState> {
    val context = LocalContext.current
    val recognizerAvailable = remember {
        SpeechRecognizer.isRecognitionAvailable(context)
    }
    val recognizer = remember(recognizerAvailable) {
        if (recognizerAvailable) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val state = remember {
        mutableStateOf(
            SpeechUiState(isRecognizerAvailable = recognizerAvailable)
        )
    }
    var sessionActive by remember { mutableStateOf(false) }

    DisposableEffect(recognizer) {
        if (recognizer == null) {
            onDispose {}
        } else {
            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    NavisLog.d(TAG, "SpeechRecognizer ready")
                }

                override fun onBeginningOfSpeech() {
                    state.value = state.value.copy(
                        errorMessage = null,
                        partialText = "",
                        isSessionActive = true,
                        voiceLevel = VoiceLevelReading.Idle
                    )
                }

                override fun onRmsChanged(rmsdB: Float) {
                    state.value = state.value.copy(
                        voiceLevel = VoiceLevelReading.fromRms(rmsdB)
                    )
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    state.value = state.value.copy(
                        isSessionActive = false,
                        voiceLevel = VoiceLevelReading.Idle
                    )
                }

                override fun onError(error: Int) {
                    val msg = speechRecognizerErrorMessage(error)
                    NavisLog.e(TAG, "SpeechRecognizer error $error -> $msg")
                    state.value = state.value.copy(
                        errorMessage = msg,
                        partialText = "",
                        isSessionActive = false,
                        voiceLevel = VoiceLevelReading.Idle
                    )
                    sessionActive = false
                }

                override fun onResults(results: Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (spoken.isNotBlank()) {
                        state.value = state.value.copy(
                            lastFinalText = spoken,
                            partialText = "",
                            errorMessage = null,
                            isSessionActive = false,
                            voiceLevel = VoiceLevelReading.Idle
                        )
                    }
                    sessionActive = false
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        state.value = state.value.copy(
                            partialText = partial,
                            errorMessage = null,
                            isSessionActive = true
                        )
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
            recognizer.setRecognitionListener(listener)
            onDispose {
                recognizer.setRecognitionListener(null)
                recognizer.destroy()
            }
        }
    }

    LaunchedEffect(recognizerAvailable) {
        state.value = state.value.copy(isRecognizerAvailable = recognizerAvailable)
    }

    LaunchedEffect(recognizer, shouldListen, isRecognizerEnabled, recognizerAvailable, locale) {
        if (!recognizerAvailable || recognizer == null) {
            state.value = state.value.copy(isRecognizerAvailable = false)
            return@LaunchedEffect
        }
        if (!isRecognizerEnabled) {
            if (sessionActive) {
                recognizer.stopListening()
                sessionActive = false
            }
            state.value = state.value.copy(
                isSessionActive = false,
                voiceLevel = VoiceLevelReading.Idle
            )
            return@LaunchedEffect
        }
        if (shouldListen && !sessionActive) {
            val languageTag = locale.toLanguageTag()
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Navis assistant is listening")
            }
            recognizer.cancel()
            sessionActive = true
            state.value = state.value.copy(
                partialText = "",
                errorMessage = null,
                isSessionActive = true,
                voiceLevel = VoiceLevelReading.Idle
            )
            recognizer.startListening(intent)
            NavisLog.d(TAG, "SpeechRecognizer startListening")
        } else if (!shouldListen && sessionActive) {
            recognizer.stopListening()
            sessionActive = false
            state.value = state.value.copy(
                isSessionActive = false,
                voiceLevel = VoiceLevelReading.Idle
            )
            NavisLog.d(TAG, "SpeechRecognizer stopListening")
        } else if (!shouldListen) {
            state.value = state.value.copy(
                isSessionActive = false,
                voiceLevel = VoiceLevelReading.Idle
            )
        }
    }

    return state
}

@Stable
private data class SpeechUiState(
    val isRecognizerAvailable: Boolean = true,
    val partialText: String = "",
    val lastFinalText: String = "",
    val errorMessage: String? = null,
    val isSessionActive: Boolean = false,
    val voiceLevel: VoiceLevelReading = VoiceLevelReading.Idle
)

@Stable
private data class VoiceLevelReading(
    val normalized: Float,
    val decibels: Float
) {
    companion object {
        val Idle = VoiceLevelReading(0f, VOICE_MIN_DECIBELS)
        fun fromRms(rms: Float?): VoiceLevelReading {
            val value = rms ?: VOICE_MIN_DECIBELS
            val clamped = value.coerceIn(VOICE_MIN_DECIBELS, VOICE_MAX_DECIBELS)
            val normalized = (clamped - VOICE_MIN_DECIBELS) / (VOICE_MAX_DECIBELS - VOICE_MIN_DECIBELS)
            return VoiceLevelReading(normalized.coerceIn(0f, 1f), clamped)
        }
    }
}

@Stable
private sealed class TtsStatus {
    data object Idle : TtsStatus()
    data class Speaking(val message: String) : TtsStatus()
    data class Completed(val message: String) : TtsStatus()
    data class Error(val reason: String) : TtsStatus()
}

@Stable
private sealed class IntentStatus {
    data object Idle : IntentStatus()
    data class Loading(val transcript: String) : IntentStatus()
    data class Success(val transcript: String, val intent: NeuralSeekIntent) : IntentStatus()
    data class Error(val reason: String, val raw: String? = null) : IntentStatus()
}

private data class PermissionSnapshot(
    val hasLocation: Boolean,
    val hasAudio: Boolean
)

private fun resolvePermissionSnapshot(context: Context): PermissionSnapshot {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val audio = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    return PermissionSnapshot(
        hasLocation = fine || coarse,
        hasAudio = audio
    )
}

private const val VOICE_MIN_DECIBELS = -5f
private const val VOICE_MAX_DECIBELS = 12f

private data class RouteState(
    val destinationName: String,
    val destinationPoint: GeoPoint,
    val points: List<GeoPoint>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val firstInstruction: String?,
    val isSimplified: Boolean
) {
}

private data class RouteProgress(
    val onRoute: Boolean,
    val distanceToRouteMeters: Double,
    val distanceTraveledMeters: Double,
    val distanceRemainingMeters: Double,
    val percentComplete: Double,
    val estimatedDurationSeconds: Double,
    val nearestPoint: GeoPoint,
    val bearingToRoute: Double
)

private data class NearbyPlace(
    val destination: GeminiClient.Destination,
    val point: GeoPoint,
    val distanceMeters: Double
)

private data class DriftState(
    val lastAnnouncementAt: Long = 0L,
    val lastEvaluatedAt: Long = 0L
)

private fun buildRouteSummary(route: RouteState): String {
    val distanceText = formatDistance(route.distanceMeters)
    val durationText = formatDuration(route.durationSeconds)
    val instruction = route.firstInstruction
    return buildString {
        append("Starting navigation to ").append(route.destinationName).append(". ")
        append("It's ").append(distanceText).append(" away, about ").append(durationText).append(".")
        if (!instruction.isNullOrBlank()) {
            append(" ").append(instruction.trim())
        }
    }
}

private fun formatDistance(meters: Double): String {
    if (meters.isNaN() || meters <= 0.0) return "nearby"
    return if (meters < 1000) {
        "${meters.toInt()} meters"
    } else {
        val km = meters / 1000.0
        String.format(Locale.US, "%.1f kilometers", km)
    }
}

private fun formatDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds <= 0.0) return "a moment"
    val minutes = (seconds / 60).toInt()
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours > 0 -> {
            if (remainingMinutes > 0) "$hours hr $remainingMinutes min" else "$hours hr"
        }
        minutes > 0 -> "$minutes min"
        else -> "${seconds.toInt()} sec"
    }
}

private fun formatShortDistance(meters: Double): String {
    if (meters.isNaN() || meters <= 0.0) return "0 meters"
    return when {
        meters < 10 -> String.format(Locale.US, "%.1f meters", meters)
        meters < 1000 -> "${meters.roundToInt()} meters"
        else -> formatDistance(meters)
    }
}

private fun formatNearbyDistanceMiles(meters: Double): String {
    if (meters.isNaN() || meters <= 0.0) return "0 miles"
    val miles = meters / 1609.34
    return when {
        miles < 0.1 -> {
            val feet = meters * 3.28084
            val roundedFeet = feet.roundToInt().coerceAtLeast(1)
            "$roundedFeet feet"
        }
        miles < 10 -> String.format(Locale.US, "%.1f miles", miles)
        else -> String.format(Locale.US, "%.0f miles", miles)
    }
}

private fun evaluateRouteProgress(route: RouteState, userPoint: GeoPoint): RouteProgress {
    val points = route.points
    if (points.isEmpty()) {
        val distance = userPoint.distanceToAsDouble(route.destinationPoint)
        val duration = estimateWalkingDuration(distance)
        val bearing = normalizeBearing(userPoint.bearingTo(route.destinationPoint).toDouble())
        return RouteProgress(
            onRoute = distance <= ROUTE_OFF_DISTANCE_METERS,
            distanceToRouteMeters = distance,
            distanceTraveledMeters = 0.0,
            distanceRemainingMeters = distance,
            percentComplete = 0.0,
            estimatedDurationSeconds = duration,
            nearestPoint = route.destinationPoint,
            bearingToRoute = bearing
        )
    }
    if (points.size == 1) {
        val distance = userPoint.distanceToAsDouble(points.first())
        val duration = estimateWalkingDuration(distance)
        val bearing = normalizeBearing(userPoint.bearingTo(points.first()).toDouble())
        return RouteProgress(
            onRoute = distance <= ROUTE_OFF_DISTANCE_METERS,
            distanceToRouteMeters = distance,
            distanceTraveledMeters = 0.0,
            distanceRemainingMeters = distance,
            percentComplete = 0.0,
            estimatedDurationSeconds = duration,
            nearestPoint = points.first(),
            bearingToRoute = bearing
        )
    }

    val reference = points.first()
    var cumulative = 0.0
    var bestDistance = Double.MAX_VALUE
    var bestTraveled = 0.0
    var bestPoint = points.first()
    for (i in 0 until points.lastIndex) {
        val start = points[i]
        val end = points[i + 1]
        val segmentLength = start.distanceToAsDouble(end)
        val projection = projectPointToSegment(userPoint, start, end, reference)
        val traveledHere = cumulative + segmentLength * projection.fraction
        if (projection.distanceMeters < bestDistance) {
            bestDistance = projection.distanceMeters
            bestTraveled = traveledHere
            bestPoint = projection.closestPoint
        }
        cumulative += segmentLength
    }
    val pathLength = when {
        route.distanceMeters.isNaN() || route.distanceMeters <= 0.0 -> cumulative
        else -> route.distanceMeters
    }.coerceAtLeast(bestTraveled)
    val distanceRemaining = (pathLength - bestTraveled).coerceAtLeast(0.0)
    val percent = if (pathLength > 0) (bestTraveled / pathLength).coerceIn(0.0, 1.0) else 0.0
    val avgSpeed = when {
        route.durationSeconds.isNaN() || route.durationSeconds <= 0.0 || route.distanceMeters <= 0.0 ->
            WALKING_SPEED_METERS_PER_SEC
        else -> route.distanceMeters / route.durationSeconds
    }.coerceAtLeast(0.5)
    val remainingDuration = if (avgSpeed > 0) distanceRemaining / avgSpeed else estimateWalkingDuration(distanceRemaining)
    val bearingToRoute = normalizeBearing(userPoint.bearingTo(bestPoint).toDouble())
    val onRoute = bestDistance <= ROUTE_OFF_DISTANCE_METERS

    return RouteProgress(
        onRoute = onRoute,
        distanceToRouteMeters = bestDistance,
        distanceTraveledMeters = bestTraveled,
        distanceRemainingMeters = distanceRemaining,
        percentComplete = percent,
        estimatedDurationSeconds = remainingDuration,
        nearestPoint = bestPoint,
        bearingToRoute = bearingToRoute
    )
}

private data class ProjectionResult(
    val distanceMeters: Double,
    val closestPoint: GeoPoint,
    val fraction: Double
)

private fun projectPointToSegment(
    point: GeoPoint,
    start: GeoPoint,
    end: GeoPoint,
    reference: GeoPoint
): ProjectionResult {
    val startVec = toLocalVector(reference, start)
    val endVec = toLocalVector(reference, end)
    val pointVec = toLocalVector(reference, point)
    val segX = endVec.first - startVec.first
    val segY = endVec.second - startVec.second
    val segLenSq = segX * segX + segY * segY
    val t = if (segLenSq == 0.0) 0.0 else ((pointVec.first - startVec.first) * segX + (pointVec.second - startVec.second) * segY) / segLenSq
    val clampedT = t.coerceIn(0.0, 1.0)
    val projX = startVec.first + clampedT * segX
    val projY = startVec.second + clampedT * segY
    val dx = pointVec.first - projX
    val dy = pointVec.second - projY
    val distance = hypot(dx, dy)
    val projectedPoint = interpolateGeoPoint(start, end, clampedT)
    return ProjectionResult(
        distanceMeters = distance,
        closestPoint = projectedPoint,
        fraction = clampedT
    )
}

private fun toLocalVector(reference: GeoPoint, point: GeoPoint): Pair<Double, Double> {
    val refLatRad = Math.toRadians(reference.latitude)
    val latRad = Math.toRadians(point.latitude)
    val deltaLat = Math.toRadians(point.latitude - reference.latitude)
    val deltaLon = Math.toRadians(point.longitude - reference.longitude)
    val x = deltaLon * cos((latRad + refLatRad) / 2.0) * EARTH_RADIUS_METERS
    val y = deltaLat * EARTH_RADIUS_METERS
    return x to y
}

private fun interpolateGeoPoint(start: GeoPoint, end: GeoPoint, fraction: Double): GeoPoint {
    if (fraction <= 0.0) return start
    if (fraction >= 1.0) return end
    val lat = start.latitude + (end.latitude - start.latitude) * fraction
    val lon = start.longitude + (end.longitude - start.longitude) * fraction
    return GeoPoint(lat, lon)
}

private fun normalizeBearing(rawBearing: Double): Double {
    var bearing = rawBearing
    while (bearing < 0) bearing += 360.0
    while (bearing >= 360.0) bearing -= 360.0
    return bearing
}

private fun buildRouteCheckMessage(route: RouteState, progress: RouteProgress): String {
    val remainingText = formatDistance(progress.distanceRemainingMeters)
    val etaText = formatDuration(progress.estimatedDurationSeconds)
    val driftText = formatShortDistance(progress.distanceToRouteMeters)
    val direction = bearingToDirection(progress.bearingToRoute)
    return if (progress.onRoute) {
        buildString {
            append("You’re on track to ").append(route.destinationName).append(". ")
            append(remainingText).append(" left, about ").append(etaText).append(".")
            when {
                progress.distanceToRouteMeters > ROUTE_WARNING_DISTANCE_METERS -> {
                    append(" You’re drifting about ").append(driftText)
                        .append(" to the ").append(direction).append(". Move back toward the path.")
                }
                progress.percentComplete >= 0.9 -> {
                    append(" You’re almost there.")
                }
            }
        }
    } else {
        buildString {
            append("You’re off the planned path by roughly ").append(driftText)
                .append(" toward the ").append(direction).append(". ")
            append("Head back to the route or ask me to reroute to ").append(route.destinationName).append(".")
        }
    }
}

private fun buildDriftWarning(progress: RouteProgress): String {
    val driftText = formatShortDistance(progress.distanceToRouteMeters)
    val direction = bearingToDirection(progress.bearingToRoute)
    return "You’re about $driftText off the planned path toward the $direction. Move back toward the blue route or ask me to reroute."
}

private fun bearingToDirection(bearing: Double): String {
    val normalized = normalizeBearing(bearing)
    val directions = listOf(
        "north",
        "northeast",
        "east",
        "southeast",
        "south",
        "southwest",
        "west",
        "northwest"
    )
    val index = ((normalized + 22.5) / 45.0).toInt() % directions.size
    return directions[index]
}

private fun buildNearbySummary(
    category: String,
    origin: GeoPoint,
    places: List<NearbyPlace>
): String {
    val label = category.ifBlank { "places" }
    if (places.isEmpty()) {
        val radiusText = formatDistance(NEARBY_MAX_DISTANCE_METERS)
        return "I couldn’t find any $label within about $radiusText. Try a different category or ask me to zoom out."
    }
    val total = places.size
    val featured = places.take(3)
    val descriptions = featured.joinToString(" ") { place ->
        val direction = bearingToDirection(origin.bearingTo(place.point).toDouble())
        val distanceText = formatNearbyDistanceMiles(place.distanceMeters)
        val name = place.destination.name.ifBlank { "this place" }
        "$name about $distanceText to the $direction."
    }
    return buildString {
        append("Here are ").append(minOf(total, 5))
            .append(" nearby ").append(label).append(". ")
        append(descriptions)
        if (total > featured.size) {
            append(" I have more results on the map if you need them.")
        }
    }
}

private suspend fun resolveIntentWithNeuralSeek(
    transcript: String,
    neuralSeekClient: NeuralSeekClient?,
    geminiClient: GeminiClient?
): Result<NeuralSeekIntent> {
    neuralSeekClient?.let { nsClient ->
        val neuralResult = nsClient.classifyQuery(transcript).mapCatching { nsResponse ->
            parseNeuralSeekIntent(nsResponse.rawText).getOrElse { throw it }
        }
        neuralResult.onSuccess { return Result.success(it) }
        val nsError = neuralResult.exceptionOrNull()
        if (nsError != null) {
            NavisLog.w(TAG, "NeuralSeek classification failed: ${nsError.message}")
        }
    }
    return classifyIntentWithGemini(transcript, geminiClient)
}

private suspend fun classifyIntentWithGemini(
    transcript: String,
    geminiClient: GeminiClient?
): Result<NeuralSeekIntent> {
    val gemini = geminiClient ?: return Result.failure(IllegalStateException("Intent assistant unavailable"))
    return gemini.classifyIntent(transcript)
}

private fun parseNeuralSeekMessage(rawSnippet: String): String? {
    val sanitized = sanitizeNeuralSeekJson(rawSnippet)
    if (sanitized.isBlank()) return null
    return try {
        val json = JSONObject(sanitized)
        extractNeuralSeekMessage(json)
    } catch (t: Throwable) {
        NavisLog.w(TAG, "Failed to parse NeuralSeek route JSON: ${t.message}")
        null
    }
}

private fun extractNeuralSeekMessage(json: JSONObject): String? {
    val messageKeys = listOf("message", "speech", "status", "text")
    messageKeys.forEach { key ->
        val value = json.optString(key)
        if (!value.isNullOrBlank()) return value
    }
    json.optJSONObject("data")?.let { dataObj ->
        messageKeys.forEach { key ->
            val value = dataObj.optString(key)
            if (!value.isNullOrBlank()) return value
        }
    }
    val dataRaw = json.opt("data")
    if (dataRaw is String && dataRaw.isNotBlank()) {
        val decoded = decodeAndExtractJson(dataRaw)
        if (!decoded.isNullOrBlank()) return decoded
    }
    val entityValue = json.optString("entity").orEmpty()
    if (entityValue.isNotBlank()) {
        val nested = decodeAndExtractJson(entityValue)
        if (!nested.isNullOrBlank()) {
            return nested
        }
    }
    json.optJSONObject("coords")?.let { coords ->
        val lat = coords.optDouble("lat")
        val lon = coords.optDouble("lon")
        if (!lat.isNaN() && !lon.isNaN()) {
            return "Coordinates received lat=${"%.5f".format(lat)} lon=${"%.5f".format(lon)}"
        }
    }
    return null
}

private fun decodeAndExtractJson(raw: String): String? {
    val cleaned = raw.trim()
    if (cleaned.isBlank()) return null
    val unescaped = cleaned
        .removeSurrounding("\"")
        .replace("\\n", "\n")
        .replace("\\\"", "\"")
    if (!looksLikeJsonObject(unescaped)) {
        return unescaped.takeIf { it.isNotBlank() }
    }
    return try {
        val nested = JSONObject(unescaped)
        val messageKeys = listOf("message", "speech", "status", "text", "entity")
        messageKeys.forEach { key ->
            val value = nested.optString(key)
            if (!value.isNullOrBlank()) return value
        }
        null
    } catch (_: Throwable) {
        unescaped
    }
}

private val JSON_FENCE_REGEX = Regex(
    pattern = """```(?:json)?\s*([\s\S]*?)```""",
    option = RegexOption.IGNORE_CASE
)

private fun sanitizeNeuralSeekJson(rawSnippet: String): String {
    val trimmed = rawSnippet.trim()
    val defenced = stripCodeFences(trimmed)
    val unquoted = defenced.removeSurrounding("\"").trim()
    return when {
        looksLikeJsonObject(unquoted) -> unquoted
        else -> extractFirstJsonObject(unquoted) ?: unquoted
    }
}

private fun stripCodeFences(text: String): String {
    val match = JSON_FENCE_REGEX.find(text)
    return when {
        match != null -> match.groupValues.getOrNull(1)?.trim() ?: text
        text.startsWith("```") -> text.trim('`', ' ', '\n', '\r', '\t')
        else -> text
    }
}

private fun looksLikeJsonObject(text: String): Boolean =
    text.startsWith("{") && text.endsWith("}")

private fun extractFirstJsonObject(text: String): String? {
    var depth = 0
    var inQuotes = false
    var escaped = false
    var startIndex = -1
    text.forEachIndexed { index, char ->
        if (escaped) {
            escaped = false
            return@forEachIndexed
        }
        when (char) {
            '\\' -> if (inQuotes) escaped = true
            '"' -> inQuotes = !inQuotes
        }
        if (inQuotes) return@forEachIndexed
        if (char == '{') {
            if (depth == 0) startIndex = index
            depth++
        } else if (char == '}') {
            depth--
            if (depth == 0 && startIndex != -1) {
                return text.substring(startIndex, index + 1)
            }
        }
    }
    return null
}

private const val SHORT_WALK_THRESHOLD_METERS = 450.0
private const val WALK_DETOUR_FACTOR = 1.75
private const val WALKING_SPEED_METERS_PER_SEC = 1.35
private const val MAX_DESTINATION_DISTANCE_METERS = 15_000.0
private const val LOCAL_DESTINATION_PREFERENCE_METERS = 2_000.0
private const val ROUTE_OFF_DISTANCE_METERS = 15.0
private const val ROUTE_WARNING_DISTANCE_METERS = 8.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
private const val EXTERNAL_DISTANCE_PREFERENCE_METERS = 120.0
private const val NEARBY_MAX_DISTANCE_METERS = 1_500.0
private const val NEURALSEEK_EXPLORE_AGENT = "main"
private const val DRIFT_COOLDOWN_MS = 25_000L

private fun selectPreferredDestination(
    origin: GeoPoint,
    primary: GeminiClient.Destination?,
    localCandidate: GeminiClient.Destination?
): GeminiClient.Destination? {
    val primaryPoint = primary?.let { GeoPoint(it.latitude, it.longitude) }
    val localPoint = localCandidate?.let { GeoPoint(it.latitude, it.longitude) }
    val primaryDistance = primaryPoint?.let { origin.distanceToAsDouble(it) } ?: Double.NaN
    val localDistance = localPoint?.let { origin.distanceToAsDouble(it) } ?: Double.NaN

    if (primaryPoint == null && localPoint == null) return null
    if (primaryPoint == null) {
        NavisLog.d(TAG, "Using local POI because Gemini returned null")
        return localCandidate
    }
    if (localPoint == null && primaryDistance.isNaN()) {
        NavisLog.w(TAG, "Gemini destination missing distance; rejecting")
        return null
    }

    val primaryTooFar =
        primaryDistance.isNaN() || primaryDistance > MAX_DESTINATION_DISTANCE_METERS
    if (primaryTooFar) {
        if (localPoint != null) {
            NavisLog.d(
                TAG,
                "Favoring local POI because Gemini distance=${primaryDistance}m"
            )
            return localCandidate
        }
        NavisLog.w(
            TAG,
            "Rejecting Gemini destination ${primary?.name} distance=${primaryDistance}m"
        )
        return null
    }

    if (localPoint == null) return primary

    val localMuchCloser = !localDistance.isNaN() &&
        (localDistance < LOCAL_DESTINATION_PREFERENCE_METERS ||
            localDistance + 200 < primaryDistance)

    return when {
        localMuchCloser -> {
            NavisLog.d(
                TAG,
                "Local POI closer (${localDistance}m) than Gemini (${primaryDistance}m)"
            )
            localCandidate
        }
        else -> primary
    }
}

private fun chooseExternalDestination(
    origin: GeoPoint,
    osmCandidate: GeminiClient.Destination?,
    geminiCandidate: GeminiClient.Destination?
): GeminiClient.Destination? {
    if (osmCandidate == null && geminiCandidate == null) return null
    if (osmCandidate == null) return geminiCandidate
    if (geminiCandidate == null) return osmCandidate

    val osmPoint = GeoPoint(osmCandidate.latitude, osmCandidate.longitude)
    val gemPoint = GeoPoint(geminiCandidate.latitude, geminiCandidate.longitude)
    val osmDistance = origin.distanceToAsDouble(osmPoint)
    val gemDistance = origin.distanceToAsDouble(gemPoint)

    if (osmDistance.isNaN() && gemDistance.isNaN()) return geminiCandidate
    if (osmDistance.isNaN()) return geminiCandidate
    if (gemDistance.isNaN()) return osmCandidate

    val osmConfidence = osmCandidate.confidence ?: 0.5
    val gemConfidence = geminiCandidate.confidence ?: 0.5

    return when {
        gemDistance + EXTERNAL_DISTANCE_PREFERENCE_METERS < osmDistance -> geminiCandidate
        osmDistance + EXTERNAL_DISTANCE_PREFERENCE_METERS < gemDistance -> osmCandidate
        gemConfidence > osmConfidence + 0.1 -> geminiCandidate
        osmConfidence > gemConfidence + 0.1 -> osmCandidate
        else -> if (gemDistance <= osmDistance) geminiCandidate else osmCandidate
    }
}

private fun buildLocationHint(origin: GeoPoint): String? {
    val lat = origin.latitude
    val lon = origin.longitude
    val campusBox = lat in 40.9..40.95 && lon in -73.15..-73.1
    return if (campusBox) {
        "User is on the Stony Brook University campus near Melville Library. Nearby landmarks include the Student Activities Center and academic quads."
    } else {
        null
    }
}

private fun adjustRouteForShortWalks(
    originPoint: GeoPoint,
    destinationPoint: GeoPoint,
    osrmRoute: OsrmRoute,
    destinationName: String = "Destination"
): RouteState {
    val directDistance = originPoint.distanceToAsDouble(destinationPoint)
    val geometry = osrmRoute.geometry
    val hasOsrmGeometry = geometry.size >= 3
    val shortDirect = directDistance in 1.0..SHORT_WALK_THRESHOLD_METERS
    val osrmDegenerate = geometry.size <= 2 || osrmRoute.distanceMeters.isNaN() || osrmRoute.distanceMeters <= 0.1
    val osrmTooLoopy =
        !directDistance.isNaN() && osrmRoute.distanceMeters > directDistance * WALK_DETOUR_FACTOR
    val shouldSimplify = shortDirect && (osrmDegenerate || osrmTooLoopy)

    val finalPoints = when {
        !shouldSimplify && hasOsrmGeometry -> geometry
        !shouldSimplify && geometry.isNotEmpty() -> densifyLine(geometry.first(), geometry.last())
        else -> densifyLine(originPoint, destinationPoint)
    }

    val finalDistance = when {
        shouldSimplify -> directDistance
        osrmRoute.distanceMeters.isNaN() || osrmRoute.distanceMeters <= 0.0 -> directDistance
        else -> osrmRoute.distanceMeters
    }
    val finalDuration = when {
        shouldSimplify -> estimateWalkingDuration(finalDistance)
        osrmRoute.durationSeconds.isNaN() || osrmRoute.durationSeconds <= 0.0 -> estimateWalkingDuration(finalDistance)
        else -> osrmRoute.durationSeconds
    }
    val instruction = if (shouldSimplify) {
        "Walk straight toward $destinationName"
    } else {
        osrmRoute.instructions.firstOrNull().orEmpty().ifBlank { "Head toward $destinationName" }
    }

    if (shouldSimplify) {
        NavisLog.d(
            TAG,
            "Simplifying short walk: direct=$directDistance osrm=${osrmRoute.distanceMeters} points=${finalPoints.size}"
        )
    } else if (!hasOsrmGeometry && geometry.isNotEmpty()) {
        NavisLog.w(
            TAG,
            "OSRM geometry returned ${geometry.size} points; densifying between endpoints"
        )
    }

    return RouteState(
        destinationName = destinationName,
        destinationPoint = destinationPoint,
        points = finalPoints,
        distanceMeters = finalDistance,
        durationSeconds = finalDuration,
        firstInstruction = instruction,
        isSimplified = shouldSimplify
    )
}

private fun densifyLine(
    start: GeoPoint,
    end: GeoPoint,
    segments: Int = 12
): List<GeoPoint> {
    if (segments <= 1) return listOf(start, end)
    val points = mutableListOf<GeoPoint>()
    for (i in 0..segments) {
        val fraction = i / segments.toDouble()
        val lat = start.latitude + (end.latitude - start.latitude) * fraction
        val lon = start.longitude + (end.longitude - start.longitude) * fraction
        points.add(GeoPoint(lat, lon))
    }
    return points
}

private fun estimateWalkingDuration(distanceMeters: Double): Double {
    if (distanceMeters.isNaN() || distanceMeters <= 0.0) return 0.0
    return distanceMeters / WALKING_SPEED_METERS_PER_SEC
}

@Composable
private fun rememberOsrmClient(): OsrmClient {
    return remember { OsrmClient() }
}

@Composable
private fun rememberElevenLabsVoice(apiKey: String): ElevenLabsVoice? {
    val context = LocalContext.current.applicationContext
    val assistant = remember(apiKey) {
        if (apiKey.isBlank()) null else ElevenLabsVoice(context, apiKey)
    }
    DisposableEffect(assistant) {
        onDispose {
            assistant?.shutdown()
        }
    }
    return assistant
}

@Composable
private fun rememberNeuralSeekClient(
    url: String,
    key: String,
    agent: String = "interpreter"
): NeuralSeekClient? {
    return remember(url, key, agent) {
        if (url.isBlank() || key.isBlank()) null else NeuralSeekClient(url, key, agent)
    }
}

@Composable
private fun rememberGeminiClient(apiKey: String): GeminiClient? {
    return remember(apiKey) {
        if (apiKey.isBlank()) null else GeminiClient(apiKey)
    }
}

@Composable
private fun rememberNominatimClient(): NominatimClient {
    return remember { NominatimClient() }
}

private fun speechRecognizerErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
    SpeechRecognizer.ERROR_CLIENT -> "Client error"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
    SpeechRecognizer.ERROR_NETWORK -> "Network error"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
    SpeechRecognizer.ERROR_SERVER -> "Server error"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
    else -> "Unknown error ($code)"
}

@Composable
private fun PushToTalkControls(
    isListening: Boolean,
    hasLocationPermission: Boolean,
    hasAudioPermission: Boolean,
    voiceLevel: VoiceLevelReading,
    speechState: SpeechUiState,
    ttsStatus: TtsStatus,
    intentStatus: IntentStatus,
    onPressChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val canListen = hasLocationPermission && hasAudioPermission
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AssistantStatusCard(
            isListening = isListening,
            hasLocationPermission = hasLocationPermission,
            hasAudioPermission = hasAudioPermission,
            voiceLevel = voiceLevel,
            speechState = speechState,
            ttsStatus = ttsStatus,
            intentStatus = intentStatus
        )
        PushToTalkButton(
            isListening = isListening,
            enabled = canListen,
            onPressChanged = onPressChanged,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun AssistantStatusCard(
    isListening: Boolean,
    hasLocationPermission: Boolean,
    hasAudioPermission: Boolean,
    voiceLevel: VoiceLevelReading,
    speechState: SpeechUiState,
    ttsStatus: TtsStatus,
    intentStatus: IntentStatus,
    modifier: Modifier = Modifier
) {
    val (title, subtitle) = when {
        !hasLocationPermission -> "Enable location" to "Grant GPS access for guidance"
        !hasAudioPermission -> "Enable microphone" to "Allow audio so Navis can listen"
        isListening -> "Listening…" to "Hold to speak to Navis"
        else -> "Assistant idle" to "Hold the mic button and ask anything"
    }
    val containerColor = if (isListening) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (hasAudioPermission) {
                VoiceLevelMeter(
                    level = if (isListening) voiceLevel else VoiceLevelReading.Idle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                SpeechTranscriptRow(
                    state = speechState,    
                    modifier = Modifier.padding(top = 12.dp)
                )
                TtsStatusRow(
                    status = ttsStatus,
                    modifier = Modifier.padding(top = 8.dp)
                )
                IntentStatusRow(
                    status = intentStatus,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PushToTalkButton(
    isListening: Boolean,
    enabled: Boolean,
    onPressChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val targetColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        isListening -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    val animatedColor = animateColorAsState(targetValue = targetColor, label = "pttColor")
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(animatedColor.value, CircleShape)
            .pointerInteropFilter { event ->
                if (!enabled) return@pointerInteropFilter false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        onPressChanged(true)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        onPressChanged(false)
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (isListening) "Stop listening" else "Hold to talk",
            tint = Color.White,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun VoiceLevelMeter(
    level: VoiceLevelReading,
    modifier: Modifier = Modifier
) {
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val activeColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(level.normalized.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(999.dp))
                .background(activeColor)
        )
    }
}

@Composable
private fun SpeechTranscriptRow(
    state: SpeechUiState,
    modifier: Modifier = Modifier
) {
    val textColor = when {
        state.errorMessage != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val displayText = when {
        !state.isRecognizerAvailable -> "Speech recognition unavailable on this device"
        state.errorMessage != null -> state.errorMessage ?: ""
        state.isSessionActive && state.partialText.isBlank() -> "Listening..."
        state.partialText.isNotBlank() -> state.partialText
        state.lastFinalText.isNotBlank() -> state.lastFinalText
        else -> "Hold to ask a question"
    }
    Text(
        text = displayText,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor,
        modifier = modifier
    )
}

@Composable
private fun TtsStatusRow(
    status: TtsStatus,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (status) {
        is TtsStatus.Speaking -> "Speaking: ${status.message}" to MaterialTheme.colorScheme.primary
        is TtsStatus.Completed -> "Responded: ${status.message}" to MaterialTheme.colorScheme.secondary
        is TtsStatus.Error -> status.reason to MaterialTheme.colorScheme.error
        TtsStatus.Idle -> "Voice assistant ready" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier
    )
}

@Composable
private fun IntentStatusRow(
    status: IntentStatus,
    modifier: Modifier = Modifier
) {
    val (text, color) = when (status) {
        IntentStatus.Idle -> "Awaiting instruction" to MaterialTheme.colorScheme.onSurfaceVariant
        is IntentStatus.Loading -> "Understanding: \"${status.transcript}\"" to MaterialTheme.colorScheme.primary
        is IntentStatus.Success -> {
            val label = status.intent.friendlyDescription
            "Intent: $label" to MaterialTheme.colorScheme.secondary
        }
        is IntentStatus.Error -> {
            val msg = buildString {
                append(status.reason)
                if (!status.raw.isNullOrBlank()) {
                    append("\n")
                    append(status.raw.take(120))
                }
            }
            msg to MaterialTheme.colorScheme.error
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier
    )
}
