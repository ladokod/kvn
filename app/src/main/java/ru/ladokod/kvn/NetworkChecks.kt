package ru.ladokod.kvn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

/**
 * Uses Android's actual physical network for bootstrap and the explicit VPN
 * Network for test requests. A "VPN connected" icon is NOT a connectivity test.
 * No credentials or URLs containing credentials are transmitted to telemetry.
 */
object NetworkChecks {
    private fun physicalNetwork(cm: ConnectivityManager): Network? {
        return cm.allNetworks
            .filter {
                val caps = cm.getNetworkCapabilities(it)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .sortedByDescending {
                cm.getNetworkCapabilities(it)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
            .firstOrNull()
    }

    fun serverIPs(context: Context, host: String): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val physical = physicalNetwork(cm)
            ?: throw IllegalStateException("Физическая сеть Android недоступна")
        val addresses = physical.getAllByName(host).toList()
        return addresses.filterIsInstance<Inet4Address>().take(4)
            .plus(addresses.filter { it !is Inet4Address }.take(4))
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct().take(8)
            .also { check(it.isNotEmpty()) { "Android DNS не вернул адрес VDS" } }
    }

    private fun vpnNetwork(cm: ConnectivityManager): Network? {
        repeat(15) {
            val net = cm.allNetworks.firstOrNull {
                val caps = cm.getNetworkCapabilities(it)
                val link = cm.getLinkProperties(it)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    (android.os.Build.VERSION.SDK_INT < 29 || caps.ownerUid == android.os.Process.myUid()) &&
                    link?.linkAddresses?.any { address ->
                        address.address.hostAddress?.substringBefore('%') == TunnelConfig.IPV4
                    } == true
            }
            if (net != null) return net
            SystemClock.sleep(200)
        }
        return null
    }

    private fun https(net: Network, address: String): String? {
        val conn = net.openConnection(URL(address)) as HttpURLConnection
        return try {
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            val status = conn.responseCode
            if (status != 200) return "HTTP$status"
            var total = 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 262144) return "ResponseTooLargeForProbe"
                }
            }
            val expected = conn.contentLengthLong
            if (total > 0 && (expected < 0 || total == expected)) null else "IncompleteResponse"
        } catch (e: Exception) {
            e.javaClass.simpleName
        } finally {
            conn.disconnect()
        }
    }

    /** null means that real HTTPS with DNS is reachable through the Android VPN. */
    fun testHttpsInsideVpn(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = vpnNetwork(cm)
            ?: return "NET00 · Android не предоставил виртуальную сеть для проверки"
        val website = runCatching { https(net, "https://example.com/") }.getOrElse { it.javaClass.simpleName }
        if (website == null) return null
        val numeric = runCatching { https(net, "https://1.1.1.1/") }.getOrElse { it.javaClass.simpleName }
        return if (numeric == null) {
            "NET11 · HTTPS по IP работает, но доменные имена недоступны ($website). Проверь DNS внутри VPN"
        } else {
            "Проверка из процесса VPN не подтвердила доступ; это не доказывает отсутствие интернета у других приложений. NET12 · HTTPS через VPN недоступен (домен: $website; IP: $numeric). Проверь сервер или маршруты"
        }
    }
}
