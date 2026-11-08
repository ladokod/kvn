package ru.ladokod.kvn.probe

import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Controlled family selection; actual TCP address is pinned, TLS still verifies the original name. */
class FamilyTlsProbe(
    private val transport: ProbeTransport,
    private val errno: (Throwable) -> String? = { null },
    private val tls: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
) {
    data class Result(val ok: Boolean, val report: String)

    fun check(label: String, host: String, family: Int, port: Int = 443): Result {
        require(label.matches(Regex("[a-z0-9_]+")))
        require(family == 4 || family == 6)
        val started = System.nanoTime()
        var phase = "vpn"
        var kind = "none"
        var ok = false
        var outcome: String
        try {
            transport.requireVpn()
            phase = "dns"
            val address = select(transport.resolve(host), family)
            if (address == null) return Result(false, "PROBE.$label phase=dns result=no_family family=$family")
            kind = addressKind(address)
            transport.requireVpn()
            phase = "tcp_connect"
            transport.socket().use { raw ->
                raw.soTimeout = 8000
                raw.connect(InetSocketAddress(address, port), 8000)
                transport.requireVpn()
                phase = "tls_handshake"
                (tls.createSocket(raw, host, port, true) as SSLSocket).use { secure ->
                    secure.soTimeout = 8000
                    secure.sslParameters = secure.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        if (host.matches(Regex("[a-z][a-z0-9.-]*"))) serverNames = listOf(SNIHostName(host))
                    }
                    secure.startHandshake()
                }
            }
            phase = "vpn_after"
            transport.requireVpn()
            ok = true
            outcome = "tls_verified"
        } catch (error: Exception) {
            outcome = generateSequence<Throwable>(error) { it.cause }.take(6).map {
                it.javaClass.simpleName + (errno(it)?.let { code -> ":$code" } ?: "")
            }.distinct().joinToString("<-")
        }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return Result(ok, "PROBE.$label phase=$phase result=$outcome family=$family dns_kind=$kind ms=$ms")
    }

    companion object {
        // No alternate family or alternate address retry: this is a controlled experiment.
        fun select(answers: Array<InetAddress>, family: Int): InetAddress? = answers.firstOrNull {
            it.address.size == if (family == 4) 4 else 16
        }

        /** Range category only, never an actual address. Reserved ranges do not alone prove FakeDNS. */
        fun addressKind(address: InetAddress): String {
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4 && (
                    b[0] == 198 && b[1] in 18..19 ||
                    b.take(3) == listOf(198, 51, 100) || b.take(3) == listOf(192, 0, 2) ||
                    b.take(3) == listOf(203, 0, 113))) return "reserved_test_range"
            if (b.size == 16 && b.take(2) == listOf(0x20, 0x01) && (
                    b.slice(2..3) == listOf(0x0d, 0xb8) || b.slice(2..5) == listOf(0, 2, 0, 0)))
                return "reserved_test_range"
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || b.size == 16 && (b[0] and 254) == 252) return "local"
            return "other"
        }
    }
}
