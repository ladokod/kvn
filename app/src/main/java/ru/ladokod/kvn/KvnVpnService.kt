package ru.ladokod.kvn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import fr.husi.libcore.*
import java.net.InetSocketAddress
import android.os.Process
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Android VpnService adapted to the pinned Husi libcore API.
 * All core operations are serialized. Neither the config nor the password is logged.
 */
open class KvnVpnService : VpnService() {
    private var stateEpoch = 0L
    private fun publishState(value: ConnectionState) = VpnState.publishFromService(stateEpoch, value)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var core: fr.husi.libcore.Service? = null
    private var tunnelMtu = TunnelConfig.MTU
    @Volatile private var tun: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var interfaceListener: InterfaceUpdateListener? = null
    private val stopping = AtomicBoolean(false)
    private val callbackProblem = AtomicReference<String?>(null)

    override fun onCreate() {
        super.onCreate()
        stateEpoch = VpnState.beginService()
        worker.scheduleWithFixedDelay({
            val engine = core
            if (!stopping.get() && engine != null && !engine.hasInstance()) {
                runCatching { engine.close() }
                core = null
                publishState(ConnectionState.Failed("VPN-ядро остановилось. Трафик заблокирован до отключения VPN.", tun != null))
                updateNotification("Ошибка ядра · трафик заблокирован")
            }
        }, 3, 3, java.util.concurrent.TimeUnit.SECONDS)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "KVN VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ON, null -> {
                if ((VpnState.state.value == ConnectionState.Starting ||
                    VpnState.state.value == ConnectionState.Checking) ||
                    (VpnState.state.value == ConnectionState.On || VpnState.state.value == ConnectionState.TunnelCreated)) return START_NOT_STICKY
                if (VpnService.prepare(this) != null) {
                    publishState(ConnectionState.Failed("Разреши подключение VPN в Android"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                stopping.set(false)
                CrashMonitor.start(this)
                publishState(ConnectionState.Starting)
                try {
                    showForeground("Подключаемся…")
                } catch (_: Exception) {
                    publishState(ConnectionState.Failed("Не удалось запустить фоновую службу"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                worker.execute { startTunnel() }
            }
            ACTION_OFF -> {
                stopping.set(true)
                worker.execute {
                closeTunnel()
                CrashMonitor.stage(this, "USER_DISCONNECTED")
                publishState(ConnectionState.Off)
                stopSelf()
            }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        var stage = "E01"
        CrashMonitor.stage(this, "LOAD_PROFILE")
        var profileForDiagnostics: ServerProfile? = null
        callbackProblem.set(null)
        try {
            closeTunnel()
            val profile = SecretStore(this).load()
                ?: error("Profile not saved")
            profileForDiagnostics = profile
            stage = "E02"
            CrashMonitor.stage(this, "CHECK_CONFIG")
            val serverIPs = try {
                NetworkChecks.serverIPs(this, profile.host)
            } catch (e: Exception) {
                throw IllegalStateException("Не удалось определить IP VDS через DNS сети телефона", e)
            }
            val config = TunnelConfig.build(profile, serverIPs)
            if (stopping.get()) return
            Libcore.checkKvnConfig(config)
            stage = "E03"
            CrashMonitor.stage(this, "START_CORE")
            val engine = Libcore.newService("KVN-0.2.0", platformBridge())
            core = engine
            engine.start()
            stage = "E04"
            CrashMonitor.stage(this, "START_SERVICE")
            engine.startConfig(config)
            if (stopping.get()) return

            check(tun != null) { "Core did not create a tunnel" }
            CrashMonitor.stage(this, "TUN_CREATED")
            publishState(ConnectionState.TunnelCreated)
            updateNotification("Туннель создан · доступ не проверен")
        } catch (e: Exception) {
            CrashMonitor.stage(this, "STARTUP_FAILED")
            // Keep an established Android interface on failure. Closing it would
            // silently restore direct routing. The user explicitly disconnects it.
            runCatching { core?.stopService() }
            runCatching { core?.close() }
            core = null
            if (!stopping.get()) {
                val coreReason = callbackProblem.get()
                val explanation = SafeDiagnostics.classify(e)
                val detail = SafeDiagnostics.detail(e, profileForDiagnostics)
                val stageText = when (stage) {
                    "E01" -> "E01 · Настройки"
                    "E02" -> "E02 · Конфигурация"
                    "E03" -> "E03 · Запуск ядра"
                    else -> "E04 · Запуск туннеля"
                }
                val callback = coreReason?.let { " [$it]" } ?: ""
                publishState(ConnectionState.Failed(
                    "$stageText$callback · ${e.javaClass.simpleName}\n$explanation\n$detail", tun != null
                ))
                if (tun == null) stopSelf() else updateNotification("Ошибка ядра · трафик заблокирован")
            }
        }
    }

    private fun showForeground(message: String) {
        val notification = notification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    private fun notification(message: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("KVN")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_kvn)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun closeTunnel() {
        val old = core
        core = null
        runCatching { old?.stopService() }
        runCatching { old?.close() }
        runCatching { tun?.close() }
        tun = null
        interfaceListener = null
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun platformBridge(): PlatformInterface = object : PlatformInterface {
        override fun openTun(): Int = this@KvnVpnService.openTun()
        override fun autoDetectInterfaceControl(fd: Int): Boolean = protect(fd)

        override fun getInterfaces() = findInterfaces()
        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) =
            startInterfaceMonitor(listener)
        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) =
            stopInterfaceMonitor()

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int
        ): ConnectionOwner {
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "Connection owner requires Android 10" }
            val uid = connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
            check(uid != Process.INVALID_UID) { "Connection owner unavailable" }
            val names = packageManager.getPackagesForUid(uid).orEmpty()
            return ConnectionOwner(uid, stringIterator(names.toList()))
        }
        override fun localDNSTransport(): LocalDNSTransport? = null
        override fun readWIFIState(): WIFIState? = null
        override fun tailscaleHostname(): String = "KVN"
    }

    // Husi's OpenTun callback has no options argument. These values are shared
    // with TunnelConfig, as Husi's VpnService shares its internal configuration.
    private fun openTun(): Int {
        check(!stopping.get()) { "VPN startup cancelled" }
        check(VpnService.prepare(this) == null) { "VPN permission revoked" }
        val builder = Builder().setSession("KVN").setMtu(tunnelMtu)
            .addAddress(TunnelConfig.IPV4, 30)
            .addAddress(TunnelConfig.IPV6, 126)
            .addDnsServer("172.19.0.2")
            .addDnsServer("fdfe:dcba:9876::2")
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val descriptor = builder.establish() ?: error("Android refused VPN interface")
        tun = descriptor
        return descriptor.fd
    }

    /** Used by libbox for default-interface selection, not as a user-facing setting. */
    private fun findInterfaces(): NetworkInterfaceIterator {
        val entries = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val name = link.interfaceName ?: return@mapNotNull null
            val sys = JavaNetworkInterface.getByName(name) ?: return@mapNotNull null
            fr.husi.libcore.NetworkInterface().apply {
                this.name = name
                index = sys.index
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libcore.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libcore.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libcore.InterfaceTypeEthernet
                    else -> Libcore.InterfaceTypeOther
                }
                dnsServer = stringIterator(link.dnsServers.mapNotNull { it.hostAddress })
                dnsSearchDomain = stringIterator(link.domains.orEmpty().split(' ').filter { it.isNotBlank() })
                addresses = stringIterator(
                    sys.interfaceAddresses.mapNotNull { addr ->
                        val address = addr.address.hostAddress?.substringBefore('%') ?: return@mapNotNull null
                        "$address/${addr.networkPrefixLength}"
                    }
                )
                mtu = sys.mtu
                var fl = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    (OsConstants.IFF_UP or OsConstants.IFF_RUNNING) else 0
                if (sys.isLoopback) fl = fl or OsConstants.IFF_LOOPBACK
                if (sys.isPointToPoint) fl = fl or OsConstants.IFF_POINTOPOINT
                if (sys.supportsMulticast()) fl = fl or OsConstants.IFF_MULTICAST
                flags = fl
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        val iterator = entries.iterator()
        return object : NetworkInterfaceIterator {
            override fun length() = entries.size
            override fun hasNext() = iterator.hasNext()
            override fun next(): fr.husi.libcore.NetworkInterface = iterator.next()
        }
    }

    private fun stringIterator(items: List<String>): StringIterator {
        val iterator = items.iterator()
        return object : StringIterator {
            override fun length(): Int = items.size
            override fun hasNext(): Boolean = iterator.hasNext()
            override fun next(): String = iterator.next()
        }
    }

    private fun startInterfaceMonitor(listener: InterfaceUpdateListener) {
        stopInterfaceMonitor()
        interfaceListener = listener
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = pushDefaultInterface()
            override fun onLost(network: android.net.Network) = pushDefaultInterface()
            override fun onLinkPropertiesChanged(network: android.net.Network, link: android.net.LinkProperties) = pushDefaultInterface()
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: NetworkCapabilities
            ) = pushDefaultInterface()
        }
        networkCallback = callback
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            callback
        )
        pushDefaultInterface()
    }

    private fun pushDefaultInterface() {
        val physical = connectivity.allNetworks.sortedByDescending { network ->
            connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.firstOrNull { network ->
            val c = connectivity.getNetworkCapabilities(network)
            c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        setUnderlyingNetworks(physical?.let { arrayOf(it) })
        val name = physical?.let { connectivity.getLinkProperties(it)?.interfaceName } ?: ""
        val index = runCatching { JavaNetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        runCatching { interfaceListener?.updateDefaultInterface(name, index) }
    }

    private fun stopInterfaceMonitor() {
        interfaceListener = null
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    override fun onRevoke() {
        stopping.set(true)
        CrashMonitor.stage(this, "VPN_REVOKED")
        worker.execute {
            closeTunnel()
            publishState(ConnectionState.Off)
            stopSelf()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopping.set(true)
        worker.execute {
            closeTunnel()
            val failure = VpnState.state.value as? ConnectionState.Failed
            publishState(failure?.copy(tunnelActive = false) ?: ConnectionState.Off)
        }
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "kvn-vpn-status"
        private const val NOTIFICATION_ID = 5001
        private const val ACTION_ON = "ru.ladokod.kvn.action.ON"
        private const val ACTION_OFF = "ru.ladokod.kvn.action.OFF"

        fun connect(context: Context) {
            val intent = Intent(context, KvnVpnService::class.java).setAction(ACTION_ON)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
        fun disconnect(context: Context) {
            val intent = Intent(context, KvnVpnService::class.java).setAction(ACTION_OFF)
            context.startService(intent)
        }
    }
}
