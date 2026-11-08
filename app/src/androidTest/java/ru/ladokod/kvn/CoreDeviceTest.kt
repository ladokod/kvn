package ru.ladokod.kvn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.husi.libcore.Libcore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 30000) fun keystorePersistsCiphertextAndDecryptsAfterRecreation() {
        val prefs = context.getSharedPreferences("kvn-secure-v1", 0)
        // This test runs on a disposable emulator, never on a configured phone.
        val profile = ServerProfile("vpn.example.com", "test-user", "sentinel-password-do-not-log")
        try {
            SecretStore(context).save(profile)
            val stored = prefs.getString("encrypted-profile", "")!!
            assertFalse(stored.contains(profile.password))
            assertFalse(stored.contains(profile.username))
            assertEquals(profile, SecretStore(context).load())
        } finally { prefs.edit().clear().commit() }
    }

    @Test(timeout = 30000) fun realNativeDistroAcceptsKvnConfig() {
        Libcore.checkKvnConfig(TunnelConfig.build(
            ServerProfile("vpn.example.com", "test-user", "sentinel-password-do-not-log"),
            listOf("192.0.2.1")
        ))
    }

    @Test(timeout = 30000) fun nativeNaiveRejectsInsecureTls() {
        val config = org.json.JSONObject(TunnelConfig.build(
            ServerProfile("vpn.example.com", "test-user", "sentinel-password-do-not-log"),
            listOf("192.0.2.1")
        ))
        config.getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("tls").put("insecure", true)
        try {
            Libcore.checkKvnConfig(config.toString())
            fail("Naive accepted TLS verification bypass")
        } catch (_: Exception) { }
    }

    // Tests the real JNI host lifecycle, NOT Android VPN or Internet access.
    // No inbound listener is opened and no traffic is generated.
    @Test(timeout = 30000) fun nativeHostStartsStopsAndReopens() {
        repeat(3) {
            val host = Libcore.newService("KVN-instrumentation", null)
            try {
                host.start()
                assertFalse(host.hasInstance())
                host.startConfig("""{"log":{"disabled":true},"outbounds":[{"type":"direct","tag":"fixture-only"}]}""")
                assertTrue(host.hasInstance())
                host.stopService()
                assertFalse(host.hasInstance())
            } finally { host.close() }
        }
    }

    @Test(timeout = 30000) fun realNativeDistroRejectsMalformedConfig() {
        try {
            Libcore.checkKvnConfig("{invalid}")
            fail("native core accepted invalid JSON")
        } catch (_: Exception) { }
    }
}
