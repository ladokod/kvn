package ru.ladokod.kvn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticDetailsTest {
    private val profile = ServerProfile("vpn.example.com", "user77", "SEC-123-privat")

    @Test fun redactsEveryProfileSecret() {
        val message = "start: dns: password SEC-123-privat; server vpn.example.com; username user77"
        val report = SafeDiagnostics.detail(RuntimeException(message), profile)
        assertFalse(report.contains("SEC-123-privat"))
        assertFalse(report.contains("vpn.example.com"))
        assertFalse(report.contains("user77"))
        assertTrue(report.contains("dns"))
    }
    @Test fun redactsCredentialsInUrlsWithoutProfile() {
        val report = SafeDiagnostics.detail(RuntimeException("dial https://other:secret@proxy.test:443 failed"), null)
        assertFalse(report.contains("other:secret"))
    }
    @Test fun preservesUsefulUnderlyingReason() {
        val error = RuntimeException("failed start DNS: invalid dns resolver tag")
        val report = SafeDiagnostics.detail(error, profile)
        assertTrue(report.contains("invalid dns resolver tag"))
    }
    @Test fun noPasswordWhenNativeReturnsJson() {
        val error = RuntimeException("cannot start: {\"password\":\"SEC-123-privat\",\"username\":\"user77\"}")
        val report = SafeDiagnostics.detail(error, profile)
        assertFalse(report.contains("SEC-123-privat"))
        assertFalse(report.contains("user77"))
    }
}
