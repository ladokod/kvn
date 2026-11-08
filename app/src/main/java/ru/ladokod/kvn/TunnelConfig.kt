package ru.ladokod.kvn

import org.json.JSONArray
import org.json.JSONObject

/** DNS for the VPN server is resolved using Android's physical network before TUN startup.
 *  All normal app DNS and TCP traffic remains inside NaïveProxy.
 */
object TunnelConfig {
    const val MTU = 9000
    const val IPV4 = "172.19.0.1"
    const val IPV6 = "fdfe:dcba:9876::1"
    fun build(profile: ServerProfile, bootstrapIPs: List<String>): String {
        val p = ProfileValidator.validate(profile.host, profile.username, profile.password)
        require(bootstrapIPs.isNotEmpty()) { "Bootstrap DNS returned no IP addresses" }
        fun obj(vararg pair: Pair<String, Any>) = JSONObject().apply { pair.forEach { put(it.first, it.second) } }
        // No direct UDP/53 queries to 1.1.1.1. DNS is provided by the underlying
        // network before the TUN starts, and pinned to this session using "hosts".
        val hosts = obj(
            "type" to "hosts", "tag" to "bootstrap",
            "predefined" to obj(p.host to JSONArray(bootstrapIPs.distinct()))
        )
        val remote = obj(
            "type" to "https", "tag" to "remote", "server" to "1.1.1.1",
            "path" to "/dns-query", "detour" to "proxy"
        )
        val naive = obj(
            "type" to "naive", "tag" to "proxy", "server" to p.host,
            "server_port" to 443, "username" to p.username, "password" to p.password,
            "quic" to false,
            "domain_resolver" to obj("server" to "bootstrap"),
            "tls" to obj("enabled" to true, "server_name" to p.host)
        )
        val tun = obj(
            "type" to "tun", "tag" to "tun-in",
            "address" to JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"),
            "mtu" to MTU, "dns_mode" to "hijack",
            "auto_route" to true, "strict_route" to false,
            "stack" to "go"
        )
        return obj(
            "log" to obj("disabled" to true),
            "experimental" to obj("cache_file" to obj(
                "enabled" to true, "store_fakeip" to true, "path" to "kvn-cache.db"
            )),
            "dns" to obj(
                "servers" to JSONArray().put(hosts).put(remote).put(obj(
                    "type" to "fakeip", "tag" to "fake",
                    "inet4_range" to "198.51.100.0/24", "inet6_range" to "2001:2::/48"
                )),
                // Husi's built-in FakeDNS rule preserves the original hostname
                // for Naive instead of forwarding a locally selected CDN address.
                "rules" to JSONArray().put(obj(
                    "inbound" to JSONArray().put("tun-in"),
                    "query_type" to JSONArray().put("A").put("AAAA"),
                    "server" to "fake", "disable_cache" to true
                )),
                "final" to "remote", "strategy" to "prefer_ipv4"
            ),
            "inbounds" to JSONArray().put(tun),
            "outbounds" to JSONArray().put(naive),
            "route" to obj(
                "auto_detect_interface" to true,
                "default_domain_resolver" to "remote",
                "final" to "proxy",
                "rules" to JSONArray()
                    .put(obj("network" to "udp", "port" to 53, "action" to "hijack-dns"))
                    .put(obj("network" to "tcp", "port" to 53, "action" to "hijack-dns"))
                    // Caddy Naive supports TCP. Reject remaining UDP after DNS; no UoT.
                    .put(obj("network" to "udp", "action" to "reject", "method" to "default", "no_drop" to true))
            )
        ).toString()
    }
}
