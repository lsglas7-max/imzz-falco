package id.imzyy.xl

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Lite build: only one JRE ships inside the APK. The other Java runtimes are downloaded once,
 * verified (SHA-256) and unpacked into filesDir/jre<N>_runtime, exactly where the upstream
 * MinecraftJREPreparer expects them. Zips are hosted as assets of the "runtimes" GitHub release
 * that the CI publishes next to the APK.
 */
object ImzyyRuntimes {
    val majors = listOf(8, 17, 21, 25)

    private fun runtimeDir(ctx: Context, major: Int) = File(ctx.filesDir, "jre${major}_runtime")

    private fun inApk(ctx: Context, major: Int): Boolean = try {
        ctx.assets.open("jre/jre$major.zip").close(); true
    } catch (_: Exception) {
        false
    }

    /** True when this runtime can be used right now (bundled or already downloaded). */
    fun installed(ctx: Context, major: Int): Boolean {
        if (inApk(ctx, major)) return true
        val d = runtimeDir(ctx, major)
        return d.isDirectory && (d.list()?.isNotEmpty() == true)
    }

    private fun manifest(): JSONObject = try {
        JSONObject(ImzyyConfig.RUNTIMES_JSON)
    } catch (_: Exception) {
        JSONObject()
    }

    /** True when the CI recorded a download for this runtime. */
    fun downloadable(major: Int): Boolean =
        ImzyyConfig.GITHUB_REPO.isNotEmpty() && manifest().has("jre$major")

    fun sizeMb(major: Int): Int = (manifest().optJSONObject("jre$major")?.optLong("size") ?: 0L).let { (it / 1_048_576L).toInt() }

    /**
     * Makes sure [major] is available. No-op when bundled/installed or not downloadable.
     * Blocking: call from a background thread. [onProgress] gets 0..1.
     */
    fun ensure(ctx: Context, major: Int, onProgress: (Float) -> Unit = {}) {
        if (installed(ctx, major) || !downloadable(major)) return
        // Never block the UI thread with a network download; the Hub downloads on a worker thread.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
        val entry = manifest().getJSONObject("jre$major")
        val sha = entry.getString("sha256").lowercase()
        val total = entry.optLong("size")
        val url = "https://github.com/${ImzyyConfig.GITHUB_REPO}/releases/download/runtimes/jre$major.zip"
        val tmp = File(ctx.cacheDir, "jre$major.zip")
        try {
            var done = 0L
            Net.downloadSha256(url, tmp, sha) { n ->
                done += n
                if (total > 0) onProgress((done.toFloat() / total * 0.8f).coerceAtMost(0.8f))
            }

            val target = runtimeDir(ctx, major)
            target.deleteRecursively()
            target.mkdirs()
            val root = target.canonicalFile
            ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    val outputFile = File(root, e.name).canonicalFile
                    require(
                        outputFile.path == root.path ||
                            outputFile.path.startsWith(root.path + File.separator)
                    ) { "Path zip tidak aman: ${e.name}" }

                    if (e.isDirectory) {
                        if (!outputFile.isDirectory && !outputFile.mkdirs()) {
                            throw java.io.IOException("Tidak bisa membuat direktori runtime: ${outputFile.path}")
                        }
                    } else {
                        val parent = outputFile.parentFile
                        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                            throw java.io.IOException("Tidak bisa membuat direktori runtime: ${parent.path}")
                        }
                        FileOutputStream(outputFile).use { outputStream ->
                            zip.copyTo(outputStream)
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }
            onProgress(1f)
            tmp.delete()
        } catch (ex: Exception) {
            runtimeDir(ctx, major).deleteRecursively()
            // Keep verified archive or .part file so a retry can continue instead of restarting.
            throw ex
        }
    }
}
