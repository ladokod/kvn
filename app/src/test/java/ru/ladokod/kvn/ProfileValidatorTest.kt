package ru.ladokod.kvn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileValidatorTest {
    @Test fun validDomain() {
        val p = ProfileValidator.validate("VPN.Example.COM.", "  bob  ", "abc:123")
        assertEquals("vpn.example.com", p.host)
        assertEquals("bob", p.username)
        assertEquals("abc:123", p.password)
    }
    @Test fun rejectUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileValidator.validate("https://vpn.example.com", "bob", "123")
        }
    }
    @Test fun rejectPath() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileValidator.validate("vpn.example.com/path", "bob", "123")
        }
    }
    @Test fun rejectColonInUsername() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileValidator.validate("vpn.example.com", "a:b", "123")
        }
    }
    @Test fun rejectEmptyPassword() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileValidator.validate("vpn.example.com", "bob", "")
        }
    }
}
