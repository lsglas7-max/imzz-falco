package id.imzyy.xl

import android.os.Build
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONObject

/** Reversible recovery tools for broken modpacks and graphics settings. */
object RecoveryTools {
    private const val RECOVERY_DIR = ".imzyy-recovery"
    private const val STATE_FILE = "active-safe-boot.json"

    data class SafeBootReport(
        val modsBackedUp: Boolean,
        val pendingPackBackedUp: Boolean,
        val backupDirectory: String,
    )

    data class ModScan(
        val jarCount: Int,
        val warnings: List<String>,
        val ids: List<String>,
    )

    private fun recoveryRoot(instance: File): File = File(instance, RECOVERY_DIR)

    private fun moveSafely(source: File, destination: File) {
        destination.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) error("Tidak bisa membuat folder cadangan: ${parent.path}")
        }
        if (source.renameTo(destination)) return
        source.copyRecursively(destination, overwrite = false)
        val sourceBytes = source.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val destBytes = destination.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        require(sourceBytes == destBytes) { "Verifikasi salinan cadangan gagal (${source.name})" }
        require(source.deleteRecursively()) { "Tidak bisa membersihkan sumber setelah dicadangkan: ${source.path}" }
    }

    /** Moves active mods and any unapplied modpack aside. Nothing is deleted. */
    fun startSafeBoot(instance: File): SafeBootReport {
        require(instance.isDirectory) { "Instance tidak ditemukan" }
        val root = instance.canonicalFile
        val recovery = recoveryRoot(root)
        val state = File(recovery, STATE_FILE)
        require(!state.exists()) { "Safe Boot masih aktif. Pulihkan cadangan dahulu sebelum memulai lagi." }

        val stamp = System.currentTimeMillis()
        val backup = File(recovery, "safe-boot-$stamp")
        if (!backup.mkdirs()) error("Tidak bisa membuat folder Safe Boot")
        var modsMoved = false
        var pendingMoved = false
        try {
            val mods = File(root, "mods")
            if (mods.exists()) {
                moveSafely(mods, File(backup, "mods"))
                modsMoved = true
            }
            val pending = File(root, Mrpack.PENDING)
            if (pending.exists()) {
                moveSafely(pending, File(backup, "pending-pack"))
                pendingMoved = true
            }
            val json = JSONObject()
                .put("directory", backup.name)
                .put("mods", modsMoved)
                .put("pending", pendingMoved)
                .put("created", stamp)
            val tmp = File(recovery, "$STATE_FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(state)) {
                tmp.copyTo(state, overwrite = true)
                tmp.delete()
            }
            return SafeBootReport(modsMoved, pendingMoved, backup.name)
        } catch (e: Exception) {
            // Roll back any completed moves if state creation failed.
            if (modsMoved) runCatching { moveSafely(File(backup, "mods"), File(root, "mods")) }
            if (pendingMoved) runCatching { moveSafely(File(backup, "pending-pack"), File(root, Mrpack.PENDING)) }
            backup.deleteRecursively()
            throw e
        }
    }

    fun safeBootActive(instance: File): Boolean = File(recoveryRoot(instance), STATE_FILE).isFile

    /** Restores the original mods and queued pack. Any files created during Safe Boot are preserved. */
    fun restoreSafeBoot(instance: File): SafeBootReport {
        val root = instance.canonicalFile
        val recovery = recoveryRoot(root)
        val stateFile = File(recovery, STATE_FILE)
        require(stateFile.isFile) { "Tidak ada cadangan Safe Boot untuk dipulihkan." }
        val state = JSONObject(stateFile.readText())
        val name = state.optString("directory")
        require(name.matches(Regex("safe-boot-[0-9]+"))) { "Data cadangan Safe Boot tidak valid" }
        val backup = File(recovery, name).canonicalFile
        require(backup.path.startsWith(recovery.canonicalPath + File.separator) && backup.isDirectory) { "Folder cadangan tidak ditemukan" }

        val hadMods = state.optBoolean("mods")
        val hadPending = state.optBoolean("pending")
        val savedMods = File(backup, "mods")
        val savedPending = File(backup, "pending-pack")
        val currentMods = File(root, "mods")
        val currentPending = File(root, Mrpack.PENDING)
        val keepMods = File(backup, "mods-created-during-safe-boot")
        val keepPending = File(backup, "pending-pack-created-during-safe-boot")
        // Validate every destination before moving anything, so a conflict cannot leave half a restore.
        if (hadMods) {
            require(savedMods.isDirectory) { "Folder mods cadangan tidak ditemukan" }
            require(!currentMods.exists() || !keepMods.exists()) { "Cadangan mod Safe Boot sudah ada; tidak menimpa file yang tersimpan." }
        }
        if (hadPending && savedPending.exists()) {
            require(!currentPending.exists() || !keepPending.exists()) { "Ada dua pending pack; keduanya dipertahankan untuk pemeriksaan manual." }
        }
        if (hadMods) {
            if (currentMods.exists()) moveSafely(currentMods, keepMods)
            moveSafely(savedMods, currentMods)
        }
        if (hadPending && savedPending.exists()) {
            if (currentPending.exists()) moveSafely(currentPending, keepPending)
            moveSafely(savedPending, currentPending)
        }
        stateFile.delete()
        // Keep the backup directory if it contains files created while Safe Boot was active.
        if (backup.listFiles().isNullOrEmpty()) backup.delete()
        return SafeBootReport(hadMods, hadPending, name)
    }

    private fun readEntry(zip: ZipFile, name: String, maxBytes: Int = 128 * 1024): String? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > maxBytes) return null
        return zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                if (out.size() > maxBytes) return@use null
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun modIdentity(file: File): Pair<String, String>? = runCatching {
        ZipFile(file).use { zip ->
            val fabric = readEntry(zip, "fabric.mod.json")
            if (fabric != null) {
                val o = JSONObject(fabric)
                return@use (o.optString("id").ifBlank { file.nameWithoutExtension }) to o.optString("version", "?")
            }
            val quilt = readEntry(zip, "quilt.mod.json")
            if (quilt != null) {
                val loader = JSONObject(quilt).optJSONObject("quilt_loader") ?: JSONObject()
                return@use (loader.optString("id").ifBlank { file.nameWithoutExtension }) to loader.optString("version", "?")
            }
            val toml = readEntry(zip, "META-INF/neoforge.mods.toml") ?: readEntry(zip, "META-INF/mods.toml")
            if (toml != null) {
                val id = Regex("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").find(toml)?.groupValues?.getOrNull(1)
                val version = Regex("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"").find(toml)?.groupValues?.getOrNull(1)
                if (!id.isNullOrBlank()) return@use id to (version ?: "?")
            }
            file.nameWithoutExtension to "?"
        }
    }.getOrNull()

    fun criticalWarnings(scan: ModScan): List<String> = scan.warnings.filter {
        it.startsWith("Mod ID duplikat") ||
            it.startsWith("Sodium dan Embeddium") ||
            it.startsWith("Iris dan Oculus") ||
            it.startsWith("OptiFine bersama")
    }

    fun scanMods(instance: File): ModScan {
        val mods = File(instance, "mods")
        val jars = mods.listFiles()?.filter { it.isFile && it.extension.equals("jar", true) }.orEmpty()
        val identities = jars.mapNotNull { jar -> modIdentity(jar)?.let { Triple(it.first.lowercase(Locale.ROOT), it.second, jar.name) } }
        val warnings = ArrayList<String>()
        identities.groupBy { it.first }.filterValues { it.size > 1 }.forEach { (id, entries) ->
            warnings.add("Mod ID duplikat '$id': ${entries.joinToString { it.third }}")
        }
        val ids = identities.map { it.first }.toSet()
        fun pair(a: String, b: String, note: String) {
            if (a in ids && b in ids) warnings.add(note)
        }
        pair("sodium", "embeddium", "Sodium dan Embeddium terpasang bersamaan; pilih salah satu renderer mod.")
        pair("iris", "oculus", "Iris dan Oculus terpasang bersamaan; keduanya tidak boleh dipakai sekaligus.")
        if ("optifine" in ids && ("sodium" in ids || "embeddium" in ids)) {
            warnings.add("OptiFine bersama Sodium/Embeddium berpotensi konflik.")
        }
        if ("voxy" in ids && "sodium" !in ids) warnings.add("Voxy terdeteksi tanpa Sodium; cek dependency dan versi modpack.")
        if (warnings.isEmpty()) warnings.add("Tidak ditemukan duplikasi ID atau konflik renderer umum dari metadata JAR.")
        return ModScan(jars.size, warnings, ids.sorted())
    }

    /** Writes a compact, redacted crash/render report inside the instance logs folder. */
    fun writeDiagnostics(instance: File): File {
        require(instance.isDirectory) { "Instance tidak ditemukan" }
        val scan = scanMods(instance)
        val logsDir = File(instance, "logs")
        if (!logsDir.isDirectory && !logsDir.mkdirs()) error("Folder logs tidak bisa dibuat")
        val latest = File(logsDir, "latest.log")
        val interesting = if (latest.isFile) latest.useLines { lines ->
            lines.filter { line ->
                val s = line.lowercase(Locale.ROOT)
                listOf("error", "exception", "caused by", "mixin", "failed", "opengl", "renderer", "sodium", "iris", "fabricloader", "modresolution").any { it in s }
            }.toList().takeLast(120).map { line ->
                line.replace(Regex("(?i)(access_token|refresh_token|client_secret)(\\s*[=:]\\s*)[^\\s,]+"), "\$1\$2[REDACTED]")
            }
        } else emptyList()
        val crashes = File(instance, "crash-reports").listFiles()?.filter { it.isFile && it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }?.take(2).orEmpty()
        val report = buildString {
            appendLine("IMZYY XL DIAGNOSTICS")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Instance: ${instance.name}")
            appendLine("Mod JAR count: ${scan.jarCount}")
            appendLine("Mod IDs: ${scan.ids.joinToString()}")
            appendLine("Warnings:")
            scan.warnings.forEach { appendLine("- $it") }
            appendLine("\nGraphics settings:")
            val options = File(instance, "options.txt")
            if (options.isFile) {
                options.useLines { lines ->
                    lines.filter { line ->
                        val key = line.substringBefore(':').lowercase(Locale.ROOT)
                        key in setOf("graphics", "renderdistance", "simulationdistance", "resourcepacks", "incompatibleresourcepacks", "clouds", "ao", "maxfps", "vsync", "biomeblendradius", "fullscreen")
                    }.take(30).forEach { appendLine(it.take(500)) }
                }
            } else appendLine("options.txt missing")
            listOf("config/iris.properties", "config/sodium-options.json", "optionsof.txt").forEach { relative ->
                val config = File(instance, relative)
                if (config.isFile) {
                    appendLine("--- $relative ---")
                    config.useLines { lines -> lines.take(35).forEach { line ->
                        appendLine(line.replace(Regex("(?i)(access_token|refresh_token|client_secret|api[_-]?key)(\\s*[=:]\\s*)[^\\s,]+"), "\$1\$2[REDACTED]").take(500))
                    } }
                }
            }
            val resourcepacks = File(instance, "resourcepacks").listFiles()?.filter { it.name.endsWith(".zip", true) || it.isDirectory }?.take(30).orEmpty()
            appendLine("Resource packs on disk: ${resourcepacks.joinToString { it.name }}")
            val shaderpacks = File(instance, "shaderpacks").listFiles()?.filter { it.name.endsWith(".zip", true) || it.isDirectory }?.take(30).orEmpty()
            appendLine("Shader packs on disk: ${shaderpacks.joinToString { it.name }}")
            appendLine("\nRelevant latest.log lines:")
            interesting.forEach { appendLine(it.take(700)) }
            appendLine("\nCrash reports:")
            crashes.forEach { crash ->
                appendLine("--- ${crash.name} ---")
                runCatching { crash.useLines { seq -> seq.toList().takeLast(80) } }.getOrDefault(emptyList()).forEach { line ->
                    appendLine(line.replace(Regex("(?i)(access_token|refresh_token|client_secret)(\\s*[=:]\\s*)[^\\s,]+"), "\$1\$2[REDACTED]").take(700))
                }
            }
        }
        val out = File(logsDir, "imzyy-diagnostics.txt")
        out.writeText(report)
        return out
    }
}

