package com.datanomous.logisticsassistant

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
import com.datanomous.logisticsassistant.audio.MicStreamer
import com.datanomous.logisticsassistant.audio.TTSPlayer
import com.datanomous.logisticsassistant.network.ChatWebSocket
import kotlinx.coroutines.*


/**
 * =====================================================================
 *  LOGISTICS ASSISTANT SERVICE
 * =====================================================================
 *
 * Foreground service that orchestrates:
 *   - Speech input pipeline: MicStreamer → /stt
 *   - Text pipeline: ChatWebSocket → /text
 *   - Speech output: TTSPlayer (play server-generated WAVs)
 *
 * Design:
 *   - Service is the long-lived orchestrator & lifecycle owner.
 *   - Pipelines (MicStreamer, ChatWebSocket, TTSPlayer) are created
 *     and destroyed here.
 *   - UI should NOT talk to pipelines directly — instead it should
 *     use LogisticsAssistantManager as façade.
 *
 * Notes:
 *   - Some legacy Intent-based commands (SEND_TEXT, PLAY_TTS) are
 *     retained for backward compatibility.
 */
class LogisticsAssistantService : Service() {

    companion object {
        private const val TAG = "LogisticsAssistant"

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
        private var micStreamer: MicStreamer? = null

        @Volatile
        private var chatWebSocket: ChatWebSocket? = null

        @Volatile
        private var ttsPlayer: TTSPlayer? = null

        // Background dispatcher for WebSocket + TTS dispatch
        private val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Service instance (assigned in onCreate).
        // NOTE: This is not ideal, but kept for compatibility with legacy APIs.
        lateinit var instance: LogisticsAssistantService

        // Prevents CPU sleep when streaming audio
        private lateinit var wakeLock: PowerManager.WakeLock


        // =====================================================================
        // 🏛 PUBLIC UI-FACING API (now used via LogisticsAssistantManager)
        // =====================================================================

        /**
         * Returns true when the /text WebSocket is alive.
         * UI / Manager uses this to adjust connection indicator.
         */
        fun isChatConnected(): Boolean {
            val ws = chatWebSocket
            return ws?.isConnected() == true
        }

        /**
         * Returns true only if micStreamer exists.
         * Does NOT guarantee the mic is active — only that the pipeline exists.
         */
        fun isMicAvailable(): Boolean {
            return micStreamer != null
        }

        /**
         * Returns current mic state (ACTIVE, MUTED, or OFF).
         */
        fun getMicState(): MicState = micState


        /**
         * Pauses microphone audio capture.
         * Updates logical state + logs failures instead of hiding them.
         */
        fun pauseMic() {
            micState = MicState.MUTED
            try {
                micStreamer?.pauseMic()
                Log.i(TAG, "🎙️ Mic paused")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ pauseMic() failed: ${e.message}", e)
            }
        }

        /**
         * Resumes microphone audio capture.
         * Updates logical state + logs failures.
         */
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
        // 🆕 MODERN IMPLEMENTATION
        // sendText(): Writes directly to WebSocket (no service restart)
        // =====================================================================

        /**
         * Sends text to the backend through the existing /text WebSocket.
         * This is the correct modern API — no foregroundService triggers.
         *
         * UI should call this via LogisticsAssistantManager.sendText().
         */
        fun sendText(text: String) {
            val ws = chatWebSocket

            if (ws == null || !ws.isConnected()) {
                Log.e(TAG, "❌ sendText(): WebSocket is not connected")
                return
            }

            svcScope.launch {
                try {
                    Log.i(TAG, "📤 [sendText] → '$text'")
                    ws.send(text)  // Use existing ChatWebSocket API as in sendToTextWS
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to send text: ${e.message}", e)
                }
            }
        }



        // =====================================================================
        // 🏚 Legacy Version (kept for compatibility)
        // sendTextLegacy(): Uses ForegroundService intent dispatch
        // =====================================================================

        /**
         * Legacy behavior:
         *  - Starts/activates LogisticsAssistantService via an Intent
         *  - Triggers onStartCommand("SEND_TEXT")
         *
         * This is kept ONLY for backward compatibility.
         * UI SHOULD NOT CALL THIS unless you need old behavior.
         */
        fun sendTextLegacy(app: Context, text: String) {
            Log.w(TAG, "[LEGACY] sendTextLegacy() invoked → '$text'")

            val intent = Intent(app, LogisticsAssistantService::class.java).apply {
                action = "SEND_TEXT"
                putExtra("text", text)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                app.startForegroundService(intent)
            else
                app.startService(intent)
        }



        // =====================================================================
        // 🔊 TTS Control — Modern + Correct Approach
        // playTts(): enqueue locally on TTSPlayer
        // =====================================================================

        /**
         * Plays a TTS audio URL using the internal TTSPlayer.
         * This avoids incorrectly starting a service from the UI layer.
         */
        fun playTts(url: String) {
            val player = ttsPlayer
            if (player == null) {
                Log.e(TAG, "❌ playTts(): TTSPlayer not initialized")
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



        // =====================================================================
        // 🏚 Legacy TTS (kept for compatibility)
        // =====================================================================

        /**
         * Legacy version:
         *  - Starts foreground service with PLAY_TTS intent.
         * Not recommended for new code.
         */
        fun playTtsLegacy(url: String) {
            Log.w(TAG, "[LEGACY] playTtsLegacy() invoked → '$url'")

            val intent = Intent(instance, LogisticsAssistantService::class.java).apply {
                action = "PLAY_TTS"
                putExtra("url", url)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                instance.startForegroundService(intent)
            else
                instance.startService(intent)
        }
    }




    // =====================================================================
    //  SERVICE LIFECYCLE
    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 [SERVICE] onCreate()")

        instance = this

        // Foreground notification (required for long-running audio/WS)
        createForegroundNotification()

        // Initialize all pipelines
        initTTSPlayer()
        initChatWebSocket()
        initMicStreamer()

        // Acquire CPU wake-lock so mic + WS keep running when screen is off
        val pmWl = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pmWl.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LogisticsAssistant::MicLock"
        )
        wakeLock.acquire()
        Log.i(TAG, "🔒 [SERVICE] WakeLock acquired")

        // Ask user to ignore battery optimizations so service can run freely
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.w(TAG, "⚠️ Requested ignore battery optimizations")
        }

        Log.i(TAG, "[SERVICE] Initialization sequence complete (MicState=$micState)")
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
                // No action = service probably just started normally.
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
            Log.e(TAG, "[SERVICE][CLEANUP] Error closing WS: ${t.message}", t)
        }

        try {
            Log.d(TAG, "[SERVICE][CLEANUP] Stopping MicStreamer")
            micStreamer?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "[SERVICE][CLEANUP] Error stopping mic: ${t.message}", t)
        }

