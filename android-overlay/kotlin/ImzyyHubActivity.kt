package id.imzyy.xl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.flamelauncher.data.instance.InstanceManager
import kr.co.donghyun.flamelauncher.data.instance.InstanceMeta
import kr.co.donghyun.flamelauncher.presentation.KeyboardLayoutEditorActivity
import org.json.JSONObject

/** Premium monochrome home for IMZYY XL. Opens the upstream launcher with PLAY. */
class ImzyyHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImzyyFramePacing.syncToDisplay(window, this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ImzyyWindow.install(application)
        setContent { ProvideTextStyle(TextStyle(fontFamily = ImzyyFonts.family)) { HubApp() } }
        handleRelaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-read the active display mode after returning from settings or another app.
        ImzyyFramePacing.syncToDisplay(window, this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRelaunch(intent)
    }

    /**
     * In-game RESTART GAME lands here with the original game intent. Do not touch mod jars until the
     * isolated Minecraft process has actually exited. If Android keeps it alive too long, leave the
     * staged pack intact and ask the user to reopen from the Hub instead of risking a half-written mods/.
     */
    private var relaunchInProgress = false

    @Suppress("DEPRECATION")
    private fun handleRelaunch(i: Intent?) {
        val game = i?.getParcelableExtra<Intent>(EXTRA_RELAUNCH) ?: return
        i.removeExtra(EXTRA_RELAUNCH)
        if (relaunchInProgress) return
        relaunchInProgress = true

        val instancePath = game.getStringExtra("instance_dir")
        if (instancePath.isNullOrBlank()) {
            relaunchInProgress = false
            android.widget.Toast.makeText(this, "Restart dibatalkan: folder instance tidak terbaca.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        android.widget.Toast.makeText(this, "Menunggu proses Minecraft berhenti…", android.widget.Toast.LENGTH_SHORT).show()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        val wait = object : Runnable {
            override fun run() {
                val alive = ImzyyBootGuard.gameProcessAlive(this@ImzyyHubActivity) || am.runningAppProcesses.orEmpty().any {
                    it.processName == "$packageName:minecraft" || it.processName.endsWith(":minecraft")
                }
                if (alive) {
                    if (android.os.SystemClock.elapsedRealtime() < deadline) {
                        h.postDelayed(this, 250L)
                    } else {
                        relaunchInProgress = false
                        android.widget.Toast.makeText(
                            this@ImzyyHubActivity,
                            "Proses Minecraft belum berhenti. Pack tetap aman; tutup game lalu tekan PLAY dari Hub.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    return
                }

                Thread {
                    try {
                        val applied = Mrpack.applyPending(File(instancePath))
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this@ImzyyHubActivity,
                                if (applied > 0) "${applied} file modpack diterapkan. Membuka ulang game…" else "Membuka ulang Minecraft…",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                            try {
                                startActivity(game)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@ImzyyHubActivity, "Gagal membuka game: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            } finally {
                                relaunchInProgress = false
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            relaunchInProgress = false
                            android.widget.Toast.makeText(
                                this@ImzyyHubActivity,
                                "Modpack tidak diterapkan: ${e.message}. File lama sudah dipertahankan bila pemindahan gagal.",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }.start()
            }
        }
        h.postDelayed(wait, 350L)
    }

    companion object {
        const val EXTRA_RELAUNCH = "imzyy_relaunch"
    }
}

class HubState(val ctx: Context, val scope: CoroutineScope) {
    private val prefs = ctx.getSharedPreferences("imzyy_hub", Context.MODE_PRIVATE)

    var tab by mutableStateOf(0)
    var selectedId by mutableStateOf(prefs.getString("inst", "") ?: "")
    var ctlTick by mutableStateOf(0)
    /** Pad button that currently receives extra keys from the "Tambah tombol" chips (combo). Empty = add a new button. */
    var comboId by mutableStateOf("")
    var rtTick by mutableStateOf(0)
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(0f)
    var query by mutableStateOf("")
    var mrInfo by mutableStateOf<MrInfo?>(null)
    var mrUri by mutableStateOf<Uri?>(null)
    var smooth by mutableStateOf(prefs.getBoolean("smooth", false))
    var texLevel by mutableStateOf(1)
    var antiLag by mutableStateOf(prefs.getBoolean("antilag", false))
    var joinLag by mutableStateOf(prefs.getBoolean("joinlag", false))
    /** 0 = low, 1 = mid, 2 = flagship (by total RAM). */
    val tier: Int = run {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / 1073741824.0
        if (am.isLowRamDevice || gb <= 4.5) 0 else if (gb <= 8.5) 1 else 2
    }
    val tierName: String get() = listOf("LOW", "MID", "FLAGSHIP")[tier]

    val logs = mutableStateListOf<String>()
    val lunaMessages = mutableStateListOf(
        LunaMessage(
            "assistant",
            "Halo! Gue Luna Sixty, asisten bawaan IMZYY XL. Gue bisa bantu membaca pesan error, menjelaskan konflik mod, memandu Safe Boot, membahas grafis Minecraft, dan menjawab pertanyaan umum. Pilih Analisis Log untuk mengirim ringkasan diagnostik instance secara sadar."
        )
    )
    var lunaDraft by mutableStateOf("")
    var lunaBusy by mutableStateOf(false)
    var lunaStatus by mutableStateOf("")
    val instances = mutableStateListOf<InstanceMeta>()
    val hits = mutableStateListOf<Hit>()
    val picked = mutableStateMapOf<String, Boolean>().also { m -> PerfPack.catalog.forEach { m[it.slug] = it.defaultOn } }
    var skinTick by mutableStateOf(0)
    var skinSlim by mutableStateOf(ImzyySkinManager.isSlim(ctx))
    var safeBootActive by mutableStateOf(false)
    var bootFailures by mutableStateOf(ImzyyBootGuard.recentFailures(ctx))

    fun say(s: String) {
        logs.add(s)
        while (logs.size > 80) logs.removeAt(0)
    }

    val meta: InstanceMeta? get() = instances.firstOrNull { it.id == selectedId }
    val gameVersion: String get() = meta?.mcVersion ?: ""
    val loader: String get() = meta?.loaderType ?: ""

    fun select(id: String) {
        selectedId = id
        prefs.edit().putString("inst", id).apply()
        safeBootActive = id.isNotEmpty() && runCatching { RecoveryTools.safeBootActive(InstanceManager.instanceDir(ctx, id)) }.getOrDefault(false)
        bootFailures = ImzyyBootGuard.recentFailures(ctx)
    }

    private fun needTarget(): File {
        val m = meta ?: error("Pilih instance dulu (buat instance di launcher jika belum ada)")
        return InstanceManager.instanceDir(ctx, m.id)
    }

    private fun needLoader(): String {
        needTarget()
        if (loader.isEmpty()) error("Instance vanilla tidak bisa pakai mod. Buat instance Fabric/Forge/NeoForge/Quilt dulu.")
        return loader
    }

    fun runJob(block: () -> Unit) {
        if (busy) return
        busy = true
        progress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                say("✗ ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
    }

    fun refreshInstances() {
        scope.launch {
            val found = withContext(Dispatchers.IO) { InstanceManager.listInstances(ctx) }
            instances.clear(); instances.addAll(found)
            if (found.none { it.id == selectedId }) select(found.firstOrNull()?.id ?: "")
            else select(selectedId)
        }
    }

    fun applyProfile(p: FpsTuner.Profile) = runJob {
        val n = FpsTuner.apply(needTarget(), p)
        say("✓ Profil ${p.label}: $n opsi diubah (cadangan: options.txt.imzyy.bak)")
    }

    fun restoreOptions() = runJob {
        say(if (FpsTuner.restore(needTarget())) "✓ options.txt dikembalikan" else "Tidak ada cadangan options.txt")
    }

    fun installPack() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum memasang mod.")
        val l = needLoader()
        val mods = File(needTarget(), "mods")
        val slugs = PerfPack.catalog.filter { picked[it.slug] == true }.map { it.slug }
        if (slugs.isEmpty()) error("Pilih minimal satu mod")
        say("Memasang paket FPS untuk $gameVersion / $l…")
        val n = PerfPack.install(mods, gameVersion, l, slugs, this::say)
        say("✓ $n file baru terpasang di instance ${meta?.name}.")
    }

    fun removePack() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum mencopot mod.")
        val n = PerfPack.uninstall(File(needTarget(), "mods"))
        say("✓ $n file paket FPS dihapus")
    }

    fun search() = runJob {
        val l = needLoader()
        val q = query.trim()
        if (q.isEmpty()) error("Ketik nama mod")
        val r = Modrinth.search(q, gameVersion, l)
        hits.clear(); hits.addAll(r)
        say("${r.size} hasil untuk \"$q\" ($gameVersion/$l)")
    }

    fun installHit(h: Hit) = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum memasang mod.")
        val l = needLoader()
        val mods = File(needTarget(), "mods")
        say("Memasang ${h.title}…")
        val n = PerfPack.install(mods, gameVersion, l, listOf(h.id), this::say)
        say("✓ ${h.title}: $n file baru")
    }

    fun loadMrpack(uri: Uri) = runJob {
        val info = Mrpack.readIndex(ctx, uri)
        mrUri = uri
        mrInfo = info
        say("Modpack: ${info.name} ${info.version} — ${info.files.size} file")
    }

    /** One picker for everything: .mrpack, a single .jar, or a .zip that holds mods. */
    fun pickFile(uri: Uri) = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum mengubah folder mods.")
        val name = ModImport.displayName(ctx, uri)
        if (name.endsWith(".jar", ignoreCase = true)) {
            val n = ModImport.install(ctx, uri, File(needTarget(), "mods"), this::say)
            say("✓ $n mod dipasang ke ${meta?.name}")
            return@runJob
        }
        val asPack = try {
            Mrpack.readIndex(ctx, uri)
        } catch (e: Exception) {
            if (name.endsWith(".mrpack", ignoreCase = true)) throw e
            null
        }
        if (asPack != null) {
            mrUri = uri
            mrInfo = asPack
            say("Modpack: ${asPack.name} ${asPack.version} — ${asPack.files.size} file")
        } else {
            val n = ModImport.install(ctx, uri, File(needTarget(), "mods"), this::say)
            say("✓ $n mod dari zip dipasang ke ${meta?.name}")
        }
    }

    fun downloadRuntime(major: Int) = runJob {
        say("Mengunduh Java $major (~${ImzyyRuntimes.sizeMb(major)} MB)…")
        ImzyyRuntimes.ensure(ctx, major) { progress = it }
        rtTick++
        say("✓ Java $major siap")
    }

    fun importMrpack() = runJob {
        val uri = mrUri ?: error("Pilih file .mrpack dulu")
        val info = mrInfo ?: error("Pilih file .mrpack dulu")
        val m = meta ?: error("Pilih instance dulu")
        val packMc = info.deps["minecraft"]
        if (packMc != null && packMc != m.mcVersion) error("Versi tidak cocok: modpack butuh Minecraft $packMc, instance ${m.mcVersion}")
        val packLoader = listOf("fabric-loader" to "fabric", "quilt-loader" to "quilt", "neoforge" to "neoforge", "forge" to "forge")
            .firstOrNull { info.deps.containsKey(it.first) }?.second
        if (packLoader != null && packLoader != m.loaderType) error("Loader tidak cocok: modpack butuh $packLoader, instance ${m.loaderType ?: "vanilla"}")
        val staged = File(needTarget(), Mrpack.PENDING)
        Mrpack.install(ctx, uri, info, staged, this::say) { progress = it }
        say("✓ Modpack siap. File lama aman; perubahan diterapkan saat PLAY berikutnya setelah Minecraft berhenti.")
    }

    fun controlsChanged() {
        ImzyyControls.save(ctx)
        ctlTick++
    }

    fun openKeyEditor() {
        KeyboardLayoutEditorActivity.start(ctx)
    }


    /** Lite: first launch of each instance gets the lightweight options.txt profile once
     *  (original is backed up; later changes by the user are never overwritten). */
    private fun liteAutoTune() {
        if (ImzyyConfig.VARIANT != "lite") return
        val m = meta ?: return
        val key = "lite_tuned_" + m.id
        if (prefs.getBoolean(key, false)) return
        try {
            FpsTuner.apply(InstanceManager.instanceDir(ctx, m.id), FpsTuner.Profile.SAVER)
            prefs.edit().putBoolean(key, true).apply()
            say("✓ Lite: profil HEMAT FPS dipasang otomatis untuk instance ini")
        } catch (e: Exception) {
            say("Lite auto-tune dilewati: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun switchSmooth(on: Boolean) {
        smooth = on
        prefs.edit().putBoolean("smooth", on).apply()
        if (on) runJob {
            val dir = needTarget()
            val count = FpsTuner.installedModCount(dir)
            val values = FpsTuner.smoothValues(dir, tier, count, ImzyyFramePacing.refreshRateHz)
            val n = FpsTuner.applyValues(dir, values)
            val mode = if (count >= 100) "mode mod berat ($count JAR)" else "$count JAR mod"
            say("✓ Anti drop FPS aktif: $n opsi, $mode, target ${values.firstOrNull { it.first == "maxFps" }?.second ?: "60"} FPS. Diterapkan ulang tiap PLAY.")
        } else say("Anti drop FPS & stutter dimatikan. Pengaturan yang sudah tertulis tetap ada; gunakan KEMBALIKAN OPTIONS ASLI untuk memulihkan backup.")
    }

    fun switchAntiLag(on: Boolean) {
        antiLag = on
        prefs.edit().putBoolean("antilag", on).apply()
        if (on) runJob {
            val n = FpsTuner.applyValues(needTarget(), FpsTuner.lagValues(tier))
            say("✓ Anti lag gerak aktif untuk HP $tierName ($n opsi). Dipasang ulang otomatis tiap PLAY.")
        } else say("Anti lag gerak dimatikan.")
    }

    fun switchJoinLag(on: Boolean) {
        joinLag = on
        prefs.edit().putBoolean("joinlag", on).apply()
        if (on) runJob {
            val d = needTarget()
            val n = FpsTuner.applyValues(d, FpsTuner.joinValues(d, tier))
            say("✓ Anti lag masuk world aktif untuk HP $tierName ($n opsi). Dipasang ulang otomatis tiap PLAY.")
        } else say("Anti lag masuk world dimatikan. Pakai KEMBALIKAN OPTIONS ASLI jika mau membatalkan opsi yang sudah ditulis.")
    }

    fun installVoxy() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum memasang Voxy.")
        val l = needLoader()
        if (l != "fabric" && l != "quilt") error("Voxy hanya untuk instance Fabric/Quilt")
        val mods = File(needTarget(), "mods")
        say("Memasang preset Voxy (Fabric API, Sodium, Voxy, Mod Menu)…")
        val n = PerfPack.install(mods, gameVersion, l, PerfPack.VOXY_PRESET, this::say)
        say("✓ $n file dipasang. Buka Mod Menu di dalam game untuk mengatur Voxy. Voxy butuh renderer OpenGL 4.x (MobileGlues/Zink), tidak jalan di GL4ES.")
    }

    fun setGameLang(code: String, label: String) = runJob {
        val d = needTarget()
        FpsTuner.applyValues(d, listOf("lang" to code), backupFirst = false)
        say("✓ Bahasa game diatur ke $label. Berlaku saat game dijalankan lagi.")
    }

    fun installModMenu() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum memasang Mod Menu.")
        val l = needLoader()
        if (l != "fabric") error("Mod Menu hanya untuk instance Fabric")
        val dir = needTarget()
        say("Memeriksa Mod Menu…")
        val added = Modrinth.ensureModMenu(listOf(File(dir, "mods")), File(dir, "mods"), gameVersion, l, this::say)
        say(if (added) "✓ Mod Menu terpasang. Mulai ulang game: tombol Mods muncul di layar utama." else "Mod Menu sudah ada di instance ini.")
    }

    fun compressTextures() = runJob {
        val dir = needTarget()
        val minSide = TextureCompress.MIN_SIDE[texLevel]
        val packs = File(dir, "resourcepacks")
        packs.mkdirs()
        val srcs = packs.listFiles(java.io.FileFilter { it.isFile && it.name.endsWith(".zip") && !it.name.endsWith(TextureCompress.SUFFIX) })?.toList().orEmpty()
        if (srcs.isNotEmpty()) {
            for (src in srcs) {
                say("Mengompres ${src.name}…")
                val dest = File(packs, src.name.removeSuffix(".zip") + TextureCompress.SUFFIX)
                val r = TextureCompress.compressPack(src, dest, minSide) { progress = it }
                say("✓ ${dest.name}: ${Net.fmtBytes(r.before)} → ${Net.fmtBytes(r.after)} (${r.changed} tekstur)")
            }
            say("Aktifkan versi -imzyy-lite di Options > Resource Packs.")
        } else {
            val jar = TextureCompress.findClientJar(ctx, dir, gameVersion) ?: error("Jar Minecraft $gameVersion tidak ditemukan; jalankan game sekali dulu atau taruh resource pack di folder resourcepacks")
            say("Membuat pack tekstur ringan dari ${jar.name}…")
            val dest = File(packs, TextureCompress.VANILLA_PACK)
            val r = TextureCompress.buildVanillaPack(jar, dest, minSide) { progress = it }
            TextureCompress.enablePack(dir, dest.name)
            say("✓ ${r.changed} tekstur diperkecil, pack ${Net.fmtBytes(r.after)} diaktifkan otomatis")
        }
    }

    fun cleanTextures() = runJob {
        say("✓ ${TextureCompress.clean(File(needTarget(), "resourcepacks"))} file hasil kompres dihapus")
    }

    fun importSkin(uri: Uri) = runJob {
        ImzyySkinManager.importPng(ctx, uri)
        skinTick++
        say("✓ Skin PNG 64×64 berhasil diimpor ke akun IMZYY.")
    }

    fun setSkinModel(slim: Boolean) {
        skinSlim = slim
        ImzyySkinManager.setSlim(ctx, slim)
    }

    fun applySkin() = runJob {
        val target = needTarget()
        if (!ImzyySkinManager.applyToInstance(ctx, target, gameVersion)) error("Impor skin PNG terlebih dahulu.")
        say("✓ Skin lokal dipasang ke resource pack instance ${meta?.name}. Akan aktif saat game dimulai ulang.")
    }

    fun removeSkin() = runJob {
        val target = meta?.let { InstanceManager.instanceDir(ctx, it.id) }
        if (target != null) ImzyySkinManager.removePackFromInstance(target)
        ImzyySkinManager.removeLocalSkin(ctx)
        skinTick++
        say("Skin lokal dihapus dari akun dan instance yang dipilih.")
    }

    private fun openLauncher() {
        ctx.startActivity(Intent().setClassName(ctx.packageName, ImzyyConfig.MAIN_ACTIVITY))
    }

    private fun minecraftProcessAlive(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return ImzyyBootGuard.gameProcessAlive(ctx) || am.runningAppProcesses.orEmpty().any {
            it.processName == "${ctx.packageName}:minecraft" || it.processName.endsWith(":minecraft")
        }
    }

    /** Blocking (worker thread). Verifies the selected instance's asset objects and re-downloads what is missing. */
    private fun assetJob(applyAdaptiveSmooth: Boolean = false): AssetRepair.Report? {
        val m = meta ?: return null
        val instanceDir = InstanceManager.instanceDir(ctx, m.id)
        val pending = File(File(instanceDir, Mrpack.PENDING), ".imzyy-ready")
        if (pending.isFile && minecraftProcessAlive()) {
            error("Minecraft masih berjalan. Tutup game sepenuhnya sebelum menerapkan modpack yang tertunda.")
        }
        val appliedMods = Mrpack.applyPending(instanceDir)
        if (appliedMods > 0) say("✓ Modpack tertunda diterapkan ($appliedMods file); mod lama dicadangkan di .imzyy-backups")
        // Recount after pending modpack application so the first launch of a 100+ modpack
        // receives the heavy-mod profile, not a profile based on the previous mods/ folder.
        if (applyAdaptiveSmooth) {
            val count = FpsTuner.installedModCount(instanceDir)
            val values = FpsTuner.smoothValues(instanceDir, tier, count, ImzyyFramePacing.refreshRateHz)
            val changed = FpsTuner.applyValues(instanceDir, values)
            val targetFps = values.firstOrNull { it.first == "maxFps" }?.second ?: "60"
            say("✓ Profil stabil pasca-modpack: $changed opsi, $count JAR mod, batas $targetFps FPS")
        }
        runCatching { TextureCompress.quietSodium(ctx, instanceDir, m.mcVersion) }
        runCatching {
            if (ImzyySkinManager.applyToInstance(ctx, InstanceManager.instanceDir(ctx, m.id), m.mcVersion)) {
                say("✓ Skin lokal IMZYY disinkronkan ke resource pack instance.")
            }
        }.onFailure { say("Skin lokal tidak dapat dipasang: ${it.message ?: "error"}") }
        say("Memeriksa asset Minecraft ${m.mcVersion}…")
        val r = AssetRepair.repair(ctx, InstanceManager.instanceDir(ctx, m.id), m.assetIndexId) { progress = it }
        when {
            r.note != null -> say("! ${r.note}")
            r.bad == 0 -> say("✓ Asset lengkap (${r.total} file)")
            r.failed == 0 -> say("✓ ${r.fixed} asset yang hilang/rusak diunduh ulang")
            else -> say("✗ ${r.failed} dari ${r.bad} asset gagal diunduh: ${r.error ?: "tidak diketahui"}. Cek internet lalu tekan lagi.")
        }
        return r
    }

    fun repairAssets() = runJob { assetJob() }

    fun repairGraphics() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum memperbaiki options dan renderer.")
        val report = GraphicsRepair.repair(needTarget())
        say("✓ Pemulihan grafis: ${report.settingsChanged} file pengaturan dibersihkan; ${report.selectedPacksReset} pilihan resource pack/shader dinonaktifkan sementara.")
        say("Semua file resource pack dan shader tetap disimpan. Backup pengaturan: ${report.backupDirectory}.")
        say("Renderer tidak dipaksa otomatis. Coba Krypton untuk baseline kompatibilitas; untuk Sodium/Iris modern uji Zink/Panfrost atau MobileGlues sesuai dukungan perangkat.")
    }

    fun restoreGraphics() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum memulihkan pengaturan grafis.")
        val n = GraphicsRepair.restore(needTarget())
        say(if (n > 0) "✓ $n file pengaturan grafis dikembalikan dari backup." else "Tidak ada backup pengaturan grafis untuk dipulihkan.")
    }

    fun startSafeBoot() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum Safe Boot memindahkan folder mods.")
        val target = needTarget()
        val report = RecoveryTools.startSafeBoot(target)
        safeBootActive = true
        val graphics = GraphicsRepair.repair(target)
        say("✓ SAFE BOOT disiapkan. Folder mods: ${if (report.modsBackedUp) "dicadangkan" else "kosong"}; pending pack: ${if (report.pendingPackBackedUp) "diamankan" else "tidak ada"}.")
        say("Shader dan resource pack aktif dimatikan sementara; file asli tetap disimpan. Sekarang HOME → PLAY, lalu jalankan instance yang sama.")
        say("Backup: .imzyy-recovery/${report.backupDirectory}; grafis: ${graphics.backupDirectory}")
    }

    fun restoreSafeBoot() = runJob {
        if (minecraftProcessAlive()) error("Tutup Minecraft sepenuhnya sebelum memulihkan modpack.")
        val report = RecoveryTools.restoreSafeBoot(needTarget())
        safeBootActive = false
        say("✓ Mods dan pending modpack dari ${report.backupDirectory} dipulihkan. Tidak ada file cadangan yang dihapus.")
    }

    fun writeDiagnostics() = runJob {
        val target = needTarget()
        val scan = RecoveryTools.scanMods(target)
        val file = RecoveryTools.writeDiagnostics(target)
        say("✓ Diagnostik tersimpan: ${file.path}")
        say("${scan.jarCount} JAR diperiksa. ${scan.warnings.take(3).joinToString(" | ")}")
        bootFailures = ImzyyBootGuard.recentFailures(ctx)
    }

    fun play() {
        // Apply the one-time Lite baseline first; active user toggles then take precedence.
        liteAutoTune()
        if (antiLag) runCatching { meta?.let { FpsTuner.applyValues(InstanceManager.instanceDir(ctx, it.id), FpsTuner.lagValues(tier)) } }
        if (joinLag) runCatching { meta?.let { val d = InstanceManager.instanceDir(ctx, it.id); FpsTuner.applyValues(d, FpsTuner.joinValues(d, tier)) } }
        if (smooth) runCatching {
            meta?.let {
                val dir = InstanceManager.instanceDir(ctx, it.id)
                val mods = FpsTuner.installedModCount(dir)
                FpsTuner.applyValues(dir, FpsTuner.smoothValues(dir, tier, mods, ImzyyFramePacing.refreshRateHz))
            }
        }
        if (meta == null || busy) { openLauncher(); return }
        busy = true
        progress = 0f
        val adaptiveSmooth = smooth
        scope.launch {
            var block = false
            var prepFailed = false
            try {
                val r = withContext(Dispatchers.IO) { assetJob(applyAdaptiveSmooth = adaptiveSmooth) }
                block = r != null && r.failed > 0
                if (!block) {
                    val scan = withContext(Dispatchers.IO) { RecoveryTools.scanMods(needTarget()) }
                    val critical = RecoveryTools.criticalWarnings(scan)
                    scan.warnings.filterNot { it.startsWith("Tidak ditemukan") }.forEach { say("⚠ $it") }
                    if (critical.isNotEmpty()) {
                        block = true
                        prepFailed = true
                        say("Launch ditahan karena konflik mod berisiko. Gunakan Safe Boot atau perbaiki JAR yang disebutkan di atas.")
                    }
                }
            } catch (e: Exception) {
                prepFailed = true
                say("Persiapan instance gagal: ${e.message ?: e.javaClass.simpleName}. Game tidak dibuka agar file mod tidak rusak.")
            } finally {
                busy = false
            }
            if (!block && !prepFailed) openLauncher()
        }
    }
}

