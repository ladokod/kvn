package ru.ladokod.kvn

import android.app.Application
import fr.husi.libcore.Libcore
import java.io.File

class KvnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, cause ->
            runCatching { CrashMonitor.crashed(this, cause) }
            previousHandler?.uncaughtException(thread, cause)
        }
        // The VPN service reports initialization problems without exposing credentials.
        runCatching {
            val base = File(filesDir, "libbox").apply { mkdirs() }
            val work = File(cacheDir, "libbox").apply { mkdirs() }
            Libcore.initCore(cacheDir.absolutePath, base.absolutePath, work.absolutePath, 50, 4, false)
            Libcore.setupRootCA(Libcore.CertSystem)
        }.onFailure { VpnState.publish(ConnectionState.Failed("Не удалось загрузить VPN-ядро")) }
    }
}
