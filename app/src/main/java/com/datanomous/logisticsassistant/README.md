/**
* ===============================================================
* 🧠 CLEAN DESIGN ARCHITECTURE — VOICE ASSISTANT SYSTEM
* ===============================================================
*
* GOAL:
*   Create a stable, predictable, maintainable system where:
*      • Mic, TTS, and WebSockets never interfere with each other.
*      • UI never owns audio resources.
*      • Service orchestrates audio and backend communication.
*      • States are explicit and impossible to conflict.
*
* ---------------------------------------------------------------
* 🔥 CORE PRINCIPLES
* ---------------------------------------------------------------
* 1) The ForegroundService is the SINGLE owner of:
*        - MicStreamer (/stt audio)
*        - TTSPlayer (audio output)
*        - ChatWebSocket (/text)
*
* 2) The Activity owns ONLY:
*        - UI rendering
*        - User input
*        - Receiving messages via broadcast
*
*    No audio. No networking. No long-lived objects.
*
* 3) Audio system operates as a STATE MACHINE, not flags:
*
*        enum class MicState {
*            OFF,            // mic not started
*            MUTED,          // recording but not sending (during TTS)
*            ACTIVE          // recording + sending to STT
*        }
*
*    This replaces allowSending / suppressAudio / reset flags.
*
* ---------------------------------------------------------------
* 🧩 CLEAN RESPONSIBILITY BREAKDOWN
* ---------------------------------------------------------------
*
*  A) LogisticsAssistantService (the "control tower")
*  -------------------------------------------------
*     - Creates MicStreamer
*     - Creates TTSPlayer
*     - Creates ChatWebSocket
*     - Runs system as a state machine:
*
*          TTS → mic.mute()
*          TTS finished → mic.activate()
*
*     - Sends text to backend over /text
*     - Broadcasts backend replies to the UI
*     - Starts mic ONLY after first greeting TTS finishes
*
*
*  B) MicStreamer (audio engine)
*  -------------------------------------------------
*     - Owns AudioRecord lifecycle
*     - Owns connection to /stt WS
*     - Performs:
*          • PCM capture loop
*          • VAD (speech detection)
*          • Segmentation
*          • Sending completed segments to STT
*
*     - Exposed control APIs:
*          • start()    → connect + begin streaming
*          • mute()     → RECORD but DO NOT send (TTS is talking)
*          • activate() → RECORD + SEND to STT
*          • stop()     → fully stop audio + WS
*
*
*  C) TTSPlayer (audio output)
*  -------------------------------------------------
*     - Owns audio playback and queue
*     - Provides callback:
*
*          onTtsFinished { service.mic.activate() }
*
*     - Never interacts with MicStreamer directly.
*
*
*  D) ChatWebSocket (/text channel)
*  -------------------------------------------------
*     - Sends user text to backend
*     - Receives assistant replies
*     - Sends broadcast to UI
*     - Optionally includes TTS URL which triggers:
*
*          service.enqueueAudio(tts_url)
*
*
*  E) Activity (UI only)
*  -------------------------------------------------
*     - Displays chat messages
*     - Sends text to Service via Intent("SEND_TEXT")
*     - Listens to broadcasts from Service
*     - NEVER touches mic, WS, or TTS
*
*
* ---------------------------------------------------------------
* 🔄 CLEAN FLOW — STARTUP
* ---------------------------------------------------------------
*
*  1. Activity requests mic permission.
*  2. Activity starts ForegroundService.
*  3. Service:
*        - Creates MicStreamer (mic initially OFF)
*        - Creates TTSPlayer
*        - Creates ChatWebSocket (connects /text)
*  4. Backend sends greeting → includes tts_url.
*  5. Service plays greeting TTS:
*        mic.mute()
*  6. TTS finishes → callback → service:
*        mic.activate()
*
*        (mic.start() runs ONLY here on first activation)
*
*
* ---------------------------------------------------------------
* 🔄 CLEAN FLOW — USER SPEAKS
* ---------------------------------------------------------------
*
*  1. MicStreamer detects speech, segments audio.
*  2. When segment ends → sends to /stt WS.
*  3. Backend:
*        - Does STT
*        - Responds with { text, tts_url }
*  4. MicStreamer.onText() → service.sendText()
*  5. ChatWebSocket receives assistant response → broadcasts to UI
*  6. If assistant includes new TTS URL:
*        service.enqueueAudio(url)
*        mic.mute() → play TTS → mic.activate()
*
*
* ---------------------------------------------------------------
* 🎯 DESIGN BENEFITS
* ---------------------------------------------------------------
*
*  ✔ Zero timing races (mic state expressed explicitly)
*  ✔ Zero overlap between TTS and mic capture
*  ✔ Zero UI dependency on audio or networking
*  ✔ Mic creation and lifecycle fully owned by Service
*  ✔ STT segmentation unaffected by UI lifecycle
*  ✔ Predictable startup: mic ONLY starts after greeting
*  ✔ Much easier to debug (clear logs + clear states)
*
*
* ---------------------------------------------------------------
* 🛠 MIGRATION PLAN (SAFE, NO BREAKAGE)
* ---------------------------------------------------------------
*
*  1) Move MicStreamer creation into Service (keep callbacks via broadcast).
*  2) Replace allowSending / suppressAudio with a single MicState variable.
*  3) Add TTSPlayer.onFinished callback instead of polling.
*  4) Service orchestrates transitions:
*
*          micState = MUTED      // while TTS plays
*          micState = ACTIVE     // after TTS
*
*  5) Activity remains UI-only.
      */
