package id.imzyy.xl

import java.io.File
import org.json.JSONArray

/** Removes only IMZYY-generated visual overrides when textures/shaders render as magenta or corrupted. */
object GraphicsRepair {
    data class Report(val settingsChanged: Int, val generatedPacksRemoved: Int)

    private val managedPackNames = setOf(
        "IMZYY-Quiet.zip",
        "IMZYY-Vanilla-Lite-imzyy-lite.zip",
    )

    private fun clearPackRefs(line: String): Pair<String, Boolean> {
        val key = when {
            line.startsWith("resourcePacks:") -> "resourcePacks:"
            line.startsWith("incompatibleResourcePacks:") -> "incompatibleResourcePacks:"
            else -> return line to false
        }
        val parsed = runCatching { JSONArray(line.removePrefix(key)) }.getOrNull() ?: return line to false
        val kept = JSONArray()
        var changed = false
        for (i in 0 until parsed.length()) {
            val value = parsed.optString(i)
            val fileName = value.removePrefix("file/")
            if (fileName in managedPackNames) changed = true else kept.put(value)
        }
        return (key + kept.toString()) to changed
    }

    private fun clearShaderChoice(file: File): Boolean {
        if (!file.isFile || file.length() > 256 * 1024) return false
        val lines = runCatching { file.readLines() }.getOrNull() ?: return false
        var changed = false
        val cleaned = lines.map { line ->
            val trimmed = line.trimStart()
            val separator = when {
                trimmed.startsWith("shaderPack:", true) -> ":"
                trimmed.startsWith("shaderPack=", true) -> "="
                trimmed.startsWith("shader_pack=", true) -> "="
                else -> null
            }
            if (separator != null) {
                val prefix = line.substring(0, line.indexOf(separator) + 1)
                if (line.substring(prefix.length).isNotBlank()) changed = true
                prefix
            } else {
                val (value, didChange) = clearPackRefs(line)
                if (didChange) changed = true
                value
            }
        }
        if (changed) file.writeText(cleaned.joinToString("\n") + "\n")
        return changed
    }

    fun repair(instance: File): Report {
        require(instance.isDirectory) { "Instance belum tersedia" }
        var settings = 0
        val options = File(instance, "options.txt")
        if (clearShaderChoice(options)) settings++

        val config = File(instance, "config")
        listOf(File(config, "iris.properties"), File(config, "iris.properties.bak"), File(instance, "optionsshaders.txt"))
            .forEach { if (clearShaderChoice(it)) settings++ }

        var removed = 0
        val packs = File(instance, "resourcepacks")
        managedPackNames.forEach { name ->
            val f = File(packs, name)
            if (f.isFile && f.delete()) removed++
        }
        return Report(settings, removed)
    }
}
