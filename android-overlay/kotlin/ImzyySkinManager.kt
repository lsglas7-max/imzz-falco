package id.imzyy.xl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local skin storage and per-instance resource-pack installer. */
object ImzyySkinManager {
    const val PACK_FILE = "IMZYY-Local-Skin.zip"
    private const val PREFS = "imzyy_skin_settings"

    private fun currentKey(context: Context): String? = ImzyyAccountStore.current(context)?.username

    private fun profileDir(context: Context): File? = currentKey(context)?.let {
        File(File(context.filesDir, "imzyy_profiles"), it).apply { mkdirs() }
    }

    fun skinFile(context: Context): File? = profileDir(context)?.let { File(it, "skin.png") }

    fun hasSkin(context: Context): Boolean = skinFile(context)?.isFile == true

    fun isSlim(context: Context): Boolean {
        val key = currentKey(context) ?: return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("slim_$key", false)
    }

    fun setSlim(context: Context, slim: Boolean) {
        val key = currentKey(context) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("slim_$key", slim).apply()
    }

    fun preview(context: Context): Bitmap? = skinFile(context)?.takeIf { it.isFile }?.let {
        BitmapFactory.decodeFile(it.absolutePath)
    }

    /** Imports only a standard 64x64 PNG skin. The file is stored in app-private storage. */
    fun importPng(context: Context, uri: Uri) {
        check(currentKey(context) != null) { "Masuk ke Akun IMZYY terlebih dahulu." }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= 5 * 1024 * 1024) { "File PNG terlalu besar (maksimum 5 MB)." }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: error("File PNG tidak dapat dibaca.")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/png") { "File yang dipilih bukan PNG." }
        require(bounds.outWidth == 64 && bounds.outHeight == 64) {
            "Ukuran skin harus 64 × 64 piksel agar cocok dengan format Java modern."
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("PNG tidak dapat dibaca sebagai gambar skin yang valid.")
        decoded.recycle()

        val target = skinFile(context) ?: error("Akun IMZYY belum aktif.")
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "skin.png.tmp")
        temp.writeBytes(bytes)
        if (target.exists() && !target.delete()) error("Skin lama tidak dapat diganti.")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun removeLocalSkin(context: Context) {
        skinFile(context)?.delete()
    }

    /**
     * Creates and enables a resource pack in the chosen instance. This is a local fallback-skin
     * override; it does not upload a Mojang/Microsoft skin or replace authenticated player textures.
     */
    fun applyToInstance(context: Context, instanceDir: File, minecraftVersion: String): Boolean {
        val skin = skinFile(context)?.takeIf { it.isFile } ?: return false
        val packDir = File(instanceDir, "resourcepacks")
        if (!packDir.exists() && !packDir.mkdirs()) error("Folder resourcepacks tidak dapat dibuat.")
        val pack = File(packDir, PACK_FILE)
        val slim = isSlim(context)
        val modernPath = if (slim) {
            "assets/minecraft/textures/entity/player/slim/alex.png"
        } else {
            "assets/minecraft/textures/entity/player/wide/steve.png"
        }
        val legacyPath = if (slim) {
            "assets/minecraft/textures/entity/alex.png"
        } else {
            "assets/minecraft/textures/entity/steve.png"
        }
        val texturePath = if (usesModernPlayerTexturePath(minecraftVersion)) modernPath else legacyPath
        val format = resourcePackFormat(minecraftVersion)
        val supportedRange = if (supportsFormatRange(minecraftVersion)) {
            ",\"supported_formats\":{\"min_inclusive\":$format,\"max_inclusive\":999}"
        } else ""
        val mcmeta = """{"pack":{"pack_format":$format,"description":"IMZYY XL Local Skin"$supportedRange}}"""

        ZipOutputStream(pack.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.mcmeta"))
            zip.write(mcmeta.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(texturePath))
            skin.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        enableResourcePack(instanceDir, PACK_FILE)
        return true
    }

    fun removePackFromInstance(instanceDir: File) {
        val packDir = File(instanceDir, "resourcepacks")
        File(packDir, PACK_FILE).delete()
        val options = File(instanceDir, "options.txt")
        if (!options.isFile) return
        val lines = options.readLines(Charsets.UTF_8).toMutableList()
        val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        if (index >= 0) {
            val array = parseResourcePacks(lines[index].substringAfter(':'))
            val values = (0 until array.length()).map { array.optString(it) }
                .filterNot { it == "file/$PACK_FILE" }
            lines[index] = "resourcePacks:${JSONArray(values).toString()}"
            options.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        }
    }

    private fun enableResourcePack(instanceDir: File, fileName: String) {
        val options = File(instanceDir, "options.txt")
        if (!options.exists()) {
            options.parentFile?.mkdirs()
            options.writeText("resourcePacks:${JSONArray(listOf("vanilla", "file/$fileName"))}\n", Charsets.UTF_8)
            return
        }
        val original = options.readText(Charsets.UTF_8)
        val backup = File(instanceDir, "options.txt.imzyy-skin.bak")
        if (!backup.exists()) backup.writeText(original, Charsets.UTF_8)
        val lines = original.split('\n').toMutableList()
        val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
        val values = if (index >= 0) {
            val array = parseResourcePacks(lines[index].substringAfter(':'))
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
                .filterNot { it == "file/$fileName" }.toMutableList()
        } else mutableListOf("vanilla")
        values.add("file/$fileName")
        val newLine = "resourcePacks:${JSONArray(values)}"
        if (index >= 0) lines[index] = newLine else lines.add(newLine)
        options.writeText(lines.joinToString("\n"), Charsets.UTF_8)
    }

    private fun parseResourcePacks(raw: String): JSONArray = runCatching { JSONArray(raw.trim()) }
        .getOrElse { JSONArray(listOf("vanilla")) }

    private fun usesModernPlayerTexturePath(version: String): Boolean {
        val parts = version.split('.', '-', '+')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 20
        return major > 1 || minor >= 19
    }

    private fun supportsFormatRange(version: String): Boolean {
        val parts = version.split('.', '-', '+')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 20
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major > 1 || minor > 20 || (minor == 20 && patch >= 2)
    }

    /** A safe baseline pack format for each resource-pack metadata generation. */
    private fun resourcePackFormat(version: String): Int {
        val parts = version.split('.', '-', '+')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 20
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (major >= 26) return 75
        return when {
            major > 1 -> 75
            minor <= 8 -> 1
            minor <= 10 -> 2
            minor <= 12 -> 3
            minor <= 14 -> 4
            minor <= 16 -> 5
            minor == 17 -> 6
            minor == 18 -> 8
            minor == 19 && patch <= 2 -> 9
            minor == 19 && patch == 3 -> 12
            minor == 19 -> 13
            minor == 20 && patch <= 1 -> 15
            minor == 20 && patch == 2 -> 18
            minor == 20 && patch <= 4 -> 22
            minor == 20 -> 32
            minor == 21 && patch <= 1 -> 34
            minor == 21 && patch <= 3 -> 42
            minor == 21 && patch == 4 -> 46
            minor == 21 && patch == 5 -> 55
            minor == 21 -> 63
            else -> 75
        }
    }
}
