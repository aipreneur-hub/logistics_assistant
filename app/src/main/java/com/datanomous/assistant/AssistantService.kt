package com.datanomous.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.datanomous.assistant.audio.AudioPlayer
import com.datanomous.assistant.audio.SpeechStreamer
import com.datanomous.assistant.network.CommandWebSocketClient
import com.datanomous.assistant.network.ResponseWebSocketClient
import kotlinx.coroutines.*
import com.datanomous.assistant.monitor.HealthMonitor
import com.datanomous.assistant.monitor.SystemHealth
import com.datanomous.assistant.tts.TextToSpeechEngine

/**
 * =====================================================================
 *  ASSISTANT SERVICE
 * =====================================================================
 *
 * Foreground service that orchestrates:
 *   - Speech input pipeline: SpeechStreamer → /stt
 *   - Text pipeline: CommandWebSocketClient → /text (UI + control)
 *   - Assistant responses:
 *       • ResponseWebSocketClient → Android TTS (TextToSpeechEngine)
 *   - Speech output:
 *       • AudioPlayer (server-generated WAV/URL - legacy)
 */
class `AssistantService.kt` : Service() {

    companion object {
        private const val TAG = "AssistantService"

        // ---------------------------------------------------------------------
        // PUBLIC MIC STATE TRACKER (used by UI and manager)
        // ---------------------------------------------------------------------
        enum class MicState { OFF, MUTED, ACTIVE }

        @Volatile
        private var micState = MicState.OFF

        // ---------------------------------------------------------------------
        // CORE PIPELINE OBJECTS — owned by the service
        // ---------------------------------------------------------------------
        @Volatile
        private var micStreamer: SpeechStreamer? = null

        @Volatile
        private var chatWebSocket: CommandWebSocketClient? = null

        // Dedicated WS for assistant responses (text-only → Android TTS)
        @Volatile
        private var responseClient: ResponseWebSocketClient? = null

        @Volatile
        private var ttsPlayer: AudioPlayer? = null  // URL/WAV playback (legacy/hybrid)

        // Background dispatcher for WebSocket + TTS dispatch
        private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Service instance (assigned in onCreate).
        lateinit var instance: `AssistantService.kt`

        // Prevents CPU sleep when streaming audio
        private lateinit var wakeLock: PowerManager.WakeLock

        @Volatile
        private var healthMonitor: HealthMonitor? = null

        @Volatile
        var pipelineBusy: Boolean = false

        fun lockPipeline() {
            Log.i(TAG, "🔒 PIPELINE LOCKED")
        }

        fun unlockPipeline() {
            Log.i(TAG, "🔓 PIPELINE UNLOCKED")
        }

        // ---------------------------------------------------------------------
        // APP RESTART / RESET
        // ---------------------------------------------------------------------
        fun hardRestartApp(context: Context) {
            Log.w(TAG, "🔴 [SERVICE] HARD RESTART — scheduling full app relaunch")

            val appContext = context.applicationContext

            try {
                val pm = appContext.packageManager
                val launchIntent =
                    pm.getLaunchIntentForPackage(appContext.packageName)?.apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                if (launchIntent == null) {
                    Log.e(TAG, "❌ hardRestartApp(): launch intent is null")
                    return
                }

                val alarmManager =
                    appContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

                val pendingIntent = android.app.PendingIntent.getActivity(
                    appContext,
                    0,
                    launchIntent,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                                android.app.PendingIntent.FLAG_IMMUTABLE
                            else 0)
                )

                val triggerAt = System.currentTimeMillis() + 400L
                alarmManager.setExact(android.app.AlarmManager.RTC, triggerAt, pendingIntent)

                Log.i(TAG, "⏰ [HARD RESTART] Relaunch scheduled in 400ms")

                try {
                    instance.stopForeground(true)
                } catch (t: Throwable) {
                    Log.w(TAG, "⚠️ stopForeground failed: ${t.message}")
                }

                try {
                    instance.stopSelf()
                } catch (t: Throwable) {
                    Log.w(TAG, "⚠️ stopSelf failed: ${t.message}")
                }

                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(0)

            } catch (e: Exception) {
                Log.e(TAG, "❌ hardRestartApp() failed: ${e.message}", e)
            }
        }

