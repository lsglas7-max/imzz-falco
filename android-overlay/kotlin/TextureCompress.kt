package id.imzyy.xl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Texture compression: shrinks PNG textures (default /2 per side = about 4x less texture memory)
 * inside resource packs, or builds a small override pack from the vanilla client jar.
 * Animated textures (.png.mcmeta) are never touched so animations keep working.
 */
object TextureCompress {
    const val SUFFIX = "-imzyy-lite.zip"
    const val VANILLA_PACK = "IMZYY-Vanilla-Lite" + SUFFIX

    /** minimum texture side (px) that gets shrunk, per level: light, medium, aggressive. */
    val MIN_SIDE = intArrayOf(64, 32, 16)
    val LEVEL_NAMES = listOf("RINGAN", "SEDANG", "AGRESIF")

    class Result(val changed: Int, val before: Long, val after: Long)

    private fun shrink(bytes: ByteArray, minSide: Int): ByteArray? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w < minSide || h < minSide || w < 2 || h < 2) return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val small = Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        val res = out.toByteArray()
        return if (res.size < bytes.size) res else null
    }

    private fun safe(name: String) = !name.contains("..") && !name.startsWith("/")

    /** Re-packs [src] into [dest] with big PNGs halved. Other entries are copied unchanged. */
    fun compressPack(src: File, dest: File, minSide: Int, onProgress: (Float) -> Unit): Result {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var changed = 0
        try {
            ZipFile(src).use { zf ->
                val entries = Collections.list(zf.entries())
                val names = HashSet<String>()
                entries.forEach { names.add(it.name) }
                ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                    var i = 0
                    for (e in entries) {
                        i++
                        onProgress(i.toFloat() / entries.size.coerceAtLeast(1))
                        if (e.isDirectory || !safe(e.name)) continue
                        var data = zf.getInputStream(e).use { it.readBytes() }
                        val animated = names.contains(e.name + ".mcmeta")
                        if (e.name.endsWith(".png") && !animated && e.name.contains("/textures/")) {
                            val s = try { shrink(data, minSide) } catch (_: Throwable) { null }
                            if (s != null) { data = s; changed++ }
                        }
                        out.putNextEntry(ZipEntry(e.name))
                        out.write(data)
                        out.closeEntry()
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true) }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return Result(changed, src.length(), dest.length())
    }

    fun findClientJar(ctx: Context, instance: File, version: String): File? {
        val roots = listOfNotNull(instance, ctx.filesDir, ctx.getExternalFilesDir(null))
        for (r in roots) {
            val hit = r.walkTopDown().maxDepth(7).firstOrNull { it.isFile && it.name == "$version.jar" }
            if (hit != null) return hit
        }
        return null
    }

    private fun packFormat(zf: ZipFile): Int {
        try {
            val e = zf.getEntry("version.json") ?: return 46
            val o = JSONObject(zf.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) })
            val pv = o.opt("pack_version")
            if (pv is Int) return pv
            if (pv is JSONObject) {
                if (pv.has("resource_major")) return pv.getInt("resource_major")
                if (pv.has("resource")) return pv.getInt("resource")
            }
        } catch (_: Exception) {
        }
        return 46
    }

    /** Builds a tiny override pack from the vanilla jar (only textures that are actually shrunk). */
    fun buildVanillaPack(jar: File, dest: File, minSide: Int, onProgress: (Float) -> Unit): Result {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var changed = 0
        var before = 0L
        try {
            ZipFile(jar).use { zf ->
                val entries = Collections.list(zf.entries())
                val names = HashSet<String>()
                entries.forEach { names.add(it.name) }
                val fmt = packFormat(zf)
                ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.putNextEntry(ZipEntry("pack.mcmeta"))
                    val meta = "{\"pack\":{\"pack_format\":" + fmt + ",\"description\":\"IMZYY XL Lite textures\"}}"
                    out.write(meta.toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                    var i = 0
                    for (e in entries) {
                        i++
                        if (i % 50 == 0) onProgress(i.toFloat() / entries.size.coerceAtLeast(1))
                        val n = e.name
                        if (e.isDirectory || !n.startsWith("assets/minecraft/textures/") || !n.endsWith(".png")) continue
                        if (names.contains(n + ".mcmeta") || !safe(n)) continue
                        val data = zf.getInputStream(e).use { it.readBytes() }
                        val s: ByteArray? = try { shrink(data, minSide) } catch (_: Throwable) { null }
                        if (s == null) continue
                        before += data.size
                        out.putNextEntry(ZipEntry(n))
                        out.write(s)
                        out.closeEntry()
                        changed++
                    }
                }
            }
            if (changed == 0) throw IllegalStateException("Tidak ada tekstur yang cukup besar pada level ini; coba level lebih agresif")
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true) }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return Result(changed, before, dest.length())
    }

    const val QUIET_PACK = "IMZYY-Quiet.zip"

    /**
     * Sodium (0.5.x) prints a red "PojavLauncher is not supported" console message on every start because it sees
     * the POJAV_* environment this engine needs. The game still runs; this tiny lang-only pack blanks that one
     * message. Returns true when the pack was (re)enabled, false when the instance has no Sodium.
     */
    fun quietSodium(ctx: Context, instance: File, version: String): Boolean {
        val hasSodium = File(instance, "mods").listFiles()?.any { it.isFile && it.name.endsWith(".jar") && it.name.contains("sodium", true) } == true
        if (!hasSodium) return false
        val packs = File(instance, "resourcepacks")
        packs.mkdirs()
        val dest = File(packs, QUIET_PACK)
        if (!dest.exists()) {
            val fmt = findClientJar(ctx, instance, version)?.let { jar ->
                try { ZipFile(jar).use { packFormat(it) } } catch (_: Exception) { null }
            } ?: 34
            val tmp = File(packs, QUIET_PACK + ".part")
            ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                out.putNextEntry(ZipEntry("pack.mcmeta"))
                out.write(("{\"pack\":{\"pack_format\":" + fmt + ",\"description\":\"IMZYY: hide Sodium launcher warning\"}}").toByteArray(Charsets.UTF_8))
                out.closeEntry()
                out.putNextEntry(ZipEntry("assets/sodium/lang/en_us.json"))
                out.write("{\"sodium.console.pojav_launcher\":\"\"}".toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        }
        enablePack(instance, dest.name)
        return true
    }

    /** Adds "file/<name>" to resourcePacks in options.txt (keeps existing entries). */
    fun enablePack(instance: File, fileName: String) {
        val f = File(instance, "options.txt")
        val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
        val key = "file/$fileName"
        val i = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        val arr = if (i >= 0) {
            try { JSONArray(lines[i].removePrefix("resourcePacks:")) } catch (_: Exception) { JSONArray().put("vanilla") }
        } else JSONArray().put("vanilla")
        for (k in 0 until arr.length()) if (arr.optString(k) == key) return
        arr.put(key)
        val line = "resourcePacks:" + arr.toString()
        if (i >= 0) lines[i] = line else lines.add(line)
        instance.mkdirs()
        f.writeText(lines.joinToString("\n") + "\n")
    }

    fun clean(packsDir: File): Int {
        var n = 0
        packsDir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(SUFFIX) && it.delete()) n++ }
        return n
    }
}
