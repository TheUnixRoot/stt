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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.cancel
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
    private val handler = Handler(Looper.getMainLooper())

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
            setContentText("Toca la burbuja en pantalla para dictar")
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
            x = 40
            y = 300
        }

        bubbleComposeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)

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
                    BubbleOverlayContent(
                        expanded = isExpanded.value,
                        state = sttState.value,
                        text = transcribedText.value,
                        error = errorMessage.value,
                        onBubbleClick = {
                            if (!isExpanded.value) {
                                isExpanded.value = true
                                updateWindowFocus(true)
                            } else {
                                handleMicClick()
                            }
                        },
                        onBubbleDrag = { dx, dy ->
                            windowParams.x += dx.toInt()
                            windowParams.y += dy.toInt()
                            windowManager?.updateViewLayout(this, windowParams)
                        },
                        onCollapse = {
                            if (sttState.value == SttState.RECORDING) {
                                recorderHelper.startRecording() // safety cancel
                            }
                            isExpanded.value = false
                            updateWindowFocus(false)
                        },
                        onCloseApp = {
                            stopSelf()
                        },
                        onActionMic = {
                            handleMicClick()
                        }
                    )
                }
            }
        }

        windowManager?.addView(bubbleComposeView, windowParams)
    }

    private fun updateWindowFocus(focusable: Boolean) {
        if (focusable) {
            windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        windowManager?.updateViewLayout(bubbleComposeView, windowParams)
    }

    private fun handleMicClick() {
        val prefs = getSharedPreferences("cf_stt_prefs", Context.MODE_PRIVATE)
        val accountId = prefs.getString("account_id", "") ?: ""
        val apiToken = prefs.getString("api_token", "") ?: ""
        val model = prefs.getString("model", "@cf/openai/whisper") ?: "@cf/openai/whisper"

        if (accountId.isBlank() || apiToken.isBlank()) {
            Toast.makeText(this, "Abre la app para configurar tu Token de Cloudflare", Toast.LENGTH_LONG).show()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(mainIntent)
            return
        }

        when (sttState.value) {
            SttState.RECORDING -> {
                sttState.value = SttState.TRANSCRIBING
                serviceScope.launch {
                    val wav = recorderHelper.stopAndGetWav()
                    if (wav.isEmpty()) {
                        errorMessage.value = "No se capturó audio"
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
                        if (resultText.isNotBlank()) {
                            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Cloudflare STT", resultText))
                            Toast.makeText(this@FloatingBubbleService, "¡Copiado al portapapeles!", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure { ex ->
                        errorMessage.value = ex.localizedMessage ?: "Error de transcripción"
                        sttState.value = SttState.ERROR
                    }
                }
            }
            SttState.TRANSCRIBING -> {
                // busy
            }
            else -> {
                val ok = recorderHelper.startRecording()
                if (ok) {
                    sttState.value = SttState.RECORDING
                    errorMessage.value = ""
                } else {
                    errorMessage.value = "No se pudo iniciar el micrófono"
                    sttState.value = SttState.ERROR
                }
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

@Composable
fun BubbleOverlayContent(
    expanded: Boolean,
    state: SttState,
    text: String,
    error: String,
    onBubbleClick: () -> Unit,
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
                .size(64.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onBubbleDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable { onBubbleClick() },
            contentAlignment = Alignment.Center
        ) {
            if (state == SttState.RECORDING) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .scale(pulseScale)
                        .background(Color(0xFFF38BA8).copy(alpha = 0.4f), CircleShape)
                )
            }

            Surface(
                modifier = Modifier.size(54.dp),
                shape = CircleShape,
                color = when (state) {
                    SttState.RECORDING -> Color(0xFFF38BA8)
                    SttState.TRANSCRIBING -> Color(0xFFFAB387)
                    else -> Color(0xFF89B4FA)
                },
                shadowElevation = 8.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state == SttState.TRANSCRIBING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color(0xFF11111B),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state == SttState.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "STT Bubble",
                            tint = Color(0xFF11111B),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    } else {
        // Expanded Panel
        Card(
            modifier = Modifier
                .width(320.dp)
                .wrapContentHeight()
                .padding(8.dp)
                .shadow(12.dp, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with drag & close
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onBubbleDrag(dragAmount.x, dragAmount.y)
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (state == SttState.RECORDING) Color(0xFFF38BA8) else Color(0xFFA6E3A1),
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cloudflare STT",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row {
                        IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Minimizar", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Big action Mic Button
                FilledIconButton(
                    onClick = onActionMic,
                    modifier = Modifier.size(68.dp),
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
                            modifier = Modifier.size(28.dp),
                            color = Color(0xFF11111B),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state == SttState.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = "Grabar",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = when (state) {
                        SttState.IDLE -> "Toca para dictar"
                        SttState.RECORDING -> "Grabando... Toca para enviar"
                        SttState.TRANSCRIBING -> "Transcribiendo con Cloudflare..."
                        SttState.SUCCESS -> "Listo (copiado al portapapeles)"
                        SttState.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        SttState.ERROR -> Color(0xFFF38BA8)
                        SttState.SUCCESS -> Color(0xFFA6E3A1)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center
                )

                if (state == SttState.ERROR && error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF38BA8),
                        textAlign = TextAlign.Center
                    )
                }

                if (text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copiar", fontSize = 12.sp)
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
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Compartir", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = onCloseApp) {
                        Text("Cerrar burbuja flotante", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
