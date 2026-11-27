package com.imzz.galaxyassistant.autopilot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.imzz.galaxyassistant.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Menghubungkan app ke AI milik pengguna (maksimal dua endpoint, dengan failover). Dua jenis endpoint:
 *  - TEKS (GET): URL berakhiran "q=" seperti https://host/api/v2/ai/gpt-5?q= . Prompt dikirim lewat query,
 *    jawabannya teks/JSON apa saja. Model ini tidak melihat gambar, jadi layar dikirim sebagai teks OCR + koordinat.
 *  - JSON (POST): kontrak di docs/AI_API_CONTRACT.md, bisa membawa screenshot.
 * Tidak ada API key di aplikasi.
 */
object AiVisionApiClient {
    /** API #1 — utama untuk Autopilot. */
    fun endpoint(): String = BuildConfig.AI_API_URL.trim()

    /** API #2 — cadangan Autopilot, dan dipakai lebih dulu untuk ngobrol dengan Skylen. */
    fun endpoint2(): String = BuildConfig.AI_API_URL_2.trim()

    /**
     * Proxy vision (folder proxy/): endpoint JSON yang bisa menerima screenshot. Kalau diisi, Autopilot
     * mencobanya PERTAMA, lalu jatuh ke API #1 dan #2 (teks/OCR) kalau gagal. Kosong = tidak dipakai.
     */
    fun visionEndpoint(): String = BuildConfig.AI_VISION_URL.trim()

    private fun isValid(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching {
            val url = URL(value.replace("{q}", "x").replace("{sid}", "x").replace("{url}", "x"))
            url.protocol.equals("https", ignoreCase = true) ||
                (url.protocol.equals("http", ignoreCase = true) &&
                    (url.host.equals("10.0.2.2", true) || url.host.equals("localhost", true) || url.host == "127.0.0.1"))
        }.getOrDefault(false)
    }

    /**
     * Urutan percobaan. Autopilot: proxy vision -> API #1 -> API #2.
     * [preferSecondary] (chat Skylen): API #2 -> API #1 -> proxy vision.
     */
    fun endpoints(preferSecondary: Boolean = false): List<String> {
        val ordered = if (preferSecondary) listOf(endpoint2(), endpoint(), visionEndpoint())
        else listOf(visionEndpoint(), endpoint(), endpoint2())
        return ordered.filter { isValid(it) }.distinct()
    }

    fun isConfigured(): Boolean = endpoints().isNotEmpty()

    /** Template API vision: URL berisi {q}, {sid}, {url}. Gambar dikirim sebagai link publik (lihat ImageHost). */
    fun isVisionTemplate(url: String): Boolean = url.contains("{url}")

    /** Endpoint gaya "…?q=" (GET, teks saja). */
    fun isTextEndpoint(url: String): Boolean = url.contains("?q=") || url.contains("&q=")

    // ------------------------------------------------------------------ endpoint teks (GET ?q=)

    /** Kirim [prompt] ke endpoint teks dan kembalikan teks jawabannya (JSON dibongkar otomatis). */
    fun askText(target: String, prompt: String, readTimeoutMs: Int = 60_000): String =
        getText(target + URLEncoder.encode(prompt, "UTF-8"), readTimeoutMs)

    /** Panggil API vision: unggah screenshot (diperkecil) ke host sementara, lalu kirim link-nya lewat {url}. */
    private fun askVision(template: String, prompt: String, frame: File): String {
        val jpeg = encodeJpeg(frame, 720, 70)
        val imageUrl = ImageHost.upload(jpeg)
        val full = template
            .replace("{q}", URLEncoder.encode(prompt, "UTF-8"))
            .replace("{sid}", URLEncoder.encode("sky" + System.currentTimeMillis(), "UTF-8"))
            .replace("{url}", URLEncoder.encode(imageUrl, "UTF-8"))
        return getText(full, 90_000)
    }

