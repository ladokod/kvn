package ru.ladokod.kvn

/** Diagnostics remain on the device. Never log or display an unredacted libbox error. */
object SafeDiagnostics {
    fun classify(e: Throwable): String {
        val text = generateSequence(e) { it.cause }
            .take(4).mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "permission" in text || "denied" in text || "not prepared" in text ->
                "возможная ошибка разрешений Android"
            "tun" in text || "vpn interface" in text || "establish" in text ->
                "возможная ошибка виртуального интерфейса"
            "naive" in text || "cronet" in text ->
                "возможная ошибка компонента NaïveProxy"
            "resolver" in text || "dns" in text ->
                "возможная ошибка DNS"
            "network" in text || "interface" in text ->
                "возможная ошибка сети"
            "config" in text || "parse" in text ->
                "возможная ошибка конфигурации"
            else -> "неизвестная причина"
        }
    }

    /**
     * A short sanitized excerpt from the Go error. This is not sent to any server.
     * Uses both known profile redaction and generic credential-pattern redaction.
     */
    fun detail(error: Throwable, profile: ServerProfile?): String {
        val raw = generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(" | ")
        if (raw.isBlank()) return "Ядро не предоставило описание ошибки"
        var text = raw
        // Hide secrets before cutting length, so truncation cannot expose prefixes.
        profile?.let { p ->
            // Credentials from the actual user profile must never be displayed.
            for (secret in listOf(p.password, p.username, p.host).filter { it.isNotEmpty() }) {
                text = text.replace(secret, "[скрыто]")
                val escaped = secret.replace("\\", "\\\\").replace("\"", "\\\"")
                if (escaped != secret) text = text.replace(escaped, "[скрыто]")
            }
        }
        text = text.replace(
            Regex("""(?i)\b(https?://|naive://)[^\s/@]{1,512}@"""),
            "$1[скрыто]@"
        )
        text = text.replace(
            Regex("""(?i)(password|passwd|username|authorization|token|secret)\s*["']?\s*[:=]\s*["']?[^\s,;}\]]+"""),
            "$1=[скрыто]"
        )
        text = text.replace(Regex("""[\r\n\t]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        // Avoid echoing a serialized config when native error includes it.
        text = text.substringBefore("{\"inbounds\"")
            .substringBefore("{\"outbounds\"")
            .substringBefore("{\"dns\"")
        return text.take(230).ifBlank { "Краткое описание ошибки отсутствует" }
    }
}
