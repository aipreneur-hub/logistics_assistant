// MessageBus.kt
package com.datanomous.assistant.shared

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Central bus for delivering bot messages from
 * ChatWebSocket → UI (Compose screens).
 *
 * - ChatWebSocket calls MessageBus.emit(...)
 * - UI subscribes to botMsgs SharedFlow
 */
object AssistantBus {
    // Buffer a few messages if UI is not active momentarily
    private val _botMsgs = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val botMsgs = _botMsgs.asSharedFlow()

    fun emit(msg: String) {
        _botMsgs.tryEmit(msg)
    }
}
