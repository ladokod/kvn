package ru.ladokod.kvn.probe

import java.net.InetAddress
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class FamilyTlsProbeTest {
    @Test fun recognizesReservedRangesWithoutLeakingAddresses() {
        for (literal in listOf("198.18.0.1", "198.19.255.254", "198.51.100.42", "2001:2::42", "2001:db8::42"))
            assertEquals("reserved_test_range", FamilyTlsProbe.addressKind(InetAddress.getByName(literal)))
        for (literal in listOf("1.1.1.1", "198.20.0.1", "2001:4860:4860::8888"))
            assertEquals("other", FamilyTlsProbe.addressKind(InetAddress.getByName(literal)))
        for (literal in listOf("127.0.0.1", "10.0.0.1", "fd00::42", "fe80::42"))
            assertEquals("local", FamilyTlsProbe.addressKind(InetAddress.getByName(literal)))
    }
    @Test fun neverSubstitutesTheOtherFamily() {
        val four = InetAddress.getByName("198.51.100.42")
        val six = InetAddress.getByName("2001:2::42")
        assertNull(FamilyTlsProbe.select(arrayOf(four), 6))
        assertEquals(six, FamilyTlsProbe.select(arrayOf(four, six), 6))
        assertEquals(four, FamilyTlsProbe.select(arrayOf(six, four), 4))
    }
    @Test fun stopsBeforeSocketWhenTheSelectedFamilyIsAbsent() {
        val transport = object : ProbeTransport {
            override fun requireVpn() = Unit
            override fun resolve(host: String) = arrayOf(InetAddress.getByName("198.51.100.42"))
            override fun open(url: URL): javax.net.ssl.HttpsURLConnection = error("must not open")
            override fun socket(): java.net.Socket = error("must not open")
        }
        val result = FamilyTlsProbe(transport).check("test", "private.example", 6)
        assertFalse(result.ok); assertTrue(result.report.contains("no_family")); assertFalse(result.report.contains("private.example"))
    }
}
