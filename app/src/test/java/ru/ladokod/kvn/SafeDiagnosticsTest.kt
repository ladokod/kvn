package ru.ladokod.kvn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafeDiagnosticsTest {
    @Test fun classifiesPermission() {
        assertEquals("возможная ошибка разрешений Android", SafeDiagnostics.classify(IllegalStateException("VPN permission denied")))
    }

    @Test fun redactsUnknownSecrets() {
        val response = SafeDiagnostics.classify(IllegalStateException("secret-password-1234"))
        assertFalse(response.contains("secret-password"))
    }

    @Test fun detectsNaive() {
        assertEquals("возможная ошибка компонента NaïveProxy", SafeDiagnostics.classify(IllegalStateException("cronet failure")))
    }
}
