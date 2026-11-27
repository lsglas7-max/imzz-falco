package id.imzyy.xl

import java.io.File

/** Reversible graphics recovery: back up settings, disable active shader/resource-pack selections. */
object GraphicsRepair {
    data class Report(
        val settingsChanged: Int,
        val selectedPacksReset: Int,
        val backupDirectory: String,
    )

    private fun backup(instance: File, source: File): String {
        val relative = source.relativeTo(instance).invariantSeparatorsPath
        val root = File(instance, ".imzyy-recovery/graphics-before")
        val target = File(root, relative)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = false)
        }
        return root.relativeTo(instance).invariantSeparatorsPath
    }

    private fun resetOptions(file: File): Pair<Boolean, Boolean> {
        if (!file.isFile || file.length() > 512 * 1024) return false to false
        val old = runCatching { file.readLines() }.getOrNull() ?: return false to false
        var changed = false
        var packsReset = false
        val updated = old.map { line ->
            val clean = line.trimStart()
            val colon = clean.indexOf(':')
            if (colon <= 0) return@map line
            val key = clean.substring(0, colon).trim()
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            when (key) {
                "resourcePacks" -> {
                    val replacement = indent + "resourcePacks:[\"vanilla\"]"
                    if (replacement != line) { changed = true; packsReset = true }
                    replacement
                }
                "incompatibleResourcePacks" -> {
                    val replacement = indent + "incompatibleResourcePacks:[]"
                    if (replacement != line) { changed = true; packsReset = true }
                    replacement
                }
                "shaderPack" -> {
                    val replacement = indent + "shaderPack:"
                    if (replacement != line) changed = true
                    replacement
                }
                else -> line
            }
        }
        if (changed) file.writeText(updated.joinToString("\n") + "\n")
        return changed to packsReset
    }

    private fun resetShaderProperties(file: File): Boolean {
        if (!file.isFile || file.length() > 256 * 1024) return false
        val old = runCatching { file.readLines() }.getOrNull() ?: return false
        var changed = false
        val updated = old.map { line ->
            val trimmed = line.trimStart()
            val split = when {
                trimmed.startsWith("shaderPack=", true) -> '='
                trimmed.startsWith("shaderPack:", true) -> ':'
                trimmed.startsWith("shader_pack=", true) -> '='
                else -> null
            }
            if (split == null) line else {
                val idx = line.indexOf(split)
                val replacement = line.substring(0, idx + 1)
                if (line.substring(idx + 1).isNotBlank()) changed = true
                replacement
            }
        }
        if (changed) file.writeText(updated.joinToString("\n") + "\n")
        return changed
    }

    /**
     * Backs up every file before editing. All user resource packs and shader files are preserved;
     * only their active selections are reset to vanilla/off for a clean diagnostic boot.
     */
    fun repair(instance: File): Report {
        require(instance.isDirectory) { "Instance belum tersedia" }
        val options = File(instance, "options.txt")
        val shaderFiles = listOf(
            File(instance, "optionsshaders.txt"),
            File(instance, "config/iris.properties"),
            File(instance, "config/iris.properties.bak"),
        )
        val files = listOf(options) + shaderFiles
        val existing = files.filter { it.isFile }
        existing.forEach { backup(instance, it) }

        var changed = 0
        var packs = 0
        val op = resetOptions(options)
        if (op.first) changed++
        if (op.second) packs++
        shaderFiles.forEach { file -> if (resetShaderProperties(file)) changed++ }
        val backupPath = File(instance, ".imzyy-recovery/graphics-before").relativeTo(instance).invariantSeparatorsPath
        return Report(changed, packs, backupPath)
    }

    /** Restores the first pre-repair copy, never overwriting it with a later state. */
    fun restore(instance: File): Int {
        val backupRoot = File(instance, ".imzyy-recovery/graphics-before")
        if (!backupRoot.isDirectory) return 0
        var restored = 0
        backupRoot.walkTopDown().filter { it.isFile }.forEach { saved ->
            val relative = saved.relativeTo(backupRoot)
            val target = File(instance, relative.path)
            target.parentFile?.mkdirs()
            saved.copyTo(target, overwrite = true)
            restored++
        }
        backupRoot.deleteRecursively()
        return restored
    }
}
