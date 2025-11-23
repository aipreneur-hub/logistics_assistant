package com.datanomous.logisticsassistant.audio

import kotlinx.coroutines.flow.MutableStateFlow

object MicUiState {
    val level = MutableStateFlow(0)   // 0–100 live mic amplitude
}