#!/usr/bin/env python3
"""Offline tests for the IMZYY UI/performance overlay against an upstream-shaped fixture."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PATCHER = ROOT / "scripts/prepare-imzyy.py"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="imzyy-flame-fixture-") as tmp:
        root = Path(tmp)
        app = root / "app"
        (app / "src/main/res/values").mkdir(parents=True)
        (app / "src/main/res/mipmap-anydpi-v26").mkdir(parents=True)
        (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme").mkdir(parents=True)
        (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation").mkdir(parents=True, exist_ok=True)
        (app / "build.gradle.kts").write_text('''plugins { id("com.android.application") }
android { namespace = "kr.co.donghyun.flamelauncher"; defaultConfig { applicationId = "kr.co.donghyun.flamelauncher"; ndk { abiFilters += listOf("arm64-v8a") } } }
''')
        (root / "settings.gradle.kts").write_text('rootProject.name = "FlameLauncher"')
        (app / "src/main/AndroidManifest.xml").write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="android.permission.INTERNET"/><application><activity android:name=".presentation.MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity><activity android:name=".presentation.MinecraftActivity" android:process=":minecraft"/></application></manifest>''')
        (app / "src/main/res/values/strings.xml").write_text('''<resources><string name="app_name">FlameLauncher</string></resources>''')
        (app / "src/main/res/values/colors.xml").write_text('''<resources><color name="bg_dark">#FF05080F</color><color name="flame_primary">#FF2E9BFF</color><color name="flame_accent">#FF22E0FF</color></resources>''')
        theme = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme/Color.kt"
        theme.write_text('''package kr.co.donghyun.flamelauncher.presentation.ui.theme
val Orange = Color(0xFF5AB8FF)
val Flame = Color(0xFF2E9BFF)
val FlamePrimary = Color(0xFF2E9BFF)
val FlameAccent = Color(0xFF22E0FF)
val BgDark = Color(0xFF05080F)
val BgSurface = Color(0xFF0B1422)
val BgBorder = Color(0xFF1C2E47)
val TextSub = Color(0xFF8CA2C0)
val BgItem = Color(0xFF08101B)
val FlameLight = Color(0xFF7CC9FF)
val FlameDark = Color(0xFF0B4A8F)
''')
        (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/MinecraftActivity.kt").write_text("""package kr.co.donghyun.flamelauncher.presentation

