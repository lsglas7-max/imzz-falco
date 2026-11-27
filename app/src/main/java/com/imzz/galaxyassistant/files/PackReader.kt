package com.imzz.galaxyassistant.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Membuka ZIP (juga APK/JAR/DOCX, yang pada dasarnya zip) atau file teks biasa langsung dari dalam app,
 * TANPA mengekstrak ke penyimpanan: isi dibaca dari memori per potongan lalu dikirim ke Skylen.
 * - Aman dari zip-slip karena tidak ada file yang ditulis dari nama entri.
 * - Batas ukuran salinan 150 MB dan batas baca per potongan, jadi zip bomb tidak bisa menghabiskan memori.
 * - ZIP berpassword tidak ditembus: dilaporkan saja.
 */
object PackReader {
    data class Item(val name: String, val size: Long)

    class Session(
        val label: String,
        val file: File,
        val isZip: Boolean,
        val items: List<Item>,
        val listTruncated: Boolean
    )

    sealed class ReadResult {
        data class Text(val name: String, val offset: Long, val text: String, val more: Boolean, val nextOffset: Long) : ReadResult()
        data class Ambiguous(val matches: List<String>) : ReadResult()
        data class Fail(val reason: String) : ReadResult()
    }

    @Volatile var session: Session? = null
        private set

    private var lastName: String? = null
    private var lastNext: Long = 0L

    private const val MAX_COPY = 150L * 1024 * 1024
    private const val MAX_ITEMS = 3000
    const val CHUNK_CHARS = 2200

    private val priorityNames = listOf(
        "readme", "package.json", "build.gradle.kts", "build.gradle", "settings.gradle", "androidmanifest.xml",
        "pom.xml", "requirements.txt", "pyproject.toml", "go.mod", "cargo.toml", "index.html", "main.kt",
        "mainactivity.kt", "main.py", "app.js", "index.js", "server.js", "vercel.json", "dockerfile"
    )

    // ------------------------------------------------------------------ buka