        fun softReset() {
            Log.w(TAG, "🔄 [SERVICE] SOFT RESET — server-only reset, mic untouched")

            val ws = chatWebSocket
            if (ws == null || !ws.isConnected()) {
                Log.e(TAG, "❌ softReset(): WS not connected")
                return
            }

            svcScope.launch {
                try {
                    Log.i(TAG, "📤 [softReset] → sending RESET command frame")
                    ws.sendCommand("reset")

                    Log.i(TAG, "🎙️ [softReset] Forcing mic re-arm...")

                    micStreamer?.stop()
                    micState = MicState.OFF

                    delay(150)

                    micStreamer?.start(svcScope)
                    micStreamer?.activateSending()

                    micState = MicState.ACTIVE
                    Log.i(TAG, "🎙️ [softReset] Mic restarted successfully!")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ softReset() failed: ${e.message}", e)
                }
            }
        }

        // =====================================================================
        // 🏛 PUBLIC UI-FACING API (via AssistantManager)
        // =====================================================================

        fun isChatConnected(): Boolean {
            val ws = chatWebSocket
            return ws?.isConnected() == true
        }

        fun isMicAvailable(): Boolean {
            return when (micState) {
                MicState.ACTIVE -> true
                MicState.MUTED -> false
                MicState.OFF -> false
            }
        }

        fun getMicState(): MicState = micState

        fun pauseMic() {
            micState = MicState.MUTED
            try {
                micStreamer?.pauseMic()
                Log.i(TAG, "🎙️ Mic paused")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ pauseMic() failed: ${e.message}", e)
            }
        }

        fun resumeMic() {
            micState = MicState.ACTIVE
            try {
                micStreamer?.resumeMic()
                Log.i(TAG, "🎙️ Mic resumed")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ resumeMic() failed: ${e.message}", e)
            }
        }

        // =====================================================================
        // TEXT SEND (modern + legacy)
        // =====================================================================

        fun sendText(text: String) {
            val ws = chatWebSocket

            if (ws == null || !ws.isConnected()) {
                Log.e(TAG, "❌ sendText(): WebSocket is not connected")
                return
            }

            svcScope.launch {
                try {
                    lockPipeline()
                    Log.i(TAG, "📤 [sendText] → '$text' -> lock pipeline / mic")
                    ws.send(text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to send text: ${e.message}", e)
                }
            }
        }

        fun sendTextLegacy(app: Context, text: String) {
            Log.w(TAG, "[LEGACY] sendTextLegacy() invoked → '$text'")

            val intent = Intent(app, `AssistantService.kt`::class.java).apply {
                action = "SEND_TEXT"
                putExtra("text", text)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                app.startForegroundService(intent)
            else
                app.startService(intent)
        }

        // =====================================================================
        // 🔊 TTS (URL/WAV via AudioPlayer) — existing behavior (HYBRID)
        // =====================================================================

        fun playTts(url: String) {
            if (url.isBlank()) {
                Log.w(TAG, "⚠️ playTts() called with blank URL")
                return
            }

            if (ttsPlayer == null) {
                Log.w(TAG, "⚠️ playTts(): AudioPlayer null → initializing lazily")
                try {
                    instance.initTTSPlayer()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ playTts(): lazy init failed: ${e.message}", e)
                }
            }

            val player = ttsPlayer
            if (player == null) {
                Log.e(TAG, "❌ playTts(): AudioPlayer still null after init → dropping TTS: $url")
                return
            }

            svcScope.launch {
                try {
                    Log.i(TAG, "🔊 [TTS] Enqueue+play URL → $url")
                    player.play(url)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ enqueue TTS failed: ${e.message}", e)
                }
            }
        }

        fun playTtsLegacy(url: String) {
            Log.w(TAG, "[LEGACY] playTtsLegacy() invoked → '$url'")

            val intent = Intent(instance, `AssistantService.kt`::class.java).apply {
                action = "PLAY_TTS"
                putExtra("url", url)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                instance.startForegroundService(intent)
            else
                instance.startService(intent)
        }

        // =====================================================================
        // 🆕 Native Android TTS for TEXT
        // =====================================================================

        fun speakText(text: String) {
            if (text.isBlank()) {
                Log.w(TAG, "⚠️ speakText() called with blank text")
                return
            }

            svcScope.launch {
                try {
                    Log.i(TAG, "🗣 [TTS] speakText() → '$text'")
                    TextToSpeechEngine.run(instance.applicationContext, text)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ speakText() failed: ${e.message}", e)
                }
            }
        }
    }

    // =====================================================================
    //  SERVICE LIFECYCLE
    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 [SERVICE] onCreate()")

        instance = this

        createForegroundNotification()

        initTTSPlayer()
        initChatWebSocket()
        initResponseWebSocket()       // /response WS for assistant text
        initMicStreamer()
        activateMic()

        val pmWl = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pmWl.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Assistant::MicLock"
        )
        wakeLock.acquire()
        Log.i(TAG, "🔒 [SERVICE] WakeLock acquired")

        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.w(TAG, "⚠️ Requested ignore battery optimizations")
        }