/** Detects an uncleanly terminated Minecraft activity without guessing from a loading dialog. */
object ImzyyBootGuard {
    private const val ACTIVE_FILE = ".imzyy-game-active.json"
    private const val FAIL_FILE = ".imzyy-game-start-failures"
    private const val FAILURE_WINDOW_MS = 10 * 60 * 1000L

    private fun marker(ctx: android.content.Context) = File(ctx.filesDir, ACTIVE_FILE)
    private fun failureFile(ctx: android.content.Context) = File(ctx.filesDir, FAIL_FILE)

    @Synchronized
    fun onGameCreated(ctx: android.content.Context) {
        val file = marker(ctx)
        val now = System.currentTimeMillis()
        val old = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (old != null) {
            val oldPid = old.optInt("pid", -1)
            val oldAt = old.optLong("started", 0L)
            if (oldPid != android.os.Process.myPid() && now - oldAt in 0..FAILURE_WINDOW_MS) {
                val count = runCatching { failureFile(ctx).readText().trim().toInt() }.getOrDefault(0)
                failureFile(ctx).writeText((count + 1).coerceAtMost(9).toString())
            }
        }
        file.writeText(JSONObject().put("pid", android.os.Process.myPid()).put("started", now).toString())
    }

    @Synchronized
    fun onGameDestroyed(ctx: android.content.Context) {
        val file = marker(ctx)
        val current = runCatching { JSONObject(file.readText()).optInt("pid", -1) }.getOrDefault(-1)
        if (current == android.os.Process.myPid()) {
            file.delete()
            failureFile(ctx).writeText("0")
        }
    }

    fun recentFailures(ctx: android.content.Context): Int =
        runCatching { failureFile(ctx).readText().trim().toInt().coerceIn(0, 9) }.getOrDefault(0)

    fun gameProcessAlive(ctx: android.content.Context): Boolean {
        val recordedPid = runCatching { JSONObject(marker(ctx).readText()).optInt("pid", -1) }.getOrDefault(-1)
        if (recordedPid > 0 && File("/proc/$recordedPid").exists()) return true
        val manager = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return manager?.runningAppProcesses.orEmpty().any {
            it.processName == "${ctx.packageName}:minecraft" || it.processName.endsWith(":minecraft")
        }
    }
}
