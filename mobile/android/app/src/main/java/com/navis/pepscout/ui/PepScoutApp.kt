package com.navis.pepscout.ui

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.navis.pepscout.detector.HazardDetector
import com.navis.pepscout.viewmodel.PepScoutViewModel
import com.navis.pepscout.viewmodel.PepScoutViewModel.ApiKeyStatus
import com.navis.pepscout.viewmodel.PepScoutViewModel.OutdoorRouteSummary
import com.navis.pepscout.stt.SpeechCapture

@Composable
fun PepScoutApp(
    viewModel: PepScoutViewModel,
    onRequestLocationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit,
    onStartHazardDetection: () -> Unit,
    onStopHazardDetection: () -> Unit,
    onScanQr: () -> Unit
) {
    val locationPermission by viewModel.locationPermissionGranted.collectAsState()
    val cameraPermission by viewModel.cameraPermissionGranted.collectAsState()
    val microphonePermission by viewModel.microphonePermissionGranted.collectAsState()
    val location by viewModel.currentLocation.collectAsState()
    val heading by viewModel.heading.collectAsState()
    val outdoorSummary by viewModel.outdoorRouteSummary.collectAsState()
    val outdoorInstructions by viewModel.outdoorInstructions.collectAsState()
    val indoorInstructions by viewModel.indoorInstructions.collectAsState()
    val indoorProgress by viewModel.indoorProgress.collectAsState()
    val indoorStatus by viewModel.indoorStatusMessage.collectAsState()
    val trackingLocation by viewModel.isTrackingLocation.collectAsState()
    val hazards by viewModel.hazards.collectAsState()
    val hazardActive by viewModel.hazardDetectionActive.collectAsState()
    val speechState by viewModel.speechState.collectAsState()
    val partialTranscript by viewModel.partialTranscript.collectAsState()
    val finalTranscript by viewModel.finalTranscript.collectAsState()
    val assistantReply by viewModel.assistantReply.collectAsState()
    val apiKeyStatus by viewModel.apiKeyStatus.collectAsState()
    val cvEnabled by viewModel.cvEnabled.collectAsState(initial = false)
    var questionText by rememberSaveable { mutableStateOf("") }
    val isListening = speechState == SpeechCapture.SpeechState.Listening

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard {
                    Text(
                        text = "Pep Scout — Indoor + Outdoor Navigation",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Voice-first guidance, live hazards, QR handoff and campus QA.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
            }

            item {
                PermissionSection(
                    locationGranted = locationPermission,
                    cameraGranted = cameraPermission,
                    micGranted = microphonePermission,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onRequestCameraPermission = onRequestCameraPermission,
                    onRequestMicrophonePermission = onRequestMicrophonePermission
                )
            }

            item {
                LocationPanel(location, heading, locationPermission, trackingLocation)
            }

            item {
                OutdoorPanel(
                    summary = outdoorSummary,
                    instructions = outdoorInstructions,
                    onRoute = { viewModel.startOutdoorRoute() }
                )
            }

            item {
                IndoorPanel(
                    status = indoorStatus,
                    progress = indoorProgress,
                    instructions = indoorInstructions,
                    onStartRoute = { viewModel.startIndoorNavigation() },
                    onStartAccessibleRoute = { viewModel.startIndoorNavigation(avoidStairs = true) },
                    onScanQr = onScanQr
                )
            }

            item {
                HazardPanel(
                    hazards = hazards,
                    enabled = hazardActive,
                    onStart = {
                        viewModel.onHazardDetectionStarted()
                        onStartHazardDetection()
                    },
                    onStop = {
                        viewModel.onHazardDetectionStopped()
                        onStopHazardDetection()
                    },
                    cvEnabled = cvEnabled,
                    onToggleCv = viewModel::setCvEnabled
                )
            }

            item {
                SpeechPanel(
                    isListening = isListening,
                    partialTranscript = partialTranscript,
                    finalTranscript = finalTranscript,
                    assistantReply = assistantReply,
                    onMicToggle = {
                        if (isListening) viewModel.stopListening() else viewModel.startListening()
                    },
                    questionText = questionText,
                    onQuestionChange = { questionText = it },
                    onSubmitQuestion = {
                        viewModel.askQuestion(questionText)
                        questionText = ""
                    },
                )
            }

            item {
                ApiKeySection(
                    status = apiKeyStatus,
                    onSave = viewModel::storeApiKeys
                )
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable Column.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun PermissionSection(
    locationGranted: Boolean,
    cameraGranted: Boolean,
    micGranted: Boolean,
    onRequestLocationPermission: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRequestMicrophonePermission: () -> Unit
) {
    SectionCard {
        Text("Permissions", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionChip("Location", locationGranted, onRequestLocationPermission)
            PermissionChip("Camera", cameraGranted, onRequestCameraPermission)
            PermissionChip("Microphone", micGranted, onRequestMicrophonePermission)
        }
    }
}

@Composable
private fun PermissionChip(label: String, granted: Boolean, onRequest: () -> Unit) {
    TextButton(
        onClick = onRequest,
        enabled = !granted
    ) {
        Text(
            text = if (granted) "$label ✓" else "Grant $label",
            color = if (granted) Color.Green else MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun LocationPanel(
    location: Location?,
    heading: Float?,
    locationPermission: Boolean,
    isTracking: Boolean
) {
    SectionCard {
        Text("Position", fontWeight = FontWeight.SemiBold)
        if (!locationPermission) {
            Text("Location access is needed to track outdoor navigation.", color = Color.Gray)
            return
        }

        if (location == null) {
            Text("Waiting for GPS fix...", color = Color.Gray)
        } else {
            Text(
                text = "Lat: ${"%.5f".format(location.latitude)}, Lon: ${"%.5f".format(location.longitude)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Accuracy: ${location.accuracy.toInt()} m | Heading: ${heading?.toInt() ?: 0}°")
            Text(if (isTracking) "Tracking every 2s" else "Location idle")
        }
    }
}

@Composable
private fun OutdoorPanel(
    summary: OutdoorRouteSummary?,
    instructions: List<String>,
    onRoute: () -> Unit
) {
    SectionCard {
        Text("Outdoor Routing", fontWeight = FontWeight.SemiBold)
        Button(onClick = onRoute) {
            Text("Navigate to SAC Bus Stop")
        }
        if (summary != null) {
            Text("Distance: ${summary.distanceMeters.toInt()} m")
            Text("ETA: ${(summary.durationSeconds / 60).toInt()} min")
        }
        Spacer(modifier = Modifier.size(8.dp))
        if (instructions.isNotEmpty()) {
            Text("Steps", fontWeight = FontWeight.Medium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                instructions.forEachIndexed { index, step ->
                    Text("${index + 1}. $step")
                }
            }
        } else {
            Text("No route yet", color = Color.Gray)
        }
    }
}

@Composable
private fun IndoorPanel(
    status: String,
    progress: Float,
    instructions: List<String>,
    onStartRoute: () -> Unit,
    onStartAccessibleRoute: () -> Unit,
    onScanQr: () -> Unit
) {
    SectionCard {
        Text("Indoor Demo", fontWeight = FontWeight.SemiBold)
        Text(status)
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStartRoute) {
                Text("Start Standard Route")
            }
            Button(onClick = onStartAccessibleRoute) {
                Text("Avoid Stairs")
            }
        }
        Button(onClick = onScanQr) {
            Text("Scan QR Anchor")
        }
        if (instructions.isNotEmpty()) {
            Text("Indoor steps", fontWeight = FontWeight.Medium)
            instructions.forEachIndexed { index, step ->
                Text("${index + 1}. $step")
            }
        } else {
            Text("Waiting for indoor route", color = Color.Gray)
        }
    }
}

@Composable
private fun HazardPanel(
    hazards: List<HazardDetector.HazardEvent>,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    cvEnabled: Boolean,
    onToggleCv: (Boolean) -> Unit
) {
    SectionCard {
        Text("Hazard Detection", fontWeight = FontWeight.SemiBold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = if (enabled) onStop else onStart) {
                Text(if (enabled) "Stop CV" else "Start CV")
            }
            Text("Detecting obstacles: ${if (enabled) "On" else "Off"}")
            Switch(checked = cvEnabled, onCheckedChange = onToggleCv)
        }
        hazards.take(3).forEach { hazard ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("${hazard.label.replaceFirstChar { it.uppercaseChar() }} – ${hazard.severity}")
                    Text("Confidence: ${"%.2f".format(hazard.confidence)}", color = Color.Gray)
                }
            }
        }
        if (hazards.isEmpty()) {
            Text("No hazards detected yet.", color = Color.Gray)
        }
    }
}

@Composable
private fun SpeechPanel(
    isListening: Boolean,
    partialTranscript: String,
    finalTranscript: String,
    assistantReply: String,
    onMicToggle: () -> Unit,
    questionText: String,
    onQuestionChange: (String) -> Unit,
    onSubmitQuestion: () -> Unit
) {
    SectionCard {
        Text("Voice Console", fontWeight = FontWeight.SemiBold)
        Button(onClick = onMicToggle) {
            Icon(Icons.Rounded.Mic, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(if (isListening) "Listening..." else "Tap to Ask")
        }
        Text("Transcript: ${if (partialTranscript.isBlank()) finalTranscript else partialTranscript}")
        Text("Pep Scout: $assistantReply")
        OutlinedTextField(
            value = questionText,
            onValueChange = onQuestionChange,
            label = { Text("Ask a question") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onSubmitQuestion, enabled = questionText.isNotBlank()) {
            Text("Submit question")
        }
    }
}

@Composable
private fun ApiKeySection(status: ApiKeyStatus, onSave: (String, String, String) -> Unit) {
    var elevenKey by rememberSaveable { mutableStateOf("") }
    var geminiKey by rememberSaveable { mutableStateOf("") }
    var neuralKey by rememberSaveable { mutableStateOf("") }

    SectionCard {
        Text("API Keys", fontWeight = FontWeight.SemiBold)
        val savedIcon = if (status.hasEleven && status.hasGemini && status.hasNeural) "✅" else "⚠️"
        Text("Keys status: $savedIcon")
        OutlinedTextField(
            value = elevenKey,
            onValueChange = { elevenKey = it },
            label = { Text("ElevenLabs key") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = geminiKey,
            onValueChange = { geminiKey = it },
            label = { Text("Gemini key") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = neuralKey,
            onValueChange = { neuralKey = it },
            label = { Text("NeuralSeek key") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onSave(elevenKey, geminiKey, neuralKey) },
            enabled = elevenKey.isNotBlank() || geminiKey.isNotBlank() || neuralKey.isNotBlank()
        ) {
            Text("Save keys")
        }
    }
}
