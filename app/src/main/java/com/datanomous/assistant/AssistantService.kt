package com.datanomous.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.datanomous.assistant.audio.AudioPlayer
import com.datanomous.assistant.audio.SpeechStreamer
import com.datanomous.assistant.monitor.HealthMonitor
import com.datanomous.assistant.monitor.SystemHealth
import com.datanomous.assistant.network.CommandWebSocketClient
import com.datanomous.assistant.network.ResponseWebSocketClient
import com.datanomous.assistant.network.SocketManager
import com.datanomous.assistant.tts.TextToSpeechEngine
import com.datanomous.assistant.tts.TtsController
import kotlinx.coroutines.*
import org.json.JSONObject

class AssistantService : Service() {

    private lateinit var wakeLock: PowerManager.WakeLock

    companion object {
        private const val TAG = "AssistantService"

        private const val WS_BASE = "wss://datanomous.co.uk"
        private const val WS_TEXT = "$WS_BASE/text"
        private const val WS_STT = "$WS_BASE/stt"
        private const val WS_RESPONSE = "$WS_BASE/response"

        enum class MicState { OFF, MUTED, ACTIVE }

        @Volatile
        var micState = MicState.OFF

        @Volatile
        var micStreamer: SpeechStreamer? = null

        // Legacy WS clients (kept, not removed)
        @Volatile
        var chatWS: CommandWebSocketClient? = null

        @Volatile
        var responseWS: ResponseWebSocketClient? = null

        // New generic SocketManagers (nullable, no lateinit)
        @Volatile
        var textWS: SocketManager? = null      // /text via SocketManager

        @Volatile
        var sttWS: SocketManager? = null       // /stt via SocketManager (future)

        @Volatile
        var ttsPlayer: AudioPlayer? = null

        lateinit var instance: AssistantService

        val svcScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        var pipelineBusy = false

        // ============================================================
        // PUBLIC UI API
        // ============================================================
        @JvmStatic
        fun uiMuteMic() {
            try {
                instance.muteMic()
                Log.d(TAG, "🎙️ uiMuteMic → OK")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ uiMuteMic failed: ${e.message}")
            }
        }

        @JvmStatic
        fun uiActivateMic() {
            try {
                instance.activateMic()
                Log.d(TAG, "🎙️ uiActivateMic → OK")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ uiActivateMic failed: ${e.message}")
            }
        }

        @JvmStatic
        fun uiIsChatConnected(): Boolean {
            val viaSocket =
                try {
                    textWS?.isConnected() == true
                } catch (_: Throwable) {
                    false
                }

            val viaLegacy = chatWS?.isConnected() == true  // legacy kept but unused
            val ok = viaSocket || viaLegacy

            Log.d(TAG, "🔌 uiIsChatConnected → $ok (socket=$viaSocket legacy=$viaLegacy)")
            return ok
        }

        @JvmStatic
        fun uiIsMicAvailable(): Boolean {
            val available = micStreamer?.isStreamingActive() == true
            Log.d(TAG, "🎙️ uiIsMicAvailable → $available")
            return available
        }

        @JvmStatic
        fun uiReconnectChat() {
            try {
                if (textWS != null) {
                    Log.d(TAG, "🔄 uiReconnectChat → SocketManager(/text)")
                    textWS?.disconnect()
                    textWS?.connect()
                } else {
                    Log.d(TAG, "🔄 uiReconnectChat → legacy CommandWebSocketClient")
                    // chatWS?.connect()   // ❌ COMMENTED: legacy disabled
                }
            } catch (e: Throwable) {
                Log.e(TAG, "❌ uiReconnectChat failed: ${e.message}")
            }
        }

        @JvmStatic
        fun uiIsAssistantRunning(): Boolean {
            val ok = this::instance.isInitialized
            Log.d(TAG, "💡 uiIsAssistantRunning → $ok")
            return ok
        }

        // ============================================================
        // PIPELINE CONTROL
        // ============================================================
        @JvmStatic
        fun lockPipeline() {
            pipelineBusy = true
            Log.i(TAG, "🔒 Pipeline locked")
        }

        @JvmStatic
        fun unlockPipeline() {
            pipelineBusy = false
            Log.i(TAG, "🔓 Pipeline unlocked")
        }

        @JvmStatic
        fun sendText(txt: String) {
            if (txt.isBlank()) return

            // Prefer new /text SocketManager
            val socket = textWS
            if (socket != null && socket.isConnected()) {
                svcScope.launch {
                    try {
                        lockPipeline()
                        val safe = txt.replace("\"", "\\\"")
                        val envelope = """
                        {
                          "type": "message",
                          "payload": {
                            "id": "u-${System.currentTimeMillis()}",
                            "sender": "USER",
                            "text": "$safe",
                            "ts": ${System.currentTimeMillis()},
                            "extra": {}
                          }
                        }
                        """.trimIndent()
                        socket.sendText(envelope)
                        Log.d(TAG, "📤 USER → /text(SocketManager): $safe")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ sendText(SocketManager) failed: ${e.message}", e)
                    }
                }
                return
            }

            // Fallback (legacy) — commented!
            /*
            val ws = chatWS
            if (ws == null || !ws.isConnected()) {
                Log.e(TAG, "❌ /text not connected — cannot send")
                return
            }
            svcScope.launch {
                try {
                    lockPipeline()
                    ws.send(txt)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ sendText(legacy) failed: ${e.message}")
                }
            }
            */
        }

        @JvmStatic
        fun speak(text: String) {
            if (text.isBlank()) return

            svcScope.launch {
                try {
                    // 🔥 Ensure TTS uses A2DP (MODE_NORMAL)
                    val am = instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.stopBluetoothSco()
                    am.setBluetoothScoOn(false)
                    am.mode = AudioManager.MODE_NORMAL

                    Log.i(TAG, "🗣 speak() → $text")
                    TextToSpeechEngine.run(instance, text, flush = true)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ speak() failed: ${e.message}", e)
                } finally {
                    unlockPipeline()
                    instance.activateMic()
                }
            }
        }


        // --------------------------------------------------------
        // AUDIO MANAGEMENT HELPERS (unchanged)
        // --------------------------------------------------------
        private fun requestAudioFocus() {
            try {
                val am = instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            } catch (e: Throwable) {
                Log.e(TAG, "❌ requestAudioFocus failed: ${e.message}")
            }
        }

        private fun abandonAudioFocus() {
            try {
                val am = instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.abandonAudioFocus(null)
            } catch (e: Throwable) {
                Log.e(TAG, "❌ abandonAudioFocus failed: ${e.message}")
            }
        }

        private fun disableCommunicationAudio() {
            try {
                val am = instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.stopBluetoothSco()
                am.setBluetoothScoOn(false)
                am.mode = AudioManager.MODE_NORMAL
                am.isSpeakerphoneOn = true
                Log.i(TAG, "🔈 Communication audio disabled for TTS")
            } catch (_: Throwable) {
                Log.e(TAG, "❌ disableCommunicationAudio failed")
            }
        }

        private fun enableCommunicationAudio() {
            try {
                val am = instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.setBluetoothScoOn(true)
                Log.i(TAG, "🎧 Communication audio restored (MIC active)")
            } catch (_: Throwable) {
                Log.e(TAG, "❌ enableCommunicationAudio failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    // RESET LOGIC
    // -------------------------------------------------------------------------
    // -------------------------------------------------------------------------
// RESET LOGIC
// -------------------------------------------------------------------------
    fun softReset() {
        svcScope.launch {
            try {
                // 1) Ensure /text WS exists with proper callbacks
                if (textWS == null) {
                    Log.w(TAG, "softReset(): textWS was null → reinitializing")
                    initTextWS()          // ✅ reuse the same initialization logic
                    delay(500)
                }

                // 2) Ensure /text WS is connected
                if (!textWS!!.isConnected()) {
                    Log.w(TAG, "softReset(): reconnecting /text WS")
                    textWS!!.disconnect()
                    delay(200)
                    textWS!!.connect()
                    delay(400)
                }

                // 3) Send RESET to server
                textWS!!.sendText("""{"type":"reset"}""")
                Log.i(TAG, "🔄 softReset(): reset sent via /text")

                // 4) Restart /response WS after reset (safe)
                responseWS?.connect(WS_RESPONSE)

                // 5) Restart STT pipeline
                val wasMuted = (micState == MicState.MUTED)

                micStreamer?.stop()
                micState = MicState.OFF

                delay(200)

                micStreamer?.start(svcScope)

                if (!wasMuted) {
                    micStreamer?.activateSending()
                    micState = MicState.ACTIVE
                } else {
                    micStreamer?.muteSending()
                    micState = MicState.MUTED
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ softReset() failed: ${e.message}")
            }
        }
    }


    fun hardRestartApp(ctx: Context) {
        try {
            val pm = ctx.packageManager
            val intent = pm.getLaunchIntentForPackage(ctx.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                } ?: return

            val alarm = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val p = android.app.PendingIntent.getActivity(
                ctx, 0, intent,
                android.app.PendingIntent.FLAG_CANCEL_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                            android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )

            alarm.setExact(
                android.app.AlarmManager.RTC,
                System.currentTimeMillis() + 300L, p
            )

            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            Log.e(TAG, "❌ hardRestartApp: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // SERVICE LIFECYCLE
    // -------------------------------------------------------------------------
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "🚀 AssistantService started")

        createForegroundNotification()

        initTTSPlayer()
        initTextWS()
        initResponseWS()
        initMicWS()

        activateMic()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Assistant:MicLock")
        wakeLock.acquire()

        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        }

        /*Handler(Looper.getMainLooper()).postDelayed({
            try {
                Log.i(TAG, "🔥 Auto softReset → requesting server greeting…")
                softReset()
            } catch (e: Exception) {
                Log.e(TAG, "❌ auto softReset failed: ${e.message}")
            }
        }, 1200) */

        val monitor = HealthMonitor(this) { state ->
            SystemHealth.state.value = state
        }
        monitor.start()
    }

    override fun onDestroy() {
        try {
            chatWS?.close()   // legacy close kept (harmless)
        } catch (_: Throwable) {
        }
        try {
            responseWS?.close()
        } catch (_: Throwable) {
        }
        try {
            micStreamer?.stop()
        } catch (_: Throwable) {
        }
        try {
            ttsPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            TextToSpeechEngine.shutdown()
        } catch (_: Throwable) {
        }

        // clean up SocketManager /text if any
        try {
            textWS?.disconnect()
        } catch (_: Throwable) {
        }

        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            Log.w(TAG, "⚠ onTaskRemoved → restoring /response WS")
            responseWS?.connect(WS_RESPONSE)
        } catch (_: Throwable) {
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -------------------------------------------------------------------------
    // INIT HELPERS
    // -------------------------------------------------------------------------
    private fun initTTSPlayer() {
        ttsPlayer = AudioPlayer(this).apply {
            onPlaybackFinished = {
                unlockPipeline()
                activateMic()
            }
        }
    }

    private fun initTextWS() {

        lateinit var socket: SocketManager

        socket = SocketManager(
            url = WS_TEXT,
            autoReconnect = true,     // ✅ MUST be true

            onJsonMessage = { json ->
                try {
                    val type = json.optString("type", "").lowercase()
                    val payload = json.optJSONObject("payload")
                    val text = payload?.optString("text", "") ?: ""

                    when (type) {
                        "ping" -> {
                            val pong = JSONObject()
                                .put("type", "pong")
                                .put("ts", System.currentTimeMillis())
                            socket.sendText(pong.toString())
                            Log.d(TAG, "↩️ pong → server")
                        }

                        "processing" -> lockPipeline()

                        "message" -> {
                            if (text.isNotBlank()) {
                                sendBroadcast(
                                    Intent("VOICE_ASSISTANT_MESSAGE")
                                        .putExtra("message", text)
                                )
                                Log.i(TAG, "💬 BOT → UI(SocketManager): $text")
                            }
                            unlockPipeline()
                        }

                        else -> {
                            Log.w(TAG, "⚠️ Unknown /text(SocketManager) type='$type'")
                            unlockPipeline()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ /text SocketManager handler error: ${e.message}", e)
                    unlockPipeline()
                }
            },

            onConnected = {
                try {
                    val deviceId = Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ANDROID_ID
                    )

                    val hello = JSONObject()
                        .put("type", "hello")
                        .put("device_id", deviceId)

                    socket.sendText(hello.toString())
                    Log.i(TAG, "📤 hello(device_id=$deviceId) via SocketManager")

                    // small delay so server can bind device/session before reset
                    Handler(Looper.getMainLooper()).postDelayed({
                        socket.sendText("""{"type":"reset"}""")
                        Log.i(TAG, "📤 reset() → triggers server greeting")
                    }, 450)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ /text SocketManager onConnected failed: ${e.message}", e)
                }
            },

            onDisconnected = {
                Log.w(TAG, "🔴 /text SocketManager disconnected")
            },

            onError = { e ->
                Log.e(TAG, "🛑 /text SocketManager error → ${e.message}", e)
            }
        )

        textWS = socket
        socket.connect()
    }





    private fun initResponseWS() {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val controller = TtsController(this)
        responseWS = ResponseWebSocketClient(
            deviceId = id,
            tts = controller,
            onConnected = { Log.i(TAG, "🟢 /response connected") },
            onDisconnected = { Log.w(TAG, "🔴 /response disconnected") }
        )
        responseWS?.connect(WS_RESPONSE)
    }

    private fun initMicWS() {
        micStreamer = SpeechStreamer(
            context = this,
            serverUrl = WS_STT,
            onLevel = {
                try {
                    com.datanomous.assistant.audio.MicUiState.level.tryEmit(it)
                } catch (_: Throwable) {
                }
            },
            onText = { text ->
                sendText(text)
            }
        )
    }

    // -------------------------------------------------------------------------
    // MIC STATE MACHINE
    // -------------------------------------------------------------------------
    private fun activateMic() {
        // 🔥 Ensure STT uses Bluetooth SCO (call mode)
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.startBluetoothSco()
            am.setBluetoothScoOn(true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ activateMic(): audio mode switch failed: ${e.message}")
        }

        val m = micStreamer ?: return
        when (micState) {
            MicState.OFF -> {
                micState = MicState.ACTIVE
                m.start(svcScope)
                m.activateSending()
            }

            MicState.MUTED -> {
                micState = MicState.ACTIVE
                m.activateSending()
            }

            MicState.ACTIVE -> {}
        }
    }

    private fun muteMic() {
        val m = micStreamer ?: return
        if (micState == MicState.ACTIVE) {
            micState = MicState.MUTED
            m.muteSending()
        }
    }

    private fun createForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "assistant_channel", "Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(ch)
        }

        val note =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, "assistant_channel")
                    .setContentTitle("Assistant Running")
                    .setContentText("Listening…")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()
            else
                Notification.Builder(this)
                    .setContentTitle("Assistant Running")
                    .setContentText("Listening…")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .build()

        startForeground(1, note)
    }
}