@Composable
fun HubApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = remember { HubState(ctx, scope) }
    var signedIn by remember { mutableStateOf(ImzyyAccountStore.current(ctx) != null) }
    LaunchedEffect(signedIn) {
        if (signedIn) {
            s.skinSlim = ImzyySkinManager.isSlim(ctx)
            s.skinTick++
            s.refreshInstances()
            withContext(Dispatchers.IO) { ImzyyControls.refresh(ctx, force = true); ImzyyPad.load(ctx) }
        }
    }

    if (!signedIn) {
        ImzyyAuthGate(onAuthenticated = { signedIn = true })
        return
    }

    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        AnimatedBackdrop()
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Box(Modifier.weight(1f)) {
                Crossfade(targetState = s.tab, animationSpec = tween(ImzyyFramePacing.durationMs(280)), label = "tab") { t ->
                    when (t) {
                        0 -> HomeTab(s)
                        1 -> BoostTab(s)
                        2 -> ModsTab(s)
                        3 -> ImportTab(s)
                        4 -> ControlsTab(s)
                        5 -> SkinTab(s)
                        6 -> AccountTab(s) {
                            ImzyyAccountStore.logout(ctx)
                            signedIn = false
                            s.tab = 0
                        }
                        7 -> LunaSixtyTab(s)
                        else -> HomeTab(s)
                    }
                }
            }
            NavBar(s)
        }
    }
}

