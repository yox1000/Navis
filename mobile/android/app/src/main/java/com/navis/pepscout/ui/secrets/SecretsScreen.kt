package com.navis.pepscout.ui.secrets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.navis.pepscout.data.Keystore
import kotlinx.coroutines.launch

/**
 * Secrets management screen for API keys
 * Allows users to configure all required API keys securely
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretsScreen(
    keystore: Keystore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSuccessMessage by remember { mutableStateOf(false) }
    var successMessage by remember { mutableStateOf("") }
    
    // API key states
    var elevenLabsKey by remember { mutableStateOf("") }
    var geminiKey by remember { mutableStateOf("") }
    var neuralSeekKey by remember { mutableStateOf("") }
    var mappedinApiKey by remember { mutableStateOf("") }
    var mappedinSecret by remember { mutableStateOf("") }
    var cohereKey by remember { mutableStateOf("") }
    
    // Visibility states
    var showElevenLabs by remember { mutableStateOf(false) }
    var showGemini by remember { mutableStateOf(false) }
    var showNeuralSeek by remember { mutableStateOf(false) }
    var showMappedinApi by remember { mutableStateOf(false) }
    var showMappedinSecret by remember { mutableStateOf(false) }
    var showCohere by remember { mutableStateOf(false) }
    
    // Validation states
    var elevenLabsError by remember { mutableStateOf<String?>(null) }
    var geminiError by remember { mutableStateOf<String?>(null) }
    var neuralSeekError by remember { mutableStateOf<String?>(null) }
    var mappedinError by remember { mutableStateOf<String?>(null) }
    var cohereError by remember { mutableStateOf<String?>(null) }
    
    // Check existing keys on load
    LaunchedEffect(Unit) {
        elevenLabsKey = if (keystore.hasApiKey(Keystore.ELEVENLABS_API_KEY)) "••••••••" else ""
        geminiKey = if (keystore.hasApiKey(Keystore.GEMINI_API_KEY)) "••••••••" else ""
        neuralSeekKey = if (keystore.hasApiKey(Keystore.NEURALSEEK_API_KEY)) "••••••••" else ""
        mappedinApiKey = if (keystore.hasApiKey(Keystore.MAPPEDIN_API_KEY)) "••••••••" else ""
        mappedinSecret = if (keystore.hasApiKey(Keystore.MAPPEDIN_SECRET)) "••••••••" else ""
        cohereKey = if (keystore.hasApiKey(Keystore.COHERE_API_KEY)) "••••••••" else ""
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "API Keys",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Button(onClick = onBack) {
                Text("Back")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Configure your API keys for voice, maps, and AI services. Keys are stored securely using Android Keystore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Success message
        if (showSuccessMessage) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = successMessage,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // ElevenLabs API Key
        EnhancedApiKeySection(
            title = "ElevenLabs API Key",
            description = "Required for high-quality text-to-speech voices",
            value = elevenLabsKey,
            onValueChange = { 
                elevenLabsKey = it
                elevenLabsError = null
            },
            isVisible = showElevenLabs,
            onVisibilityToggle = { showElevenLabs = !showElevenLabs },
            onSave = {
                scope.launch {
                    val validation = ApiKeyValidator.validateElevenLabsKey(elevenLabsKey)
                    when (validation) {
                        is ValidationResult.Valid -> {
                            if (keystore.setElevenLabsKey(elevenLabsKey)) {
                                showSuccess("ElevenLabs API key saved and validated successfully")
                                elevenLabsError = null
                            }
                        }
                        is ValidationResult.Invalid -> {
                            elevenLabsError = validation.message
                        }
                        is ValidationResult.Unknown -> {
                            if (keystore.setElevenLabsKey(elevenLabsKey)) {
                                showSuccess("ElevenLabs API key saved (validation skipped: ${validation.message})")
                                elevenLabsError = null
                            }
                        }
                    }
                }
            },
            placeholder = "sk-...",
            error = elevenLabsError
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Gemini API Key
        ApiKeySection(
            title = "Gemini API Key",
            description = "Required for intent classification and natural language processing",
            value = geminiKey,
            onValueChange = { geminiKey = it },
            isVisible = showGemini,
            onVisibilityToggle = { showGemini = !showGemini },
            onSave = {
                scope.launch {
                    if (keystore.setGeminiKey(geminiKey)) {
                        showSuccess("Gemini API key saved successfully")
                    }
                }
            },
            placeholder = "AIza..."
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Mappedin API Keys
        Text(
            text = "Mappedin SDK",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Required for indoor maps and routing",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SecureTextField(
                    value = mappedinApiKey,
                    onValueChange = { mappedinApiKey = it },
                    label = "API Key",
                    isVisible = showMappedinApi,
                    onVisibilityToggle = { showMappedinApi = !showMappedinApi },
                    placeholder = "mappedin_api_key"
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                SecureTextField(
                    value = mappedinSecret,
                    onValueChange = { mappedinSecret = it },
                    label = "Secret",
                    isVisible = showMappedinSecret,
                    onVisibilityToggle = { showMappedinSecret = !showMappedinSecret },
                    placeholder = "mappedin_secret"
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                scope.launch {
                    val apiSuccess = keystore.setMappedinApiKey(mappedinApiKey)
                    val secretSuccess = keystore.setMappedinSecret(mappedinSecret)
                    
                    if (apiSuccess && secretSuccess) {
                        showSuccess("Mappedin credentials saved successfully")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Mappedin Keys")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // NeuralSeek API Key
        ApiKeySection(
            title = "NeuralSeek API Key",
            description = "Required for Q&A functionality",
            value = neuralSeekKey,
            onValueChange = { neuralSeekKey = it },
            isVisible = showNeuralSeek,
            onVisibilityToggle = { showNeuralSeek = !showNeuralSeek },
            onSave = {
                scope.launch {
                    if (keystore.setNeuralSeekKey(neuralSeekKey)) {
                        showSuccess("NeuralSeek API key saved successfully")
                    }
                }
            },
            placeholder = "ns-..."
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Cohere API Key (Optional)
        ApiKeySection(
            title = "Cohere API Key (Optional)",
            description = "For enhanced search reranking. Falls back to local reranking if not provided.",
            value = cohereKey,
            onValueChange = { cohereKey = it },
            isVisible = showCohere,
            onVisibilityToggle = { showCohere = !showCohere },
            onSave = {
                scope.launch {
                    if (keystore.setCohereKey(cohereKey)) {
                        showSuccess("Cohere API key saved successfully")
                    }
                }
            },
            placeholder = "co-..."
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Status Summary
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Configuration Status",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                StatusRow("ElevenLabs", keystore.hasApiKey(Keystore.ELEVENLABS_API_KEY))
                StatusRow("Gemini", keystore.hasApiKey(Keystore.GEMINI_API_KEY))
                StatusRow("Mappedin", keystore.hasMappedinKeys())
                StatusRow("NeuralSeek", keystore.hasApiKey(Keystore.NEURALSEEK_API_KEY))
                StatusRow("Cohere (Optional)", keystore.hasApiKey(Keystore.COHERE_API_KEY))
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Built with sponsors
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Built with:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "🎤 ElevenLabs • 🗺️ Mappedin • 🤖 Gemini • 🧠 NeuralSeek",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
    
    // Helper function to show success message
    fun showSuccess(message: String) {
        successMessage = message
        showSuccessMessage = true
        scope.launch {
            kotlinx.coroutines.delay(3000)
            showSuccessMessage = false
        }
    }
}

@Composable
private fun EnhancedApiKeySection(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    isVisible: Boolean,
    onVisibilityToggle: () -> Unit,
    onSave: () -> Unit,
    placeholder: String,
    error: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SecureTextField(
                value = value,
                onValueChange = onValueChange,
                label = "API Key",
                isVisible = isVisible,
                onVisibilityToggle = onVisibilityToggle,
                placeholder = placeholder,
                isError = error != null
            )
            
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = value.isNotBlank()
            ) {
                Text("Validate & Save")
            }
        }
    }
}

@Composable
private fun ApiKeySection(
    title: String,
    description: String,
    value: String,
    onValueChange: (String) -> Unit,
    isVisible: Boolean,
    onVisibilityToggle: () -> Unit,
    onSave: () -> Unit,
    placeholder: String
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SecureTextField(
                value = value,
                onValueChange = onValueChange,
                label = "API Key",
                isVisible = isVisible,
                onVisibilityToggle = onVisibilityToggle,
                placeholder = placeholder
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Key")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecureTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isVisible: Boolean,
    onVisibilityToggle: () -> Unit,
    placeholder: String,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onVisibilityToggle) {
                Icon(
                    imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (isVisible) "Hide" else "Show"
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError
    )
}

@Composable
private fun StatusRow(
    service: String,
    isConfigured: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = service,
            style = MaterialTheme.typography.bodyMedium
        )
        
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isConfigured) "✓" else "✗",
                color = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge
            )
            
            Spacer(modifier = Modifier.width(4.dp))
            
            Text(
                text = if (isConfigured) "Configured" else "Missing",
                style = MaterialTheme.typography.bodySmall,
                color = if (isConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}