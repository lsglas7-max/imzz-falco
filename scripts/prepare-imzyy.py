#!/usr/bin/env python3
"""Apply the IMZYY XL identity to a clean FlameLauncher Android checkout.

The upstream applicationId/namespace are intentionally preserved in this
first integration pass: changing Android OAuth callback identity without
re-registering and validating the Microsoft application can break sign-in.
The visible app name, launcher icon, palette and output artifact are branded.
"""
from __future__ import annotations
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)

ICON = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF000000" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#FFFFFFFF" android:pathData="M20,26 L43,26 L54,45 L65,26 L88,26 L88,82 L68,82 L68,55 L54,76 L40,55 L40,82 L20,82 Z" />
    <path android:fillColor="#FF8C8C8C" android:pathData="M54,17 L63,32 L54,47 L45,32 Z" />
</vector>
'''


def require_file(path: Path) -> str:
    if not path.is_file():
        raise RuntimeError(f"File upstream tidak ditemukan: {path}")
    return path.read_text(encoding="utf-8")


def patch_strings(app: Path) -> None:
    patched_count = 0
    for path in (app / "src/main/res").rglob("strings.xml"):
        source = path.read_text(encoding="utf-8")
        patched, count = re.subn(
            r'(<string\s+name="app_name"[^>]*>).*?(</string>)',
            r'\g<1>IMZYY XL Launcher\g<2>',
            source,
            count=1,
            flags=re.DOTALL,
        )
        if count:
            path.write_text(patched, encoding="utf-8")
            patched_count += 1
    if patched_count == 0:
        raise RuntimeError("Resource string app_name tidak ditemukan.")


def patch_palette(app: Path) -> None:
    path = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme/Color.kt"
    source = require_file(path)
    replacements = {
        "0xFF5AB8FF": "0xFFE6E6E6",
        "0xFF2E9BFF": "0xFFF2F2F2",
        "0xFF22E0FF": "0xFFBDBDBD",
        "0xFF7CC9FF": "0xFFD9D9D9",
        "0xFF0B4A8F": "0xFF3A3A3A",
        "0xFF05080F": "0xFF000000",
        "0xFF0B1422": "0xFF0A0A0A",
        "0xFF1C2E47": "0xFF262626",
        "0xFF8CA2C0": "0xFF8C8C8C",
        "0xFF08101B": "0xFF050505",
        # sources that were already patched by v2 (violet) are converted as well
        "0xFF9B7BFF": "0xFFE6E6E6",
        "0xFF8B5CF6": "0xFFF2F2F2",
        "0xFF080B16": "0xFF000000",
        "0xFF11182B": "0xFF0A0A0A",
        "0xFF293653": "0xFF262626",
        "0xFF9AA8C7": "0xFF8C8C8C",
        "0xFF0D1424": "0xFF050505",
        "0xFFC4B5FD": "0xFFD9D9D9",
        "0xFF4C1D95": "0xFF3A3A3A",
        "0xFF22D3EE": "0xFFBDBDBD",
        "0xFFA78BFA": "0xFFBDBDBD",
        "0xFFE8F2FF": "0xFFF5F5F5",
        "0xFF6D7F96": "0xFF6E6E6E",
        "0xFF2A1424": "0xFF1A1A1A",
        "0x9905080F": "0x99000000",
    }
    for old, new in replacements.items():
        source = source.replace(old, new)
    if "0xFFF2F2F2" not in source or "0xFF000000" not in source:
        raise RuntimeError("Palette Compose tidak berhasil dipatch.")
    path.write_text(source, encoding="utf-8")

    colors = app / "src/main/res/values/colors.xml"
    xml = require_file(colors)
    xml = xml.replace("#FF05080F", "#FF000000")
    xml = xml.replace("#FF0B1422", "#FF0A0A0A")
    xml = xml.replace("#FF2E9BFF", "#FFF2F2F2")
    xml = xml.replace("#FF22E0FF", "#FFBDBDBD")
    colors.write_text(xml, encoding="utf-8")


def patch_icon(app: Path) -> None:
    (app / "src/main/res/drawable").mkdir(parents=True, exist_ok=True)
    (app / "src/main/res/drawable/imzyy_launcher_foreground.xml").write_text(ICON, encoding="utf-8")
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = app / "src/main/res/mipmap-anydpi-v26" / name
        if not path.exists():
            continue
        tree = ET.parse(path)
        root = tree.getroot()
        foreground = root.find("foreground")
        if foreground is None:
            raise RuntimeError(f"Adaptive icon tidak memiliki foreground: {path}")
        foreground.set(f"{{{ANDROID}}}drawable", "@drawable/imzyy_launcher_foreground")
        tree.write(path, encoding="utf-8", xml_declaration=True)


def patch_in_app_branding(app: Path) -> None:
    main_screen = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/screen/MainScreen.kt"
    source = require_file(main_screen)
    source, count_long = re.subn(r'"FlameLauncher"', '"IMZYY XL"', source)
    source, count_short = re.subn(r'"Flame"', '"IMZYY"', source)
    if (count_long < 1 and '"IMZYY XL"' not in source) or (count_short < 1 and '"IMZYY"' not in source):
        raise RuntimeError("Teks brand di app bar tidak ditemukan; upstream UI mungkin berubah.")
    source, topbar_count = re.subn(
        r"\.height\(52\.dp\)(\s*\.background\(BgSurface\))",
        r".height(58.dp)\1",
        source,
        count=1,
    )
    source = source.replace(
        '"IMZYY XL", color = TextPrimary, fontSize = 14.sp',
        '"IMZYY XL", color = TextPrimary, fontSize = 16.sp',
        1,
    )
    main_screen.write_text(source, encoding="utf-8")

    # Replace the upstream flame logo with a vector mark so the in-app header
    # and Android launcher icon use the same IMZYY identity.
    res = app / "src/main/res"
    for path in res.rglob("img_logo.*"):
        if path.is_file():
            path.unlink()
    logo = res / "drawable/img_logo.xml"
    logo.parent.mkdir(parents=True, exist_ok=True)
    logo.write_text(ICON.replace('android:width="108dp" android:height="108dp"',
                                 'android:width="48dp" android:height="48dp"'), encoding="utf-8")



INTER_URL = "https://github.com/rsms/inter/releases/download/v4.0/Inter-4.0.zip"
INTER_FILES = {"Inter-Regular.ttf": "inter_regular.ttf", "Inter-Medium.ttf": "inter_medium.ttf",
               "Inter-SemiBold.ttf": "inter_semibold.ttf", "Inter-Bold.ttf": "inter_bold.ttf"}
# Optional tighter display cut for Bold/ExtraBold headings (more character than plain Inter Bold).
INTER_DISPLAY = {"InterDisplay-Bold.ttf": "inter_display_bold.ttf", "InterDisplay-ExtraBold.ttf": "inter_display_xbold.ttf"}


def _app_namespace(app: Path) -> str | None:
    for name in ("build.gradle.kts", "build.gradle"):
        f = app / name
        if f.exists():
            m = re.search(r"namespace\s*=?\s*[\"']([\w.]+)[\"']", f.read_text(encoding="utf-8"))
            if m:
                return m.group(1)
    return None


def patch_fonts(app: Path) -> str:
    """Bundle Inter (open-source, closest to the iPhone look; Apple's SF Pro cannot be redistributed).
    Falls back to the system sans face if the download or namespace lookup fails."""
    import io
    import os
    import urllib.request
    import zipfile

    hub = app / "src/main/java/id/imzyy/xl"
    hub.mkdir(parents=True, exist_ok=True)
    fallback = "package id.imzyy.xl\n\nimport androidx.compose.ui.text.font.FontFamily\n\nobject ImzyyFonts {\n    val family: FontFamily = FontFamily.SansSerif\n}\n"
    ns = _app_namespace(app)
    font_dir = app / "src/main/res/font"
    ready = all((font_dir / v).exists() for v in INTER_FILES.values())
    if not ready and ns and not os.environ.get("IMZYY_SKIP_FONTS"):
        try:
            data = urllib.request.urlopen(INTER_URL, timeout=60).read()
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                found = {}
                for n in z.namelist():
                    base = n.rsplit("/", 1)[-1]
                    if (base in INTER_FILES or base in INTER_DISPLAY) and "ttf" in n.lower() and "variable" not in n.lower():
                        found.setdefault(base, n)
                if all(k in found for k in INTER_FILES):
                    font_dir.mkdir(parents=True, exist_ok=True)
                    for base, target in {**INTER_FILES, **INTER_DISPLAY}.items():
                        if base in found:
                            (font_dir / target).write_bytes(z.read(found[base]))
                    ready = True
        except Exception as exc:  # network optional
            print(f"fonts: Inter tidak diunduh ({exc}); pakai font sistem")
    display = all((font_dir / v).exists() for v in INTER_DISPLAY.values())
    bold_file, xbold_file = ("inter_display_bold", "inter_display_xbold") if display else ("inter_bold", "inter_bold")
    if ready and ns:
        (hub / "ImzyyFonts.kt").write_text(
            "package id.imzyy.xl\n\nimport androidx.compose.ui.text.font.Font\nimport androidx.compose.ui.text.font.FontFamily\n"
            "import androidx.compose.ui.text.font.FontWeight\nimport " + ns + ".R\n\nobject ImzyyFonts {\n    val family: FontFamily = FontFamily(\n"
            "        Font(R.font.inter_regular, FontWeight.Normal),\n        Font(R.font.inter_medium, FontWeight.Medium),\n"
            "        Font(R.font.inter_semibold, FontWeight.SemiBold),\n        Font(R.font." + bold_file + ", FontWeight.Bold),\n"
            "        Font(R.font." + xbold_file + ", FontWeight.ExtraBold),\n    )\n}\n", encoding="utf-8")
        print("fonts: Inter dipasang")
    else:
        (hub / "ImzyyFonts.kt").write_text(fallback, encoding="utf-8")
    return "id.imzyy.xl.ImzyyFonts.family"


def patch_typography(app: Path) -> None:
    theme_dir = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme"
    type_file = theme_dir / "Type.kt"
    require_file(type_file)
    typography = '''package kr.co.donghyun.flamelauncher.presentation.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// IMZYY XL typography: strong hierarchy, compact tracking and a clean system sans face.
