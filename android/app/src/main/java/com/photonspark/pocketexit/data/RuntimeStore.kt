package com.photonspark.pocketexit.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object RuntimeStore {
    private val mutable = MutableStateFlow(AgentRuntime())
    val state: StateFlow<AgentRuntime> = mutable.asStateFlow()

    fun update(transform: (AgentRuntime) -> AgentRuntime) {
        mutable.update(transform)
    }

    fun reset(message: String = "Stopped") {
        mutable.value = AgentRuntime(statusMessage = message)
    }
}
