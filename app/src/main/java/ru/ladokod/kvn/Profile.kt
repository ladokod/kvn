package ru.ladokod.kvn

import java.net.IDN

data class ServerProfile(val host: String, val username: String, val password: String)

object ProfileValidator {
    private val label = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    fun validate(hostInput: String, usernameInput: String, passwordInput: String): ServerProfile {
        val raw = hostInput.trim().removeSuffix(".")
        require(!raw.contains("://") && !raw.contains('/') && !raw.contains(':') && !raw.contains('@')) {
            "Укажи только домен, без https://, порта и пути"
        }
        val host = try { IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase() }
                   catch (_: Exception) { throw IllegalArgumentException("Неверный адрес сервера") }
        require(host.length in 4..253 && host.contains('.') && host.split('.').all { label.matches(it) }) {
            "Нужен корректный домен, например vpn.example.com"
        }
        val username = usernameInput.trim()
        require(username.length in 1..100 && !username.contains(':') && !username.any { it.isISOControl() }) {
            "Проверь логин (без двоеточия)"
        }
        require(passwordInput.isNotEmpty() && passwordInput.length <= 512 &&
                !passwordInput.any { it == '\n' || it == '\r' }) {
            "Укажи пароль (до 512 символов)"
        }
        return ServerProfile(host, username, passwordInput)
    }
}