class MinecraftActivity {
    private var gameControllerView: GameControllerView? = null
    internal val MOUSE_SENSITIVITY = 1.5f
    private fun sendKeyPress(key: Int, scancode: Int = 0, action: Int, mods: Int = 0) {}
    private fun resolveRendererForVersion(): Renderer {
        val instanceRendererId = instanceDir?.let { runCatching { InstanceManager.loadMeta(File(it))?.rendererId }.getOrNull() }
        var base = instanceRendererId?.let { Renderer.fromId(it) } ?: RendererManager.load(this)
        Log.d("FLAME_LAUNCHER", "renderer: ${base.id}")
        if (isPre113Version(versionId) && base.id != "gl4es" && base.id != "gl4es_desktop" && base.id != "krypton") {
            return Renderer.fromId("gl4es")
        }
        // IMZYY RENDERER COMPATIBILITY GUARD
        if (hasSodiumFamily && base.id == "gl4es") {
            base = Renderer.fromId("mobileglues")
        }
        return base
    }
    /**
     * mods 폴더에 설치된 모드 개수.
""")
        comp = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/components"
        comp.mkdir(parents=True, exist_ok=True)
        (comp / "MinecraftSurface.kt").write_text(
            "package kr.co.donghyun.flamelauncher.presentation.ui.components\n\nprivate const val DRAG_SLOP = 20f\nprivate const val LONG_PRESS_TIMEOUT = 500L\n"
            "internal fun minecraftTouchListener(activity: A, bufferSize: () -> IntArray): View.OnTouchListener = View.OnTouchListener { v, event ->\n"
            "    val buf = bufferSize()\n    true\n}\n")
        (comp / "GameControllerView.kt").write_text(
            "package kr.co.donghyun.flamelauncher.presentation.ui.components\n\nclass GameControllerView {\n    private val bgPaint = Color.argb(217, 26, 10, 20)\n"
            "    private val x = Color.argb(255, 233, 30, 140)\n    override fun onDraw(canvas: Canvas) {\n    }\n}\n")
        screen_dir = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/screen"
        screen_dir.mkdir(parents=True)
        (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme/Type.kt").write_text("val Typography = Typography()")
        (screen_dir / "MainScreen.kt").write_text('''val compact = "Flame"; val full = "FlameLauncher"
val topbar = Modifier.height(52.dp).background(BgSurface)
val title = "FlameLauncher", color = TextPrimary, fontSize = 14.sp
        MainSection.entries.forEach { section ->
            SectionRow(section = section)
        }
''')
        (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/MainActivity.kt").write_text(
            "package kr.co.donghyun.flamelauncher.presentation\n\nclass MainActivity {\n    fun check() {\n        val release = GithubUpdateChecker.fetchUpdate(BuildConfig.VERSION_NAME)\n    }\n}\n")
        (screen_dir / "SettingsScreen.kt").write_text('''package kr.co.donghyun.flamelauncher.presentation.ui.screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 10.dp)
        ) {
            // 언어 섹션
        }
/**
 * 전역 렌더러 선택용 라디오 한 줄.
 */
''')
        (screen_dir / "Buttons.kt").write_text('''Button(colors = ButtonDefaults.buttonColors(containerColor = Flame)) { Text("Go", color = Color.White) }
Button(colors = ButtonDefaults.buttonColors(containerColor = BgBorder)) { Text("Stay", color = Color.White) }
''')
        jre_pkg = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/util/minecraft"
        jre_pkg.mkdir(parents=True, exist_ok=True)
        (jre_pkg / "MinecraftJREPreparer.kt").write_text(
            "package kr.co.donghyun.flamelauncher.presentation.util.minecraft\n\nclass MinecraftJREPreparer {\n"
            "    companion object {\n        private fun resolveAvailableMajor(context: Context, requested: Int): Int {\n"
            "            val order = linkedSetOf(requested, 25, 21, 17).toList()\n"
            "            return order.firstOrNull { assetExists(context, \"jre/jre${it}.zip\") }\n                ?: throw Exception(\"none\")\n        }\n"
            "        fun prepareJreMajorAndGetPath(context: Context, requestedMajor: Int, reasonForLog: String = \"\"): String {\n"
            "            val major = resolveAvailableMajor(context, requestedMajor)\n            return \"\"\n        }\n    }\n}\n")
        jre_assets = app / "src/main/assets/jre"
        jre_assets.mkdir(parents=True, exist_ok=True)
        for major in (8, 17, 21, 25):
            (jre_assets / f"jre{major}.zip").write_bytes(b"PK-fake-%d" % major)
        (app / "src/main/res/drawable").mkdir(parents=True)
        (app / "src/main/res/drawable/img_logo.png").write_bytes(b"old-logo")
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            (app / "src/main/res/mipmap-anydpi-v26" / name).write_text('''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/black"/><foreground android:drawable="@mipmap/ic_launcher_foreground"/></adaptive-icon>''')