val Typography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.3).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 44.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
)
'''
    typography = typography.replace("FontFamily.SansSerif", patch_fonts(app))
    type_file.write_text(typography, encoding="utf-8")

    # Strengthen explicit text weights without touching layout or state logic.
    ui_root = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui"
    for source_file in ui_root.rglob("*.kt"):
        source = source_file.read_text(encoding="utf-8")
        updated = source.replace("FontWeight.Normal", "FontWeight.SemiBold")
        updated = updated.replace("FontWeight.Medium", "FontWeight.SemiBold")
        updated = re.sub(
            r"RoundedCornerShape\((8|10)\.dp\)",
            lambda match: f"RoundedCornerShape({10 if match.group(1) == '8' else 12}.dp)",
            updated,
        )
        if updated != source:
            source_file.write_text(updated, encoding="utf-8")


def patch_performance_profiles(app: Path) -> None:
    path = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/screen/SettingsScreen.kt"
    source = require_file(path)
    marker = "// IMZYY PERFORMANCE PROFILES"
    if marker in source:
        return

    anchor = '''            verticalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 10.dp)
        ) {
            // 언어 섹션'''
    replacement = '''            verticalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 10.dp)
        ) {
            // IMZYY PERFORMANCE PROFILES
            ImzyyPerformanceProfiles(
                settings = settings,
                maxHeapCeilingMb = maxHeapCeilingMb,
                compact = compact,
                onSettingsChange = onSettingsChange,
            )
            // 언어 섹션'''
    if anchor not in source:
        raise RuntimeError("SettingsScreen content anchor changed; performance presets were not injected.")
    source = source.replace(anchor, replacement, 1)

    function_anchor = '''/**
 * 전역 렌더러 선택용 라디오 한 줄.'''
    profile_function = '''/**
 * One-tap, conservative game profiles. These modify existing upstream settings only;
 * no undocumented JVM flags or unsupported renderer switches are injected.
 */
@Composable
private fun ImzyyPerformanceProfiles(
    settings: JvmSettings,
    maxHeapCeilingMb: Int,
    compact: Boolean,
    onSettingsChange: (JvmSettings) -> Unit,
) {
    val safeCeiling = maxHeapCeilingMb.coerceAtLeast(1024)
    fun heapShare(percent: Float, hardLimitMb: Int): Int =
        (safeCeiling * percent).toInt().coerceIn(1024, minOf(safeCeiling, hardLimitMb))

    val performanceHeap = heapShare(0.45f, 2048)
    val balancedHeap = heapShare(0.58f, 3072)
    val visualHeap = heapShare(0.68f, 4096)
    val cardShape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, cardShape)
            .border(1.dp, BgBorder, cardShape)
            .padding(if (compact) 12.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "IMZYY PERFORMANCE",
            color = TextMain,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.4.sp,
        )
        Text(
            "Quick profiles for memory and render scale. Changes apply on the next game launch.",
            color = TextSub,
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    onSettingsChange(settings.copy(
                        maxHeapMb = performanceHeap,
                        resolutionScalePercent = 75,
                        useG1GC = true,
                    ))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Flame),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            ) {
                Text("FPS", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
            }
            Button(
                onClick = {
                    onSettingsChange(settings.copy(
                        maxHeapMb = balancedHeap,
                        resolutionScalePercent = 85,
                        useG1GC = true,
                    ))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BgBorder),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            ) {
                Text("BALANCED", color = TextMain, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            }
            Button(
                onClick = {
                    onSettingsChange(settings.copy(
                        maxHeapMb = visualHeap,
                        resolutionScalePercent = 100,
                        useG1GC = true,
                    ))
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BgBorder),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
            ) {
                Text("VISUAL", color = TextMain, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
            }
        }
        Text(
            "Heap is capped by detected device memory; keep extra RAM available for graphics and Android.",
            color = TextSub,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

'''
    if function_anchor not in source:
        raise RuntimeError("SettingsScreen helper insertion anchor changed.")
    source = source.replace(function_anchor, profile_function + function_anchor, 1)
    path.write_text(source, encoding="utf-8")


def _replace_once(source: str, old: str, new: str, label: str) -> str:
    """Anchor-strict replace: the anchor must exist exactly once, unless the patch is already applied."""
    if new in source:
        return source
    if source.count(old) != 1:
        raise RuntimeError(f"Anchor tidak ditemukan/tidak unik untuk patch '{label}' (upstream berubah?)")
    return source.replace(old, new)


def _ensure_import(source: str, fqcn: str) -> str:
    line = f"import {fqcn}"
    if line in source:
        return source
    match = re.search(r"^package [^\n]+\n", source, re.MULTILINE)
    if not match:
        raise RuntimeError("Deklarasi package tidak ditemukan untuk menambah import")
    return source[: match.end()] + "\n" + line + "\n" + source[match.end():]


def patch_renderer_compatibility(app: Path) -> None:
    """Best-effort renderer guard tolerant of upstream method/comment changes."""
    path = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/MinecraftActivity.kt"
    src = require_file(path)
    marker = "IMZYY RENDERER COMPATIBILITY GUARD"
    if marker in src:
        print("renderer compatibility: already applied")
        return

    guard = '''        // IMZYY RENDERER COMPATIBILITY GUARD
        // Sodium/Embeddium require a modern OpenGL path; avoid GL4ES for this session.
        val modsDirForRenderer = instanceDir?.let { File(it, "mods") }
        val hasSodiumFamily = modsDirForRenderer?.listFiles()?.any { mod ->
            mod.isFile && mod.name.endsWith(".jar", ignoreCase = true) &&
                (mod.name.contains("sodium", ignoreCase = true) || mod.name.contains("embeddium", ignoreCase = true))
        } == true
        if (hasSodiumFamily && base.id in setOf("gl4es", "gl4es_desktop")) {
            val previousRenderer = base.id
            base = Renderer.fromId("mobileglues")
            android.util.Log.w("FLAME_LAUNCHER", "IMZYY: Sodium/Embeddium + $previousRenderer; using MobileGlues for this session")
        }
'''

    candidates = list(re.finditer(r"(?m)^(?P<indent>[ \t]*)return\s+base\s*$", src))
    selected = None
    if len(candidates) == 1:
        selected = candidates[0]
    elif candidates:
        for candidate in candidates:
            prefix = src[:candidate.start()]
            funcs = list(re.finditer(r"(?m)^\s*(?:(?:private|internal|protected|public|override)\s+)*fun\s+([A-Za-z0-9_]*[Rr]enderer[A-Za-z0-9_]*)\s*\([^)]*\)\s*:\s*Renderer\s*\{", prefix))
            if funcs:
                function = funcs[-1]
                if src[function.end():candidate.start()].count("return base") == 1:
                    selected = candidate
                    break

    if selected is None:
        print("renderer compatibility: skipped (upstream renderer method changed); choose MobileGlues manually for Sodium")
        return

    indent = selected.group("indent")
    src = src[:selected.start()] + guard + indent + "return base" + src[selected.end():]
    path.write_text(src, encoding="utf-8")
    print("renderer compatibility: patched safely before renderer return")

def patch_controls(app: Path) -> None:
    """Hook IMZYY controls (look sensitivity, touch slop/long-press, virtual mouse + cursor overlay)
    into the upstream game input code and recolor the on-screen buttons to monochrome."""
    base = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation"

    activity = base / "MinecraftActivity.kt"
    src = require_file(activity)
    src = _replace_once(
        src,
        "    internal val MOUSE_SENSITIVITY = 1.5f",
        "    internal val MOUSE_SENSITIVITY: Float get() = ImzyyControls.lookSensitivity(this)",
        "look sensitivity",
    )
    src = _replace_once(
        src,
        "    private var gameControllerView: GameControllerView? = null",
        "    internal var gameControllerView: GameControllerView? = null",
        "controller view visibility",
    )
    src = _ensure_import(src, "id.imzyy.xl.ImzyyControls")
    activity.write_text(src, encoding="utf-8")

    surface = base / "ui/components/MinecraftSurface.kt"
    src = require_file(surface)
    src = _replace_once(
        src,
        "private const val DRAG_SLOP = 20f",
        "private val DRAG_SLOP: Float get() = ImzyyControls.dragSlop",
        "drag slop",
    )
    src = _replace_once(
        src,
        "private const val LONG_PRESS_TIMEOUT = 500L",
        "private val LONG_PRESS_TIMEOUT: Long get() = ImzyyControls.longPressMs",
        "long press",
    )
    src = _replace_once(
        src,
        "    val buf = bufferSize()\n",
        "    val buf = bufferSize()\n    if (ImzyyControls.handleTouchpad(activity, v, event, buf)) return@OnTouchListener true\n",
        "touchpad hook",
    )
    src = _ensure_import(src, "id.imzyy.xl.ImzyyControls")
    surface.write_text(src, encoding="utf-8")

    controller = base / "ui/components/GameControllerView.kt"
    src = require_file(controller)
    src = _replace_once(
        src,
        "    override fun onDraw(canvas: Canvas) {\n",
        "    override fun onDraw(canvas: Canvas) {\n        ImzyyControls.drawCursor(canvas, this, activity)\n",
        "cursor overlay",
    )
    for old, new in (
        ("Color.argb(217, 26, 10, 20)", "Color.argb(210, 10, 10, 10)"),
        ("Color.argb(217, 107, 0, 64)", "Color.argb(217, 55, 55, 55)"),
        ("Color.argb(255, 156, 16, 96)", "Color.argb(255, 95, 95, 95)"),
        ("Color.argb(255, 233, 30, 140)", "Color.argb(255, 150, 150, 150)"),
        ("Color.argb(178, 122, 40, 85)", "Color.argb(178, 90, 90, 90)"),
        ("Color.argb(178, 255, 107, 181)", "Color.argb(210, 255, 255, 255)"),
    ):
        src = src.replace(old, new)
    src = _ensure_import(src, "id.imzyy.xl.ImzyyControls")
    controller.write_text(src, encoding="utf-8")
    print("controls: look sensitivity, slop, long-press, touchpad + cursor hooks applied")


def patch_input_sender(app: Path) -> None:
    """Find the upstream key sender on MinecraftActivity and wire ImzyyInput.key() to it.
    Falls back to Android KeyEvent dispatch when nothing usable is found."""
    act = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/MinecraftActivity.kt"
    hub = app / "src/main/java/id/imzyy/xl/ImzyyInput.kt"
    src = require_file(act)
    skip = ("char", "text", "string", "codepoint", "unicode")
    chosen = None
    for m in re.finditer(r"(?:(private|protected|internal)\s+)?fun\s+(send\w*Key\w*)\s*\(([^)]*)\)", src):
        name = m.group(2)
        if any(s in name.lower() for s in skip):
            continue
        args, ok = [], True
        for raw in [p.strip() for p in m.group(3).split(",") if p.strip()]:
            pm = re.match(r"(?:vararg\s+)?(\w+)\s*:\s*([\w?.<>]+)\s*(=\s*.+)?$", raw)
            if not pm:
                ok = False
                break
            pn, pt, default = pm.group(1), pm.group(2), pm.group(3)
            low = pn.lower()
            if "scan" in low or "mod" in low:
                args.append(f"{pn} = 0")
            elif low in ("action", "pressed", "down", "state", "press", "isdown", "ispressed", "keydown"):
                args.append(f"{pn} = down" if pt.startswith("Boolean") else f"{pn} = (if (down) 1 else 0)")
            elif "key" in low or "code" in low:
                use_droid = "android" in low or ("GLFW" not in src and "KEYCODE_" in src)
                args.append(f"{pn} = {'droid' if use_droid else 'glfw'}")
            elif default:
                continue
            else:
                ok = False
                break
        if ok and args:
            chosen = (m, name, args)
            break
    if chosen is None:
        print("controls-pad: key sender tidak ditemukan; pakai KeyEvent dispatch")
        return
    m, name, args = chosen
    if m.group(1) in ("private", "protected"):
        src = src[: m.start(1)] + "internal" + src[m.end(1):]
        act.write_text(src, encoding="utf-8")
    text = hub.read_text(encoding="utf-8")
    body_start = text.index("    fun key(")
    body_end = text.index("    fun mouse(")
    new_key = (
        "    fun key(a: MinecraftActivity, glfw: Int, droid: Int, down: Boolean) {\n"
        f"        a.{name}({', '.join(args)})\n    }}\n\n"
    )
    hub.write_text(text[:body_start] + new_key + text[body_end:], encoding="utf-8")
    print(f"controls-pad: key sender = {name}({', '.join(args)})")


def patch_lite(app: Path, repo: str, runtimes_out: Path | None) -> str:
    """Lite variant: keep only JRE 21 inside the APK. The other runtimes are moved to
    `runtimes_out` (published by CI as release assets) and downloaded on demand by the app.
    Returns the JSON manifest {jreN: {size, sha256}} that is baked into ImzyyConfig."""
    import hashlib
    import json
    import shutil

    jre_dir = app / "src/main/assets/jre"
    manifest_file = app / "imzyy-runtimes.json"
    if not repo:
        print("lite: IMZYY_GITHUB_REPO kosong -> runtime TIDAK dibuang (tidak ada tempat unduh)")
        return "{}"
    strip = (8, 17, 25)
    manifest: dict = {}
    for major in strip:
        zip_path = jre_dir / f"jre{major}.zip"
        if not zip_path.is_file():
            continue
        digest = hashlib.sha256()
        with zip_path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
        manifest[f"jre{major}"] = {"size": zip_path.stat().st_size, "sha256": digest.hexdigest()}
        if runtimes_out is not None:
            runtimes_out.mkdir(parents=True, exist_ok=True)
            shutil.copy2(zip_path, runtimes_out / zip_path.name)
        zip_path.unlink()
    if manifest:
        manifest_file.write_text(json.dumps(manifest), encoding="utf-8")
    elif manifest_file.is_file():
        manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    if not (jre_dir / "jre21.zip").is_file():
        raise RuntimeError("lite: jre21.zip tidak ada; varian Lite butuh Java 21 bawaan")
    print(f"lite: runtimes diunduh-saat-butuh = {sorted(manifest)}; bawaan = jre21")
    return json.dumps(manifest, separators=(",", ":"))


def patch_runtime_hooks(app: Path) -> None:
    """On-demand JRE: let MinecraftJREPreparer use downloaded runtimes and try a download first."""
    path = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/util/minecraft/MinecraftJREPreparer.kt"
    src = require_file(path)
    src = _replace_once(
        src,
        'return order.firstOrNull { assetExists(context, "jre/jre${it}.zip") }',
        'return order.firstOrNull { assetExists(context, "jre/jre${it}.zip") || ImzyyRuntimes.installed(context, it) }',
        "runtime resolve",
    )
    src = _replace_once(
        src,
        "            val major = resolveAvailableMajor(context, requestedMajor)\n",
        "            try { ImzyyRuntimes.ensure(context, requestedMajor) } catch (e: Exception) { Log.w(TAG, \"Runtime download gagal: ${e.message}\") }\n            val major = resolveAvailableMajor(context, requestedMajor)\n",
        "runtime ensure",
    )
    src = _ensure_import(src, "id.imzyy.xl.ImzyyRuntimes")
    path.write_text(src, encoding="utf-8")
    print("runtimes: on-demand JRE hooks applied")


def patch_hide_release_notes(app: Path) -> None:
    """Remove the upstream "Release Notes / latest release" side-menu entry and the GitHub update popup
    (it pointed at the upstream repository and made a network call on every launch). Idempotent."""
    base = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation"
    main_screen = base / "ui/screen/MainScreen.kt"
    src = require_file(main_screen)
    src = _replace_once(
        src,
        "MainSection.entries.forEach { section ->",
        "MainSection.entries.filter { it != MainSection.NOTES }.forEach { section ->",
        "hide release notes menu",
    )
    main_screen.write_text(src, encoding="utf-8")

    main_activity = base / "MainActivity.kt"
    src = require_file(main_activity)
    src = _replace_once(
        src,
        "val release = GithubUpdateChecker.fetchUpdate(BuildConfig.VERSION_NAME)",
        "val release: GithubRelease? = emptyList<GithubRelease>().firstOrNull() // IMZYY: upstream update check disabled",
        "disable upstream update popup",
    )
    main_activity.write_text(src, encoding="utf-8")
    print("release notes: side-menu entry hidden, upstream update popup disabled")


def patch_fps_text(app: Path) -> None:
    """The FPS chip is accent-filled; its label must be dark now that the accent is near-white."""
    path = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/screen/SettingsScreen.kt"
    src = require_file(path)
    src = src.replace('Text("FPS", color = Color.White,', 'Text("FPS", color = Color.Black,')
    path.write_text(src, encoding="utf-8")


def patch_contrast(app: Path) -> None:
    """The accent is now near-white. Upstream hard-codes white text/icons on accent-filled
    buttons, which would turn invisible; flip the first Color.White that follows a
    `containerColor = Flame*` (within one button block) to black. Idempotent."""
    ui_root = app / "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui"
    pattern = re.compile(
        r"(containerColor\s*=\s*(?:Flame|FlamePrimary|Orange)\b(?:(?!Button\s*\().){0,900}?)Color\.White",
        re.DOTALL,
    )
    flipped = 0
    for source_file in ui_root.rglob("*.kt"):
        source = source_file.read_text(encoding="utf-8")
        updated, count = pattern.subn(r"\1Color.Black", source)
        if count:
            source_file.write_text(updated, encoding="utf-8")
            flipped += count
    print(f"contrast: {flipped} white-on-accent usages flipped to black")


def install_hub(app: Path, repo: str, version: str, variant: str = "full", runtimes: str = "{}") -> None:
    """Copy the self-contained IMZYY Hub (Compose) into the app and make it the launcher entry.
    The upstream MainActivity stays untouched; PLAY opens it."""
    overlay = Path(__file__).resolve().parents[1] / "android-overlay"
    src_dir = overlay / "kotlin"
    if not src_dir.is_dir():
        raise RuntimeError("android-overlay/kotlin tidak ditemukan")
    dest = app / "src/main/java/id/imzyy/xl"
    dest.mkdir(parents=True, exist_ok=True)
    for kt in src_dir.glob("*.kt"):
        (dest / kt.name).write_text(kt.read_text(encoding="utf-8"), encoding="utf-8")
    tpl = (src_dir / "ImzyyConfig.kt.tpl").read_text(encoding="utf-8")
    escaped = runtimes.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    (dest / "ImzyyConfig.kt").write_text(
        tpl.replace("@VERSION@", version).replace("@REPO@", repo).replace("@VARIANT@", variant).replace("@RUNTIMES@", escaped),
        encoding="utf-8",
    )
    res = app / "src/main/res/values"
    res.mkdir(parents=True, exist_ok=True)
    (res / "imzyy_theme.xml").write_text((overlay / "res/imzyy_theme.xml").read_text(encoding="utf-8"), encoding="utf-8")

    manifest_path = app / "src/main/AndroidManifest.xml"
    ns_tools = "http://schemas.android.com/tools"
    ET.register_namespace("tools", ns_tools)
    tree = ET.parse(manifest_path)
    root = tree.getroot()
    application = root.find("application")
    if application is None:
        raise RuntimeError("<application> tidak ada di manifest")
    name_attr = f"{{{ANDROID}}}name"
    hub_name = "id.imzyy.xl.ImzyyHubActivity"
    if not any(a.get(name_attr) == hub_name for a in application.findall("activity")):
        moved = 0
        for tag in ("activity", "activity-alias"):
            for node in application.findall(tag):
                for flt in list(node.findall("intent-filter")):
                    cats = [c.get(name_attr) for c in flt.findall("category")]
                    if "android.intent.category.LAUNCHER" in cats:
                        node.remove(flt)
                        moved += 1
        if moved == 0:
            raise RuntimeError("Intent filter LAUNCHER upstream tidak ditemukan")
        hub = ET.SubElement(application, "activity")
        hub.set(name_attr, hub_name)
        hub.set(f"{{{ANDROID}}}exported", "true")
        hub.set(f"{{{ANDROID}}}theme", "@style/ImzyyHubTheme")
        hub.set(f"{{{ANDROID}}}label", "IMZYY XL")
        flt = ET.SubElement(hub, "intent-filter")
        ET.SubElement(flt, "action").set(name_attr, "android.intent.action.MAIN")
        ET.SubElement(flt, "category").set(name_attr, "android.intent.category.LAUNCHER")
    prov_name = "id.imzyy.xl.ImzyyInitProvider"
    if not any(p.get(name_attr) == prov_name for p in application.findall("provider")):
        game_process = None
        for node in application.findall("activity"):
            if str(node.get(name_attr, "")).endswith("MinecraftActivity"):
                game_process = node.get(f"{{{ANDROID}}}process")
        prov = ET.SubElement(application, "provider")
        prov.set(name_attr, prov_name)
        prov.set(f"{{{ANDROID}}}authorities", "${applicationId}.imzyyinit")
        prov.set(f"{{{ANDROID}}}exported", "false")
        prov.set(f"{{{ANDROID}}}initOrder", "100")
        if game_process:
            prov.set(f"{{{ANDROID}}}process", game_process)
    perms = [p.get(name_attr) for p in root.findall("uses-permission")]
    if "android.permission.INTERNET" not in perms:
        perm = ET.Element("uses-permission")
        perm.set(name_attr, "android.permission.INTERNET")
        root.insert(0, perm)
    tree.write(manifest_path, encoding="utf-8", xml_declaration=True)


def patch_root_branding(root: Path) -> None:
    settings = root / "settings.gradle.kts"
    if settings.is_file():
        source = settings.read_text(encoding="utf-8")
        source = re.sub(r'rootProject\.name\s*=\s*"[^"]+"', 'rootProject.name = "IMZYY XL Launcher"', source, count=1)
        settings.write_text(source, encoding="utf-8")


def validate_upstream(root: Path) -> None:
    app = root / "app"
    gradle = require_file(app / "build.gradle.kts")
    manifest = require_file(app / "src/main/AndroidManifest.xml")
    is_app = "com.android.application" in gradle or "android.application" in gradle  # version-catalog alias too
    if not is_app or "abiFilters" not in gradle or "arm64-v8a" not in gradle:
        raise RuntimeError("Build upstream tidak sesuai: modul Android ARM64 tidak terdeteksi.")
    if "kr.co.donghyun.flamelauncher.presentation.MainActivity" not in manifest and 'android:name=".presentation.MainActivity"' not in manifest:
        raise RuntimeError("MainActivity upstream tidak ditemukan di manifest.")
    if 'android:name=".presentation.MinecraftActivity"' not in manifest:
        raise RuntimeError("MinecraftActivity tidak terdaftar di aplikasi utama; pemeriksaan single-app gagal.")
    for relative in (
        "src/main/java/kr/co/donghyun/flamelauncher/presentation/MinecraftActivity.kt",
        "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme/Color.kt",
        "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/theme/Type.kt",
        "src/main/java/kr/co/donghyun/flamelauncher/presentation/ui/screen/SettingsScreen.kt",
        "src/main/res/values/strings.xml",
    ):
        require_file(app / relative)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--engine-dir", required=True, type=Path)
    parser.add_argument("--repo", default=os.environ.get("IMZYY_GITHUB_REPO", ""), help="owner/name for the in-app GitHub card")
    parser.add_argument("--version", default=os.environ.get("IMZYY_VERSION", "3.0.0"))
    parser.add_argument("--variant", choices=("full", "lite"), default=os.environ.get("IMZYY_VARIANT", "full"))
    parser.add_argument("--runtimes-out", type=Path, default=None, help="folder that receives the JREs stripped from the Lite APK")
    args = parser.parse_args()
    root = args.engine_dir.resolve()
    validate_upstream(root)
    app = root / "app"
    patch_strings(app)
    patch_palette(app)
    patch_typography(app)
    patch_performance_profiles(app)
    patch_icon(app)
    patch_in_app_branding(app)
    patch_contrast(app)
    patch_fps_text(app)
    patch_controls(app)
    patch_renderer_compatibility(app)
    patch_hide_release_notes(app)
    runtimes = patch_lite(app, args.repo, args.runtimes_out) if args.variant == "lite" else "{}"
    install_hub(app, args.repo, args.version, args.variant, runtimes)
    patch_runtime_hooks(app)
    patch_input_sender(app)
    patch_root_branding(root)
    # Keep upstream Android namespace/applicationId intact until the Microsoft
    # OAuth redirect URI has been independently reconfigured and tested.
    print("IMZYY XL monochrome UI, local IMZYY login, local PNG skin studio, Hub (Boost/Mods/.mrpack import), typography and device-capped performance profiles applied (ARM64 upstream engine retained).")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"IMZYY prepare failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
