package id.imzyy.xl

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

class HttpFailure(val code: Int, url: String) : Exception("HTTP $code untuk $url")

/** Small dependency-free HTTPS helper. HTTPS only, hash-verified downloads. */
object Net {
    private const val UA = "IMZYY-XL-Launcher/" + ImzyyConfig.VERSION

    fun fmtBytes(b: Long): String = when {
        b >= 1_073_741_824L -> "%.2f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024L -> "${b / 1024} KB"
        else -> "$b B"
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "Hanya HTTPS yang diizinkan: $url" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 75_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    fun getText(url: String): String {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpFailure(code, url)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Opens [url] (HTTPS, redirects followed) and hands the stream to [block]. */
    fun stream(url: String, block: (java.io.InputStream) -> Unit) {
        val c = open(url)
        c.readTimeout = 60_000
        try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpFailure(code, url)
            c.inputStream.use(block)
        } finally {
            c.disconnect()
        }
    }

    /** Downloads with resumable HTTP Range, retry/backoff and hash verification. */
    fun download(url: String, dest: File, sha512: String?, sha1: String?, onBytes: (Long) -> Unit = {}) {
        require(sha512 != null || sha1 != null) { "File tanpa hash ditolak: ${dest.name}" }
        downloadVerified(url, dest, if (sha512 != null) "SHA-512" else "SHA-1", (sha512 ?: sha1)!!.lowercase(), onBytes)
    }

    /** SHA-256 variant used for downloadable Java runtimes. */
    fun downloadSha256(url: String, dest: File, sha256: String, onBytes: (Long) -> Unit = {}) {
        downloadVerified(url, dest, "SHA-256", sha256.lowercase(), onBytes)
    }

    private fun downloadVerified(url: String, dest: File, algorithm: String, expected: String, onBytes: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var last: Exception? = null
        for (attempt in 1..4) {
            var c: HttpURLConnection? = null
            try {
                val resumeAt = if (part.isFile) part.length() else 0L
                c = open(url)
                c.connectTimeout = 20_000
                c.readTimeout = 75_000
                if (resumeAt > 0L) c.setRequestProperty("Range", "bytes=$resumeAt-")
                val code = c.responseCode
                if (code == 416 && resumeAt > 0L) {
                    if (verify(part, algorithm, expected)) {
                        promote(part, dest)
                        return
                    }
                    part.delete()
                    throw java.io.IOException("Range tidak valid; mengulang dari awal")
                }
                if (code !in 200..299) throw HttpFailure(code, url)
                val append = code == 206 && resumeAt > 0L
                if (code == 206 && resumeAt == 0L) throw java.io.IOException("Server mengirim partial response tanpa Range")
                if (append) {
                    val range = c.getHeaderField("Content-Range") ?: ""
                    if (!range.startsWith("bytes $resumeAt-")) {
                        part.delete()
                        throw java.io.IOException("Content-Range tidak cocok; unduhan diulang dari awal")
                    }
                }
                if (!append && part.exists()) part.delete()

                val md = MessageDigest.getInstance(algorithm)
                if (append) part.inputStream().use { existing ->
                    val buf = ByteArray(128 * 1024)
                    while (true) { val n = existing.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                    onBytes(resumeAt)
                }
                c.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            onBytes(n.toLong())
                        }
                        out.fd.sync()
                    }
                }
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got != expected) {
                    part.delete()
                    throw java.io.IOException("Hash ${dest.name} tidak cocok; unduhan diulang")
                }
                promote(part, dest)
                return
            } catch (e: Exception) {
                last = e
                // Keep partial bytes for resumable retry; hash mismatch explicitly clears them above.
                if (attempt < 4) try { Thread.sleep(700L * attempt) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            } finally {
                c?.disconnect()
            }
        }
        throw java.io.IOException("Gagal mengunduh ${dest.name} setelah 4 percobaan: ${last?.message}", last)
    }

    private fun verify(file: File, algorithm: String, expected: String): Boolean {
        if (!file.isFile) return false
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) } == expected
    }

    private fun promote(part: File, dest: File) {
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) part.copyTo(dest, overwrite = true)
        if (part.exists()) part.delete()
    }

}

