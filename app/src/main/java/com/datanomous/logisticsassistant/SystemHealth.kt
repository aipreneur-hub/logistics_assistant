package com.datanomous.logisticsassistant.monitor

import kotlinx.coroutines.flow.MutableStateFlow

object SystemHealth {
    val state = MutableStateFlow(
        HealthMonitor.HealthState(
            network = HealthMonitor.State.UNKNOWN,
            chat = HealthMonitor.State.UNKNOWN,
            mic = HealthMonitor.State.UNKNOWN
        )
    )
}