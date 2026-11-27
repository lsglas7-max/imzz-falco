package id.imzyy.xl

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class MrFile(
    val path: String,
    val urls: List<String>,
    val sha512: String?,
    val sha1: String?,
    val size: Long,
    val clientUnsupported: Boolean,
)

data class MrInfo(
    val name: String,
    val version: String,
    val summary: String,
    val deps: Map<String, String>,
    val files: List<MrFile>,
)

/** Modrinth .mrpack (format v1) reader and installer. */
object Mrpack {
    private val allowedHosts = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")

    private fun readLimited(zin: ZipInputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = zin.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) error("modrinth.index.json terlalu besar")
        }
        return out.toByteArray()
    }

    fun readIndex(ctx: Context, uri: Uri): MrInfo {
        val raw = ctx.contentResolver.openInputStream(uri) ?: error("Tidak bisa membuka file")
        raw.use { stream ->
            ZipInputStream(stream).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (e.name == "modrinth.index.json") {
                        val json = JSONObject(String(readLimited(zin, 8_000_000), Charsets.UTF_8))
                        if (json.optString("game") != "minecraft") error("Bukan modpack Minecraft")
                        if (json.optInt("formatVersion") != 1) error("formatVersion ${json.optInt("formatVersion")} tidak didukung")
                        val depsJson = json.optJSONObject("dependencies") ?: JSONObject()
                        val deps = LinkedHashMap<String, String>()
                        depsJson.keys().forEach { k -> deps[k] = depsJson.getString(k) }
                        val arr = json.getJSONArray("files")
                        val files = ArrayList<MrFile>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            val urls = ArrayList<String>()
                            val ua = o.getJSONArray("downloads")
                            for (j in 0 until ua.length()) urls.add(ua.getString(j))
                            val h = o.optJSONObject("hashes")
                            files.add(
                                MrFile(
                                    path = o.getString("path"),
                                    urls = urls,
                                    sha512 = h?.optString("sha512")?.ifEmpty { null },
                                    sha1 = h?.optString("sha1")?.ifEmpty { null },
                                    size = o.optLong("fileSize"),
                                    clientUnsupported = o.optJSONObject("env")?.optString("client") == "unsupported",
                                ),
                            )
                        }
                        return MrInfo(json.optString("name", "Modpack"), json.optString("versionId"), json.optString("summary"), deps, files)
                    }
                    e = zin.nextEntry
                }
            }
        }
        error("modrinth.index.json tidak ada: ini bukan file .mrpack yang valid")
    }

    private fun safeFile(root: File, rel: String): File {
        require(rel.isNotEmpty() && !rel.startsWith("/") && !rel.contains("\\")) { "Path tidak aman: $rel" }
        val f = File(root, rel).canonicalFile
        require(f.path.startsWith(root.path + File.separator)) { "Path keluar dari folder instance: $rel" }
        return f
    }

    private fun hostOk(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = url.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        return host in allowedHosts
    }

    const val PENDING = ".imzyy-pending"

    /** Player settings a modpack must not overwrite (its options.txt would reset language, keybinds, graphics...). */
    val KEEP = setOf("options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat")

    /**
     * Moves a staged modpack (see in-game MODS) into the instance. Only call while the game process is NOT running:
     * replacing mod jars under a live JVM crashes it. Returns the number of files applied.
     */
    private fun moveDirectory(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        if (source.renameTo(destination)) return
        source.copyRecursively(destination, overwrite = true)
        source.deleteRecursively()
    }

    /**
     * Applies a completed staged pack only after the game process has exited. The previous mods folder is
     * backed up first, then replaced as a unit so old jar versions cannot remain beside new pack versions.
     * On failure, the previous mods folder is restored and the pending directory is retained for retry.
     */
    fun applyPending(instance: File): Int {
        val src = File(instance, PENDING)
        if (!src.isDirectory || !File(src, ".imzyy-ready").isFile) return 0
        val instanceRoot = instance.canonicalFile
        val stageRoot = src.canonicalFile
        require(stageRoot.path.startsWith(instanceRoot.path + File.separator)) { "Folder pending tidak valid" }

        // Apply configs/resource overrides first. Keep live mods untouched if one of these copies fails.
        var n = 0
        src.walkTopDown().filter { it.isFile }.toList().forEach { f ->
            val rel = f.relativeTo(src).invariantSeparatorsPath
            if (rel == ".imzyy-ready" || rel == "mods" || rel.startsWith("mods/")) return@forEach
            val dest = File(instanceRoot, rel).canonicalFile
            require(dest.path.startsWith(instanceRoot.path + File.separator)) { "Path pending keluar dari instance: $rel" }
            if (rel in KEEP && dest.exists()) {
                f.delete()
                return@forEach
            }
            dest.parentFile?.mkdirs()
            if (dest.exists() && !dest.delete()) error("Tidak bisa mengganti ${dest.name}")
            if (!f.renameTo(dest)) {
                f.copyTo(dest, overwrite = true)
                f.delete()
            }
            n++
        }

        // Replace mods as one directory only after every staged file has passed the checks above.
        val stagedMods = File(src, "mods")
        val liveMods = File(instance, "mods")
        val backupRoot = File(instance, ".imzyy-backups")
        var backupMods: File? = null
        if (stagedMods.isDirectory) {
            if (liveMods.exists()) {
                backupRoot.mkdirs()
                backupMods = File(backupRoot, "mods-${System.currentTimeMillis()}")
                moveDirectory(liveMods, backupMods!!)
            }
            try {
                moveDirectory(stagedMods, liveMods)
                n += liveMods.walkTopDown().count { it.isFile }
            } catch (e: Exception) {
                liveMods.deleteRecursively()
                if (backupMods?.exists() == true) moveDirectory(backupMods!!, liveMods)
                throw IllegalStateException("Gagal mengganti folder mods; cadangan lama dipulihkan: ${e.message}", e)
            }
        }

        src.deleteRecursively()
        // Keep the two newest safety backups to avoid unbounded storage growth.
        backupRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith("mods-") }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.deleteRecursively() }
        return n
    }

    fun install(ctx: Context, uri: Uri, info: MrInfo, target: File, say: (String) -> Unit, onProgress: (Float) -> Unit) {
        val isPendingStage = target.name == PENDING
        if (isPendingStage && target.exists()) target.deleteRecursively()
        target.mkdirs()
        val root = target.canonicalFile
        val todo = info.files.filter { !it.clientUnsupported }
        say("Mengunduh ${todo.size} file, total ${Net.fmtBytes(todo.sumOf { it.size })} (verifikasi hash)…")

        val pool = Executors.newFixedThreadPool(6)
        val done = AtomicInteger(0)
        val failures = ConcurrentLinkedQueue<String>()
        val tasks = todo.map { f ->
            Callable<Unit> {
                try {
                    val dest = safeFile(root, f.path)
                    if (!(dest.exists() && f.size > 0 && dest.length() == f.size)) {
                        val url = f.urls.firstOrNull { hostOk(it) } ?: error("Tidak ada host unduhan yang diizinkan")
                        Net.download(url, dest, f.sha512, f.sha1)
                    }
                } catch (e: Exception) {
                    failures.add("${f.path}: ${e.message}")
                }
                onProgress(done.incrementAndGet().toFloat() / todo.size.coerceAtLeast(1) * 0.9f)
            }
        }
        pool.invokeAll(tasks)
        pool.shutdown()
        failures.take(5).forEach { say("✗ $it") }
        if (failures.isNotEmpty()) error("${failures.size} file gagal diunduh; import dibatalkan sebelum menimpa overrides")

        say("Menyalin overrides…")
        var copied = 0
        val raw = ctx.contentResolver.openInputStream(uri) ?: error("Tidak bisa membuka file")
        raw.use { stream ->
            ZipInputStream(stream).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val n = e.name
                    val rel = when {
                        n.startsWith("overrides/") -> n.removePrefix("overrides/")
                        n.startsWith("client-overrides/") -> n.removePrefix("client-overrides/")
                        else -> null
                    }
                    if (rel != null && !e.isDirectory && rel.isNotEmpty()) {
                        val dest = safeFile(root, rel)
                        if (!(rel in KEEP && dest.exists())) {
                            dest.parentFile?.mkdirs()
                            FileOutputStream(dest).use { out -> zin.copyTo(out) }
                            copied++
                        }
                    }
                    e = zin.nextEntry
                }
            }
        }
        if (isPendingStage) {
            File(target, "mods").mkdirs()
            File(target, ".imzyy-ready").writeText("ready\n")
        }
        onProgress(1f)
        say("✓ Selesai: ${todo.size} file + $copied override")
    }
}