/**
 * Verifies the Minecraft asset objects (sounds, language files, the Mojang logo...) of one instance and
 * re-downloads whatever is missing or truncated. Upstream downloads assets once at install time and
 * swallows every error, so a single dropped connection leaves a permanent hole that later crashes the
 * game with NoSuchFileException while the loading overlay renders. This runs before every PLAY.
 */
object AssetRepair {
    private const val HOST = "https://resources.download.minecraft.net/"
    private const val THREADS = 8
    private const val ATTEMPTS = 3

    class Report(val total: Int, val bad: Int, val fixed: Int, val failed: Int, val error: String?, val note: String?)

    private class Obj(val hash: String, val size: Long)

    /** Same lookup order as the upstream game activity: first root whose assets/indexes is not empty. */
    fun assetsDir(ctx: Context, instanceDir: File): File {
        val ext = ctx.getExternalFilesDir(null)
        val roots = listOfNotNull(instanceDir, ext, ctx.filesDir).distinct()
        for (r in roots) {
            val a = File(r, "assets")
            if (File(a, "indexes").listFiles()?.isNotEmpty() == true) return a
        }
        return File(ext ?: ctx.filesDir, "assets")
    }

    private fun indexFile(assets: File, indexId: String): File? {
        val dir = File(assets, "indexes")
        if (indexId.isNotEmpty()) {
            val f = File(dir, "$indexId.json")
            if (f.isFile && f.length() > 0) return f
        }
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") && it.length() > 0 }?.maxByOrNull { it.lastModified() }
    }

    private fun readObjects(index: File): List<Obj> {
        val objects = JSONObject(index.readText()).getJSONObject("objects")
        val out = ArrayList<Obj>(objects.length())
        val seen = HashSet<String>()
        val keys = objects.keys()
        while (keys.hasNext()) {
            val o = objects.getJSONObject(keys.next())
            val h = o.getString("hash")
            if (h.length >= 2 && seen.add(h)) out.add(Obj(h, o.optLong("size", -1L)))
        }
        return out
    }

    private fun isBad(objectsDir: File, o: Obj): Boolean {
        val f = File(objectsDir, o.hash.substring(0, 2) + "/" + o.hash)
        if (!f.isFile) return true
        val len = f.length()
        return len == 0L && o.size != 0L || (o.size >= 0 && len != o.size)
    }

    /**
     * Blocking; call from a worker thread. Returns a report; never throws for download problems.
     * [onProgress] gets 0..1 while downloading.
     */
    fun repair(ctx: Context, instanceDir: File, indexId: String, onProgress: (Float) -> Unit = {}): Report {
        val assets = assetsDir(ctx, instanceDir)
        val index = indexFile(assets, indexId)
            ?: return Report(0, 0, 0, 0, null, "Index asset tidak ada di ${assets.path}; buat ulang instance di launcher")
        val objectsDir = File(assets, "objects")
        val all = try {
            readObjects(index)
        } catch (e: Exception) {
            return Report(0, 0, 0, 0, null, "Index asset ${index.name} rusak (${e.message}); hapus instance lalu buat lagi")
        }
        val bad = all.filter { isBad(objectsDir, it) }
        if (bad.isEmpty()) return Report(all.size, 0, 0, 0, null, null)

        val done = AtomicInteger(0)
        val fixed = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val jobs = bad.map { o ->
                Callable {
                    val dest = File(objectsDir, o.hash.substring(0, 2) + "/" + o.hash)
                    if (dest.exists()) dest.delete()
                    var ok = false
                    var attempt = 1
                    while (attempt <= ATTEMPTS && !ok) {
                        try {
                            Net.download(HOST + o.hash.substring(0, 2) + "/" + o.hash, dest, null, o.hash)
                            ok = dest.isFile && dest.length() > 0L || o.size == 0L
                        } catch (e: Exception) {
                            firstError.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                            try { Thread.sleep(400L * attempt) } catch (_: InterruptedException) {}
                        }
                        attempt++
                    }
                    if (ok) fixed.incrementAndGet()
                    onProgress(done.incrementAndGet().toFloat() / bad.size)
                    ok
                }
            }
            pool.invokeAll(jobs)
        } finally {
            pool.shutdown()
        }
        val f = fixed.get()
        return Report(all.size, bad.size, f, bad.size - f, firstError.get(), null)
    }
}
