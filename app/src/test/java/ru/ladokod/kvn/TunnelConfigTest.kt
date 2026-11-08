package ru.ladokod.kvn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConfigTest {
    private val config = JSONObject(TunnelConfig.build(
        ServerProfile("vpn.example.com", "test-user", "test-password"),
        listOf("203.0.113.9")
    ))

    @Test fun bootstrapUsesPreResolvedIPAndNeverDirectUDP53() {
        val bootstrap = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("bootstrap", bootstrap.getString("tag"))
        assertEquals("hosts", bootstrap.getString("type"))
        assertEquals("203.0.113.9", bootstrap.getJSONObject("predefined")
            .getJSONArray("vpn.example.com").getString(0))
        assertFalse(bootstrap.has("detour"))
        assertFalse(bootstrap.has("server"))
    }

    @Test fun remoteDnsGoesOverNaive() {
        val dns = config.getJSONObject("dns")
        val remote = dns.getJSONArray("servers").getJSONObject(1)
        assertEquals("proxy", remote.getString("detour"))
        assertEquals("remote", dns.getString("final"))
        assertEquals("https", remote.getString("type"))
        assertEquals("1.1.1.1", remote.getString("server"))
    }

    @Test fun naivePreservesDomainForHttpsCertificate() {
        val proxy = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("naive", proxy.getString("type"))
        assertEquals("bootstrap", proxy.getJSONObject("domain_resolver").getString("server"))
        assertEquals("vpn.example.com", proxy.getString("server"))
        assertEquals("vpn.example.com", proxy.getJSONObject("tls").getString("server_name"))
        assertEquals(443, proxy.getInt("server_port"))
    }

    @Test fun allNormalTcpRoutesToProxyAndUdpRejectedWithoutLeak() {
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertEquals(1, config.getJSONArray("outbounds").length())
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("hijack-dns", rules.getJSONObject(0).getString("action"))
        assertEquals("udp", rules.getJSONObject(0).getString("network"))
        assertEquals(53, rules.getJSONObject(0).getInt("port"))
        assertEquals("hijack-dns", rules.getJSONObject(1).getString("action"))
        assertEquals("tcp", rules.getJSONObject(1).getString("network"))
        assertEquals(53, rules.getJSONObject(1).getInt("port"))
        assertEquals("reject", rules.getJSONObject(2).getString("action"))
        assertEquals("udp", rules.getJSONObject(2).getString("network"))
        assertFalse(rules.getJSONObject(2).has("port"))
        assertEquals(9000, config.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
        assertEquals("go", config.getJSONArray("inbounds").getJSONObject(0).getString("stack"))
        assertTrue(config.getJSONArray("inbounds").getJSONObject(0).getBoolean("auto_route"))
        assertEquals("hijack", config.getJSONArray("inbounds").getJSONObject(0).getString("dns_mode"))
    }

    @Test fun fakeDnsRestoresHostnamesLikeHusiAndPersistsMappings() {
        val dns = config.getJSONObject("dns")
        assertEquals("fakeip", dns.getJSONArray("servers").getJSONObject(2).getString("type"))
        val rule = dns.getJSONArray("rules").getJSONObject(0)
        assertEquals("tun-in", rule.getJSONArray("inbound").getString(0))
        assertEquals("fake", rule.getString("server"))
        assertTrue(rule.getBoolean("disable_cache"))
        assertEquals(2, rule.getJSONArray("query_type").length())
        assertTrue(config.getJSONObject("route").getBoolean("auto_detect_interface"))
        assertTrue(config.getJSONObject("experimental").getJSONObject("cache_file").getBoolean("store_fakeip"))
        assertTrue(config.getJSONObject("log").getBoolean("disabled"))
        assertFalse(config.getJSONArray("outbounds").getJSONObject(0).has("udp_over_tcp"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun bootstrapCannotBeEmpty() {
        TunnelConfig.build(ServerProfile("vpn.example.com", "test", "pass"), emptyList())
    }
}