        healthMonitor = HealthMonitor(
            context = this
        ) { health ->
            SystemHealth.state.value = health
        }

        healthMonitor?.start()
        Log.i(TAG, "🩺 [SERVICE] HealthMonitor started")

        Log.i(TAG, "[SERVICE] Initialization sequence complete (MicState=${Companion.micState})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "[SERVICE] onStartCommand → action=${intent?.action}")

        when (intent?.action) {

            "SEND_TEXT" -> {
                val text = intent.getStringExtra("text") ?: ""
                Log.d(TAG, "[SERVICE][SEND_TEXT] Received text='$text'")
                if (text.isNotBlank()) {
                    sendToTextWS(text)
                } else {
                    Log.w(TAG, "[SERVICE][SEND_TEXT] Blank text received → ignoring")
                }
            }

            "STOP_SERVICE" -> {
                Log.w(TAG, "[SERVICE] STOP_SERVICE received → stopping")
                stopSelf()
            }

            "PLAY_TTS" -> {
                val url = intent.getStringExtra("url") ?: ""
                if (url.isNotBlank()) {
                    Log.i(TAG, "🔊 [SERVICE] PLAY_TTS: $url")
                    playTtsInternal(url)
                } else {
                    Log.w(TAG, "[SERVICE][PLAY_TTS] Blank URL → ignoring")
                }
            }

            else -> {
                Log.d(TAG, "[SERVICE] onStartCommand with no specific action")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "🧹 [SERVICE] onDestroy() → shutting down everything")

        try {
            Log.d(TAG, "[SERVICE][CLEANUP] Closing /text WS")
            chatWebSocket?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "[SERVICE][CLEANUP] Error closing /text WS: ${t.message}", t)
        }

        try {
            Log.d(TAG, "[SERVICE][CLEANUP] Closing /response WS")
            responseClient?.close()
        } catch (t: Throwable) {
            Log.e(TAG, "[SERVICE][CLEANUP] Error closing /response WS: ${t.message}", t)
        }

        try {
            Log.d(TAG, "[SERVICE][CLEANUP] Stopping SpeechStreamer")
            micStreamer?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "[SERVICE][CLEANUP] Error stopping mic: ${t.message}", t)
        }