@Composable
private fun NavBar(s: HubState) {
    val names = listOf("HOME", "BOOST", "MODS", "IMPORT", "KONTROL", "SKIN", "AKUN", "LUNA AI")
    Row(
        Modifier.fillMaxWidth().background(Ink.Black).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { i, n -> Chip(n, s.tab == i, compact = true) { s.tab = i } }
    }
}

@Composable
private fun Title(text: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, color = Ink.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Text(sub, color = Ink.Sub, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomeTab(s: HubState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Reveal(0) { Spacer(Modifier.height(24.dp)) } }
        item {
            Reveal(1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShimmerTitle("IMZYY XL")
                    Text(
                        if (ImzyyConfig.VARIANT == "lite") "LITE  •  Minecraft Java  •  ARM64" else "Minecraft Java  •  ARM64",
                        color = Ink.Sub, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
                    )
                }
            }
        }
        item { Reveal(2) { GlowButton("▶  PLAY", { s.play() }) } }
        item {
            Reveal(2) {
                Panel {
                    Text("Akun IMZYY", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Login di dashboard memakai akun IMZYY lokal. Catatan: engine Minecraft bawaan project ini masih dapat meminta akun Microsoft saat membuka alur game upstream.", color = Ink.Sub, fontSize = 11.sp)
                    InkButton("KELOLA AKUN", { s.tab = 6 }, filled = false)
                }
            }
        }
        item {
            Reveal(2) {
                Panel {
                    Text("Perbaiki asset", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Dicek otomatis tiap PLAY. Pakai tombol ini kalau game crash NoSuchFileException di assets/objects.", color = Ink.Sub, fontSize = 12.sp)
                    InkButton("PERIKSA & PERBAIKI ASSET", { s.repairAssets() }, filled = false, enabled = !s.busy)
                }
            }
        }
        item {
            Reveal(3) {
                Panel {
                    Text("Boost FPS", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Profil options.txt + paket mod performa (Sodium, Lithium, FerriteCore, ModernFix…).", color = Ink.Sub, fontSize = 13.sp)
                    InkButton("BUKA BOOST", { s.tab = 1 }, filled = false)
                }
            }
        }
        item {
            Reveal(3) {
                Panel {
                    Text("Luna Sixty AI", color = Ink.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text("Asisten bawaan untuk tanya-jawab, analisis boot loop, konflik mod, dan layar magenta berdasarkan ringkasan log instance.", color = Ink.Sub, fontSize = 12.sp)
                    InkButton("BUKA LUNA SIXTY", { s.tab = 7 }, filled = false)
                }
            }
        }
        item {
            Reveal(4) {
                Panel {
                    Text("Mod & Modpack", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Cari mod dari Modrinth sesuai versi + loader, atau import file .mrpack.", color = Ink.Sub, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        InkButton("CARI MOD", { s.tab = 2 }, Modifier.weight(1f), filled = false)
                        InkButton("IMPORT .MRPACK", { s.tab = 3 }, Modifier.weight(1f), filled = false)
                    }
                }
            }
        }
        item { Reveal(4) { RuntimeCard(s) } }
        item { LogCard(s) }
    }
}

@Composable
private fun RuntimeCard(s: HubState) {
    val tick = s.rtTick
    Panel {
        Text("Runtime Java", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Versi Minecraft memilih Java otomatis. Yang belum terpasang diunduh sekali saja (tanpa perlu uninstall).",
            color = Ink.Sub, fontSize = 12.sp,
        )
        ImzyyRuntimes.majors.forEach { m ->
            val ready = tick >= 0 && ImzyyRuntimes.installed(s.ctx, m)
            val can = ImzyyRuntimes.downloadable(m)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Java $m", color = Ink.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (ready) {
                    Chip("TERPASANG", true, compact = true) {}
                } else if (can) {
                    Chip("UNDUH ${ImzyyRuntimes.sizeMb(m)} MB", false, compact = true) { if (!s.busy) s.downloadRuntime(m) }
                } else {
                    Text("tidak tersedia", color = Ink.Sub, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(s: HubState) {
    Panel {
        Text("Instance", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (s.instances.isEmpty()) {
            Text("Belum ada instance. Buat satu di launcher (PLAY → instance baru), lalu muat ulang.", color = Ink.Sub, fontSize = 12.sp)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.instances.forEach { m -> Chip(m.name, s.selectedId == m.id) { s.select(m.id) } }
            Chip("↻ MUAT ULANG", false) { s.refreshInstances() }
        }
        val m = s.meta
        if (m != null) {
            Text(
                "Minecraft ${m.mcVersion}  •  ${(m.loaderType ?: "vanilla").uppercase()}" + (m.loaderVersion?.let { " $it" } ?: ""),
                color = Ink.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LogCard(s: HubState) {
    if (s.logs.isEmpty() && !s.busy) return
    Panel {
        if (s.busy) InkProgress(s.progress)
        s.logs.takeLast(8).forEach { Text(it, color = Ink.Sub, fontSize = 12.sp) }
    }
}

@Composable
private fun BoostTab(s: HubState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Reveal(0) { Title("BOOST", "Optimasi FPS langsung dari launcher") } }
        item { Reveal(1) { TargetCard(s) } }
        item {
            Reveal(2) {
                Panel {
                    Text("Profil grafis (options.txt)", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Render distance, partikel, bayangan, AO, awan. Cadangan disimpan otomatis.", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FpsTuner.Profile.values().forEach { p ->
                            InkButton(p.label, { s.applyProfile(p) }, Modifier.weight(1f), filled = p == FpsTuner.Profile.SAVER, enabled = !s.busy)
                        }
                    }
                    InkButton("KEMBALIKAN OPTIONS ASLI", { s.restoreOptions() }, filled = false, enabled = !s.busy)
                }
            }
        }
        item {
            Reveal(3) {
                Panel {
                    Text("Paket mod performa", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    PerfPack.catalog.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Chip(if (s.picked[m.slug] == true) "ON" else "OFF", s.picked[m.slug] == true) { s.picked[m.slug] = s.picked[m.slug] != true }
                            Column(Modifier.weight(1f)) {
                                Text(m.label, color = Ink.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(m.note, color = Ink.Sub, fontSize = 12.sp)
                            }
                        }
                    }
                    InkButton("PASANG PAKET FPS", { s.installPack() }, enabled = !s.busy)
                    InkButton("COPOT PAKET FPS", { s.removePack() }, filled = false, enabled = !s.busy)
                }
            }
        }
        item {
            Reveal(4) {
                Panel {
                    Text("Perbaikan render / tekstur", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Reset pilihan shader dan resource pack aktif ke Vanilla untuk diagnosis. Semua file pack asli tetap disimpan dan bisa dipilih kembali.", color = Ink.Sub, fontSize = 12.sp)
                    Text("Renderer harus diuji dari Settings upstream. Untuk baseline coba Krypton; untuk Sodium/Iris modern uji Zink/Panfrost atau MobileGlues jika tersedia. Jangan paksa GL4ES untuk mod OpenGL modern.", color = Ink.Sub, fontSize = 12.sp)
                    InkButton("RESET RENDER & RESOURCE PACK", { s.repairGraphics() }, enabled = !s.busy)
                    InkButton("PULIHKAN PENGATURAN GRAFIS", { s.restoreGraphics() }, filled = false, enabled = !s.busy)
                }
            }
        }
        item {
            Reveal(4) {
                Panel {
                    Text("IMZYY RECOVERY CENTER", color = Ink.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Text(
                        if (s.bootFailures > 0) "Sesi Minecraft sebelumnya berhenti tanpa penanda selesai (${s.bootFailures} kali dalam 10 menit). Coba Safe Boot untuk menguji tanpa modpack."
                        else "Kalau Minecraft berulang di Booting atau layar magenta, uji dulu tanpa mod. Mods dan modpack akan dipindah ke backup, bukan dihapus.",
                        color = Ink.Sub, fontSize = 12.sp,
                    )
                    if (s.safeBootActive) {
                        Text("SAFE BOOT AKTIF • mods dan pending modpack sedang dicadangkan", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        InkButton("PULIHKAN MODS & MODPACK", { s.restoreSafeBoot() }, enabled = !s.busy)
                    } else {
                        InkButton("START SAFE BOOT • TANPA MOD", { s.startSafeBoot() }, enabled = !s.busy)
                    }
                    InkButton("BUAT LOG DIAGNOSTIK", { s.writeDiagnostics() }, filled = false, enabled = !s.busy)
                    InkButton("ANALISIS DENGAN LUNA SIXTY", { s.tab = 7 }, filled = false, enabled = !s.busy)
                    Text("Safe Boot menyimpan cadangan di .imzyy-recovery. Setelah pengujian, gunakan tombol pulihkan untuk mengembalikan modpack. Luna hanya mengirim ringkasan saat kamu menekan Analisis Log.", color = Ink.Sub, fontSize = 10.sp)
                }
            }
        }
        item {
            Reveal(5) {
                Panel {
                    Text("Anti drop FPS & stutter", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Profil adaptif untuk vanilla maupun modpack: jumlah JAR dihitung otomatis; 100+ mod memakai batas FPS dan jarak render lebih konservatif. Profil dihitung ulang setelah modpack diterapkan dan setiap kamu tekan PLAY.", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("AKTIF", s.smooth) { s.switchSmooth(true) }
                        Chip("MATI", !s.smooth) { s.switchSmooth(false) }
                    }
                }
            }
        }
        item {
            Reveal(4) {
                Panel {
                    Text("Cegah gerak sedikit lag", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("HP kamu terdeteksi ${s.tierName}. " + (if (s.tier == 2) "Flagship tidak wajib, tapi boleh diaktifkan agar lebih mulus." else "Disarankan AKTIF: render distance, bayangan, AO dan awan diringankan supaya gerak tidak patah-patah."), color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("AKTIF", s.antiLag) { s.switchAntiLag(true) }
                        Chip("MATI", !s.antiLag) { s.switchAntiLag(false) }
                    }
                }
            }
        }
        item {
            Reveal(4) {
                Panel {
                    Text("Kurangi lag pas masuk world", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Render & simulation distance diturunkan sesuai HP (${s.tierName}), update chunk tidak menahan frame, partikel/bayangan/biome blend diringankan, jadi loading chunk awal tidak patah-patah. Hanya menurunkan jarak (nilai yang sudah kamu set lebih kecil tidak dinaikkan). Dipasang ulang tiap PLAY.", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("AKTIF", s.joinLag) { s.switchJoinLag(true) }
                        Chip("MATI", !s.joinLag) { s.switchJoinLag(false) }
                    }
                }
            }
        }
        item {
            Reveal(5) {
                Panel {
                    Text("Bahasa game", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Atur bahasa Minecraft untuk instance ini tanpa membuka menu game (berguna kalau modpack mengganti bahasa).", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("INDONESIA", false, compact = true) { s.setGameLang("in_id", "Indonesia") }
                        Chip("ENGLISH", false, compact = true) { s.setGameLang("en_us", "English") }
                    }
                }
            }
        }
        item {
            Reveal(5) {
                Panel {
                    Text("Voxy (LOD jarak jauh)", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Pasang Fabric API + Sodium + Voxy + Mod Menu sekali tekan. Atur Voxy langsung di dalam game lewat Mod Menu. Voxy butuh renderer OpenGL 4.x (MobileGlues/Zink); di OpenGL ES murni atau GL4ES tidak bisa jalan.", color = Ink.Sub, fontSize = 12.sp)
                    InkButton("PASANG PRESET VOXY", { s.installVoxy() }, enabled = !s.busy)
                }
            }
        }
        item {
            Reveal(6) {
                Panel {
                    Text("Kompres tekstur", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Memperkecil PNG secara hemat memori (decode langsung pada resolusi lebih kecil, tanpa membuat bitmap penuh ganda). Pack di resourcepacks dikompres; kalau kosong dibuat pack ringan dari Minecraft vanilla. Tekstur animasi .png.mcmeta tidak disentuh.", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextureCompress.LEVEL_NAMES.forEachIndexed { i, n -> Chip(n, s.texLevel == i) { s.texLevel = i } }
                    }
                    InkButton("KOMPRES TEKSTUR", { s.compressTextures() }, enabled = !s.busy)
                    InkButton("HAPUS HASIL KOMPRES", { s.cleanTextures() }, filled = false, enabled = !s.busy)
                }
            }
        }
        item { LogCard(s) }
    }
}

@Composable
private fun ModsTab(s: HubState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Reveal(0) { Title("MODS", "Cari & pasang dari Modrinth") } }
        item { Reveal(1) { TargetCard(s) } }
        item {
            Reveal(1) {
                Panel {
                    Text("Menu Mods di dalam game", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Tombol Mods di layar utama Minecraft datang dari Mod Menu (Fabric). Banyak modpack tidak membawanya; tekan ini untuk memasangnya.", color = Ink.Sub, fontSize = 12.sp)
                    InkButton("PASANG MOD MENU", { s.installModMenu() }, filled = false, enabled = !s.busy)
                }
            }
        }
        item {
            Reveal(2) {
                Panel {
                    InkField(s.query, { s.query = it }, "Cari mod (mis. sodium, iris, ferrite)")
                    InkButton("CARI", { s.search() }, enabled = !s.busy)
                }
            }
        }
        items(s.hits.toList()) { h ->
            Panel {
                Text(h.title, color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(h.desc, color = Ink.Sub, fontSize = 12.sp, maxLines = 3)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${h.downloads} unduhan", color = Ink.Sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.width(150.dp)) { InkButton("PASANG", { s.installHit(h) }, enabled = !s.busy) }
                }
            }
        }
        item { LogCard(s) }
    }
}

@Composable
private fun ImportTab(s: HubState) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) s.pickFile(uri)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Reveal(0) { Title("IMPORT", "Modpack .mrpack, mod .jar, atau zip berisi mods") } }
        item { Reveal(1) { TargetCard(s) } }
        item {
            Reveal(2) {
                Panel {
                    InkButton("PILIH FILE (.mrpack / .jar / .zip)", { picker.launch(arrayOf("*/*")) }, enabled = !s.busy)
                    val info = s.mrInfo
                    if (info != null) {
                        Text(info.name + "  " + info.version, color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        if (info.summary.isNotEmpty()) Text(info.summary, color = Ink.Sub, fontSize = 12.sp, maxLines = 3)
                        Text(info.deps.entries.joinToString("  •  ") { it.key + " " + it.value }, color = Ink.Text, fontSize = 12.sp)
                        Text("${info.files.size} file (${Net.fmtBytes(info.files.sumOf { it.size })}) akan diunduh dan diverifikasi hash-nya.", color = Ink.Sub, fontSize = 12.sp)
                        Text("Buat dulu instance dengan versi & loader di atas lewat launcher, lalu pilih foldernya sebagai target.", color = Ink.Sub, fontSize = 12.sp)
                        InkButton("IMPORT KE FOLDER INSTANCE", { s.importMrpack() }, enabled = !s.busy)
                    }
                }
            }
        }
        item { LogCard(s) }
    }
}

@Composable
private fun SliderRow(title: String, valueText: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Ink.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(valueText, color = Ink.Sub, fontSize = 13.sp)
        }
        InkSlider(value, min, max, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsTab(s: HubState) {
    val tick = s.ctlTick // recompose on change
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Reveal(0) { Title("KONTROL", "Kamera, mouse virtual & tombol") } }
        item {
            Reveal(1) {
                Panel {
                    Text("Kamera & sentuhan", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    SliderRow("Sensitivitas kamera", "%.2f".format(ImzyyControls.look) + if (tick >= 0) "x" else "", ImzyyControls.look, 0.3f, 3.5f) {
                        ImzyyControls.look = it; s.controlsChanged()
                    }
                    SliderRow("Ambang geser (anti salah tap)", "${ImzyyControls.dragSlop.toInt()} px", ImzyyControls.dragSlop, 6f, 50f) {
                        ImzyyControls.dragSlop = it; s.controlsChanged()
                    }
                    SliderRow("Tahan lama (hancurkan / klik kanan)", "${ImzyyControls.longPressMs} ms", ImzyyControls.longPressMs.toFloat(), 250f, 900f) {
                        ImzyyControls.longPressMs = it.toLong(); s.controlsChanged()
                    }
                }
            }
        }
        item {
            Reveal(2) {
                Panel {
                    Text("Mouse virtual (touchpad)", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Di menu & inventori: geser jari untuk memindahkan kursor, ketuk = klik kiri, tahan atau ketuk 2 jari = klik kanan. Saat bermain tetap kontrol kamera biasa.", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("AKTIF", ImzyyControls.touchpad) { ImzyyControls.touchpad = true; s.controlsChanged() }
                        Chip("MATI (sentuh langsung)", !ImzyyControls.touchpad) { ImzyyControls.touchpad = false; s.controlsChanged() }
                    }
                    SliderRow("Kecepatan kursor", "%.2f".format(ImzyyControls.cursorSpeed) + "x", ImzyyControls.cursorSpeed, 0.4f, 3.5f) {
                        ImzyyControls.cursorSpeed = it; s.controlsChanged()
                    }
                    SliderRow("Ukuran kursor", "%.2f".format(ImzyyControls.cursorSize) + "x", ImzyyControls.cursorSize, 0.6f, 2.5f) {
                        ImzyyControls.cursorSize = it; s.controlsChanged()
                    }
                }
            }
        }
        item {
            Reveal(3) {
                Panel {
                    Text("Tombol layar", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Atur posisi, ukuran, dan tombol di editor bawaan. Perubahan kontrol di atas berlaku saat game dimulai/dibuka lagi.", color = Ink.Sub, fontSize = 12.sp)
                    InkButton("BUKA EDITOR TOMBOL", { s.openKeyEditor() })
                    InkButton("RESET KONTROL KE DEFAULT", { ImzyyControls.reset(s.ctx); s.ctlTick++ }, filled = false)
                }
            }
        }
        item {
            Reveal(4) {
                Panel {
                    Text("Tombol IMZYY (di dalam game)", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("WASD, klik kiri/kanan, dan tombol lain muncul di game, termasuk sebelum membuat world. Di dalam game ketuk tombol PAD (atas): geser tombol untuk mengatur posisi, ketuk tombol untuk ganti mode / sembunyikan / gabung tombol lain, lalu ketuk SIMPAN. Tombol MODS (atas) membuka pencarian & pemasangan modpack.", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("AKTIF", ImzyyPad.enabled) { ImzyyPad.enabled = true; ImzyyPad.save(s.ctx) }
                        Chip("MATI", !ImzyyPad.enabled) { ImzyyPad.enabled = false; ImzyyPad.save(s.ctx) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("SELALU TAMPIL", ImzyyPad.alwaysShow) { ImzyyPad.alwaysShow = true; ImzyyPad.save(s.ctx) }
                        Chip("SAAT MENU SAJA", !ImzyyPad.alwaysShow) { ImzyyPad.alwaysShow = false; ImzyyPad.save(s.ctx) }
                    }
                    SliderRow("Ukuran tombol", "%.2f".format(ImzyyPad.size) + "x", ImzyyPad.size, 0.7f, 1.6f) { ImzyyPad.size = it; ImzyyPad.save(s.ctx) }
                    SliderRow("Kepekatan tombol", "%.0f".format(ImzyyPad.opacity * 100) + "%", ImzyyPad.opacity, 0.3f, 1f) { ImzyyPad.opacity = it; ImzyyPad.save(s.ctx) }
                }
            }
        }
        item {
            Reveal(5) {
                Panel {
                    Text("Daftar tombol", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Ketuk mode untuk mengganti: TEKAN (aktif selagi ditekan), TAHAN (ketuk sekali = terkunci, ketuk lagi = lepas), KLIK / TAHAN LAMA (ketuk = klik, tahan lama = terkunci).", color = Ink.Sub, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("SEMBUNYI SEMUA", false, compact = true) { ImzyyPad.setAllHidden(s.ctx, true) }
                        Chip("TAMPILKAN SEMUA", false, compact = true) { ImzyyPad.setAllHidden(s.ctx, false) }
                    }
                    for (b in ImzyyPad.buttons.toList()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                b.key.replace("+", " + ") + if (b.hidden) "   (disembunyikan)" else "",
                                color = if (b.hidden) Ink.Sub else Ink.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip(ImzyyPad.modeName(b.mode), true, compact = true) { ImzyyPad.cycleMode(s.ctx, b.id) }
                                Chip(if (b.hidden) "TAMPILKAN" else "SEMBUNYIKAN", b.hidden, compact = true) { ImzyyPad.toggleHidden(s.ctx, b.id) }
                                Chip("+ GABUNG", s.comboId == b.id, compact = true) { s.comboId = if (s.comboId == b.id) "" else b.id }
                                if (b.key.contains("+")) Chip("HAPUS GABUNGAN", false, compact = true) { ImzyyPad.clearCombo(s.ctx, b.id) }
                                Chip("HAPUS", false, compact = true) { ImzyyPad.remove(s.ctx, b.id); if (s.comboId == b.id) s.comboId = "" }
                            }
                        }
                    }
                }
            }
        }
        item {
            Reveal(6) {
                Panel {
                    val target = ImzyyPad.buttons.firstOrNull { it.id == s.comboId }
                    Text(if (target != null) "Gabungkan ke tombol ${target.label}" else "Tambah tombol", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        if (target != null) "Ketuk tombol di bawah untuk menambah ke kombinasi (ditekan bersamaan), mis. CTRL + C. Ketuk SELESAI jika sudah." else "Ketuk satu tombol untuk membuat tombol baru. Untuk kombinasi, pilih \"+ GABUNG\" pada tombol di atas.",
                        color = Ink.Sub, fontSize = 12.sp,
                    )
                    if (target != null) Chip("SELESAI", true, compact = true) { s.comboId = "" }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (k in PadKeys.order) Chip(k, false, compact = true) {
                            if (target != null) ImzyyPad.addKeyTo(s.ctx, target.id, k) else ImzyyPad.add(s.ctx, k)
                        }
                    }
                    InkButton("RESET TOMBOL IMZYY", { ImzyyPad.reset(s.ctx) }, filled = false)
                }
            }
        }
    }
}

@Composable
private fun ImzyyAuthGate(onAuthenticated: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var registerMode by remember { mutableStateOf(!ImzyyAccountStore.hasAccounts(ctx)) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        AnimatedBackdrop()
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            ShimmerTitle("IMZYY XL")
            Text("ACCOUNT SYSTEM", color = Ink.Sub, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(24.dp))
            Panel {
                Text(if (registerMode) "BUAT AKUN IMZYY" else "SELAMAT DATANG KEMBALI", color = Ink.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (registerMode) "Buat identitas launcher kamu. Username dan password disimpan secara lokal di perangkat ini."
                    else "Masuk menggunakan akun IMZYY yang sudah dibuat di perangkat ini.",
                    color = Ink.Sub, fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("LOGIN", !registerMode) { registerMode = false; error = "" }
                    Chip("REGISTER", registerMode) { registerMode = true; error = "" }
                }
                InkField(username, { username = it; error = "" }, "Username")
                InkPasswordField(password, { password = it; error = "" }, "Password")
                if (registerMode) InkPasswordField(confirm, { confirm = it; error = "" }, "Konfirmasi password")
                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A8A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                InkButton(
                    if (busy) "MEMPROSES…" else if (registerMode) "BUAT AKUN SEKARANG" else "MASUK KE IMZYY",
                    onClick = {
                        if (busy) return@InkButton
                        if (registerMode && password != confirm) {
                            error = "Konfirmasi password belum sama."
                        } else {
                            busy = true
                            error = ""
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        if (registerMode) ImzyyAccountStore.register(ctx, username, password)
                                        else ImzyyAccountStore.login(ctx, username, password)
                                    }
                                    password = ""
                                    confirm = ""
                                    onAuthenticated()
                                } catch (e: Exception) {
                                    error = e.message ?: "Tidak dapat memproses akun."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "AKUN LOKAL • Tidak perlu email • Tidak dikirim ke server. Akun ini untuk masuk ke launcher, bukan akun Minecraft online.",
                color = Ink.Sub, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SkinTab(s: HubState) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { s.importSkin(it) }
    }
    val tick = s.skinTick
    val preview = remember(tick) { ImzyySkinManager.preview(ctx) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Reveal(0) { Title("SKIN STUDIO", "Impor PNG dan pasang ke instance Minecraft") } }
        item { Reveal(1) { TargetCard(s) } }
        item {
            Reveal(2) {
                Panel {
                    Text("SKIN PREVIEW", color = Ink.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (preview != null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = preview.asImageBitmap(),
                                contentDescription = "Preview tekstur skin Minecraft",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.width(180.dp).height(180.dp),
                            )
                        }
                        Text("Skin tersimpan di profil IMZYY ini.", color = Ink.Sub, fontSize = 12.sp)
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(120.dp).background(Ink.Raised, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Text("BELUM ADA SKIN", color = Ink.Sub, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        Text("Pilih gambar PNG skin Minecraft berukuran tepat 64 × 64 piksel.", color = Ink.Sub, fontSize = 12.sp)
                    }
                    InkButton("IMPORT FILE PNG", { picker.launch(arrayOf("image/png")) }, enabled = !s.busy)
                    Text("Model lengan skin", color = Ink.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("CLASSIC · STEVE", !s.skinSlim) { s.setSkinModel(false) }
                        Chip("SLIM · ALEX", s.skinSlim) { s.setSkinModel(true) }
                    }
                    Text("Pilih model yang sesuai dengan desain skin PNG kamu.", color = Ink.Sub, fontSize = 11.sp)
                }
            }
        }
        item {
            Reveal(3) {
                Panel {
                    Text("TERAPKAN KE MINECRAFT", color = Ink.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Pilih instance pada kartu INSTANCE di atas, lalu pasang skin. Launcher membuat resource pack IMZYY dan mengaktifkannya otomatis ketika PLAY dijalankan.",
                        color = Ink.Sub, fontSize = 12.sp,
                    )
                    InkButton("PAKAI SKIN DI INSTANCE", { s.applySkin() }, enabled = !s.busy && preview != null)
                    InkButton("HAPUS SKIN LOKAL", { s.removeSkin() }, filled = false, enabled = !s.busy && preview != null)
                }
            }
        }
        item {
            Reveal(4) {
                Panel {
                    Text("CATATAN KOMPATIBILITAS", color = Ink.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Skin ini dipasang sebagai pengganti tekstur skin bawaan lokal melalui resource pack. Ini tidak mengunggah skin ke akun Minecraft, tidak mengubah skin pemain online yang sudah memiliki tekstur sendiri, dan hasilnya bergantung pada versi serta model player.",
                        color = Ink.Sub, fontSize = 11.sp,
                    )
                }
            }
        }
        item { LogCard(s) }
    }
}

@Composable
private fun AccountTab(s: HubState, onLogout: () -> Unit) {
    val account = ImzyyAccountStore.current(s.ctx)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Reveal(0) { Title("AKUN IMZYY", "Identitas lokal launcher kamu") } }
        item {
            Reveal(1) {
                Panel {
                    Box(
                        Modifier.fillMaxWidth().height(90.dp).background(Ink.Raised, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(account?.displayName?.take(1)?.uppercase() ?: "I", color = Ink.White, fontWeight = FontWeight.ExtraBold, fontSize = 38.sp)
                    }
                    Text(account?.displayName ?: "IMZYY USER", color = Ink.White, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                    Text("@${account?.username ?: "local"}", color = Ink.Sub, fontSize = 13.sp)
                    Chip("AKUN AKTIF DI PERANGKAT INI", true, compact = true) {}
                }
            }
        }
        item {
            Reveal(2) {
                Panel {
                    Text("KEAMANAN & PENYIMPANAN", color = Ink.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Password disimpan sebagai hash PBKDF2 bersalt di penyimpanan privat aplikasi, bukan sebagai teks biasa. Akun ini bersifat lokal: belum ada sinkronisasi cloud, reset password email, atau login lintas perangkat.",
                        color = Ink.Sub, fontSize = 12.sp,
                    )
                    Text(
                        "Akun IMZYY hanya mengelola akses ke launcher dan skin lokal. Ini bukan akun Minecraft dan tidak menerbitkan token untuk server online.",
                        color = Ink.Sub, fontSize = 12.sp,
                    )
                    InkButton("KELUAR DARI AKUN", onLogout, filled = false)
                }
            }
        }
    }
}
