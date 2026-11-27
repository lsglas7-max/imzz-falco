package id.imzyy.xl

import java.io.File

/** Writes safe, well-known keys into the instance's options.txt (original backed up once). */
object FpsTuner {
    enum class Profile(val label: String, val values: List<Pair<String, String>>) {
        SAVER(
            "HEMAT FPS",
            listOf(
                "renderDistance" to "4", "simulationDistance" to "5", "particles" to "2",
                "entityShadows" to "false", "ao" to "0", "graphicsMode" to "0", "fancyGraphics" to "false",
                "biomeBlendRadius" to "0", "mipmapLevels" to "0", "entityDistanceScaling" to "0.5",
                "maxFps" to "60", "enableVsync" to "false", "renderClouds" to "\"false\"", "cloudStatus" to "\"off\"",
            ),
        ),
        BALANCED(
            "SEIMBANG",
            listOf(
                "renderDistance" to "8", "simulationDistance" to "8", "particles" to "1",
                "entityShadows" to "false", "ao" to "1", "graphicsMode" to "0", "fancyGraphics" to "false",
                "biomeBlendRadius" to "1", "mipmapLevels" to "2", "entityDistanceScaling" to "0.75",
                "maxFps" to "90", "enableVsync" to "false", "renderClouds" to "\"fast\"", "cloudStatus" to "\"fast\"",
            ),
        ),
        QUALITY(
            "KUALITAS",
            listOf(
                "renderDistance" to "12", "simulationDistance" to "10", "particles" to "0",
                "entityShadows" to "true", "ao" to "2", "graphicsMode" to "1", "fancyGraphics" to "true",
                "biomeBlendRadius" to "2", "mipmapLevels" to "4", "entityDistanceScaling" to "1.0",
                "maxFps" to "120", "enableVsync" to "false", "renderClouds" to "\"true\"", "cloudStatus" to "\"fancy\"",
            ),
        ),
    }

    /** Anti FPS-drop / anti-stutter: stable frame cap, no vsync stalls, cheaper chunk updates. */
    val SMOOTH: List<Pair<String, String>> = listOf(
        "enableVsync" to "false", "maxFps" to "60", "prioritizeChunkUpdates" to "0",
        "biomeBlendRadius" to "0", "entityDistanceScaling" to "0.6", "menuBackgroundBlurriness" to "0",
        "mipmapLevels" to "1", "particles" to "2", "simulationDistance" to "6", "improvedTransparency" to "false",
    )

    /** Anti micro-lag while moving. tier: 0 low, 1 mid, 2 flagship (only the gentle part). */
    fun lagValues(tier: Int): List<Pair<String, String>> {
        val base = listOf("enableVsync" to "false", "maxFps" to "60", "entityShadows" to "false", "ao" to "false", "renderClouds" to "false", "graphicsMode" to "0")
        return when (tier) {
            0 -> base + listOf("renderDistance" to "5", "simulationDistance" to "5", "entityDistanceScaling" to "0.5", "particles" to "2")
            1 -> base + listOf("renderDistance" to "8", "simulationDistance" to "6")
            else -> listOf("enableVsync" to "false", "entityShadows" to "false")
        }
    }

    private fun current(instance: File, key: String): String? =
        File(instance, "options.txt").takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')

    /**
     * Cheaper world join: fewer chunks to build/simulate at once, no chunk-update stalls.
     * Distances are only ever lowered (a value the user already set lower is kept). tier: 0 low, 1 mid, 2 flagship.
     */
    fun joinValues(instance: File, tier: Int): List<Pair<String, String>> {
        val renderCap = when (tier) { 0 -> 4; 1 -> 6; else -> 8 }
        val simCap = when (tier) { 0 -> 4; 1 -> 5; else -> 6 }
        fun capped(key: String, cap: Int): Pair<String, String> {
            val cur = current(instance, key)?.trim()?.toIntOrNull()
            return key to (if (cur != null && cur < cap) cur else cap).toString()
        }
        return listOf(
            capped("renderDistance", renderCap), capped("simulationDistance", simCap),
            "prioritizeChunkUpdates" to "0", "biomeBlendRadius" to "0", "entityShadows" to "false",
            "particles" to "2", "mipmapLevels" to "0", "improvedTransparency" to "false",
            "enableVsync" to "false", "entityDistanceScaling" to "0.6",
        )
    }

    fun apply(instance: File, profile: Profile): Int = applyValues(instance, profile.values)

    fun applyValues(instance: File, values: List<Pair<String, String>>, backupFirst: Boolean = true): Int {
        instance.mkdirs()
        val f = File(instance, "options.txt")
        val backup = File(instance, "options.txt.imzyy.bak")
        if (backupFirst && f.exists() && !backup.exists()) f.copyTo(backup)
        val lines = if (f.exists()) f.readLines().toMutableList() else mutableListOf()
        var changed = 0
        for ((k, v) in values) {
            val line = "$k:$v"
            val i = lines.indexOfFirst { it.startsWith("$k:") }
            if (i >= 0) {
                if (lines[i] != line) { lines[i] = line; changed++ }
            } else {
                lines.add(line); changed++
            }
        }
        f.writeText(lines.joinToString("\n") + "\n")
        return changed
    }

    fun restore(instance: File): Boolean {
        val backup = File(instance, "options.txt.imzyy.bak")
        if (!backup.exists()) return false
        backup.copyTo(File(instance, "options.txt"), overwrite = true)
        return true
    }
}
