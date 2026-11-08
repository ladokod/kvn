package ru.ladokod.kvn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.*
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import fr.husi.libcore.Libcore
import fr.husi.libcore.PlatformInterface
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real Husi JNI + production Android Builder; traffic originates in another app UID. */
@RunWith(AndroidJUnit4::class)
class HusiTrafficTest {
    @get:Rule val rule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun waitVpn(cm: ConnectivityManager): Network {
        val deadline = SystemClock.elapsedRealtime() + 10000
        do {
            val net = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.let { caps ->
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.ownerUid == Process.myUid()
                } == true && cm.getLinkProperties(network)?.linkAddresses?.any {
                    it.address.hostAddress == TunnelConfig.IPV4
                } == true
            }
            if (net != null) return net
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Owned TUN was not published")
    }

    private fun probe(fixture: JSONObject, blocked: Boolean): Bundle {
        val done = CountDownLatch(1)
        val result = AtomicReference<Bundle>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(code: Int, data: Bundle) { result.set(data); done.countDown() }
        }
        // Parcel as the framework ResultReceiver: the other APK cannot load an
        // anonymous subclass belonging to the instrumentation APK.
        val parcel = Parcel.obtain()
        val remote = try {
            receiver.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        context.startActivity(Intent().setClassName("ru.ladokod.kvn.probe", "ru.ladokod.kvn.probe.FixtureProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("port", fixture.getInt("content_port"))
            .putExtra("ca", fixture.getString("ca")).putExtra("blocked", blocked).putExtra("receiver", remote))
        assertTrue("Separate UID probe did not complete", done.await(90, TimeUnit.SECONDS))
        return result.get().also {
            assertNotEquals("Traffic must use another UID", Process.myUid(), it.getInt("uid"))
            assertTrue("Separate UID probe: ${it.getString("report")}", it.getBoolean("ok"))
        }
    }

    private fun assertNoCredentialsInLogs(fixture: JSONObject) {
        val user = fixture.getString("username")
        val pass = fixture.getString("password")
        val encoded = listOf(pass, "invalid-$pass").map {
            android.util.Base64.encodeToString("$user:$it".toByteArray(), android.util.Base64.NO_WRAP)
        }
        val forbidden = listOf(user, pass) + encoded
        val native = context.cacheDir.resolve("libbox/stderr.log")
        val texts = mutableListOf<String>()
        if (native.exists()) {
            assertTrue("Native log unexpectedly large", native.length() <= 1024 * 1024)
            texts += native.readText()
        }
        texts += instrumentation.uiAutomation.executeShellCommand("logcat -d -v brief -t 2000").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        for (text in texts) for (secret in forbidden) {
            assertFalse("Proxy credentials leaked to runtime logs", text.contains(secret))
        }
    }

    @Test(timeout = 240000) fun realTunTrafficLargeParallelFamiliesDnsUdpAndReconnect() {
        val fixture = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/kvn-fixture.json").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> JSONObject(reader.readText()) }
        }
        assertNull("Disposable emulator VPN consent must be granted by test script", VpnService.prepare(context))
        val service = (rule.bindService(Intent(context, AuditVpnService::class.java)
            .setAction("ru.ladokod.kvn.AUDIT")) as AuditVpnService.LocalBinder).service
        val bridge = KvnVpnService::class.java.getDeclaredMethod("platformBridge").apply { isAccessible = true }
            .invoke(service) as PlatformInterface
        val close = KvnVpnService::class.java.getDeclaredMethod("closeTunnel").apply { isAccessible = true }
        val mtuField = KvnVpnService::class.java.getDeclaredField("tunnelMtu").apply { isAccessible = true }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        for ((mtu, badCredentials) in listOf(9000 to false, 1500 to false, 1280 to false, 9000 to true)) {
            mtuField.setInt(service, mtu)
            val protectCalls = AtomicInteger()
            val platform = object : PlatformInterface by bridge {
                override fun autoDetectInterfaceControl(fd: Int): Boolean = bridge.autoDetectInterfaceControl(fd).also {
                    if (it) protectCalls.incrementAndGet()
                }
            }
            val config = JSONObject(TunnelConfig.build(
                ServerProfile("proxy.fixture", fixture.getString("username"), fixture.getString("password")),
                listOf("10.0.2.2")
            ))
            config.getJSONArray("inbounds").getJSONObject(0).put("mtu", mtu)
            config.getJSONArray("outbounds").getJSONObject(0).apply {
                put("server_port", fixture.getInt("proxy_port"))
                if (badCredentials) put("password", "invalid-" + fixture.getString("password"))
                getJSONObject("tls").put("certificate", fixture.getString("ca"))
            }
            config.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).apply {
                put("server", "198.18.0.53"); put("server_port", fixture.getInt("doh_port"))
                put("tls", JSONObject().put("enabled", true).put("server_name", "doh.fixture")
                    .put("certificate", fixture.getString("ca")))
            }
            val core = Libcore.newService("KVN-fixture", platform)
            var net: Network? = null
            try {
                Libcore.checkKvnConfig(config.toString())
                core.start(); core.startConfig(config.toString())
                net = waitVpn(cm)
                assertEquals("Production Builder MTU", mtu, cm.getLinkProperties(net)?.mtu)
                if (badCredentials) {
                    probe(fixture, true)
                    assertTrue("Invalid auth must still protect sockets", protectCalls.get() > 0)
                    assertNotNull("Invalid auth must keep the blocking TUN", cm.getLinkProperties(net))
                    assertNoCredentialsInLogs(fixture)
                    println("HUSI_TRAFFIC bad_auth_fail_closed=true")
                    continue
                }
                val result = probe(fixture, false)
                assertEquals(fixture.getString("large_sha256"), result.getString("large_sha256"))
                assertEquals(fixture.getString("large_sha256"), result.getString("v6_sha256"))
                assertEquals(fixture.getString("png_sha256"), result.getString("png_sha256"))
                assertTrue("Real outbound socket protection must run", protectCalls.get() > 0)
                val iface = cm.getLinkProperties(net)?.interfaceName
                core.stopService()
                assertEquals("Keep TUN on core stop", iface, cm.getLinkProperties(net)?.interfaceName)
                probe(fixture, true)
                assertNoCredentialsInLogs(fixture)
                println("HUSI_TRAFFIC mtu=$mtu separate_uid=true sha256_v4_v6=true parallel=16 dns_doh=true udp443=reject fail_closed=true")
            } finally {
                runCatching { core.stopService() }; try { core.close() } finally { close.invoke(service) }
                val old = net
                if (old != null) {
                    val deadline = SystemClock.elapsedRealtime() + 10000
                    while (cm.getNetworkCapabilities(old) != null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                    assertNull("TUN must disappear before reconnect", cm.getNetworkCapabilities(old))
                }
            }
        }
    }
}
