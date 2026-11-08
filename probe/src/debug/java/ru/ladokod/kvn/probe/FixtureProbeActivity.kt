package ru.ladokod.kvn.probe

import android.app.Activity
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.PortUnreachableException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Debug-only, fixed synthetic endpoints, public fixture CA; no proxy credentials. */
class FixtureProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") val receiver = intent.getParcelableExtra<ResultReceiver>("receiver") ?: run { finish(); return }
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val result = Bundle().apply { putInt("uid", Process.myUid()) }
            try {
                val port = intent.getIntExtra("port", 0).also { require(it in 1..65535) }
                val caText = intent.getStringExtra("ca")!!.also { require(it.length < 8192) }
                val ca = CertificateFactory.getInstance("X.509").generateCertificate(caText.byteInputStream())
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", ca) }
                val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                val trust = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }.socketFactory
                val transport = VpnTransport.capture(getSystemService(ConnectivityManager::class.java))
                val runner = ProbeRunner(object : ProbeTransport by transport {
                    override fun open(url: java.net.URL) = transport.open(url).apply { sslSocketFactory = trust }
                }, VpnTransport::errno)
                if (intent.getBooleanExtra("blocked", false)) {
                    transport.requireVpn() // Losing the VPN is not a passing fail-closed test.
                    val check = runner.check(Check("blocked", "https://198.18.0.77:$port/site.css"))
                    transport.requireVpn()
                    result.putBoolean("ok", !check.ok && check.bytes == 0L)
                    result.putString("report", check.report)
                } else {
                    val checks = listOf(
                        Check("html", "https://html.fixture:$port/page.html"),
                        Check("css", "https://assets.fixture:$port/site.css"),
                        Check("image", "https://assets.fixture:$port/pixel.png"),
                        Check("large", "https://v4.fixture:$port/large", 2097152, 2097152),
                        Check("v6", "https://[2001:db8::77]:$port/large", 2097152, 2097152),
                        Check("wrong_host", "https://198.18.0.53:$port/site.css"),
                        Check("truncated", "https://assets.fixture:$port/truncated", 262144, 65536)
                    )
                    val measured = checks.map { runner.check(it) }
                    val familyProbe = FamilyTlsProbe(transport, VpnTransport::errno, trust)
                    val families = listOf(4, 6).map { familyProbe.check("fixture_tls$it", "html.fixture", it, port) }
                    val mismatch = familyProbe.check("fixture_wrong_host", "198.18.0.53", 4, port)
                    val pool = Executors.newFixedThreadPool(16)
                    val parallel = try {
                        (1..16).map { n -> pool.submit<Measurement> {
                            runner.check(Check("parallel$n", "https://parallel.fixture:$port/parallel", 262144, 262144))
                        } }.map { it.get(30, TimeUnit.SECONDS) }
                    } finally { pool.shutdownNow() }
                    val reject = listOf("198.18.0.77", "2001:db8::77").all { address ->
                        transport.datagramSocket().use { udp ->
                            udp.soTimeout = 2000; udp.connect(InetAddress.getByName(address), 443)
                            try {
                                udp.send(DatagramPacket(byteArrayOf(1), 1)); udp.receive(DatagramPacket(ByteArray(64), 64)); false
                            } catch (_: PortUnreachableException) { true }
                        }
                    }
                    val txtDns = transport.datagramSocket().use { udp ->
                        udp.soTimeout = 6000
                        val query = byteArrayOf(0x12,0x34,1,0,0,1,0,0,0,0,0,0,4,104,116,109,108,7,102,105,120,116,117,114,101,0,0,16,0,1)
                        udp.send(DatagramPacket(query, query.size, InetAddress.getByName("172.19.0.2"), 53))
                        val packet = DatagramPacket(ByteArray(2048), 2048); udp.receive(packet)
                        packet.length >= 12 && packet.data[0] == 0x12.toByte() && packet.data[1] == 0x34.toByte() && (packet.data[2].toInt() and 0x80) != 0
                    }
                    val afterUdp = runner.check(Check("after_udp", "https://v4.fixture:$port/site.css"))
                    result.putBoolean("ok", measured.take(5).all { it.ok } && !measured[5].ok &&
                        measured[5].report.contains("SSLPeerUnverifiedException") && !measured[6].ok &&
                        families.all { it.ok } && !mismatch.ok && mismatch.report.contains("CertificateException") &&
                        parallel.all { it.ok && it.bytes == 262144L } && reject && txtDns && afterUdp.ok)
                    result.putString("report", (measured.map { it.report } + families.map { it.report } + mismatch.report +
                        "parallel=${parallel.count { it.ok }} udp_reject=$reject dns_txt=$txtDns after_udp=${afterUdp.ok}").joinToString("\n"))
                    result.putString("large_sha256", measured[3].sha256)
                    result.putString("v6_sha256", measured[4].sha256)
                    result.putString("png_sha256", measured[2].sha256)
                }
            } catch (error: Exception) { result.putString("report", "PROBE.fixture.${error.javaClass.simpleName}") }
            finally { receiver.send(0, result); worker.shutdown(); runOnUiThread { finish() } }
        }
    }
}
