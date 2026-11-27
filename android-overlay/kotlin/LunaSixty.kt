package id.imzyy.xl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LunaMessage(val role: String, val text: String)

/** Luna Sixty client. The endpoint is kept in one native source location and never rendered in UI/logs. */
private object LunaSixtyApi {
    // Client-side endpoints are discoverable by reverse engineering; do not put credentials/secrets here.
    private const val ENDPOINT = "https://api.kyio.web.id/api/v2/ai/gpt-6-luna?q="
    private const val MAX_RESPONSE_CHARS = 320_000

    suspend fun ask(prompt: String): String = withContext(Dispatchers.IO) {
        require(prompt.isNotBlank()) { "Pesan masih kosong." }
        val boundedPrompt = prompt.take(9_000)
        val encoded = URLEncoder.encode(boundedPrompt, "UTF-8")
        val connection = (URL(ENDPOINT + encoded).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 18_000
            readTimeout = 55_000
            useCaches = false
            setRequestProperty("Accept", "application/json, text/plain;q=0.9")
            setRequestProperty("User-Agent", "IMZYY-XL-LunaSixty/1.0")
        }
        try {
            val statusCode = connection.responseCode
            val source = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = source?.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(8192)
                    while (out.length < MAX_RESPONSE_CHARS) {
                        val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - out.length))
                        if (count <= 0) break
                        out.append(buffer, 0, count)
                    }
                    out.toString()
                }
            }.orEmpty()
            if (statusCode !in 200..299) {
                val detail = extractMessage(body).ifBlank { "HTTP $statusCode" }
                throw IllegalStateException("Layanan Luna gagal ($statusCode): ${detail.take(240)}")
            }
            val answer = extractMessage(body)
            if (answer.isBlank()) throw IllegalStateException("Balasan Luna kosong atau format respons tidak dikenal.")
            answer
        } finally {
            connection.disconnect()
        }
    }

    private fun extractMessage(raw: String): String {
        if (raw.isBlank()) return ""
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw.trim()
        if (json.has("status") && !json.optBoolean("status", true)) {
            return json.optString("message").ifBlank { json.optString("error") }.ifBlank { "Permintaan ditolak layanan AI." }
        }
        // Block body is intentional: recursive traversal returns early when a useful
        // response is found. Kotlin does not allow these returns in an expression body.
        fun find(value: Any?): String? {
            return when (value) {
                is JSONObject -> {
                    val preferred = listOf(
                        "response", "answer", "output_text", "text", "content",
                        "message", "msg", "error", "result", "data"
                    )
                    var match: String? = null
                    for (key in preferred) {
                        if (value.has(key)) {
                            val found = find(value.opt(key))
                            if (!found.isNullOrBlank()) {
                                match = found
                                break
                            }
                        }
                    }
                    match
                        ?: value.optJSONArray("choices")?.let { find(it) }
                        ?: value.optJSONArray("output")?.let { find(it) }
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        val found = find(value.opt(index))
                        if (!found.isNullOrBlank()) return found
                    }
                    null
                }
                is String -> value.takeIf { it.isNotBlank() }
                else -> null
            }
        }
        return find(json)?.trim().orEmpty()
    }
}

