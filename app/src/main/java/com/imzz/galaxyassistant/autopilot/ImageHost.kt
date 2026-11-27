package com.imzz.galaxyassistant.autopilot

import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * API vision kyio menerima gambar lewat parameter `url=` (link publik), bukan upload langsung.
 * Jadi screenshot diunggah dulu ke host file SEMENTARA tanpa akun/API key, lalu link-nya dikirim ke API vision.
 *
 * PRIVASI: selama file ada di host, siapa pun yang tahu link-nya bisa membukanya. Karena itu fitur ini
 * hanya jalan kalau pengguna menyalakannya sendiri, gambar diperkecil, dan layar login/pembayaran/OTP tidak pernah diunggah.
 */
object ImageHost {
    private data class Host(val name: String, val endpoint: String, val field: String)

    // Dicoba berurutan. Keduanya host file sementara; kebijakan hapus-otomatis ditentukan masing-masing host.
    private val hosts = listOf(
        Host("uguu", "https://uguu.se/upload", "files[]"),
        Host("tmpfiles", "https://tmpfiles.org/api/v1/upload", "file")
    )

    private val urlRegex = Regex("https?://[^\"\\s<>\\\\]+")

    fun upload(jpeg: ByteArray): String {
        var last: Exception? = null
        for (host in hosts) {
            try {
                return uploadTo(host, jpeg)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Upload screenshot gagal.")
    }

    private fun uploadTo(host: Host, jpeg: ByteArray): String {
        val boundary = "----skylen" + UUID.randomUUID().toString().replace("-", "")
        val connection = (URL(host.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "SkylenAutopilot/1.6")
        }
        try {
            connection.outputStream.use { out ->
                val head = "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"${host.field}\"; filename=\"screen.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n"
                out.write(head.toByteArray(Charsets.UTF_8))
                out.write(jpeg)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("${host.name} HTTP $status")
            return extractUrl(body) ?: error("${host.name}: balasan tanpa link")
        } finally {
            connection.disconnect()
        }
    }

    /** Ambil link file dari balasan host (JSON atau teks biasa). */
    private fun extractUrl(body: String): String? {
        val clean = body.replace("\\/", "/")
        var url = urlRegex.find(clean)?.value?.trimEnd(',', '}', ']', ')') ?: return null
        // tmpfiles.org: link halaman harus diubah ke link unduhan langsung
        if (url.contains("tmpfiles.org/") && !url.contains("tmpfiles.org/dl/")) {
            url = url.replace("tmpfiles.org/", "tmpfiles.org/dl/")
        }
        return url
    }
}
