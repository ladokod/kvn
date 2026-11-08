package ru.ladokod.kvn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials never enter URLs, GitHub, Android Backup or ordinary logs. */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("kvn-secure-v1", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun save(profile: ServerProfile) {
        val clean = ProfileValidator.validate(profile.host, profile.username, profile.password)
        val json = JSONObject().put("host", clean.host)
            .put("username", clean.username).put("password", clean.password).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val bytes = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(KEY_DATA, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
            "Не удалось сохранить настройки"
        }
    }

    fun load(): ServerProfile? {
        val text = prefs.getString(KEY_DATA, null) ?: return null
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        require(bytes.size >= 12 + 16) { "Повреждены сохранённые настройки" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val decrypted = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        val obj = JSONObject(String(decrypted, Charsets.UTF_8))
        return ProfileValidator.validate(obj.getString("host"), obj.getString("username"), obj.getString("password"))
    }

    companion object {
        private const val KEY_ALIAS = "kvn-profile-aes-gcm-v1"
        private const val KEY_DATA = "encrypted-profile"
    }
}
