package ru.ladokod.kvn

import android.content.Intent
import android.os.Binder

/** Only in debug APKs; provides a local binding for the real Husi/TUN instrumentation test. */
class AuditVpnService : KvnVpnService() {
    inner class LocalBinder : Binder() { val service get() = this@AuditVpnService }
    override fun onBind(intent: Intent) =
        if (intent.action == "ru.ladokod.kvn.AUDIT") LocalBinder() else super.onBind(intent)
}
