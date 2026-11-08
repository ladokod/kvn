package ru.ladokod.kvn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface ConnectionState {
    data object Off : ConnectionState
    data object Starting : ConnectionState
    data object Checking : ConnectionState
    data object TunnelCreated : ConnectionState
    data object On : ConnectionState
    data class Failed(val message: String, val tunnelActive: Boolean = false) : ConnectionState
}

object VpnState {
    @Volatile var revision: Long = 0
        private set
    private var serviceEpoch = 0L
    private val mutable = MutableStateFlow<ConnectionState>(ConnectionState.Off)
    val state: StateFlow<ConnectionState> get() = mutable
    @Synchronized fun beginService(): Long {
        serviceEpoch++
        publish(ConnectionState.Off)
        return serviceEpoch
    }
    @Synchronized fun publishFromService(epoch: Long, value: ConnectionState) {
        if (epoch == serviceEpoch) publish(value)
    }
    @Synchronized fun publish(value: ConnectionState) { revision++; mutable.value = value }
}
