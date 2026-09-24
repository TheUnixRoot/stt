package com.theunixroot.stt

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FloatingBubbleService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var bubbleComposeView: ComposeView? = null
    private val recorderHelper = AudioRecorderHelper()

    private val isExpanded = mutableStateOf(false)
    private val sttState = mutableStateOf(SttState.IDLE)
    private val transcribedText = mutableStateOf("")
    private val errorMessage = mutableStateOf("")

    private lateinit var windowParams: WindowManager.LayoutParams
    private var bubbleSavedX = 50
    private var bubbleSavedY = 300

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        startForegroundNotification()
        setupOverlayView()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun startForegroundNotification() {
        val channelId = "floating_stt_channel"
        val channelName = "Burbuja de transcripción"
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(chan)
        }

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val pStopIntent = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.apply {
            setContentTitle("Burbuja STT activa")
            setContentText("Toca para dictar e inyectar | Mantén pulsado para abrir panel")
            setSmallIcon(R.mipmap.ic_launcher)
            addAction(Notification.Action.Builder(null, "Cerrar burbuja", pStopIntent).build())
            setOngoing(true)
        }.build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1001, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayView() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleSavedX
            y = bubbleSavedY
        }

        bubbleComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)

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
                    BubbleOverlayContent(
                        expanded = isExpanded.value,
                        state = sttState.value,
                        text = transcribedText.value,
                        error = errorMessage.value,
                        onBubbleShortPress = {
                            if (sttState.value == SttState.RECORDING) {
                                stopAndTranscribe()
                            } else if (sttState.value != SttState.TRANSCRIBING) {
                                startAudioRecording()
                            }
                        },
                        onBubbleLongPress = {
                            vibrate(50)
                            expandPanel()
                        },
                        onBubbleDrag = { dx, dy ->
                            if (!isExpanded.value) {
                                windowParams.x += dx.toInt()
                                windowParams.y += dy.toInt()
                                bubbleSavedX = windowParams.x
                                bubbleSavedY = windowParams.y
                                clampBubblePosition()
                                windowManager?.updateViewLayout(this, windowParams)
                            }
                        },
                        onCollapse = {
                            collapsePanel()
                        },
                        onCloseApp = {
                            stopSelf()
                        },
                        onActionMic = {
                            if (sttState.value == SttState.RECORDING) {
                                stopAndTranscribe()
                            } else {
                                startAudioRecording()
                            }
                        }
                    )
                }
            }
        }

        windowManager?.addView(bubbleComposeView, windowParams)
    }

    private fun clampBubblePosition() {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val bubbleSize = (70 * dm.density).toInt()

        windowParams.x = windowParams.x.coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
        windowParams.y = windowParams.y.coerceIn(50, (screenH - bubbleSize - 50).coerceAtLeast(50))
    }

    private fun expandPanel() {
        isExpanded.value = true
        // Center the panel on screen so it never overflows off-screen
        windowParams.gravity = Gravity.CENTER
        windowParams.x = 0
        windowParams.y = 0
        windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        windowManager?.updateViewLayout(bubbleComposeView, windowParams)
    }

    private fun collapsePanel() {
        isExpanded.value = false
        // Restore bubble to saved coordinates
        windowParams.gravity = Gravity.TOP or Gravity.START
        windowParams.x = bubbleSavedX
        windowParams.y = bubbleSavedY
        clampBubblePosition()
        windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        windowManager?.updateViewLayout(bubbleComposeView, windowParams)
    }

    private fun vibrate(millis: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(millis)
        }
    }

    private fun startAudioRecording() {
        val prefs = getSharedPreferences("cf_stt_prefs", Context.MODE_PRIVATE)
        val accountId = prefs.getString("account_id", "") ?: ""
        val apiToken = prefs.getString("api_token", "") ?: ""

        if (accountId.isBlank() || apiToken.isBlank()) {
            Toast.makeText(this, "Configura primero tu Token de Cloudflare en la App", Toast.LENGTH_LONG).show()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(mainIntent)
            return
        }

        val started = recorderHelper.startRecording()
        if (started) {
            vibrate(40)
            sttState.value = SttState.RECORDING
            errorMessage.value = ""
        } else {
            errorMessage.value = "Error al iniciar micrófono"
            sttState.value = SttState.ERROR
        }
    }

    private fun stopAndTranscribe() {
        vibrate(30)
        sttState.value = SttState.TRANSCRIBING

        val prefs = getSharedPreferences("cf_stt_prefs", Context.MODE_PRIVATE)
        val accountId = prefs.getString("account_id", "") ?: ""
        val apiToken = prefs.getString("api_token", "") ?: ""
        val model = prefs.getString("model", "@cf/openai/whisper") ?: "@cf/openai/whisper"

        serviceScope.launch {
            val wav = recorderHelper.stopAndGetWav()
            if (wav.isEmpty()) {
                errorMessage.value = "Audio vacío"
                sttState.value = SttState.ERROR
                return@launch
            }

            val res = CloudflareSttService.transcribeAudio(
                accountId = accountId.trim(),
                apiToken = apiToken.trim(),
                audioBytes = wav,
                model = model.trim()
            )

            res.onSuccess { resultText ->
                transcribedText.value = resultText
                sttState.value = SttState.SUCCESS
                vibrate(60)

                if (resultText.isNotBlank()) {
                    // Inject into active input
                    val injected = STTAccessibilityService.injectTextIntoFocusedInput(resultText)

                    // Also always copy to clipboard
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("Cloudflare STT", resultText))

                    if (injected) {
                        Toast.makeText(this@FloatingBubbleService, "¡Texto inyectado!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@FloatingBubbleService, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { ex ->
                errorMessage.value = ex.localizedMessage ?: "Error de transcripción"
                sttState.value = SttState.ERROR
                vibrate(120)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceJob.cancel()
        if (bubbleComposeView != null) {
            windowManager?.removeView(bubbleComposeView)
            bubbleComposeView = null
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_SERVICE = "com.theunixroot.stt.STOP_SERVICE"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BubbleOverlayContent(
    expanded: Boolean,
    state: SttState,
    text: String,
    error: String,
    onBubbleShortPress: () -> Unit,
    onBubbleLongPress: () -> Unit,
    onBubbleDrag: (Float, Float) -> Unit,
    onCollapse: () -> Unit,
    onCloseApp: () -> Unit,
    onActionMic: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    if (!expanded) {
        // Floating Bubble (Like Google Maps PiP bubble)
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (state == SttState.RECORDING) 1.25f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )

        Box(
            modifier = Modifier
                .size(68.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onBubbleDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .combinedClickable(
                    onClick = onBubbleShortPress,
                    onLongClick = onBubbleLongPress
                ),
            contentAlignment = Alignment.Center
        ) {
            if (state == SttState.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .scale(pulseScale)
                        .background(Color(0xFFFF5277).copy(alpha = 0.45f), CircleShape)
                )
            }

            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = when (state) {
                    SttState.RECORDING -> Color(0xFFFF5277)
                    SttState.TRANSCRIBING -> Color(0xFFFFB300)
                    SttState.SUCCESS -> Color(0xFF00E676)
                    else -> Color(0xFF00E5FF)
                },
                shadowElevation = 10.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state == SttState.TRANSCRIBING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            color = Color(0xFF0B0E14),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state == SttState.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "STT Bubble",
                            tint = Color(0xFF0B0E14),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Expanded Panel: centered, bounded, responsive
        Card(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(8.dp)
                .shadow(16.dp, RoundedCornerShape(26.dp)),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
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
                                    when (state) {
                                        SttState.RECORDING -> Color(0xFFFF5277)
                                        SttState.TRANSCRIBING -> Color(0xFFFFB300)
                                        SttState.SUCCESS -> Color(0xFF00E676)
                                        else -> Color(0xFF00E5FF)
                                    },
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cloudflare STT",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Minimizar", modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Big action Mic Button
                FilledIconButton(
                    onClick = onActionMic,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = when (state) {
                            SttState.RECORDING -> Color(0xFFFF5277)
                            SttState.TRANSCRIBING -> Color(0xFFFFB300)
                            SttState.SUCCESS -> Color(0xFF00E676)
                            else -> Color(0xFF00E5FF)
                        },
                        contentColor = Color(0xFF0B0E14)
                    ),
                    enabled = state != SttState.TRANSCRIBING
                ) {
                    if (state == SttState.TRANSCRIBING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = Color(0xFF0B0E14),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state == SttState.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Grabar",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = when (state) {
                        SttState.IDLE -> "Toca para dictar"
                        SttState.RECORDING -> "Escuchando... Toca para finalizar"
                        SttState.TRANSCRIBING -> "Transcribiendo con Cloudflare..."
                        SttState.SUCCESS -> "Listo (inyectado / copiado)"
                        SttState.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (state) {
                        SttState.ERROR -> Color(0xFFFF5277)
                        SttState.SUCCESS -> Color(0xFF00E676)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center
                )

                if (state == SttState.ERROR && error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF5277),
                        textAlign = TextAlign.Center
                    )
                }

                if (text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("STT", text))
                                Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copiar", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, text)
                                    type = "text/plain"
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Compartir texto").apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Compartir", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = onCloseApp) {
                    Text("Cerrar burbuja flotante", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
            }
        }
    }
}
