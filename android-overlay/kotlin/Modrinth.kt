package id.imzyy.xl

import java.net.URLEncoder
import org.json.JSONArray

data class Hit(val id: String, val slug: String, val title: String, val desc: String, val downloads: Long)

data class ModFile(
    val projectId: String,
    val slug: String,
    val filename: String,
    val url: String,
    val sha512: String?,
    val sha1: String?,
    val requiredProjects: List<String>,
    val size: Long = 0L,
)

object Modrinth {
    private const val API = "https://api.modrinth.com/v2"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Release versions of Minecraft, newest first. */
    fun releaseVersions(): List<String> {
        val arr = JSONArray(Net.getText("$API/tag/game_version"))
        val rows = ArrayList<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("version_type") == "release") rows.add(o.optString("date") to o.getString("version"))
        }
        return rows.sortedByDescending { it.first }.map { it.second }
    }

    fun search(query: String, gameVersion: String, loader: String): List<Hit> {
        val facets = JSONArray()
            .put(JSONArray().put("project_type:mod"))
            .put(JSONArray().put("versions:$gameVersion"))
            .put(JSONArray().put("categories:$loader"))
            .put(JSONArray().put("client_side:required").put("client_side:optional"))
        val url = "$API/search?query=${enc(query)}&limit=20&index=relevance&facets=${enc(facets.toString())}"
        val hits = org.json.JSONObject(Net.getText(url)).getJSONArray("hits")
        val out = ArrayList<Hit>()
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            out.add(Hit(h.getString("project_id"), h.optString("slug"), h.optString("title"), h.optString("description"), h.optLong("downloads")))
        }
        return out
    }

    /** Newest compatible file (prefers release builds); null if the project has no matching build. */
    fun resolve(idOrSlug: String, gameVersion: String, loader: String): ModFile? {
        val url = "$API/project/${enc(idOrSlug)}/version?loaders=${enc("[\"$loader\"]")}&game_versions=${enc("[\"$gameVersion\"]")}"
        val arr = try {
            JSONArray(Net.getText(url))
        } catch (e: HttpFailure) {
            if (e.code == 404) return null else throw e
        }
        if (arr.length() == 0) return null
        var pick = arr.getJSONObject(0)
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            if (v.optString("version_type") == "release") { pick = v; break }
        }
        val files = pick.getJSONArray("files")
        var file = files.getJSONObject(0)
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (f.optBoolean("primary")) { file = f; break }
        }
        val name = file.getString("filename")
        if (!name.endsWith(".jar")) return null
        val hashes = file.optJSONObject("hashes")
        val deps = ArrayList<String>()
        val da = pick.optJSONArray("dependencies")
        if (da != null) for (i in 0 until da.length()) {
            val d = da.getJSONObject(i)
            if (d.optString("dependency_type") == "required" && !d.isNull("project_id")) deps.add(d.getString("project_id"))
        }
        return ModFile(
            projectId = pick.getString("project_id"),
            slug = idOrSlug,
            filename = name,
            url = file.getString("url"),
            sha512 = hashes?.optString("sha512")?.ifEmpty { null },
            sha1 = hashes?.optString("sha1")?.ifEmpty { null },
            requiredProjects = deps,
            size = file.optLong("size"),
        )
    }

    /** Modpacks (.mrpack) for one Minecraft version + loader (loader may be empty). */
    fun searchPacks(query: String, gameVersion: String, loader: String): List<Hit> {
        val facets = JSONArray()
            .put(JSONArray().put("project_type:modpack"))
            .put(JSONArray().put("versions:$gameVersion"))
        if (loader.isNotEmpty()) facets.put(JSONArray().put("categories:$loader"))
        val url = "$API/search?query=${enc(query)}&limit=25&index=${if (query.isEmpty()) "downloads" else "relevance"}&facets=${enc(facets.toString())}"
        val hits = org.json.JSONObject(Net.getText(url)).getJSONArray("hits")
        val out = ArrayList<Hit>()
        for (i in 0 until hits.length()) {
            val h = hits.getJSONObject(i)
            out.add(Hit(h.getString("project_id"), h.optString("slug"), h.optString("title"), h.optString("description"), h.optLong("downloads")))
        }
        return out
    }

    /** Newest .mrpack of a modpack project for this Minecraft version + loader (prefers release builds). */
    fun resolvePack(idOrSlug: String, gameVersion: String, loader: String): ModFile? {
        val loaders = if (loader.isNotEmpty()) "loaders=${enc("[\"$loader\"]")}&" else ""
        val url = "$API/project/${enc(idOrSlug)}/version?${loaders}game_versions=${enc("[\"$gameVersion\"]")}"
        val arr = try {
            JSONArray(Net.getText(url))
        } catch (e: HttpFailure) {
            if (e.code == 404) return null else throw e
        }
        if (arr.length() == 0) return null
        var pick = arr.getJSONObject(0)
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            if (v.optString("version_type") == "release") { pick = v; break }
        }
        val files = pick.getJSONArray("files")
        var file: org.json.JSONObject? = null
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (f.getString("filename").endsWith(".mrpack") && (file == null || f.optBoolean("primary"))) file = f
        }
        val chosen = file ?: return null
        val hashes = chosen.optJSONObject("hashes")
        return ModFile(
            projectId = pick.getString("project_id"),
            slug = idOrSlug,
            filename = chosen.getString("filename"),
            url = chosen.getString("url"),
            sha512 = hashes?.optString("sha512")?.ifEmpty { null },
            sha1 = hashes?.optString("sha1")?.ifEmpty { null },
            requiredProjects = emptyList(),
            size = chosen.optLong("size"),
        )
    }

    /**
     * Fabric only: makes sure Mod Menu (the "Mods" button on the title screen) is present, plus Fabric API if no
     * Fabric API jar exists yet. Looks in every folder of [existing]; new files go to [dest]. Returns true if added.
     */
    fun ensureModMenu(existing: List<java.io.File>, dest: java.io.File, gameVersion: String, loader: String, say: (String) -> Unit): Boolean {
        if (loader != "fabric") return false
        fun has(part: String) = existing.any { d ->
            d.listFiles()?.any { it.isFile && it.name.endsWith(".jar") && it.name.contains(part, true) } == true
        }
        if (has("modmenu") || has("mod-menu")) return false
        dest.mkdirs()
        val wanted = ArrayList<String>()
        if (!has("fabric-api")) wanted.add("fabric-api")
        wanted.add("modmenu")
        var added = false
        for (slug in wanted) {
            val f = resolve(slug, gameVersion, loader)
            if (f == null) {
                say("– $slug: tidak ada build untuk $gameVersion")
                continue
            }
            say("↓ ${f.filename}")
            Net.download(f.url, java.io.File(dest, f.filename), f.sha512, f.sha1)
            added = true
        }
        return added
    }
}