        try {
            Log.d(TAG, "[SERVICE][CLEANUP] Stopping AudioPlayer")
            ttsPlayer?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "[SERVICE][CLEANUP] Error stopping TTS: ${t.message}", t)
        }

        try {
            TextToSpeechEngine.shutdown()
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Error shutting down TTS: ${t.message}", t)
        }

        try {
            healthMonitor?.stop()
            Log.i(TAG, "🩺 [SERVICE] HealthMonitor stopped")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Error stopping HealthMonitor: ${t.message}", t)
        }

        try {
            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "🔓 [SERVICE] WakeLock released")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [SERVICE] Error releasing WakeLock: ${e.message}", e)
        }

        micStreamer = null
        ttsPlayer = null
        chatWebSocket = null
        responseClient = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =====================================================================
    // INITIALIZATION HELPERS
    // =====================================================================

    private fun initTTSPlayer() {
        Log.i(TAG, "🎧 [TTS][INIT] Creating AudioPlayer")

        ttsPlayer = AudioPlayer(applicationContext).apply {
            onPlaybackFinished = {
                Log.i(TAG, "🔚 [TTS] Playback finished -> pipeline / mic unlocked")
                Companion.unlockPipeline()
                activateMic()
            }
        }
    }

    private fun initChatWebSocket() {
        Log.i(TAG, "🌐 [WS-TEXT][INIT] Initializing /text WebSocket")

        // Use positional args to avoid name-mismatch errors
        chatWebSocket = CommandWebSocketClient(
            this,
            "ws://128.140.66.158:8000/text",
            { msg ->
                Log.i(TAG, "📥 [WS-TEXT] Incoming message → broadcasting to UI: $msg")
                sendBroadcast(
                    Intent("VOICE_ASSISTANT_MESSAGE")
                        .putExtra("message", msg)
                )
            },
            { err ->
                Log.e(TAG, "❌ [WS-TEXT] Error: ${err.message}", err)
            }
        )

        Log.d(TAG, "🌐 [WS-TEXT][CONNECT] Connecting…")
        chatWebSocket?.connect()
    }

    // /response WebSocket — ASSISTANT RESPONSES ONLY
    private fun initResponseWebSocket() {
        Log.i(TAG, "🌐 [WS-RESPONSE][INIT] Initializing /response WebSocket")

        val deviceId = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val ttsController = com.datanomous.assistant.tts.TtsController(applicationContext)

        responseClient = ResponseWebSocketClient(
            deviceId = deviceId,
            tts = ttsController,
            onConnected = {
                Log.i(TAG, "🟢 /response connected")
            },
            onDisconnected = {
                Log.w(TAG, "🔴 /response disconnected")
            }
        )

        responseClient?.connect("ws://128.140.66.158:8000/response")
    }

    private fun initMicStreamer() {
        Log.i(TAG, "🎙️ [MIC][INIT] Creating SpeechStreamer (MicState=${Companion.micState})")

        micStreamer = SpeechStreamer(
            context = this,
            serverUrl = "ws://128.140.66.158:8000/stt",
            onLevel = { level ->
                try {
                    com.datanomous.assistant.audio.MicUiState.level.tryEmit(level)
                } catch (_: Throwable) {
                }
            },
            onText = { text ->
                Log.i(TAG, "📝 [STT][TEXT] '$text' → routing to /text WS")
                Companion.sendText(text)
            }
        )
    }

    private fun playTtsInternal(url: String) {
        muteMic()
        ttsPlayer?.play(url)
    }

    // =====================================================================
    // FOREGROUND NOTIFICATION
    // =====================================================================

    private fun createForegroundNotification() {
        Log.d(TAG, "[SERVICE][NOTIFICATION] Creating foreground notification")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "assistant_channel",
                "Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, "assistant_channel")
                    .setContentTitle("Assistant Running")
                    .setContentText("Listening for commands…")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()
            else
                Notification.Builder(this)
                    .setContentTitle("Assistant Running")
                    .setContentText("Listening for commands…")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()

        startForeground(1, notification)
    }

    // =====================================================================
    // MIC STATE MACHINE
    // =====================================================================

    private fun activateMic() {
        val mic = micStreamer ?: run {
            Log.e(TAG, "[MIC][ACTIVE] micStreamer=null → cannot activate")
            return
        }

        when (Companion.micState) {
            MicState.OFF -> {
                Log.i(TAG, "🎙️ [STATE] OFF → ACTIVE (starting mic)")
                Companion.micState = MicState.ACTIVE
                mic.start(mic.getScope())
                mic.activateSending()
            }

            MicState.MUTED -> {
                Log.i(TAG, "🎙️ [STATE] MUTED → ACTIVE (resuming mic sending)")
                Companion.micState = MicState.ACTIVE
                mic.activateSending()
            }

            MicState.ACTIVE -> {
                Log.d(TAG, "🎙️ [STATE] Mic already active → no change")
            }
        }
    }

    private fun muteMic() {
        val mic = micStreamer ?: run {
            Log.w(TAG, "[MIC][MUTE] micStreamer=null → cannot mute")
            return
        }

        if (Companion.micState == MicState.ACTIVE) {
            Log.i(TAG, "🔇 [STATE] ACTIVE → MUTED (disabling sending)")
            Companion.micState = MicState.MUTED
            mic.muteSending()
        } else {
            Log.d(TAG, "[MIC][MUTE] Ignored — mic not active (MicState=${Companion.micState})")
        }
    }

    // =====================================================================
    // TEXT WS SENDING (legacy)
    // =====================================================================

    private fun sendToTextWS(text: String) {
        val ws = chatWebSocket ?: run {
            Log.e(TAG, "❌ [WS-TEXT][SEND] WebSocket null")
            return
        }

        if (ws.isConnected()) {
            Log.i(TAG, "📨 [WS-TEXT][SEND] '$text'")
            ws.send(text)
        } else {
            Log.w(TAG, "🌐 [WS-TEXT][SEND] WS disconnected → reconnecting…")
            ws.connect()
            ws.send(text)
        }
    }

    // =====================================================================
    // TTS REQUEST HANDLING (INSTANCE API)
    // =====================================================================

    fun enqueueAudio(url: String) {
        Log.i(TAG, "🔊 [TTS][REQUEST] enqueueAudio(url=$url)")

        muteMic()

        ttsPlayer?.play(url)
            ?: Log.e(TAG, "❌ [TTS] AudioPlayer=null → cannot play: $url")
    }
}
