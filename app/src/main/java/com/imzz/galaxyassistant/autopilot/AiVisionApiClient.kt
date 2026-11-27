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

    private fun isValid(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching {
            val url = URL(value)
            url.protocol.equals("https", ignoreCase = true) ||
                (url.protocol.equals("http", ignoreCase = true) &&
                    (url.host.equals("10.0.2.2", true) || url.host.equals("localhost", true)))
        }.getOrDefault(false)
    }

    /** Daftar endpoint valid. [preferSecondary] = true -> API #2 dicoba lebih dulu (dipakai untuk chat Skylen). */
    fun endpoints(preferSecondary: Boolean = false): List<String> {
        val list = listOf(endpoint(), endpoint2()).filter { isValid(it) }.distinct()
        return if (preferSecondary) list.reversed() else list
    }

    fun isConfigured(): Boolean = endpoints().isNotEmpty()

    /** Endpoint gaya "…?q=" (GET, teks saja). */
    fun isTextEndpoint(url: String): Boolean = url.contains("q=")

    // ------------------------------------------------------------------ endpoint teks (GET ?q=)

    /** Kirim [prompt] ke endpoint teks dan kembalikan teks jawabannya (JSON dibongkar otomatis). */
    fun askText(target: String, prompt: String, readTimeoutMs: Int = 60_000): String {
        val full = target + URLEncoder.encode(prompt, "UTF-8")
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

    fun plan(frame: File, ocrText: String, layout: String, goal: String, previousActions: String): PlannedAction {
        check(isConfigured()) {
            "AI API belum dikonfigurasi. Isi galaxyAiApiUrl dengan endpoint API yang sudah lu punya."
        }
        val jsonBody by lazy { buildJsonBody(frame, ocrText, layout, goal, previousActions) }

        var lastError: Exception? = null
        for (target in endpoints()) {
            try {
                return if (isTextEndpoint(target)) {
                    parsePlanText(askText(target, buildPlanPrompt(ocrText, layout, goal, previousActions), 70_000))
                } else {
                    parseAction(postJson(target, jsonBody, 45_000))
                }
            } catch (error: Exception) {
                lastError = error // coba API berikutnya (failover)
            }
        }
        throw lastError ?: IllegalStateException("AI API tidak merespons.")
    }

    private fun buildPlanPrompt(ocrText: String, layout: String, goal: String, previousActions: String): String {
        val screen = layout.ifBlank { ocrText }.take(1500)
        return buildString {
            append("Kamu adalah Skylen, AI yang mengendalikan layar HP Android lewat tap dan swipe. ")
            append("Kamu TIDAK bisa melihat gambar; kamu hanya diberi teks layar hasil OCR beserta koordinat titik tengahnya ")
            append("(format \"x,y teks\", x dan y 0.0 sampai 1.0, 0,0 = kiri atas).\n")
            append("TUJUAN: ").append(goal.take(400)).append('\n')
            append("AKSI SEBELUMNYA:\n").append(previousActions.take(500).ifBlank { "(belum ada)" }).append('\n')
            append("LAYAR SEKARANG:\n").append(screen.ifBlank { "(tidak ada teks terbaca; mungkin game/video tanpa teks)" }).append('\n')
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

    private fun buildJsonBody(frame: File, ocrText: String, layout: String, goal: String, previousActions: String): JSONObject {
        require(frame.exists() && frame.length() > 0L) { "Screenshot terbaru belum tersedia." }
        val source = BitmapFactory.decodeFile(frame.absolutePath)
            ?: error("Screenshot tidak bisa dibaca.")
        val maxSide = 1440
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(source.width, source.height))
        val scaled = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else source
        val imageBytes = try {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
                output.toByteArray()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
            source.recycle()
        }
        return JSONObject()
            .put("goal", goal.take(1500))
            .put("ocrText", ocrText.take(5000))
            .put("ocrLayout", layout.take(3000))
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
