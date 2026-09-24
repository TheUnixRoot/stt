package com.theunixroot.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    onPrimary = Color(0xFF0B0E14),
                    secondary = Color(0xFFFF5277),
                    surface = Color(0xFF161922),
                    onSurface = Color(0xFFEDEBF5),
                    surfaceVariant = Color(0xFF242938),
                    onSurfaceVariant = Color(0xFFA5ACBD),
                    background = Color(0xFF0D0F17),
                    onBackground = Color(0xFFEDEBF5)
                )
            ) {
                MainSettingsScreen(
                    onLaunchBubble = {
                        startFloatingBubble()
                    }
                )
            }
        }
    }

    private fun startFloatingBubble() {
        val serviceIntent = Intent(this, FloatingBubbleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Burbuja flotante iniciada en pantalla", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun MainSettingsScreen(
    onLaunchBubble: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cf_stt_prefs", Context.MODE_PRIVATE) }

    var accountId by remember { mutableStateOf(prefs.getString("account_id", "") ?: "") }
    var apiToken by remember { mutableStateOf(prefs.getString("api_token", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "@cf/openai/whisper") ?: "@cf/openai/whisper") }

    var hasOverlayPermission by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true)
    }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val isAccessibilityEnabled = remember {
        mutableStateOf(STTAccessibilityService.instance != null)
    }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasOverlayPermission = Settings.canDrawOverlays(context)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Cloudflare STT Bubble",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Toca para hablar, inyecta en el campo activo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Permisos
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Permisos Requeridos",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Permiso Superposición
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Burbuja Flotante",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Mostrar sobre otras aplicaciones",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (hasOverlayPermission) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676))
                        } else {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        overlayLauncher.launch(intent)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Activar", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Permiso Micrófono
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Micrófono",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Para grabar tu voz en alta definición",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (hasMicPermission) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676))
                        } else {
                            Button(
                                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Activar", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Permiso Accesibilidad (para auto-inyección)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Inyección de Texto",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Accesibilidad (escribe en cualquier app)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isAccessibilityEnabled.value || STTAccessibilityService.instance != null) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676))
                        } else {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Text("Activar", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Configuración Tokens
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Credenciales Cloudflare",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

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

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = apiToken,
                        onValueChange = {
                            apiToken = it
                            prefs.edit().putString("api_token", it).apply()
                        },
                        label = { Text("API Token (Workers AI)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

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

            Spacer(modifier = Modifier.height(24.dp))

            // Botón Lanzar Burbuja
            Button(
                onClick = {
                    if (accountId.isBlank() || apiToken.isBlank()) {
                        Toast.makeText(context, "Por favor completa el Account ID y Token", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (!hasOverlayPermission) {
                        Toast.makeText(context, "Debes otorgar el permiso de superposición", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (!hasMicPermission) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        return@Button
                    }
                    onLaunchBubble()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.BubbleChart, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Iniciar Burbuja Flotante",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}
