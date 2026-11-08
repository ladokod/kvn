package ru.ladokod.kvn.probe

import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Fixed-label measurements only; neither response content nor exception messages leave this class. */
data class Check(val label: String, val url: String, val limit: Int = 262144, val expectedBytes: Long? = null) {
    init { require(label.matches(Regex("[a-z0-9_]+"))); require(URL(url).protocol == "https"); require(limit in 1..2097152) }
}
data class Measurement(val ok: Boolean, val bytes: Long, val sha256: String, val report: String)

interface ProbeTransport {
    fun requireVpn()
    fun resolve(host: String): Array<InetAddress>
    fun open(url: URL): HttpsURLConnection
    fun socket(): java.net.Socket = throw UnsupportedOperationException()
}

private class RedirectNotFollowed : Exception()

class ProbeRunner(private val transport: ProbeTransport, private val errno: (Throwable) -> String? = { null }) {
    fun check(check: Check): Measurement {
        val started = System.nanoTime()
        var phase = "vpn"
        var connection: HttpsURLConnection? = null
        var bytes = 0L
        var status = 0
        var a = 0
        var aaaa = 0
        val digest = MessageDigest.getInstance("SHA-256")
        var result: String
        var ok = false
        try {
            transport.requireVpn()
            val url = URL(check.url)
            phase = "dns"
            val answers = transport.resolve(url.host)
            a = answers.count { it.address.size == 4 }; aaaa = answers.size - a
            check(answers.isNotEmpty())
            transport.requireVpn()
            phase = "connect_tls"
            connection = transport.open(url).apply {
                connectTimeout = 8000; readTimeout = 8000
                instanceFollowRedirects = false; useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
            }
            connection.connect()
            phase = "headers"
            status = connection.responseCode
            if (status in 300..399) throw RedirectNotFollowed()
            check(status in 200..299)
            phase = "body"
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            connection.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (bytes < check.limit) {
                    if (System.nanoTime() > deadline) throw java.net.SocketTimeoutException()
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), check.limit - bytes).toInt())
                    if (n < 0) break
                    digest.update(buffer, 0, n); bytes += n
                }
            }
            val expected = check.expectedBytes ?: connection.contentLengthLong.takeIf { it >= 0 }
            if (expected != null && bytes < minOf(expected, check.limit.toLong())) throw java.io.EOFException()
            if (check.expectedBytes != null && bytes != check.expectedBytes) throw java.io.EOFException()
            if (status != 204 && bytes == 0L) throw java.io.EOFException()
            phase = "vpn_after"
            transport.requireVpn()
            ok = true
            result = if (bytes == check.limit.toLong() && check.expectedBytes == null) "bounded_ok" else "complete_ok"
        } catch (error: Exception) {
            // Names and whitelisted errno only. No URL, headers, bodies, certificates or raw errors.
            result = generateSequence<Throwable>(error) { it.cause }.take(6).map {
                it.javaClass.simpleName + (errno(it)?.let { code -> ":$code" } ?: "")
            }.distinct().joinToString("<-")
        } finally { connection?.disconnect() }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return Measurement(ok, bytes, digest.digest().joinToString("") { "%02x".format(it) },
            "PROBE.${check.label} phase=$phase result=$result status=$status A=$a AAAA=$aaaa bytes=$bytes ms=$ms")
    }

    companion object {
        val publicChecks = listOf(
            Check("numeric_v4", "https://1.1.1.1/cdn-cgi/trace"),
            Check("numeric_v6", "https://[2606:4700:4700::1111]/cdn-cgi/trace"),
            Check("html", "https://example.com/"),
            Check("css", "https://cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.min.css"),
            Check("youtube_html", "https://www.youtube.com/"),
            Check("youtube_image", "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg"),
            Check("large", "https://speed.cloudflare.com/__down?bytes=2097152", 2097152, 2097152)
        )
    }
}
