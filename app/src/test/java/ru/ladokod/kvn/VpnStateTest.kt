package ru.ladokod.kvn

import org.junit.Assert.*
import org.junit.Test

class VpnStateTest {
    @Test fun teardownOfOldServiceCannotHideNewConnection() {
        val old = VpnState.beginService()
        VpnState.publishFromService(old, ConnectionState.TunnelCreated)
        val current = VpnState.beginService()
        VpnState.publishFromService(current, ConnectionState.Starting)
        VpnState.publishFromService(old, ConnectionState.Off)
        assertEquals(ConnectionState.Starting, VpnState.state.value)
        VpnState.publishFromService(current, ConnectionState.TunnelCreated)
        assertEquals(ConnectionState.TunnelCreated, VpnState.state.value)
    }

    @Test fun replacingServiceInvalidatesPendingProbeEvenWhenBothAreOff() {
        VpnState.beginService()
        val checkedRevision = VpnState.revision
        VpnState.beginService()
        assertTrue(VpnState.revision > checkedRevision)
        assertEquals(ConnectionState.Off, VpnState.state.value)
    }
}
