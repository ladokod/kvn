package ru.ladokod.kvn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persist only lifecycle stage and exception class. Never save credentials or raw stacktraces. */
object CrashMonitor {
    private const val PREFS = "kvn-crash-markers-v1"

    fun start(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("started", System.currentTimeMillis())
            .putString("stage", "START_REQUESTED")
            .remove("exception")
            .commit()
    }

    fun stage(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("stage", value)
            .commit()
    }

    fun crashed(context: Context, error: Throwable) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("exception", error.javaClass.simpleName.take(60))
            .commit()
    }

    fun previousAbnormalExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong("started", 0L)
        if (since == 0L) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val abnormal = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_SIGNALED
        )
        val exit = runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 12)
                .filter { it.timestamp >= since && it.reason in abnormal &&
                    it.processName == context.packageName }
                .maxByOrNull { it.timestamp }
        }.getOrNull() ?: return null
        val reason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNAL"
            else -> return null
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(Date(exit.timestamp))
        val stage = prefs.getString("stage", "UNKNOWN") ?: "UNKNOWN"
        val extra = prefs.getString("exception", null)?.let { " · " + it }.orEmpty()
        return "Предыдущее завершение: " + reason +
            " (status=" + exit.status + ")" +
            " · " + time + " · этап " + stage + extra
    }
}
