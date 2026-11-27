package id.imzyy.xl

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class PerfMod(val slug: String, val label: String, val note: String, val defaultOn: Boolean)

/** FPS/performance mod catalogue resolved live from Modrinth for the chosen version + loader. */
object PerfPack {
    val catalog = listOf(
        PerfMod("sodium", "Sodium / Embeddium", "Mesin render. Butuh renderer OpenGL 3.2+ (Zink/MobileGlues/Krypton); jangan aktifkan di GL4ES.", false),
        PerfMod("lithium", "Lithium", "Optimasi logika game & server internal.", true),
        PerfMod("ferrite-core", "FerriteCore", "Hemat RAM.", true),
        PerfMod("modernfix", "ModernFix", "Loading lebih cepat, RAM lebih hemat.", true),
        PerfMod("immediatelyfast", "ImmediatelyFast", "Mempercepat HUD, teks & GUI.", true),
        PerfMod("entityculling", "Entity Culling", "Tidak merender entity yang tak terlihat.", true),
        PerfMod("dynamic-fps", "Dynamic FPS", "Turunkan FPS saat game di background (hemat baterai).", false),
        PerfMod("voxy", "Voxy (LOD jarak jauh)", "Fabric + Sodium saja. Berat dan butuh OpenGL 4.6: sering tidak jalan di HP, coba satu per satu.", false),
    )

    /** Voxy needs Fabric + Sodium; Mod Menu gives the in-game settings screen. */
    val VOXY_PRESET = listOf("fabric-api", "sodium", "voxy", "modmenu")

    private const val TRACK = ".imzyy-fps-pack.json"

    /** Sodium has no Forge/NeoForge build; Embeddium is the equivalent port. */
    fun slugFor(slug: String, loader: String): String =
        if (slug == "sodium" && (loader == "forge" || loader == "neoforge")) "embeddium" else slug

    fun install(modsDir: File, gameVersion: String, loader: String, slugs: List<String>, say: (String) -> Unit): Int {
        modsDir.mkdirs()
        val seen = HashSet<String>()
        val installed = ArrayList<String>()
        val queue = ArrayDeque<String>()
        var totalBytes = 0L
        slugs.forEach { queue.add(slugFor(it, loader)) }
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            if (!seen.add(key)) continue
            val f = Modrinth.resolve(key, gameVersion, loader)
            if (f == null) { say("– $key: tidak ada build untuk $gameVersion/$loader"); continue }
            seen.add(f.projectId)
            val dest = File(modsDir, f.filename)
            if (dest.exists()) {
                say("= ${f.filename} sudah ada")
            } else {
                say("↓ ${f.filename} (${Net.fmtBytes(f.size)})")
                Net.download(f.url, dest, f.sha512, f.sha1)
                totalBytes += f.size
                installed.add(f.filename)
            }
            f.requiredProjects.filter { it !in seen }.forEach { queue.add(it) }
        }
        if (totalBytes > 0) say("Total unduhan: ${Net.fmtBytes(totalBytes)}")
        remember(modsDir, installed)
        return installed.size
    }

    private fun remember(modsDir: File, names: List<String>) {
        if (names.isEmpty()) return
        val t = File(modsDir, TRACK)
        val arr = if (t.exists()) runCatching { JSONArray(t.readText()) }.getOrDefault(JSONArray()) else JSONArray()
        names.forEach { arr.put(it) }
        t.writeText(arr.toString())
    }

    /** Remove only the files this launcher installed. */
    fun uninstall(modsDir: File): Int {
        val t = File(modsDir, TRACK)
        if (!t.exists()) return 0
        val arr = runCatching { JSONArray(t.readText()) }.getOrDefault(JSONArray())
        var n = 0
        for (i in 0 until arr.length()) {
            val f = File(modsDir, arr.getString(i))
            if (f.parentFile == modsDir && f.delete()) n++
        }
        t.delete()
        return n
    }

    fun trackedCount(modsDir: File): Int {
        val t = File(modsDir, TRACK)
        return if (t.exists()) runCatching { JSONArray(t.readText()).length() }.getOrDefault(0) else 0
    }
}