        try {
            Log.d(TAG, "[SERVICE][CLEANUP] Stopping TTSPlayer")
            ttsPlayer?.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "[SERVICE][CLEANUP] Error stopping TTS: ${t.message}", t)
        }

        // 🔐 WakeLock cleanup (companion property)
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                Log.i(TAG, "🔓 [SERVICE] WakeLock released")
            }
        } catch (e: UninitializedPropertyAccessException) {
            Log.w(TAG, "⚠️ [SERVICE] WakeLock was never initialized, nothing to release")
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [SERVICE] Error releasing WakeLock: ${e.message}", e)
        }

        micStreamer = null
        ttsPlayer = null
        chatWebSocket = null

        super.onDestroy()
    }


    override fun onBind(intent: Intent?): IBinder? = null



    // =====================================================================
    // INITIALIZATION
    // =====================================================================

    /**
     * Initializes TTSPlayer and wires callback to re-activate mic
     * when playback is finished.
     */
    private fun initTTSPlayer() {
        Log.i(TAG, "🎧 [TTS][INIT] Creating TTSPlayer")

        ttsPlayer = TTSPlayer(applicationContext).apply {
            onPlaybackFinished = {
                Log.i(TAG, "🔚 [TTS] Playback finished → activating mic")
                activateMic()
            }
        }
    }

    /**
     * Initializes /text WebSocket and hooks message → broadcast to UI.
     */
    private fun initChatWebSocket() {
        Log.i(TAG, "🌐 [WS-TEXT][INIT] Initializing /text WebSocket")

        chatWebSocket = ChatWebSocket(
            context = this,
            url = "wss://unpalatal-danille-semiexternally.ngrok-free.dev/text",
            onMessage = { msg ->
                Log.i(TAG, "📥 [WS-TEXT] Incoming message → broadcasting to UI: $msg")
                sendBroadcast(
                    Intent("VOICE_ASSISTANT_MESSAGE")
                        .putExtra("message", msg)
                )
            },
            onError = { err ->
                Log.e(TAG, "❌ [WS-TEXT] Error: ${err.message}", err)
            }
        )

        Log.d(TAG, "🌐 [WS-TEXT][CONNECT] Connecting…")
        chatWebSocket?.connect()
    }

    /**
     * Initializes MicStreamer and wires STT transcription → /text WS.
     */
    private fun initMicStreamer() {
        Log.i(TAG, "🎙️ [MIC][INIT] Creating MicStreamer (MicState=$micState)")

        micStreamer = MicStreamer(
            context = this,
            serverUrl = "wss://unpalatal-danille-semiexternally.ngrok-free.dev/stt",
            onText = { text ->
                Log.i(TAG, "📝 [STT][TEXT] '$text' → routing to /text WS")
                // Use the modern direct WebSocket API
                sendText(text)
            }
        )
    }

    /**
     * Plays TTS via TTSPlayer and mutes mic while speaking.
     */
    private fun playTtsInternal(url: String) {
        muteMic()
        ttsPlayer?.play(url)
    }



    // =====================================================================
    // FOREGROUND NOTIFICATION
    // =====================================================================

    /**
     * Creates and attaches a foreground notification for this service.
     * Required by Android for long-running background work (mic / WS).
     */
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
                    .setContentTitle("Logistics Assistant Running")
                    .setContentText("Listening for commands…")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()
            else
                Notification.Builder(this)
                    .setContentTitle("Logistics Assistant Running")
                    .setContentText("Listening for commands…")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()

        startForeground(1, notification)
    }



    // =====================================================================
    // MIC STATE MACHINE
    // =====================================================================

    /**
     * Activates microphone streaming or resumes sending audio,
     * depending on current MicState.
     */
    private fun activateMic() {
        val mic = micStreamer ?: run {
            Log.e(TAG, "[MIC][ACTIVE] micStreamer=null → cannot activate")
            return
        }

        when (micState) {
            MicState.OFF -> {
                Log.i(TAG, "🎙️ [STATE] OFF → ACTIVE (starting mic)")
                micState = MicState.ACTIVE
                mic.start(mic.getScope())
                mic.activateSending()
            }

            MicState.MUTED -> {
                Log.i(TAG, "🎙️ [STATE] MUTED → ACTIVE (resuming mic sending)")
                micState = MicState.ACTIVE
                mic.activateSending()
            }

            MicState.ACTIVE -> {
                Log.d(TAG, "🎙️ [STATE] Mic already active → no change")
            }
        }
    }

    /**
     * Mutes microphone sending (audio stream may still run, but is not sent).
     */
    private fun muteMic() {
        val mic = micStreamer ?: run {
            Log.w(TAG, "[MIC][MUTE] micStreamer=null → cannot mute")
            return
        }

        if (micState == MicState.ACTIVE) {
            Log.i(TAG, "🔇 [STATE] ACTIVE → MUTED (disabling sending)")
            micState = MicState.MUTED
            mic.muteSending()
        } else {
            Log.d(TAG, "[MIC][MUTE] Ignored — mic not active (MicState=$micState)")
        }
    }



    // =====================================================================
    // TEXT WS SENDING (LEGACY PATH FOR INTENT-BASED SEND)
    // =====================================================================

    /**
     * Legacy internal helper used by onStartCommand("SEND_TEXT").
     * New code should prefer the modern [sendText] API.
     */
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

    /**
     * Public instance-level API used by other components
     * to enqueue and play a TTS URL.
     *
     * Mutes mic while speaking.
     */
    fun enqueueAudio(url: String) {
        Log.i(TAG, "🔊 [TTS][REQUEST] enqueueAudio(url=$url)")

        muteMic()

        ttsPlayer?.play(url)
            ?: Log.e(TAG, "❌ [TTS] TTSPlayer=null → cannot play: $url")
    }
}
