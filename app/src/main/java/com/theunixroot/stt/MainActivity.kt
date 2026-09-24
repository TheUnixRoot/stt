package com.theunixroot.stt

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

enum class SttState {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    SUCCESS,
    ERROR
}

class MainActivity : ComponentActivity() {

    private val recorderHelper = AudioRecorderHelper()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make window behave like a compact bottom-aligned or centered pop-up
        window.apply {
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFF38BA8),
                    onPrimary = Color(0xFF11111B),
                    secondary = Color(0xFF89B4FA),
                    surface = Color(0xFF1E1E2E),
                    onSurface = Color(0xFFCDD6F4),
                    surfaceVariant = Color(0xFF313244),
                    onSurfaceVariant = Color(0xFFA6ADC8),
                    background = Color(0xFF181825),
                    onBackground = Color(0xFFCDD6F4)
                )
            ) {
                SttPopupScreen(
                    recorderHelper = recorderHelper,
                    onClose = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SttPopupScreen(
    recorderHelper: AudioRecorderHelper,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("cf_stt_prefs", Context.MODE_PRIVATE) }

    var accountId by remember { mutableStateOf(prefs.getString("account_id", "") ?: "") }
    var apiToken by remember { mutableStateOf(prefs.getString("api_token", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "@cf/openai/whisper") ?: "@cf/openai/whisper") }
    var showSettings by remember { mutableStateOf(accountId.isBlank() || apiToken.isBlank()) }

    var state by remember { mutableStateOf(SttState.IDLE) }
    var transcribedText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val started = recorderHelper.startRecording()
            if (started) {
                state = SttState.RECORDING
            } else {
                errorMessage = "No se pudo iniciar la grabación de audio."
                state = SttState.ERROR
            }
        } else {
            Toast.makeText(context, "Se necesita permiso de micrófono", Toast.LENGTH_SHORT).show()
        }
    }

    fun startRecord() {
        if (accountId.isBlank() || apiToken.isBlank()) {
            showSettings = true
            Toast.makeText(context, "Configura primero tu Account ID y Token de Cloudflare", Toast.LENGTH_LONG).show()
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val started = recorderHelper.startRecording()
            if (started) {
                state = SttState.RECORDING
                errorMessage = ""
            } else {
                errorMessage = "No se pudo inicializar el micrófono."
                state = SttState.ERROR
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopRecordAndTranscribe() {
        state = SttState.TRANSCRIBING
        coroutineScope.launch {
            val wavBytes = recorderHelper.stopAndGetWav()
            if (wavBytes.isEmpty()) {
                errorMessage = "Audio vacío o no grabado."
                state = SttState.ERROR
                return@launch
            }

            val result = CloudflareSttService.transcribeAudio(
                accountId = accountId.trim(),
                apiToken = apiToken.trim(),
                audioBytes = wavBytes,
                model = model.trim()
            )

            result.onSuccess { text ->
                transcribedText = text
                state = SttState.SUCCESS
                // Auto-copy to clipboard for convenience
                if (text.isNotBlank()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Cloudflare STT", text))
                    Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { ex ->
                errorMessage = ex.localizedMessage ?: "Error desconocido al transcribir"
                state = SttState.ERROR
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (state == SttState.RECORDING) Color(0xFFFF5555) else Color(0xFFA6E3A1),
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Cloudflare STT",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Configuración",
                            tint = if (showSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            AnimatedVisibility(visible = showSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = "Configuración Cloudflare Workers AI",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = accountId,
                        onValueChange = {
                            accountId = it
                            prefs.edit().putString("account_id", it).apply()
                        },
                        label = { Text("Account ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiToken,
                        onValueChange = {
                            apiToken = it
                            prefs.edit().putString("api_token", it).apply()
                        },
                        label = { Text("API Token (Bearer)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            prefs.edit().putString("model", it).apply()
                        },
                        label = { Text("Modelo (@cf/openai/whisper)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Action / Record Button
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = if (state == SttState.RECORDING) 1.22f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(100.dp)
            ) {
                if (state == SttState.RECORDING) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .scale(pulseScale)
                            .background(Color(0xFFF38BA8).copy(alpha = 0.35f), CircleShape)
                    )
                }

                FilledIconButton(
                    onClick = {
                        when (state) {
                            SttState.RECORDING -> stopRecordAndTranscribe()
                            SttState.TRANSCRIBING -> {} // wait
                            else -> startRecord()
                        }
                    },
                    modifier = Modifier.size(76.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = when (state) {
                            SttState.RECORDING -> Color(0xFFF38BA8)
                            SttState.TRANSCRIBING -> Color(0xFFFAB387)
                            else -> Color(0xFF89B4FA)
                        },
                        contentColor = Color(0xFF11111B)
                    ),
                    enabled = state != SttState.TRANSCRIBING
                ) {
                    if (state == SttState.TRANSCRIBING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = Color(0xFF11111B),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state == SttState.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (state == SttState.RECORDING) "Parar" else "Grabar",
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = when (state) {
                    SttState.IDLE -> "Toca el micrófono para hablar"
                    SttState.RECORDING -> "Escuchando... Toca para finalizar"
                    SttState.TRANSCRIBING -> "Transcribiendo con Cloudflare..."
                    SttState.SUCCESS -> "Transcripción completada (copiada)"
                    SttState.ERROR -> "Error al procesar"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when (state) {
                    SttState.ERROR -> Color(0xFFF38BA8)
                    SttState.SUCCESS -> Color(0xFFA6E3A1)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center
            )

            // Result or Error Area
            if (state == SttState.ERROR && errorMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    color = Color(0xFFF38BA8),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }

            if (transcribedText.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = transcribedText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Cloudflare STT", transcribedText))
                            Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copiar", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }

                    Button(
                        onClick = {
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, transcribedText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Compartir texto"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Compartir", color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}
