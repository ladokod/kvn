package ru.ladokod.kvn.probe

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.OsConstants
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Capture once; never reselect a network or retry on physical transport. */
class VpnTransport(private val cm: ConnectivityManager, private val network: Network) : ProbeTransport {
    override fun requireVpn() {
        check(cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
    }
    override fun resolve(host: String) = network.getAllByName(host)
    override fun open(url: URL) = (network.openConnection(url) as HttpsURLConnection)
    override fun socket() = network.socketFactory.createSocket()
    fun datagramSocket() = java.net.DatagramSocket().apply { requireVpn(); network.bindSocket(this) }
    fun facts(): String {
        val link = cm.getLinkProperties(network)
        return "PROBE.network.http_proxy=${link?.httpProxy != null} mtu=${link?.mtu ?: 0}"
    }

    companion object {
        fun capture(cm: ConnectivityManager): VpnTransport {
            val deadline = android.os.SystemClock.elapsedRealtime() + 3000
            do {
                val network = cm.activeNetwork
                if (network != null && cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                    return VpnTransport(cm, network).apply { requireVpn() }
                android.os.SystemClock.sleep(50)
            } while (android.os.SystemClock.elapsedRealtime() < deadline)
            throw NoActiveVpnForThisApp()
        }
        fun errno(error: Throwable): String? = (error as? ErrnoException)?.let {
            when (it.errno) {
                OsConstants.EPERM -> "EPERM"
                OsConstants.EACCES -> "EACCES"
                OsConstants.ENETUNREACH -> "ENETUNREACH"
                OsConstants.EHOSTUNREACH -> "EHOSTUNREACH"
                OsConstants.ETIMEDOUT -> "ETIMEDOUT"
                OsConstants.ECONNRESET -> "ECONNRESET"
                OsConstants.ECONNREFUSED -> "ECONNREFUSED"
                else -> "OTHER_ERRNO"
            }
        }
    }
}

class NoActiveVpnForThisApp : IllegalStateException()