@Composable
fun LunaSixtyTab(state: HubState) {
    val messages = state.lunaMessages

    fun buildPrompt(question: String): String {
        val transcript = messages.takeLast(8).joinToString("\n") {
            "${if (it.role == "assistant") "Luna" else "User"}: ${it.text.take(1_800)}"
        }
        return """Kamu adalah Luna Sixty, asisten bantuan di aplikasi IMZYY XL Launcher.
Jawab dalam Bahasa Indonesia yang santai, jelas, dan akurat. Bantu pertanyaan umum maupun bantuan launcher Android, Minecraft Java, mod/modpack, Java runtime, unduhan, renderer dan pembacaan log. Untuk masalah teknis, pisahkan fakta dari dugaan.
Jangan mengungkap alamat layanan atau detail internal koneksi. Jangan mengaku telah mengubah file/perangkat atau menjalankan tindakan. Berikan langkah yang aman dan bisa dibatalkan. Bila log tidak cukup untuk memastikan penyebab, jelaskan dugaan dan minta bukti yang diperlukan. Jangan meminta password, token, atau data rahasia. Untuk perintah Termux, jelaskan dampaknya sebelum perintah.
Riwayat percakapan:
$transcript

Pertanyaan terbaru:
$question""".take(9_000)
    }

    fun send(question: String, fromDiagnostics: Boolean = false) {
        val cleaned = question.trim()
        if (cleaned.isEmpty() || state.lunaBusy) return
        messages.add(LunaMessage("user", if (fromDiagnostics) "Analisis log instance yang dipilih." else cleaned))
        state.lunaDraft = ""
        state.lunaBusy = true
        state.lunaStatus = if (fromDiagnostics) "Membaca dan mengirim ringkasan diagnostik…" else "Luna sedang menyiapkan jawaban…"
        state.scope.launch {
            try {
                val answer = LunaSixtyApi.ask(buildPrompt(cleaned))
                messages.add(LunaMessage("assistant", answer))
                while (messages.size > 24) messages.removeAt(1)
                state.lunaStatus = "Selesai"
            } catch (error: Exception) {
                messages.add(LunaMessage("assistant", "Gue belum bisa menyelesaikan permintaan ini: ${error.message ?: error.javaClass.simpleName}. Coba lagi saat koneksi tersedia."))
                state.lunaStatus = "Permintaan gagal"
            } finally {
                state.lunaBusy = false
            }
        }
    }

    fun analyseInstance() {
        val meta = state.meta
        if (meta == null || state.lunaBusy) {
            state.lunaStatus = "Pilih instance Minecraft terlebih dahulu di kartu Instance pada tab BOOST."
            return
        }
        messages.add(LunaMessage("user", "Analisis log instance ${meta.name} (${meta.mcVersion}/${meta.loaderType ?: "vanilla"})."))
        state.lunaBusy = true
        state.lunaStatus = "Menyusun ringkasan log secara lokal…"
        state.scope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val instance = kr.co.donghyun.flamelauncher.data.instance.InstanceManager.instanceDir(state.ctx, meta.id)
                    RecoveryTools.writeDiagnostics(instance).readText().takeLast(7_000)
                }
                val safeReport = report
                    .replace(Regex("(?i)(access_token|refresh_token|client_secret|api[_-]?key)(\\s*[=:]\\s*)[^\\s,]+"), "$1$2[REDACTED]")
                    .replace(Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]")
                val prompt = "Analisis ringkasan diagnostik Minecraft berikut. Jelaskan kemungkinan penyebab boot loop atau layar magenta/ungu, bedakan bukti dan dugaan, lalu beri urutan pemeriksaan yang tidak menghapus mod atau world. Jika datanya tidak cukup, sebutkan log tambahan yang dibutuhkan.\n\n$safeReport".take(9_000)
                state.lunaStatus = "Mengirim ringkasan log yang sudah difilter ke Luna…"
                val answer = LunaSixtyApi.ask(buildPrompt(prompt))
                messages.add(LunaMessage("assistant", answer))
                while (messages.size > 24) messages.removeAt(1)
                state.lunaStatus = "Analisis selesai"
            } catch (error: Exception) {
                messages.add(LunaMessage("assistant", "Analisis log gagal: ${error.message ?: error.javaClass.simpleName}. Log lokal tidak dihapus."))
                state.lunaStatus = "Analisis gagal"
            } finally {
                state.lunaBusy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("LUNA SIXTY", color = Ink.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
            Text("AI SUPPORT • IMZYY XL", color = Ink.Sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }
        Panel {
            Text("BANTUAN PINTAR", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Tanyakan apa saja tentang launcher. Untuk masalah Minecraft, pilih instance di BOOST lalu gunakan Analisis Log.", color = Ink.Sub, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("BOOT LOOP", false, compact = true) { send("Minecraft saya berhenti di Booting terus. Bantu jelaskan penyebab yang perlu diperiksa dan langkah aman.") }
                Chip("LAYAR MAGENTA", false, compact = true) { send("Minecraft menampilkan layar magenta/ungu. Bantu diagnosis renderer, shader, resource pack, dan konflik mod secara bertahap.") }
            }
            InkButton("ANALISIS LOG INSTANCE", { analyseInstance() }, enabled = !state.lunaBusy && state.meta != null, filled = false)
            Text("Analisis log hanya berjalan setelah tombol ini ditekan. Pertanyaan dan ringkasan log yang dipilih akan diproses layanan AI; jangan kirim password, token, atau data pribadi. Tidak ada log yang dikirim otomatis.", color = Ink.Sub, fontSize = 10.sp)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                val isAssistant = message.role == "assistant"
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (isAssistant) Ink.Raised else Ink.Surface, RoundedCornerShape(16.dp))
                        .border(1.dp, Ink.Line, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(if (isAssistant) "LUNA SIXTY" else "KAMU", color = if (isAssistant) Ink.White else Ink.Sub, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(message.text, color = Ink.Text, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
        if (state.lunaStatus.isNotBlank()) Text(state.lunaStatus, color = Ink.Sub, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f)
                    .background(Ink.Raised, RoundedCornerShape(14.dp))
                    .border(1.dp, Ink.Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 13.dp, vertical = 12.dp),
            ) {
                if (state.lunaDraft.isBlank()) Text("Tanya Luna Sixty…", color = Ink.Sub, fontSize = 13.sp)
                BasicTextField(
                    value = state.lunaDraft,
                    onValueChange = { state.lunaDraft = it.take(1_500) },
                    singleLine = true,
                    enabled = !state.lunaBusy,
                    textStyle = TextStyle(color = Ink.Text, fontSize = 13.sp, fontFamily = ImzyyFonts.family),
                    cursorBrush = SolidColor(Ink.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.weight(0.42f)) {
                InkButton(if (state.lunaBusy) "…" else "KIRIM", { send(state.lunaDraft) }, enabled = !state.lunaBusy && state.lunaDraft.isNotBlank())
            }
        }
    }
}