    fun open(context: Context, uri: Uri): Session {
        val resolver = context.contentResolver
        val label = displayName(context, uri) ?: "file"
        val target = File(context.cacheDir, "skylen_pack.bin")
        if (target.exists()) target.delete()

        var copied = 0L
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    copied += n
                    if (copied > MAX_COPY) {
                        target.delete()
                        error("File lebih dari 150 MB, terlalu besar untuk dibaca di HP.")
                    }
                    out.write(buffer, 0, n)
                }
            }
        } ?: error("File tidak bisa dibuka.")

        val zip = looksLikeZip(target)
        val items: List<Item>
        var truncated = false
        if (zip) {
            val list = ArrayList<Item>()
            try {
                ZipFile(target).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if (list.size >= MAX_ITEMS) { truncated = true; break }
                        list.add(Item(entry.name, entry.size))
                    }
                }
            } catch (e: Exception) {
                error("ZIP rusak atau tidak bisa dibuka: ${e.message?.take(120)}")
            }
            items = list.sortedWith(compareBy({ it.name.count { c -> c == '/' } }, { it.name.lowercase(Locale.ROOT) }))
        } else {
            items = listOf(Item(label, copied))
        }
        return Session(label, target, zip, items, truncated).also {
            session = it
            lastName = null
            lastNext = 0L
        }
    }

    private fun looksLikeZip(file: File): Boolean {
        val head = ByteArray(4)
        val n = file.inputStream().use { it.read(head) }
        return n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4
    }

    private fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    // ------------------------------------------------------------------ baca

    /** Baca satu file di dalam sesi. [query] = nama persis, akhiran path, atau potongan nama. */
    fun read(query: String, fromOffset: Long = 0L, maxChars: Int = CHUNK_CHARS): ReadResult {
        val s = session ?: return ReadResult.Fail("Belum ada ZIP/file yang dibuka. Tekan tombol BUKA ZIP dulu.")
        val q = query.trim().trim('"', '\'').lowercase(Locale.ROOT)
        if (q.isEmpty()) return ReadResult.Fail("Sebut nama file yang mau dibaca.")

        val exact = s.items.filter { it.name.lowercase(Locale.ROOT) == q }
        val suffix = s.items.filter { it.name.lowercase(Locale.ROOT).endsWith("/$q") }
        val partial = s.items.filter { it.name.lowercase(Locale.ROOT).contains(q) }
        val matches = when {
            exact.isNotEmpty() -> exact
            suffix.isNotEmpty() -> suffix
            else -> partial
        }
        if (matches.isEmpty()) return ReadResult.Fail("Nggak ada file yang namanya mengandung \"$query\".")
        if (matches.size > 1) return ReadResult.Ambiguous(matches.take(40).map { it.name })

        val item = matches.first()
        return try {
            openStream(s, item).use { stream ->
                skipFully(stream, fromOffset)
                val buffer = ByteArray(maxChars * 3)
                var total = 0
                while (total < buffer.size) {
                    val n = stream.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                if (total == 0 && fromOffset > 0) return ReadResult.Fail("Sudah habis, nggak ada bagian lanjutan.")
                if (buffer.take(minOf(total, 4000)).any { it.toInt() == 0 }) {
                    return ReadResult.Fail("${item.name} itu file biner (gambar/program/data), nggak bisa dibaca sebagai teks.")
                }
                val decoded = String(buffer, 0, total, Charsets.UTF_8)
                val text = decoded.take(maxChars)
                val consumed = text.toByteArray(Charsets.UTF_8).size.toLong()
                val next = fromOffset + consumed
                val more = (item.size < 0 && decoded.length > maxChars) || (item.size >= 0 && next < item.size)
                lastName = item.name
                lastNext = next
                ReadResult.Text(item.name, fromOffset, text, more, next)
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("encrypted", true) || msg.contains("password", true)) {
                ReadResult.Fail("${item.name} dilindungi password. Gw nggak bisa buka itu.")
            } else ReadResult.Fail("Gagal baca ${item.name}: ${msg.take(100)}")
        }
    }

    /** Bagian berikutnya dari file yang terakhir dibaca. */
    fun readNext(): ReadResult {
        val name = lastName ?: return ReadResult.Fail("Belum ada file yang lagi dibaca.")
        return read(name, lastNext)
    }

    private fun openStream(s: Session, item: Item): InputStream {
        if (!s.isZip) return s.file.inputStream()
        val zf = ZipFile(s.file)
        val entry = zf.getEntry(item.name) ?: run { zf.close(); error("Entri hilang dari ZIP.") }
        val inner = zf.getInputStream(entry)
        return object : InputStream() {
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
            override fun close() {
                runCatching { inner.close() }
                runCatching { zf.close() }
            }
        }
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var left = bytes
        val sink = ByteArray(32 * 1024)
        while (left > 0) {
            val n = stream.read(sink, 0, minOf(left, sink.size.toLong()).toInt())
            if (n < 0) break
            left -= n
        }
    }

    // ------------------------------------------------------------------ ringkasan untuk Skylen

    /** Daftar lengkap (untuk dialog "daftar file"). */
    fun fullListing(): String {
        val s = session ?: return "Belum ada ZIP/file yang dibuka."
        return buildString {
            append(s.label).append(" — ").append(s.items.size).append(" file")
            if (s.listTruncated) append(" (daftar dipotong di $MAX_ITEMS)")
            append("\n\n")
            s.items.forEach { append(it.name).append("  (").append(sizeText(it.size)).append(")\n") }
        }
    }

    /**
     * Susun pesan analisis untuk Skylen. Panjang dibatasi ±2900 huruf karena endpoint "?q=" membawa
     * semuanya lewat URL. Mengembalikan (tampilan singkat untuk log, pesan lengkap).
     */
    fun buildAnalysisMessage(): Pair<String, String> {
        val s = session ?: return "" to ""
        val display = "analisis ${s.label}"
        val msg = StringBuilder()
        msg.append("Tolong baca ini. ")
        msg.append(if (s.isZip) "Isi ZIP \"${s.label}\" (${s.items.size} file)." else "File \"${s.label}\".")
        msg.append(" Ringkas ini apa, strukturnya, dan sebutkan masalah atau saran perbaikan kalau ada.\n")

        if (s.isZip) {
            val stats = s.items.groupingBy { extOf(it.name) }.eachCount().entries
                .sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key}:${it.value}" }
            msg.append("Tipe file: ").append(stats).append('\n')
            msg.append("Struktur:\n")
            var used = 0
            var shown = 0
            for (item in s.items) {
                val line = "- ${item.name.take(70)} (${sizeText(item.size)})\n"
                if (used + line.length > 1000) break
                msg.append(line); used += line.length; shown++
            }
            if (shown < s.items.size) msg.append("(+${s.items.size - shown} file lain; minta gw 'baca <nama file>' untuk lihat isinya)\n")
        }

        // cuplikan file penting
        var budget = 2900 - msg.length
        val picks = if (s.isZip) pickImportant(s) else s.items
        for (item in picks) {
            if (budget < 350) break
            val r = read(item.name, 0L, minOf(700, budget - 120))
            if (r is ReadResult.Text && r.text.isNotBlank()) {
                val block = "\n[${item.name}]\n${r.text.trim()}\n"
                msg.append(block)
                budget -= block.length
            }
        }
        // jangan biarkan sesi "baca lanjut" menunjuk ke file cuplikan terakhir
        lastName = null
        lastNext = 0L
        return display to msg.toString().take(3000)
    }

    private fun pickImportant(s: Session): List<Item> {
        val chosen = ArrayList<Item>()
        for (p in priorityNames) {
            val hit = s.items.firstOrNull {
                val base = it.name.substringAfterLast('/').lowercase(Locale.ROOT)
                base == p || (p == "readme" && base.startsWith("readme"))
            }
            if (hit != null && hit !in chosen && hit.size != 0L) chosen.add(hit)
            if (chosen.size >= 5) break
        }
        return chosen
    }

    private fun extOf(name: String): String {
        val base = name.substringAfterLast('/')
        return if (base.contains('.')) "." + base.substringAfterLast('.').lowercase(Locale.ROOT) else "(tanpa ekstensi)"
    }

    private fun sizeText(size: Long): String = when {
        size < 0 -> "?"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", size / 1048576.0)
    }
}