    private fun getText(full: String, readTimeoutMs: Int): String {
        val connection = (URL(full).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("AI API HTTP $status: ${raw.take(300)}")
            val text = extractText(raw)
            if (text.isBlank()) error("AI API membalas kosong.")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private val textKeys = listOf(
        "result", "response", "answer", "reply", "speech", "text", "content", "message",
        "output", "data", "choices", "candidates", "parts"
    )

    /** Ambil teks jawaban dari JSON bentuk apa pun ({"result":"…"}, OpenAI, Gemini…). Bukan JSON -> dikembalikan apa adanya. */
    fun extractText(raw: String): String {
        val trimmed = raw.trim()
        val root: Any? = when {
            trimmed.startsWith("{") -> runCatching { JSONObject(trimmed) }.getOrNull()
            trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
            else -> null
        }
        if (root == null) return trimmed
        return findText(root, 0) ?: trimmed
    }

    private fun findText(value: Any?, depth: Int): String? {
        if (depth > 6) return null
        when (value) {
            is String -> return value.takeIf { it.isNotBlank() }
            is JSONObject -> {
                for (key in textKeys) {
                    if (value.has(key)) findText(value.opt(key), depth + 1)?.let { return it }
                }
                return null
            }
            is JSONArray -> {
                for (i in 0 until value.length()) findText(value.opt(i), depth + 1)?.let { return it }
                return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Autopilot

    /**
     * [allowImage] = pengguna sudah mengizinkan screenshot dikirim ke layanan luar (API vision / proxy).
     * Layar yang terdeteksi login/pembayaran/OTP tidak pernah dikirim sebagai gambar.
     */
    fun plan(
        frame: File, ocrText: String, layout: String, scene: String,
        goal: String, previousActions: String, allowImage: Boolean = false
    ): PlannedAction {
        check(isConfigured()) {
            "AI API belum dikonfigurasi. Isi galaxyAiApiUrl dengan endpoint API yang sudah lu punya."
        }
        val imageOk = allowImage && !ScreenUnderstanding.isSensitiveScreen(ocrText)
        val targets = endpoints().filter { isTextEndpoint(it) || imageOk }
        check(targets.isNotEmpty()) { "Tidak ada endpoint yang boleh dipakai saat ini." }
        val jsonBody by lazy { buildJsonBody(frame, ocrText, layout, scene, goal, previousActions) }

        var lastError: Exception? = null
        for (target in targets) {
            try {
                return when {
                    isVisionTemplate(target) ->
                        parsePlanText(askVision(target, buildPlanPrompt(ocrText, layout, scene, goal, previousActions, true), frame))
                    isTextEndpoint(target) ->
                        parsePlanText(askText(target, buildPlanPrompt(ocrText, layout, scene, goal, previousActions, false), 70_000))
                    else -> parseAction(postJson(target, jsonBody, 45_000))
                }
            } catch (error: Exception) {
                lastError = error // coba endpoint berikutnya (failover)
            }
        }
        throw lastError ?: IllegalStateException("AI API tidak merespons.")
    }

    private fun buildPlanPrompt(ocrText: String, layout: String, scene: String, goal: String, previousActions: String, hasImage: Boolean): String {
        val screen = layout.ifBlank { ocrText }.take(1100)
        return buildString {
            append("Kamu adalah Skylen, AI yang mengendalikan layar HP Android lewat tap dan swipe. ")
            if (hasImage) {
                append("Kamu diberi SCREENSHOT layar (gambar terlampir) plus teks OCR dengan koordinat titik tengahnya ")
                append("(format \"x,y teks\"). Semua koordinat x,y pakai skala 0.0 sampai 1.0 relatif lebar/tinggi layar, 0,0 = kiri atas.\n")
            } else {
                append("Kamu TIDAK bisa melihat gambar; kamu hanya diberi teks layar hasil OCR beserta koordinat titik tengahnya ")
                append("(format \"x,y teks\", x dan y 0.0 sampai 1.0, 0,0 = kiri atas).\n")
            }
            append("TUJUAN: ").append(goal.take(400)).append('\n')
            append("AKSI SEBELUMNYA:\n").append(previousActions.take(500).ifBlank { "(belum ada)" }).append('\n')
            append("LAYAR SEKARANG:\n").append(screen.ifBlank { "(tidak ada teks terbaca; mungkin game/video tanpa teks)" }).append('\n')
            if (scene.isNotBlank()) {
                append("OBJEK MENCOLOK (perkiraan kasar dari warna di layar, bukan pengenalan gambar):\n")
                append(scene.take(700)).append('\n')
                append("Kalau layar nggak punya teks (menu game, tombol ikon), pakai objek ini sebagai kandidat titik tap; kalau nggak yakin balas ask_user.\n")
            }
            append("Balas HANYA satu objek JSON tanpa penjelasan lain: ")
            append("{\"type\":\"tap|swipe|type_text|wait|ask_user|stop\",\"x\":0.5,\"y\":0.5,\"x2\":0.5,\"y2\":0.5,")
            append("\"text\":\"\",\"waitMs\":1500,\"reason\":\"alasan singkat\",\"speech\":\"kalimat pendek santai\"}. ")
            append("Untuk tap pakai koordinat teks yang kamu tuju. Scroll/geser video = swipe dari bawah ke atas (y 0.75 ke 0.25). ")
            append("Jangan pernah login, OTP, bayar/beli, hapus akun, atau kirim pesan/posting. Kalau ragu atau layar tidak jelas, balas ask_user.")
        }
    }

    private fun parsePlanText(text: String): PlannedAction {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return PlannedAction(type = "ask_user", reason = "AI membalas di luar format JSON: ${text.take(120)}")
        }
        return runCatching { parseAction(text.substring(start, end + 1)) }
            .getOrElse { PlannedAction(type = "ask_user", reason = "JSON aksi dari AI tidak bisa dibaca.") }
    }

    private fun buildJsonBody(frame: File, ocrText: String, layout: String, scene: String, goal: String, previousActions: String): JSONObject {
        val imageBytes = encodeJpeg(frame, 1440, 80)
        return JSONObject()
            .put("goal", goal.take(1500))
            .put("ocrText", ocrText.take(5000))
            .put("ocrLayout", layout.take(3000))
            .put("scene", scene.take(1200))
            .put("previousActions", previousActions.take(1500))
            .put("screenshotMimeType", "image/jpeg")
            .put("screenshotBase64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
            .put("responseFormat", "planned_action_json")
            .put(
                "safety",
                JSONObject()
                    .put("allowedActions", JSONArray().put("tap").put("swipe").put("type_text").put("wait").put("ask_user").put("stop"))
                    .put("neverAutomateSensitiveActions", true)
                    .put("neverBypassAuthenticationOrAntiCheat", true)
            )
    }

    /** Baca screenshot, perkecil sisi terpanjang ke [maxSide], lalu JPEG kualitas [quality]. */
    private fun encodeJpeg(frame: File, maxSide: Int, quality: Int): ByteArray {
        require(frame.exists() && frame.length() > 0L) { "Screenshot terbaru belum tersedia." }
        val source = BitmapFactory.decodeFile(frame.absolutePath)
            ?: error("Screenshot tidak bisa dibaca.")
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(source.width, source.height))
        val scaled = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else source
        return try {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
                output.toByteArray()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
    }

    /** POST JSON ke [target] dan mengembalikan body respons (2xx). */
    fun postJson(target: String, body: JSONObject, readTimeoutMs: Int = 45_000): String {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                error("AI API HTTP $status: ${responseText.take(400)}")
            }
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAction(responseText: String): PlannedAction {
        val root = JSONObject(responseText)
        // Accept either a direct action, {"action": {...}}, or Gemini-compatible
        // proxy responses whose first text part contains the action JSON.
        val action = when {
            root.has("type") -> root
            root.optJSONObject("action") != null -> root.getJSONObject("action")
            else -> {
                val contentText = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
                val jsonText = contentText.substringAfter('{', "").let { "{" + it.substringBeforeLast('}', it) + "}" }
                JSONObject(jsonText)
            }
        }

        val type = action.optString("type", "ask_user").lowercase()
        if (type !in setOf("tap", "swipe", "type_text", "wait", "ask_user", "stop")) {
            return PlannedAction(type = "ask_user", reason = "API memberikan jenis aksi yang tidak didukung.")
        }
        return PlannedAction(
            type = type,
            x = action.optDouble("x", 0.5).coerceIn(0.0, 1.0),
            y = action.optDouble("y", 0.5).coerceIn(0.0, 1.0),
            x2 = action.optDouble("x2", 0.5).coerceIn(0.0, 1.0),
            y2 = action.optDouble("y2", 0.5).coerceIn(0.0, 1.0),
            text = action.optString("text").take(100),
            waitMs = action.optLong("waitMs", 1500L).coerceIn(500L, 8000L),
            reason = action.optString("reason").take(300),
            speech = action.optString("speech").take(240)
        )
    }
}
