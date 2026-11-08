package ru.ladokod.kvn.probe

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ProbeRunnerTest {
    private class Response(private val body: ByteArray, private val length: Long, private val status: Int = 200) : HttpsURLConnection(URL("https://fixture.invalid/")) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream() = body.inputStream()
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private class Transport(val response: Response, private val allowedChecks: Int = 100) : ProbeTransport {
        var guards = 0; var resolutions = 0; var opens = 0
        override fun requireVpn() { check(++guards <= allowedChecks) }
        override fun resolve(host: String): Array<InetAddress> { resolutions++; return arrayOf(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4))) }
        override fun open(url: URL): HttpsURLConnection { opens++; return response }
    }
    @Test fun refusesBeforeDnsOrSocketWithoutVpn() {
        val t = Transport(Response(byteArrayOf(1), 1), 0)
        val r = ProbeRunner(t).check(Check("test", "https://fixture.invalid/"))
        assertFalse(r.ok); assertEquals(0, t.resolutions); assertEquals(0, t.opens)
    }
    @Test fun refusesIfVpnDisappearsAfterDnsWithoutFallback() {
        val t = Transport(Response(byteArrayOf(1), 1), 1)
        assertFalse(ProbeRunner(t).check(Check("test", "https://fixture.invalid/")).ok)
        assertEquals(1, t.resolutions); assertEquals(0, t.opens)
    }
    @Test fun detectsTruncatedBodyAndAlwaysCloses() {
        val response = Response(byteArrayOf(1, 2), 12)
        val result = ProbeRunner(Transport(response)).check(Check("test", "https://fixture.invalid/"))
        assertFalse(result.ok); assertTrue(result.report.contains("EOFException")); assertEquals(2, result.bytes); assertTrue(response.closed)
    }
    @Test fun doesNotVerifyIfVpnDisappearsDuringTransfer() {
        val t = Transport(Response(byteArrayOf(1, 2), 2), 2)
        val r = ProbeRunner(t).check(Check("test", "https://fixture.invalid/"))
        assertFalse(r.ok); assertEquals(2, r.bytes); assertTrue(r.report.contains("phase=vpn_after"))
        assertEquals(1, t.opens)
    }
    @Test fun boundsUnknownLengthAndDoesNotClaimComplete() {
        val result = ProbeRunner(Transport(Response(ByteArray(500), -1))).check(Check("test", "https://fixture.invalid/", 100))
        assertTrue(result.ok); assertEquals(100, result.bytes); assertTrue(result.report.contains("bounded_ok"))
    }
    @Test fun rejectsRedirectWithoutFollowingIt() {
        val response = Response("secret-body".toByteArray(), 11, 302)
        val t = Transport(response)
        val result = ProbeRunner(t).check(Check("test", "https://fixture.invalid/"))
        assertFalse(result.ok); assertFalse(response.instanceFollowRedirects); assertEquals(1, t.opens)
        assertTrue(result.report.contains("RedirectNotFollowed"))
        assertFalse(result.report.contains("secret")); assertFalse(result.report.contains("fixture.invalid"))
    }
    @Test fun reportsDnsFailureWithoutLeakingMessages() {
        val t = object : ProbeTransport {
            override fun requireVpn() = Unit
            override fun resolve(host: String): Array<InetAddress> = throw IllegalStateException("password=private https://user:secret@host/")
            override fun open(url: URL): HttpsURLConnection = error("must not open")
        }
        val result = ProbeRunner(t).check(Check("test", "https://fixture.invalid/"))
        assertFalse(result.ok); assertTrue(result.report.contains("phase=dns")); assertFalse(result.report.contains("secret"))
    }
}
