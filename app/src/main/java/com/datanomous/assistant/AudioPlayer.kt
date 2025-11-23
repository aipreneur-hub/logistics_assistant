package com.datanomous.logisticsassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import okio.sink
import okio.buffer
import kotlinx.coroutines.*
import kotlinx.coroutines.isActive

private const val TAG = "LogisticsAssistant -TTSPlayer"

// Shared HTTP client
private val httpClient = OkHttpClient.Builder().build()

class TTSPlayer(private val context: Context) {

    private val appContext = context.applicationContext

    // NEW CALLBACK (required by LogisticsAssistantService)
    var onPlaybackFinished: (() -> Unit)? = null

    // Queue system (kept from your version)
    private val queue = LinkedBlockingQueue<String>()
    private val executor = Executors.newSingleThreadExecutor()

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var workerJob: Job? = null

    private val cacheDir = File(appContext.cacheDir, "tts_cache").apply { mkdirs() }

    // Audio focus
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { focus ->
            Log.w(TAG, "🔊 AudioFocus changed → $focus")
        }

    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    init {
        executor.execute { queueWorker() }
    }

    // ----------------------------------------------------
    // PUBLIC API
    // ----------------------------------------------------

    /** NEW: Called by service */
    fun play(url: String) {
        Log.d(TAG, "🎶 play(url) → enqueueing")
        queue.offer(url)
    }

    /** old public API but still supported */
    fun enqueue(url: String) {
        Log.d(TAG, "📥 enqueue(url) → queue.offer(url)")
        queue.offer(url)
    }

    fun isPlaying(): Boolean = isPlaying

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Throwable) {}
        mediaPlayer = null
        isPlaying = false
    }

    // ----------------------------------------------------
    // WORKER
    // ----------------------------------------------------

    private fun queueWorker() {
        if (workerJob != null) return

        workerJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "▶️ TTS worker loop started")
            while (isActive) {
                try {
                    val next = queue.take()
                    Log.d(TAG, "🎧 Worker picked TTS job: $next")
                    playInternal(next)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Worker loop error: ${e.message}", e)
                }
            }
        }
    }

    // ----------------------------------------------------
    // PLAYBACK ENGINE
    // ----------------------------------------------------
    private fun playInternal(urlOrPath: String) {
        try {
            isPlaying = true

            // 1) Request audio focus
            val focus = audioManager.requestAudioFocus(focusRequest)
            if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "⚠️ AudioFocus NOT granted")
            }

            // 2) Download file if URL
            val file =
                if (urlOrPath.startsWith("http")) downloadAndCache(urlOrPath)
                else File(urlOrPath)

            if (file == null || !file.exists()) {
                Log.e(TAG, "❌ Missing file: $urlOrPath")
                return
            }

            val uri = Uri.fromFile(file)
            val mp = MediaPlayer()
            mediaPlayer = mp

            val lock = Object()

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            mp.setDataSource(appContext, uri)

            mp.setOnPreparedListener {
                Log.i(TAG, "▶️ Playback start")
                mp.start()
            }

            mp.setOnCompletionListener {
                Log.i(TAG, "🏁 Playback completed")
                synchronized(lock) { lock.notify() }
                mp.release()
            }

            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "❌ MediaPlayer error: $what / $extra")
                synchronized(lock) { lock.notify() }
                mp.release()
                true
            }

            mp.prepareAsync()

            synchronized(lock) { lock.wait() }

        } catch (e: Exception) {
            Log.e(TAG, "❌ playInternal() failed: ${e.message}", e)

        } finally {
            mediaPlayer = null
            isPlaying = false

            try { audioManager.abandonAudioFocusRequest(focusRequest) } catch (_: Throwable) {}

            // THE IMPORTANT PART:
            Log.i(TAG, "🔚 TTS done → calling onPlaybackFinished()")
            onPlaybackFinished?.invoke()
        }
    }

    // ----------------------------------------------------
    // DOWNLOAD HELPERS
    // ----------------------------------------------------
    private fun downloadAndCache(url: String): File? {
        return try {
            Log.d(TAG, "⬇️ Downloading $url")
            val req = okhttp3.Request.Builder().url(url).build()

            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body ?: return null

                val out = File(cacheDir, "${url.hashCode()}.wav")
                out.sink().buffer().use { it.writeAll(body.source()) }
                out
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Download failed: ${e.message}", e)
            null
        }
    }
}