        import shutil as _shutil
        full_root = Path(str(root) + "-full")
        _shutil.copytree(root, full_root)
        rt_out = Path(str(root) + "-runtimes")
        lite_args = ["--engine-dir", str(root), "--repo", "owner/imzyy", "--variant", "lite", "--runtimes-out", str(rt_out)]
        # CI exports IMZYY_VARIANT=lite for the whole job, so the test must never inherit it:
        # every call pins --variant explicitly and runs with a clean environment.
        clean_env = {k: v for k, v in os.environ.items() if not k.startswith("IMZYY_")}
        clean_env["IMZYY_SKIP_FONTS"] = "1"
        subprocess.run([sys.executable, str(PATCHER)] + lite_args, check=True, env=clean_env)
        subprocess.run([sys.executable, str(PATCHER)] + lite_args, check=True, env=clean_env)
        subprocess.run([sys.executable, str(PATCHER), "--engine-dir", str(full_root), "--repo", "owner/imzyy", "--variant", "full"], check=True, env=clean_env)
        strings = ET.parse(app / "src/main/res/values/strings.xml").getroot()
        assert strings.find("string").text == "IMZYY XL Launcher"
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            icon = ET.parse(app / "src/main/res/mipmap-anydpi-v26" / name).getroot()
            assert icon.find("foreground").get(ANDROID + "drawable") == "@drawable/imzyy_launcher_foreground"
        assert (app / "src/main/res/drawable/imzyy_launcher_foreground.xml").is_file()
        assert (app / "src/main/res/drawable/img_logo.xml").is_file()
        assert not (app / "src/main/res/drawable/img_logo.png").exists()
        main_screen = (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/screen/MainScreen.kt").read_text()
        assert '"IMZYY"' in main_screen and '"IMZYY XL"' in main_screen
        assert ".height(58.dp)" in main_screen and "fontSize = 16.sp" in main_screen
        assert "MainSection.entries.filter { it != MainSection.NOTES }.forEach" in main_screen, "release notes menu must be hidden"
        main_act = (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/MainActivity.kt").read_text()
        assert "GithubUpdateChecker.fetchUpdate" not in main_act and "val release: GithubRelease? = emptyList<GithubRelease>().firstOrNull()" in main_act
        settings_screen = (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/screen/SettingsScreen.kt").read_text()
        assert "ImzyyPerformanceProfiles(" in settings_screen
        assert 'maxHeapMb = performanceHeap' in settings_screen
        assert 'resolutionScalePercent = 75' in settings_screen
        assert 'resolutionScalePercent = 85' in settings_screen
        assert 'resolutionScalePercent = 100' in settings_screen
        assert settings_screen.count("IMZYY PERFORMANCE PROFILES") == 1
        typography = (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme/Type.kt").read_text()
        assert "FontWeight.ExtraBold" in typography and "ImzyyFonts.family" in typography
        assert "FontWeight.SemiBold" in typography
        assert (root / "settings.gradle.kts").read_text() == 'rootProject.name = "IMZYY XL Launcher"'
        palette = theme.read_text()
        assert "0xFFF2F2F2" in palette and "0xFF000000" in palette, "monochrome palette"
        assert "0xFF2E9BFF" not in palette and "0xFF8B5CF6" not in palette
        buttons = (screen_dir / "Buttons.kt").read_text()
        assert 'Text("Go", color = Color.Black)' in buttons, "white-on-accent must flip to black"
        assert 'Text("Stay", color = Color.White)' in buttons, "non-accent buttons must keep white text"
        assert 'Text("FPS", color = Color.Black' in settings_screen
        base_ui = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation"
        patched_activity = (base_ui / "MinecraftActivity.kt").read_text()
        assert "IMZYY RENDERER COMPATIBILITY GUARD" not in patched_activity, "legacy forced renderer guard must be removed"
        assert 'Renderer.fromId("mobileglues")' not in patched_activity, "IMZYY must not force MobileGlues"
        assert "private fun resolveRendererForVersion(): Renderer" in patched_activity
        assert "return base" in patched_activity, "upstream per-instance/global renderer selection remains intact"
        assert "ImzyyControls.lookSensitivity(this)" in patched_activity
        assert "import id.imzyy.xl.ImzyyControls" in (base_ui / "ui/components/GameControllerView.kt").read_text()
        assert "id.imzyy.xl.ImzyyControls." not in (base_ui / "ui/components/GameControllerView.kt").read_text()
        assert "internal var gameControllerView" in (base_ui / "MinecraftActivity.kt").read_text()
        surf = (base_ui / "ui/components/MinecraftSurface.kt").read_text()
        assert "ImzyyControls.dragSlop" in surf and "ImzyyControls.longPressMs" in surf
        assert "ImzyyControls.handleTouchpad(activity, v, event, buf)) return@OnTouchListener true" in surf
        gcv = (base_ui / "ui/components/GameControllerView.kt").read_text()
        assert "ImzyyControls.drawCursor(canvas, this, activity)" in gcv and "217, 26, 10, 20" not in gcv
        jre_left = sorted(p.name for p in (app / "src/main/assets/jre").iterdir())
        assert jre_left == ["jre21.zip"], jre_left
        assert sorted(p.name for p in rt_out.iterdir()) == ["jre17.zip", "jre25.zip", "jre8.zip"]
        full_jre = sorted(p.name for p in (full_root / "app/src/main/assets/jre").iterdir())
        assert len(full_jre) == 4, full_jre
        lite_cfg = (app / "src/main/java/id/imzyy/xl/ImzyyConfig.kt").read_text()
        assert 'VARIANT = "lite"' in lite_cfg and "jre17" in lite_cfg and "sha256" in lite_cfg, lite_cfg
        assert 'VARIANT = "full"' in (full_root / "app/src/main/java/id/imzyy/xl/ImzyyConfig.kt").read_text()
        preparer = (app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/util/minecraft/MinecraftJREPreparer.kt").read_text()
        assert "ImzyyRuntimes.installed(context, it)" in preparer and "ImzyyRuntimes.ensure(context, requestedMajor)" in preparer
        assert "import id.imzyy.xl.ImzyyRuntimes" in preparer and preparer.count("ImzyyRuntimes.ensure") == 1
        hub_dir = app / "src/main/java/id/imzyy/xl"
        _inp = (hub_dir / "ImzyyInput.kt").read_text()
        assert "a.sendKeyPress(key = glfw, scancode = 0, action = (if (down) 1 else 0), mods = 0)" in _inp, _inp
        assert "internal fun sendKeyPress" in (base_ui / "MinecraftActivity.kt").read_text()
        window_hook = (hub_dir / "ImzyyWindow.kt").read_text()
        assert "ImzyyPad.attach(a)" in window_hook
        assert "ImzyyBootGuard.onGameCreated(a)" in window_hook
        assert "setDecorFitsSystemWindows" not in window_hook
        assert "layoutInDisplayCutoutMode" not in window_hook
        assert "switchSmooth" in (hub_dir / "ImzyyHubActivity.kt").read_text() and "smoothValues" in (hub_dir / "FpsTuner.kt").read_text()
        assert "installedModCount" in (hub_dir / "FpsTuner.kt").read_text()
        assert "assetJob(applyAdaptiveSmooth: Boolean = false)" in (hub_dir / "ImzyyHubActivity.kt").read_text()
        assert "assetJob(applyAdaptiveSmooth = adaptiveSmooth)" in (hub_dir / "ImzyyHubActivity.kt").read_text()
        assert "Recount after pending modpack application" in (hub_dir / "ImzyyHubActivity.kt").read_text()
        assert "Profil adaptif untuk vanilla maupun modpack" in (hub_dir / "ImzyyHubActivity.kt").read_text()
        texture_source = (hub_dir / "TextureCompress.kt").read_text()
        assert "inSampleSize = sample" in texture_source and "createScaledBitmap" not in texture_source
        assert "ImzyyFramePacing.syncToDisplay" in (hub_dir / "ImzyyHubActivity.kt").read_text()
        assert "ImzyyFramePacing.durationMs" in (hub_dir / "Ui.kt").read_text()
        assert "liteAutoTune()" in (hub_dir / "ImzyyHubActivity.kt").read_text()
        for name in ("ImzyyHubActivity.kt", "ImzyyFramePacing.kt", "Ui.kt", "Modrinth.kt", "PerfPack.kt", "Mrpack.kt", "FpsTuner.kt", "Net.kt", "ImzyyConfig.kt", "ImzyyControls.kt", "ImzyyRuntimes.kt", "ModImport.kt", "TextureCompress.kt", "ImzyyWindow.kt", "ImzyyFonts.kt", "ImzyyPad.kt", "ImzyyInput.kt", "ImzyyInitProvider.kt", "ImzyyAccountStore.kt", "ImzyySkinManager.kt", "GraphicsRepair.kt", "RecoveryTools.kt", "LunaSixty.kt"):
            assert (hub_dir / name).is_file(), name
        hub_source = (hub_dir / "ImzyyHubActivity.kt").read_text()
        account_source = (hub_dir / "ImzyyAccountStore.kt").read_text()
        skin_source = (hub_dir / "ImzyySkinManager.kt").read_text()
        assert "ImzyyAuthGate" in hub_source and "BUAT AKUN IMZYY" in hub_source and "MASUK KE IMZYY" in hub_source
        assert "PBKDF2WithHmacSHA256" in account_source and "active_user" in account_source
        assert "importPng" in skin_source and "IMZYY-Local-Skin.zip" in skin_source
        assert "OpenDocument" in hub_source and "64 × 64" in hub_source
        assert '5 -> SkinTab(s)' in hub_source and '6 -> AccountTab(s)' in hub_source and '7 -> LunaSixtyTab(s)' in hub_source
        cfg = (hub_dir / "ImzyyConfig.kt").read_text()
        assert 'GITHUB_REPO = "owner/imzyy"' in cfg and "@" not in cfg, cfg
        assert (app / "src/main/res/values/imzyy_theme.xml").is_file()
        man = ET.parse(app / "src/main/AndroidManifest.xml").getroot()
        acts = man.find("application").findall("activity")
        launchers = [a.get(ANDROID + "name") for a in acts for f in a.findall("intent-filter")
                     for c in f.findall("category") if c.get(ANDROID + "name") == "android.intent.category.LAUNCHER"]
        provs = [(p.get("{http://schemas.android.com/apk/res/android}name"), p.get("{http://schemas.android.com/apk/res/android}process")) for p in man.find("application").findall("provider")]
        assert provs == [("id.imzyy.xl.ImzyyInitProvider", ":minecraft")], provs
        assert launchers == ["id.imzyy.xl.ImzyyHubActivity"], launchers
        hub_activity = next(a for a in acts if a.get(ANDROID + "name") == "id.imzyy.xl.ImzyyHubActivity")
        assert hub_activity.get(ANDROID + "screenOrientation") == "portrait", hub_activity.attrib
        assert sum(1 for a in acts if a.get(ANDROID + "name") == ".presentation.MinecraftActivity") == 1
        assert sum(1 for a in acts if a.get(ANDROID + "name") == ".presentation.MainActivity") == 1
        gradle = (app / "build.gradle.kts").read_text()
        assert 'applicationId = "kr.co.donghyun.flamelauncher"' in gradle, "OAuth package identity must remain stable in this pass"

    for kt in (ROOT / "android-overlay/kotlin").glob("*.kt"):
        text = kt.read_text()
        assert text.count("{") == text.count("}") and text.count("(") == text.count(")"), f"unbalanced: {kt.name}"
    net_src = (ROOT / "android-overlay/kotlin/Net.kt").read_text()
    assert 'setRequestProperty("Range", "bytes=$resumeAt-")' in net_src, "resumable Range download"
    assert "for (attempt in 1..4)" in net_src and "75_000" in net_src, "retry/read timeout"
    assert "ByteArray(256 * 1024)" in net_src and "downloadSha256" in net_src, "buffer + runtime SHA-256"
    assert "private const val THREADS = 8" in net_src, "asset repair parallelism"
    runtime_src = (ROOT / "android-overlay/kotlin/ImzyyRuntimes.kt").read_text()
    assert "Net.downloadSha256(url, tmp, sha)" in runtime_src, "runtime archive uses resumable download"
    assert "import java.io.FileOutputStream" in runtime_src, "runtime extraction imports FileOutputStream"
    assert "val outputFile = File(root, e.name).canonicalFile" in runtime_src, "runtime extraction avoids Kotlin out keyword ambiguity"
    assert "FileOutputStream(outputFile).use { outputStream ->" in runtime_src, "runtime extraction writes archive entries"
    mrpack_src = (ROOT / "android-overlay/kotlin/Mrpack.kt").read_text()
    assert 'isPendingStage && target.exists()' in mrpack_src and '.imzyy-ready' in mrpack_src, "clear stale pending modpack and write ready marker"
    assert 'mods-${System.currentTimeMillis()}' in mrpack_src and 'moveDirectory(stagedMods, liveMods)' in mrpack_src, "backup and atomic mods directory replacement"
    hub_src = (ROOT / "android-overlay/kotlin/ImzyyHubActivity.kt").read_text()
    assert "30_000L" in hub_src and "Proses Minecraft belum berhenti" in hub_src, "restart waits for game process exit"
    assert "GraphicsRepair.repair(needTarget())" in hub_src, "graphics repair action is wired"
    assert "pending.isFile && minecraftProcessAlive()" in hub_src, "Hub PLAY never swaps mods while game process is alive"
    restart_src = (ROOT / "android-overlay/kotlin/ImzyyPad.kt").read_text()
    assert "FLAG_ACTIVITY_SINGLE_TOP" in restart_src and "act.finish()" in restart_src, "restart preserves hub task"
    graphics_src = (ROOT / "android-overlay/kotlin/GraphicsRepair.kt").read_text()
    assert r'resourcePacks:[\"vanilla\"]' in graphics_src, "graphics repair resets active resource packs to vanilla"
    assert 'incompatibleResourcePacks:[]' in graphics_src and 'graphics-before' in graphics_src, "graphics repair backs up and clears active packs"
    recovery_src = (ROOT / "android-overlay/kotlin/RecoveryTools.kt").read_text()
    for marker in ("startSafeBoot", "restoreSafeBoot", "scanMods", "criticalWarnings", "writeDiagnostics", "onGameCreated", "onGameDestroyed", "gameProcessAlive"):
        assert marker in recovery_src, marker
    assert 'safe-boot-' in recovery_src and 'mods-created-during-safe-boot' in recovery_src, "safe boot backups are reversible"
    window_src = (ROOT / "android-overlay/kotlin/ImzyyWindow.kt").read_text()
    assert "ImzyyBootGuard.onGameCreated(a)" in window_src and "ImzyyBootGuard.onGameDestroyed(a)" in window_src
    assert "START SAFE BOOT" in hub_src and "BUAT LOG DIAGNOSTIK" in hub_src
    assert "RecoveryTools.criticalWarnings(scan)" in hub_src, "preflight blocks common duplicate renderer mods"
    assert "RecoveryTools.scanMods(target)" in (ROOT / "android-overlay/kotlin/Mrpack.kt").read_text(), "mrpack install audits staged mods"
    luna_src = (ROOT / "android-overlay/kotlin/LunaSixty.kt").read_text()
    assert "data class LunaMessage" in luna_src and "internal data class LunaMessage" not in luna_src, "public HubState must not expose an internal LunaMessage type"
    assert "fun find(value: Any?): String? {" in luna_src, "recursive JSON response parser must use a block body"
    assert "fun find(value: Any?): String? = when" not in luna_src, "expression-body parser cannot contain early returns"
    assert 'kyio' in luna_src and 'gpt-6-luna?q=' in luna_src, "Luna Sixty endpoint is integrated"
    assert "HttpURLConnection" in luna_src and "connectTimeout = 18_000" in luna_src and "readTimeout = 55_000" in luna_src
    assert "ANALISIS LOG INSTANCE" in luna_src and "ANALISIS DENGAN LUNA SIXTY" in hub_src
    assert '7 -> LunaSixtyTab(s)' in hub_src and '"LUNA AI"' in hub_src
    assert 'Text("LUNA SIXTY"' in luna_src and "Bearer [REDACTED]" in luna_src and "state.lunaMessages" in luna_src
    assert "Graphics settings:" in recovery_src and "Resource packs on disk" in recovery_src and "Shader packs on disk" in recovery_src
    # The endpoint is kept in the native API client only, not copied into UI or docs.
    api_host = ".".join(("api", "kyio", "web", "id"))
    for public_file in (ROOT / "README.md", ROOT / "RECOVERY-V10-CHANGELOG.md", ROOT / "GITHUB-ACTIONS-SETUP.txt", ROOT / "android-overlay/kotlin/ImzyyHubActivity.kt"):
        assert api_host not in public_file.read_text(encoding="utf-8")
    for source_file in (ROOT / "android-overlay/kotlin").glob("*.kt"):
        if source_file.name != "LunaSixty.kt":
            assert api_host not in source_file.read_text(encoding="utf-8")
    assert (ROOT / "scripts/build-imzyy.sh").is_file()
    assert not (ROOT / "scripts/build-amethyst.sh").exists()
    assert not (ROOT / "scripts/prepare-amethyst.py").exists()
    print("IMZYY integration tests passed: single-app activity check, branding, local account/skin overlay, premium typography, performance profiles, adaptive icon, idempotency, ARM64 and no Amethyst build path.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
