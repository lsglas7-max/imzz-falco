package com.imzyyknox.gameturbo;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;

/**
 * IMZYY KNOX Game Turbo v8.8 - floating hub hitam/putih/abu-abu. Semua fitur jalan langsung dari sini.
 * Posisi bubble dan panel selalu sinkron dan tersimpan (tidak balik ke posisi lama),
 * dan hub dibuat hemat RAM supaya tidak di-kill / reload.
 */
public class FloatingService extends Service {
    static boolean running;
    private static FloatingService inst;
    /** Hub benar-benar hidup (dipakai GuardJob untuk memutuskan perlu dinyalakan ulang atau tidak). */
    static boolean alive() { return inst != null && running; }
    private static final ArrayList<String> LOG = new ArrayList<>();

    // Palet monokrom: hitam, putih, abu-abu.
    private static final int CY = 0xFFF4F4F6, BL = 0xFFC8C8CE, PU = 0xFF86868E, OK = 0xFFFFFFFF, WARN = 0xFFBDBDC4, BAD = 0xFFFF6464, MUTE = 0xFF8B8B93;
    private static final int TXT = 0xFFEDEDF0, DARK = 0xFF050505;
    private static final int[] TARGETS = {10, 30, 60, 90, 0};
    private static final int[] TAB_ICON = {IconView.BOLT, IconView.FPS, IconView.PING, IconView.FAN, IconView.CACHE};
    private static final String[] TAB_NAME = {"HOME", "FPS", "NET", "FAN", "CACHE"};
    private static final int TAB_APPS = 4, TAB_CACHE = 5, TAB_NET = 8, TAB_FAN = 9, TAB_INFO = 10, TAB_MENU = 11, TAB_CLEANER = 12;
    /** Judul halaman di header panel, indeks sama dengan nomor tab. */
    private static final String[] PAGE_TITLE = {"Status ponsel", "FPS & refresh rate", "Tampilan & profil", "Alat & izin", "Jendela mengambang", "Bersihkan cache",
            "Voice changer", "GPU & grafis", "Network game", "Kipas & suara mesin", "Tentang", "Game Turbo", "Aktifkan Cleaner"};
    private static final long MIN_CACHE_BYTES = 64L * 1024;
    private static final String[] GPU_NAMES = {"AUTO", "PERFORMA", "SEIMBANG", "HEMAT"};

    private WindowManager wm;
    private SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final FpsEngine.Meter meter = new FpsEngine.Meter();
    private WindowManager.LayoutParams bp, pp;
    private View bubble;
    private TextView status;
    private FanView miniFan;
    private ReviewOverlay review;
    private LinearLayout fpsStripBox;
    private TextView miniFpsNow, miniFpsPrev, miniFpsDelta, miniThermalLine;
    private int liveFps = 0, previousFps = 0, fpsSampleFrames = 0;
    private long fpsSampleStart = 0L;
    private boolean fpsSamplerOn = false;
    private LinearLayout panel;
    private FrameLayout content;
    private final ArrayList<LinearLayout> navItems = new ArrayList<>();
    private HorizontalScrollView navScroll;
    /** Urutan tampil menu: inti (HOME FPS GPU NET FAN), lalu aplikasi (CACHE JENDELA), lalu alat (TOOLS STYLE VOICE). */
    private static final int[] NAV_ORDER = {0, 1, 8, 9, 5};
    private final ArrayList<TextView> fpsBtns = new ArrayList<>();
    private final ArrayList<TextView> toggles = new ArrayList<>();
    private TextView tRam, tBat, tTemp, tTherm, tHz;
    private TextView menuStat, headTitle, headSub, optSub;
    private IconView headIcon;
    private View barFill, barRest;
    private LinearLayout appList, logBox;
    private TextView gpuName, gpuSub, optLine;
    private final VoiceEngine voice = new VoiceEngine();
    private float customPitch = 1.0f;
    private final ArrayList<TextView> fxChips = new ArrayList<>();
    private int micFor; // 0 = izin saja, 1 = rekam, 2 = LIVE
    private TextView netLine, netSub;
    /** Kipas CPU + GPU di header, menyatu dengan kipas FPS (miniFan). */
    private FanView hdrCpu, hdrGpu;
    private TextView hdrCpuTxt, hdrCpuSub, hdrGpuTxt, hdrGpuSub;
    private long closeArmedAt;
    private static boolean crashHook;
    private View[] coreFill;
    private volatile String gpuRenderer = "";
    private volatile boolean fanReading;
    private boolean playOnOpen;
    private long pendingUntil;
    private final java.util.concurrent.ExecutorService statsExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean netBusy, sysCleaning;
    private volatile long sysBefore = -1;
    private TextView appSummary;
    private int curTab = 0, tickCount = 0, scanGen = 0;
    private boolean loadingApps = false;
    private final ArrayList<AppRow> lastRows = new ArrayList<>();
    /** Baris daftar cache yang sedang tampil, supaya angka MB bisa diubah di tempat setelah dibersihkan. */
    private final HashMap<String, TextView> rowSub = new HashMap<>();
    private final ArrayList<String> watchPkgs = new ArrayList<>();
    private final HashMap<String, Long> cleanBefore = new HashMap<>();
    private View curtain; private TextView curtainTxt;
    private final Runnable curtainOff = this::hideCurtain;

    private static class AppRow {
        String label, pkg; long cache; long last;
        AppRow(String l, String p, long c, long t) { label = l; pkg = p; cache = c; last = t; }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            tickCount++;
            refresh();
            // Auto-refresh daftar jarang (20 dtk) dan tidak saat pembersihan jalan: hemat RAM/CPU.
            if ((curTab == TAB_APPS || curTab == TAB_CACHE) && tickCount % 10 == 0 && CleanerService.pending() == 0) reloadApps();
            ui.postDelayed(this, 2000);
        }
    };
    /** Layar menyala / dibuka kunci: periksa panel & bubble saat itu juga, jangan menunggu siklus watchdog berikutnya. */
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!running) return;
            ui.removeCallbacks(windowWatchdog);
            ui.post(windowWatchdog);
        }
    };
    private final BroadcastReceiver orientationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { ui.postDelayed(FloatingService.this::reflow, 150); ui.postDelayed(FloatingService.this::reflow, 600); }
    };

    // Watchdog ringan: beberapa firmware Samsung dapat melepas TYPE_APPLICATION_OVERLAY
    // saat tekanan memori / perubahan konfigurasi. Jika panel masih diminta terbuka,
    // pasang kembali tanpa membuat instance panel baru.
    private final Runnable windowWatchdog = new Runnable() {
        @Override public void run() {
            if (!running || wm == null) return;
            if (!Settings.canDrawOverlays(FloatingService.this)) {
                android.util.Log.w("IMZYY-Floating", "Overlay permission unavailable; waiting for user to restore it");
                ui.postDelayed(this, 2500);
                return;
            }
            try {
                String gm = AccessibilityGuard.tick(FloatingService.this);
                if (gm != null) { status(gm, WARN); if (!gm.contains("dinyalakan ulang")) alertCleanerDown(); }
            } catch (Throwable ignored) { }
            if (review != null && sp.getBoolean("review_panel", false)) { try { review.ensure(); } catch (Throwable ignored) { } }
            final boolean anti = sp.getBoolean("hub_antivanish", true);
            if (anti) {
                try {
                    if (bubble == null || !bubble.isAttachedToWindow()) {
                        android.util.Log.w("IMZYY-Floating", "Bubble detached; rebuilding overlay view");
                        rebuildBubble();
                    }
                    // Bubble tersembunyi padahal panel tidak ada = jendela "hilang" tanpa sebab: tampilkan lagi.
                    if (panel == null && bubble != null && bubble.isAttachedToWindow()) {
                        if (bubble.getVisibility() != View.VISIBLE) bubble.setVisibility(View.VISIBLE);
                        if (bubble.getAlpha() < 1f) bubble.setAlpha(1f);
                        clampBubble();   // bawa kembali ke layar bila posisinya keluar batas
                    }
                    // Animasi buka macet (alpha 0) membuat panel tak terlihat: pulihkan.
                    if (panel != null && panel.isAttachedToWindow() && panel.getAlpha() < .05f) {
                        panel.setAlpha(1f); panel.setScaleX(1f); panel.setScaleY(1f);
                    }
                    if (sp.getBoolean("panel_open", false) && (panel == null || !panel.isAttachedToWindow())) {
                        // Temporary WindowManager detach: retain the open intent and rebuild, never treat it as user-close.
                        if (panel != null) {
                            try { wm.removeViewImmediate(panel); } catch (Throwable ignored) { }
                            panel = null; pp = null; status = null; content = null;
                        }
                        if (bubble != null && bubble.isAttachedToWindow()) bubble.setVisibility(View.GONE);
                        openPanel();
                    }
                } catch (Throwable e) {
                    android.util.Log.e("IMZYY-Floating", "Overlay watchdog recovery failed", e);
                }
            }
            ui.postDelayed(this, anti ? 1200 : 2500);
        }
    };

    // ------------------------------------------------------------ lifecycle
    @Override public void onCreate() {
        super.onCreate();
        running = true; inst = this;
        sp = getSharedPreferences("turbo", MODE_PRIVATE);
        sp.edit().putBoolean("hub_was_on", true).putBoolean("hub_antivanish", true).apply();
        curTab = TAB_MENU;
        // Jangan aktifkan tipe MICROPHONE hanya karena izin sudah pernah diberikan.
        // Android 14+ membatasi microphone FGS saat aplikasi berada di background;
        // panel harus tetap hidup sebagai SPECIAL_USE tanpa bergantung pada izin mic.
        if (!foreground(false)) {
            android.util.Log.e("IMZYY-Floating", "Cannot promote FloatingService to foreground; stopping instead of running unprotected");
            GuardJob.revive(getApplicationContext(), 3000L);   // coba lagi sebentar lagi, hub tidak hilang selamanya
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        IntentFilter cfg = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(orientationReceiver, cfg, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(orientationReceiver, cfg);
        IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wake.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, wake, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(wakeReceiver, wake);
        } catch (Throwable ignored) { }
        GuardJob.schedule(getApplicationContext());   // penjaga berkala: tidak butuh akses aksesibilitas
        CleanerService.listener = this::onCleaner;
        AccessibilityGuard.watch(getApplicationContext());
        installCrashHook();
        if (KeepAlive.wanted(this)) KeepAlive.arm(getApplicationContext(), 20_000L);   // detak alarm: tetap hidup walau proses dibunuh
        ui.postDelayed(this::reportLastDeath, 2500);
        // AUTO START MESIN: setiap hub hidup (reboot, restart sistem, pemulihan penjaga) mesin yang tadi aktif dinyalakan lagi.
        boolean auto = sp.getBoolean("engine_autostart", true);
        if (!auto) sp.edit().putBoolean("net_boost", false).putBoolean("fan_engine", false).apply();
        boolean anyEngine = sp.getBoolean("net_boost", false) || sp.getBoolean("fan_engine", false) || sp.getBoolean("fan_opt", false);
        if (sp.getBoolean("net_boost", false)) NetEngine.lockOn(this);
        showBubble();
        if (sp.getBoolean("fan_engine", false)) ui.postDelayed(this::startFanEngine, 600);
        if (sp.getBoolean("fan_opt", false)) ui.postDelayed(engineTick, 1500);
        if (anyEngine) ui.postDelayed(() -> { if (running) addLog("AUTO START • mesin aktif dipulihkan"); }, 2000);
        ui.removeCallbacks(windowWatchdog);
        ui.postDelayed(windowWatchdog, 2500);
        // Kalau hub sempat di-restart sistem, panel dibuka lagi persis di tempat terakhir (tidak "relog").
        if (sp.getBoolean("panel_open", false) && Settings.canDrawOverlays(this)) ui.postDelayed(this::openPanel, 300);
        review = new ReviewOverlay(getApplicationContext(), wm, sp);
        if (sp.getBoolean("review_panel", false)) ui.postDelayed(this::reviewOn, 700);
    }

    /** Panel review (FPS, baterai, suhu, CPU/GPU) mengambang terpisah dari floating window utama. */
    private void reviewOn() {
        if (review == null || !running) return;
        if (!Settings.canDrawOverlays(this)) { status("Panel review butuh izin tampil di atas aplikasi", WARN); return; }
        review.setSuppressed(panel != null && sp.getBoolean("review_hide_with_panel", false));
        if (!review.show()) status("Panel review gagal dipasang", WARN);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (!foreground(false)) {
            android.util.Log.e("IMZYY-Floating", "Foreground re-promotion failed in onStartCommand");
            GuardJob.revive(getApplicationContext(), 3000L);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (i != null && "STOP".equals(i.getAction())) { userStop(); return START_NOT_STICKY; }
        if (i != null && i.getBooleanExtra("show_monitor", false)) {
            sp.edit().putBoolean("review_panel", true).apply();
            ui.post(() -> {
                if (review != null) {
                    review.setSuppressed(false);
                    if (!review.show()) status("Monitor butuh izin tampil di atas aplikasi", WARN);
                    else status("Monitor live aktif • geser untuk pindah, ketuk untuk mode kipas", OK);
                }
            });
        }
        if (i != null && i.getBooleanExtra("open", false)) {
            curTab = i.getIntExtra("tab", TAB_MENU);
            ui.post(() -> { if (panel == null) { playOnOpen = true; openPanel(); } else showTab(curTab); });
        } else if (i == null && sp.getBoolean("panel_open", false)) {
            // START_STICKY dapat memanggil ulang service dengan Intent null.
            ui.postDelayed(() -> { if (panel == null && Settings.canDrawOverlays(this)) openPanel(); }, 350);
        }
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // stopWithTask=false + START_STICKY sudah menangani lifecycle service.
        // Jangan menjadwalkan startForegroundService lewat alarm: pada Android baru
        // pembatasan background-start dapat membuat service crash/restart berulang.
        // Jaring pengaman: bila service ternyata ikut mati setelah aplikasi di-swipe dari recents, job ini menyalakannya lagi.
        // Bila service masih hidup, job langsung selesai tanpa melakukan apa pun.
        GuardJob.revive(getApplicationContext(), 4000L);
        // Samsung sering membunuh proses saat kartu di-swipe dari Recents. Alarm tepat waktu (USE_EXACT_ALARM) boleh memulai
        // foreground service dari background, jadi hub hidup lagi sekitar 1,5 detik kemudian bila prosesnya ikut mati.
        if (KeepAlive.wanted(this)) KeepAlive.arm(getApplicationContext(), 1500L);
        super.onTaskRemoved(rootIntent);
    }

    /** Bila aplikasi crash, jadwalkan hub hidup lagi (kecuali pengguna sudah menutupnya atau ANTI HILANG dimatikan). */
    private void installCrashHook() {
        if (crashHook) return;
        crashHook = true;
        final Context app = getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                String t = new java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date()) + " [" + th.getName() + "]\n" + sw;
                java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(app.getFilesDir(), "last_crash.txt"));
                fo.write((t.length() > 4000 ? t.substring(0, 4000) : t).getBytes("UTF-8")); fo.close();
            } catch (Throwable ignored) { }
            try {
                android.content.SharedPreferences crashPrefs = app.getSharedPreferences("turbo", MODE_PRIVATE);
                if (crashPrefs.getBoolean("hub_was_on", false) && crashPrefs.getBoolean("hub_antivanish", true)) {
                    GuardJob.revive(app, 4000L);
                    KeepAlive.arm(app, 8000L);
                }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(th, ex);
        });
    }

    /** Mulai ulang semua mesin yang aktif tanpa menutup hub: suara mesin, optimizer, Wi-Fi lock, pembaca CPU/GPU, FPS, dan cek overlay. */
    private void restartEngines() {
        status("Memulai ulang mesin…", WARN);
        final boolean eng = sp.getBoolean("fan_engine", false);
        stopFanEngine();
        NetEngine.lockOff();
        ui.removeCallbacks(engineTick);
        ui.postDelayed(() -> {
            if (!running) return;
            if (eng) startFanEngine();
            if (sp.getBoolean("fan_opt", false)) ui.post(engineTick);
            if (sp.getBoolean("net_boost", false)) NetEngine.lockOn(this);
            if (panel != null) {
                ui.removeCallbacks(hdrTick); ui.post(hdrTick);
                stopFpsSampler(); startFpsSampler();
            }
            ui.removeCallbacks(windowWatchdog); ui.post(windowWatchdog);
            GuardJob.schedule(getApplicationContext());
            status("Mesin dimulai ulang • overlay dicek", OK);
            addLog("RESTART MESIN • selesai");
        }, 450);
    }

    /** Section toggle: auto start mesin, anti hilang, dan tombol mulai ulang mesin (dipakai di TOOLS dan FAN). */
    private void engineGuardSection(LinearLayout c) {
        c.addView(title("AUTO START & ANTI HILANG"), mt(4));
        smartSwitchDef(c, "engine_autostart", true, IconView.BOLT, "AUTO START MESIN", "Mesin yang aktif (suara mesin, optimizer, Wi-Fi low-latency) menyala lagi sendiri tiap hub hidup.");
        smartSwitchDef(c, "hub_antivanish", true, IconView.SHIELD, "ANTI HILANG", "Overlay dipasang ulang cepat bila hilang, hub dihidupkan lagi (alarm penjaga + job) bila sistem membunuhnya.");
        LinearLayout rb = row();
        ibtn(rb, IconView.BOLT, "MULAI ULANG MESIN", true, this::restartEngines);
        c.addView(rb, mt(6));
    }

    // ------------------------------------------------------------ diagnosa: kenapa hub mati
    private static String reasonName(int r) {
        switch (r) {
            case 1: return "keluar sendiri";
            case 2: return "dibunuh sinyal";
            case 3: return "RAM HABIS";
            case 4: return "CRASH";
            case 5: return "CRASH NATIVE";
            case 6: return "ANR (macet)";
            case 7: return "gagal mulai";
            case 8: return "izin diubah";
            case 9: return "pemakaian berlebih";
            case 10: return "dihentikan pengguna/sistem";
            case 11: return "dihentikan pengguna";
            case 12: return "ketergantungan mati";
            case 13: return "dibunuh sistem/OEM";
            case 14: return "dibekukan";
            case 15: return "status paket berubah";
            case 16: return "aplikasi diperbarui";
            default: return "tidak diketahui";
        }
    }

    private static String deathHint(int r) {
        switch (r) {
            case 3: return "RAM habis, sistem membunuh hub. Tutup aplikasi berat atau kurangi fitur aktif.";
            case 4: case 5: return "Hub crash. Lihat detail di TOOLS > PERMANEN & KESEHATAN.";
            case 6: return "Hub macet (ANR). Detail di TOOLS > PERMANEN & KESEHATAN.";
            case 10: case 11: return "Dihentikan dari pengaturan, task manager, atau Paksa berhenti.";
            case 13: case 2: case 9: return "Dibunuh sistem/OEM. Atur Tanpa batasan dan keluarkan dari Aplikasi tidur.";
            case 14: return "Dibekukan sistem. Atur Tanpa batasan baterai.";
            case 16: return "Aplikasi diperbarui.";
            default: return "Penyebab tidak jelas.";
        }
    }

    /** Ringkasan 5 kematian proses terakhir (Android 11+) + crash terakhir yang tercatat. */
    private String deathReport() {
        StringBuilder sb = new StringBuilder();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(getPackageName(), 0, 5);
                if (l == null || l.isEmpty()) sb.append("Belum ada catatan mati.");
                else for (android.app.ApplicationExitInfo e : l) {
                    long mnt = Math.max(0, (System.currentTimeMillis() - e.getTimestamp()) / 60000L);
                    sb.append("• ").append(reasonName(e.getReason())).append(" • ")
                      .append(mnt < 90 ? mnt + " mnt lalu" : (mnt / 60) + " jam lalu");
                    String d = e.getDescription();
                    if (d != null && !d.isEmpty()) sb.append(" • ").append(d.length() > 60 ? d.substring(0, 60) : d);
                    sb.append("\n");
                }
            } catch (Throwable e) { sb.append("Riwayat tidak bisa dibaca."); }
        } else sb.append("Riwayat penyebab mati butuh Android 11+.");
        try {
            File f = new File(getFilesDir(), "last_crash.txt");
            if (f.exists() && f.length() > 0) {
                byte[] b = new byte[(int) Math.min(f.length(), 700)];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                int n = in.read(b); in.close();
                sb.append("\nCRASH TERAKHIR:\n").append(new String(b, 0, Math.max(0, n), "UTF-8"));
            }
        } catch (Throwable ignored) { }
        return sb.toString().trim();
    }

    /** Sekali per kematian baru: catat penyebabnya di log panel supaya tahu kenapa hub tadi mati. */
    private void reportLastDeath() {
        if (!running || Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(getPackageName(), 0, 1);
            if (l == null || l.isEmpty()) return;
            android.app.ApplicationExitInfo e = l.get(0);
            if (e.getTimestamp() <= sp.getLong("exit_seen_ts", 0L)) return;
            sp.edit().putLong("exit_seen_ts", e.getTimestamp()).apply();
            if (e.getReason() == 1) return;
            String msg = "Hub tadi mati: " + reasonName(e.getReason()) + " • " + deathHint(e.getReason());
            addLog(msg);
            status(msg, WARN);
        } catch (Throwable ignored) { }
    }

    private void openSamsungSleep() {
        String[][] tries = {
            {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"}
        };
        for (String[] t : tries) {
            try {
                startActivity(new Intent().setComponent(new android.content.ComponentName(t[0], t[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                status("Keluarkan IMZYY KNOX dari Aplikasi tidur / Aplikasi tidur lanjutan", WARN);
                return;
            } catch (Throwable ignored) { }
        }
        open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        status("Baterai > Batas penggunaan latar belakang: keluarkan app ini dari Aplikasi tidur", WARN);
    }

    /** Papan kesehatan permanen: status nyata tiap penghalang + riwayat kenapa hub mati. */
    private void permanenSection(LinearLayout c) {
        c.addView(title("PERMANEN & KESEHATAN"), mt(4));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        permRow(c, "Tanpa batasan baterai", pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()), this::antiKill);
        permRow(c, "Alarm penjaga (tepat waktu)", KeepAlive.canExact(this),
                () -> open(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName()))));
        boolean bgOk = true;
        if (Build.VERSION.SDK_INT >= 28) { try { bgOk = !((ActivityManager) getSystemService(ACTIVITY_SERVICE)).isBackgroundRestricted(); } catch (Throwable ignored) { } }
        permRow(c, "Latar belakang tidak dibatasi", bgOk, () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        boolean bucketOk = true;
        if (Build.VERSION.SDK_INT >= 28) { try { bucketOk = ((android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE)).getAppStandbyBucket() <= 30; } catch (Throwable ignored) { } }
        permRow(c, "Prioritas siaga (bukan Rare/Restricted)", bucketOk, () -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        boolean notifOk = true;
        try { notifOk = ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled(); } catch (Throwable ignored) { }
        permRow(c, "Notifikasi hub diizinkan", notifOk, () -> open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        LinearLayout sl = row();
        ibtn(sl, IconView.SHIELD, "APLIKASI TIDUR", true, this::openSamsungSleep);
        c.addView(sl, mt(6));
        c.addView(title("RIWAYAT MATI"), mt(6));
        final TextView dt = note(deathReport());
        dt.setPadding(dp(9), dp(7), dp(9), dp(7));
        dt.setBackground(bg(0xFF141416, 0xFF0A0A0B, 10, 0x1FFFFFFF));
        c.addView(dt, mt(2));
        LinearLayout dl = row();
        ibtn(dl, IconView.PLAY, "SEGARKAN", true, () -> dt.setText(deathReport()));
        c.addView(dl, mt(4));
        c.addView(note("Hub dijaga lapis-lapis: service foreground, watchdog overlay 1,2 detik, job penjaga, dan alarm tepat waktu yang tetap hidup walau prosesnya dibunuh. Satu-satunya jalan menutup permanen: tombol X dua ketukan. Paksa berhenti dari pengaturan menghapus semua alarm dan tidak bisa dilawan aplikasi biasa. Kalau masih mati, buka RIWAYAT MATI: penyebabnya tertulis di sana."), mt(6));
    }

    /** Satu-satunya jalan menutup hub secara permanen: tombol X (dua ketukan) atau aksi STOP dari pengguna. */
    private void userStop() {
        sp.edit().putBoolean("panel_open", false).putBoolean("hub_was_on", false).apply();
        GuardJob.cancelRevive(getApplicationContext());
        KeepAlive.cancel(getApplicationContext());
        stopSelf();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Lepas daftar aplikasi & hentikan pemindaian saat RAM sistem sempit. Bubble tetap ringan.
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) {
            lastRows.clear(); lastRows.trimToSize();
            scanGen++; loadingApps = false;
            if (panel == null) meter.stop();
        }
    }

    @Override public void onDestroy() {
        // Mati bukan karena pengguna (hub_was_on masih true): sistem/OEM yang mematikan. Jadwalkan hidup lagi.
        try { if (sp != null && sp.getBoolean("hub_was_on", false)) GuardJob.revive(getApplicationContext(), 1500L); } catch (Throwable ignored) { }
        running = false; inst = null;
        voice.release();
        if (review != null) { review.destroy(); review = null; }
        NetEngine.lockOff();
        CleanerService.listener = null;
        hideCurtain();
        AccessibilityGuard.unwatch(getApplicationContext());
        FanSound.stop();
        FanSound.stopEngine();
        statsExec.shutdownNow();
        try { unregisterReceiver(orientationReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(wakeReceiver); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
        meter.stop();
        try { if (bubble != null && bubble.isAttachedToWindow()) wm.removeViewImmediate(bubble); } catch (Throwable ignored) { }
        try { if (panel != null && panel.isAttachedToWindow()) wm.removeViewImmediate(panel); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ui.postDelayed(this::reflow, 100);
        ui.postDelayed(this::reflow, 500);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private boolean hasMic() { return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    /** Tipe foreground menyesuaikan izin mikrofon supaya rekaman jalan walau game di depan. */
    private boolean foreground(boolean mic) {
        try {
            Notification n = notification();
            if (Build.VERSION.SDK_INT >= 34) {
                // Floating UI selalu berjalan sebagai SPECIAL_USE. MICROPHONE hanya
                // diminta saat fitur rekam/LIVE benar-benar dimulai oleh pengguna.
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        | (mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
                startForeground(7, n, type);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7, n, mic ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
            } else {
                startForeground(7, n);
            }
            return true;
        } catch (Throwable e) {
            android.util.Log.e("IMZYY-Floating", "Foreground promotion failed (mic=" + mic + ")", e);
            return false;
        }
    }

    /** Dipanggil MicPermissionActivity setelah dialog izin mikrofon. */
    static void micResult(boolean ok) {
        final FloatingService f = inst;
        if (f == null) return;
        f.ui.post(() -> {
            int purpose = f.micFor; f.micFor = 0;
            if (!ok) { f.status("Izin mikrofon ditolak • aktifkan di TOOLS > IZIN", BAD); return; }
            if (purpose == 2) f.startLive(); else if (purpose == 1) f.startRec(); else f.status("Izin mikrofon aktif", OK);
        });
    }

    /** Dipanggil CacheClearActivity setelah dialog bersihkan cache sistem selesai. -2 = firmware tidak punya dialog itu. */
    static void cacheDialogResult(final int code) {
        final FloatingService f = inst;
        if (f == null) return;
        f.ui.post(() -> f.onSystemCleanDone(code));
    }

    private Notification notification() {
        String ch = "imzyy_turbo";
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(ch, "Game Turbo Panel", NotificationManager.IMPORTANCE_LOW));
        // Tidak ada tombol STOP di notifikasi supaya hub tidak mati karena tersenggol. Tutup lewat tombol X (dua ketukan) di panel.
        PendingIntent open = PendingIntent.getService(this, 3, new Intent(this, FloatingService.class).putExtra("open", true).putExtra("tab", TAB_MENU),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = new Notification.Builder(this, ch).setContentTitle("IMZYY KNOX Game Turbo")
                .setContentText("Hub dijaga permanen • tap untuk membuka panel")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view, "BUKA", open).build());
        if (Build.VERSION.SDK_INT >= 31) nb.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return nb.build();
    }

    // ------------------------------------------------------------ ui helpers
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    private GradientDrawable bg(int c1, int c2, int r, int stroke) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c1, c2});
        g.setCornerRadius(dp(r));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView txt(String s, int sz, int c, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sz); t.setTextColor(c);
        t.setTypeface(Typeface.create(bold ? "sans-serif" : "sans-serif-medium", Typeface.BOLD));
        return t;
    }

    private IconView icon(int type, int sizeDp, int color) {
        IconView v = new IconView(this).set(type, color, false);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout.LayoutParams mt(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private LinearLayout.LayoutParams w1(boolean first) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); if (!first) p.leftMargin = dp(5); return p; }

    private WindowManager.LayoutParams lp(int w, int h) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        return p;
    }

    private final Runnable statusFade = () -> {
        if (status == null) return;
        status.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (status != null) { status.setText("SIAP"); status.setTextColor(TXT); status.setAlpha(1f); status.setBackground(bg(0xFF1D1D20, 0xFF111113, 10, 0x22FFFFFF)); }
        }).start();
    };
    private void status(String s, int c) {
        if (status == null) return;
        ui.removeCallbacks(statusFade);
        status.animate().cancel();
        status.setAlpha(1f);
        status.setText(s);
        status.setTextColor(c == BAD ? BAD : c == MUTE ? MUTE : TXT);
        status.setBackground(bg(0xFF1D1D20, 0xFF111113, 10, c == WARN ? 0x66FFFFFF : c == OK ? 0x44FFFFFF : 0x22FFFFFF));
        // Pesan proses tampil ringkas di panel, lalu menghilang sendiri; bukan notifikasi Android.
        ui.postDelayed(statusFade, 2800L);
    }

    private void open(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
        catch (Exception e) { status("Tidak tersedia di perangkat ini", WARN); }
    }

    private void openWriteSettings() { open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); }

    /**
     * Pengguna menyalakan fitur yang butuh "Ubah setelan sistem": buka halaman izinnya, dan begitu izin diberikan
     * fitur yang tadi diminta otomatis menyala (tidak perlu tap toggle lagi). Izin yang sudah ada tidak pernah diminta ulang.
     */
    private void askWrite() {
        if (Settings.System.canWrite(this)) {
            // Izin SUDAH aktif: jangan buka halaman izin lagi (itu yang bikin loop "Ubah pengaturan sistem").
            sp.edit().putBoolean("pending_write", false).putString("pending_feature", "").apply();
            status("Izin sudah aktif • " + FpsEngine.friendly(), WARN);
            return;
        }
        sp.edit().putBoolean("pending_write", true).apply();
        openWriteSettings();
        status("Izinkan 'Ubah setelan sistem' sekali • fitur menyala otomatis setelahnya", WARN);
        armPending();
    }

    private final Runnable pendingPoll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { resolvePending(); } catch (Throwable ignored) { }
            boolean any = sp.getBoolean("pending_write", false) || sp.getBoolean("pending_dnd", false) || sp.getBoolean("pending_cleaner", false);
            if (any && SystemClock.elapsedRealtime() < pendingUntil) ui.postDelayed(this, 1200);
        }
    };

    private void armPending() {
        pendingUntil = SystemClock.elapsedRealtime() + 180_000L;
        ui.removeCallbacks(pendingPoll);
        ui.postDelayed(pendingPoll, 1000);
    }

    private void resolvePending() {
        boolean changed = false;
        if (sp.getBoolean("pending_write", false) && Settings.System.canWrite(this)) {
            sp.edit().putBoolean("pending_write", false).apply();
            String f = sp.getString("pending_feature", "");
            if ("battery_profile".equals(f)) {
                if (ProfileEngine.batteryBalance(this, true)) { sp.edit().putBoolean("battery_profile", true).apply(); status("Izin aktif • Battery Balance dinyalakan", OK); }
            } else if ("touch_profile".equals(f)) touchBoost(true, true);
            else if ("bright".equals(f)) bright(true);
            changed = true;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (sp.getBoolean("pending_dnd", false) && nm.isNotificationPolicyAccessGranted()) {
            sp.edit().putBoolean("pending_dnd", false).apply();
            dnd(true);
            changed = true;
        }
        if (sp.getBoolean("pending_cleaner", false) && CleanerService.enabled()) {
            sp.edit().putBoolean("pending_cleaner", false).apply();
            status("IMZYY Cleaner aktif • tap aksinya sekali lagi", OK);
            changed = true;
        }
        if (changed && panel != null) { paintAll(); showTab(curTab); }
    }

    private void alertCleanerDown() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String ch = "imzyy_alert";
            nm.createNotificationChannel(new NotificationChannel(ch, "Peringatan Cleaner", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(this, 2, AccessibilityGuard.setupIntent(this), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(8, new Notification.Builder(this, ch).setContentTitle("IMZYY Cleaner mati")
                    .setContentText("Ketuk untuk mengaktifkan lagi")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentIntent(pi).setAutoCancel(true).build());
        } catch (Throwable ignored) { }
    }

    private boolean hasAllFiles() { return Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager(); }

    private LinearLayout newPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(2), 0, dp(6));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));
        content.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        return col;
    }

    private TextView title(String s) {
        TextView t = txt(s, 8, MUTE, true);
        t.setLetterSpacing(.16f);
        t.setPadding(dp(2), dp(10), 0, dp(2));
        return t;
    }

    private TextView note(String s) {
        TextView t = txt(s, 8, MUTE, false);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    private View sheen() {
        View v = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00FFFFFF, 0x77FFFFFF, 0x00FFFFFF});
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(1))));
        return v;
    }

    private TextView tile(LinearLayout r, int ic, String label, boolean first) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER); c.setPadding(0, dp(8), 0, dp(8));
        c.setBackground(bg(0xFF1A1A1D, 0xFF0C0C0D, 12, 0x22FFFFFF));
        LinearLayout lr = row(); lr.setGravity(Gravity.CENTER);
        lr.addView(icon(ic, 11, MUTE));
        TextView l = txt(label, 7, MUTE, false); l.setLetterSpacing(.12f); l.setPadding(dp(4), 0, 0, 0);
        lr.addView(l);
        TextView v = txt("--", 15, 0xFFFFFFFF, true);
        v.setGravity(Gravity.CENTER);
        c.addView(lr); c.addView(v);
        r.addView(c, w1(first));
        return v;
    }

    private TextView btn(LinearLayout r, String s, boolean first, Runnable a) {
        TextView t = txt(s, 9, TXT, true);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(3), dp(10), dp(3), dp(10));
        t.setMaxLines(1); t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(bg(0xFF262629, 0xFF161618, 11, 0x33FFFFFF));
        t.setOnClickListener(v -> a.run());
        r.addView(t, w1(first));
        return t;
    }

    /** Tombol dengan ikon buatan sendiri di depan label. */
    private LinearLayout ibtn(LinearLayout r, int ic, String s, boolean first, Runnable a) {
        LinearLayout b = row(); b.setGravity(Gravity.CENTER); b.setPadding(dp(4), dp(9), dp(4), dp(9));
        b.setBackground(bg(0xFF262629, 0xFF161618, 11, 0x33FFFFFF));
        b.addView(icon(ic, 13, TXT));
        TextView t = txt(s, 8, TXT, true); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); t.setPadding(dp(4), 0, 0, 0);
        b.addView(t);
        b.setOnClickListener(v -> a.run());
        r.addView(b, w1(first));
        return b;
    }

    /** Compact action button used by the simplified pages. */
    private void btn1(LinearLayout parent, String label, Runnable action) {
        TextView b = txt(label, 10, 0xFFE5E5E5, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setBackground(bg(0xFF202024, 0xFF101012, 10, 0x55FFFFFF));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> { try { if (action != null) action.run(); } catch (Throwable t) { status("Aksi gagal: " + t.getMessage(), WARN); } });
        parent.addView(b, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Status row with a clear action; avoids missing-helper build failures. */
    private void permRow(LinearLayout parent, String label, boolean enabled, Runnable action) {
        LinearLayout r = row();
        TextView state = txt((enabled ? "✓  " : "!  ") + label, 9, enabled ? OK : WARN, true);
        state.setGravity(Gravity.CENTER_VERTICAL);
        state.setPadding(dp(8), dp(9), dp(8), dp(9));
        state.setBackground(bg(0xFF1B1B1F, 0xFF101012, 8, 0x33FFFFFF));
        r.addView(state, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView act = txt(enabled ? "CEK" : "ATUR", 8, enabled ? 0xFFBDBDBD : OK, true);
        act.setGravity(Gravity.CENTER);
        act.setPadding(dp(10), dp(8), dp(10), dp(8));
        act.setBackground(bg(0xFF29292D, 0xFF171719, 8, 0x55FFFFFF));
        act.setOnClickListener(v -> { try { if (action != null) action.run(); } catch (Throwable t) { status("Tidak dapat membuka pengaturan", WARN); } });
        r.addView(act, new LinearLayout.LayoutParams(-2, -2));
        parent.addView(r, mt(3));
    }

    private void bigButton(LinearLayout parent, int ic, String s, Runnable a, boolean primary) {
        LinearLayout b = row(); b.setGravity(Gravity.CENTER); b.setPadding(0, dp(12), 0, dp(12));
        int fg = primary ? DARK : TXT;
        b.setBackground(primary ? bg(CY, PU, 14, 0) : bg(0xFF232326, 0xFF121214, 14, 0x44FFFFFF));
        b.addView(icon(ic, 15, fg));
        TextView t = txt(s, 11, fg, true); t.setLetterSpacing(.1f); t.setPadding(dp(7), 0, 0, 0);
        b.addView(t);
        b.setOnClickListener(v -> a.run());
        parent.addView(b, mt(8));
    }

    private void paintChips(List<TextView> chips, int sel) {
        for (int i = 0; i < chips.size(); i++) {
            TextView t = chips.get(i); boolean on = i == sel;
            t.setTextColor(on ? DARK : TXT);
            t.setBackground(on ? bg(CY, PU, 10, 0) : bg(0xFF262629, 0xFF161618, 10, 0x2AFFFFFF));
        }
    }

    /** Segmented selector (replaces Spinner, which does not work well inside an overlay). */
    private void chips(LinearLayout parent, final String key, String[] labels) { chips(parent, key, labels, null); }

    private void chips(LinearLayout parent, final String key, String[] labels, final Runnable onChange) {
        LinearLayout line = row();
        final ArrayList<TextView> cs = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = txt(labels[i], 8, TXT, true);
            t.setGravity(Gravity.CENTER); t.setPadding(0, dp(9), 0, dp(9)); t.setMaxLines(1);
            t.setOnClickListener(v -> { sp.edit().putInt(key, idx).apply(); paintChips(cs, idx); if (onChange != null) onChange.run(); });
            cs.add(t); line.addView(t, w1(i == 0));
        }
        paintChips(cs, sp.getInt(key, 0));
        parent.addView(line, mt(6));
    }

    private void toggle(LinearLayout r, final String key, final String label, boolean first) {
        TextView t = txt(label, 9, 0xFF9C9CA4, true);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(3), dp(10), dp(3), dp(10)); t.setMaxLines(1);
        t.setTag(new String[]{key, label});
        t.setOnClickListener(v -> {
            boolean n = !sp.getBoolean(key, false);
            if (key.equals("dnd")) dnd(n); else if (key.equals("bright")) bright(n); else if (key.equals("touch_profile")) touchBoost(n, true); else awake(n);
            paintAll();
        });
        toggles.add(t); paintToggle(t);
        r.addView(t, w1(first));
    }

    private void paintToggle(TextView t) {
        String[] k = (String[]) t.getTag();
        boolean on = sp.getBoolean(k[0], false);
        t.setText(k[1]);
        t.setTextColor(on ? DARK : 0xFF9C9CA4);
        t.setBackground(on ? bg(CY, BL, 11, 0) : bg(0xFF161618, 0xFF0C0C0D, 11, 0x2AFFFFFF));
    }

    private void paintAll() { for (TextView t : toggles) paintToggle(t); }

    private void tintSwitch(Switch sw) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{0xFFFFFFFF, 0xFF9A9AA2}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{0x99FFFFFF, 0xFF2C2C30}));
    }

    private void smartSwitch(LinearLayout parent, final String key, int ic, String name, String detail) { smartSwitchDef(parent, key, false, ic, name, detail); }

    private void smartSwitchDef(LinearLayout parent, final String key, boolean def, int ic, String name, String detail) {
        LinearLayout item = row();
        item.setPadding(dp(9), dp(7), dp(7), dp(7));
        item.setBackground(bg(0xFF141416, 0xFF0A0A0B, 11, 0x1FFFFFFF));
        IconView iv = icon(ic, 15, TXT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(15), dp(15)); ilp.rightMargin = dp(8);
        item.addView(iv, ilp);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.addView(txt(name, 9, 0xFFF5F5F7, true));
        col.addView(txt(detail, 7, MUTE, false));
        final Switch sw = new Switch(this);
        tintSwitch(sw);
        sw.setChecked(sp.getBoolean(key, def));
        final boolean[] guard = new boolean[1];
        sw.setOnCheckedChangeListener((btnView, on) -> {
            if (guard[0]) return;
            if ("frame_opt".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                if (on) setFps(0); else { FpsEngine.reset(this); status("Frame optimization off • refresh otomatis", WARN); }
            } else if ("battery_profile".equals(key)) {
                if (!ProfileEngine.batteryBalance(this, on)) {
                    guard[0] = true; sw.setChecked(!on); guard[0] = false;
                    if (on) sp.edit().putString("pending_feature", "battery_profile").apply();
                    askWrite();
                } else {
                    sp.edit().putBoolean(key, on).apply();
                    status(on ? "Battery Balance aktif" : "Pengaturan layar dipulihkan", OK);
                }
            } else if ("cleaner_wanted".equals(key)) {
                AccessibilityGuard.markWanted(this, on);
                if (on && !CleanerService.enabled()) enableCleaner(true);
                else status(on ? "Cleaner dijaga tetap aktif" : "Penjagaan Cleaner dimatikan", on ? OK : MUTE);
            } else if ("review_panel".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                if (on) {
                    if (!Settings.canDrawOverlays(this)) { guard[0] = true; sw.setChecked(false); guard[0] = false; sp.edit().putBoolean(key, false).apply(); status("Panel review butuh izin tampil di atas aplikasi", WARN); }
                    else { reviewOn(); status("Panel review aktif • geser untuk memindahkan, ketuk untuk ganti tampilan", OK); }
                } else { if (review != null) review.hide(); status("Panel review dimatikan", MUTE); }
            } else if ("review_hide_with_panel".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                if (review != null) review.setSuppressed(on && panel != null);
            } else if ("hub_gesture_safe".equals(key) || "hub_flush".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                reflow();
            } else if ("touch_profile".equals(key)) {
                if (!touchBoost(on, true)) { guard[0] = true; sw.setChecked(!on); guard[0] = false; }
            } else if ("net_boost".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                if (on) {
                    boolean held = NetEngine.lockOn(this);
                    if (!held) { guard[0] = true; sw.setChecked(false); guard[0] = false; sp.edit().putBoolean(key, false).apply(); }
                    status(held ? "Wi-Fi low-latency aktif • Wi-Fi tidak masuk hemat daya" : "Wi-Fi lock gagal • tidak ada Wi-Fi?", held ? OK : WARN);
                } else { NetEngine.lockOff(); status("Wi-Fi low-latency dilepas", MUTE); }
            } else if ("fan_engine".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                if (on) { startFanEngine(); status("Suara mesin menyala • RPM ikut beban CPU/GPU", OK); }
                else { stopFanEngine(); status("Suara mesin dimatikan", MUTE); }
            } else if ("fan_opt".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                if (on) fanOptKick(); else status("Fan Optimizer dimatikan", MUTE);
            } else if ("engine_autostart".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                status(on ? "Auto start mesin aktif • mesin menyala lagi sendiri" : "Auto start mesin mati • mesin tidak dilanjutkan setelah hub restart", on ? OK : MUTE);
            } else if ("hub_antivanish".equals(key)) {
                sp.edit().putBoolean(key, on).apply();
                if (on) { GuardJob.schedule(getApplicationContext()); KeepAlive.arm(getApplicationContext(), 15_000L); ui.removeCallbacks(windowWatchdog); ui.post(windowWatchdog); }
                else KeepAlive.cancel(getApplicationContext());
                status(on ? "Anti hilang aktif • jendela dijaga" : "Anti hilang mati • tidak dipasang ulang otomatis", on ? OK : WARN);
            } else sp.edit().putBoolean(key, on).apply();
        });
        item.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        item.addView(sw);
        parent.addView(item, mt(6));
    }

    // ------------------------------------------------------------ position memory
    private int sw() {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) { try { return wm.getCurrentWindowMetrics().getBounds().width(); } catch (Throwable ignored) { } }
        return getResources().getDisplayMetrics().widthPixels;
    }
    private int sh() {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) { try { return wm.getCurrentWindowMetrics().getBounds().height(); } catch (Throwable ignored) { } }
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int hubMode() { return sp.getInt("hub_mode", 0); }   // 0 = tombol bulat, 1 = sidebar, 2 = handle swipe
    /** Sidebar dan handle swipe sama-sama menempel di tepi layar. */
    private boolean hubSide() { return hubMode() >= 1; }
    private boolean hubSwipe() { return hubMode() == 2; }
    /** Cara membuka di mode swipe: 0 = swipe atau ketuk, 1 = ketuk saja, 2 = swipe saja. */
    private int hubTrigger() { return Math.max(0, Math.min(2, sp.getInt("hub_trigger", 0))); }
    /** Panjang swipe ke arah dalam layar sampai menu terbuka (dp): SEDANG, PENDEK, PANJANG. */
    private int swipeLenDp() { return new int[]{40, 18, 80}[Math.max(0, Math.min(2, sp.getInt("hub_swipe_len", 0)))]; }
    private int hubSize() { return Math.max(0, Math.min(2, sp.getInt("hub_size", 0))); }
    private int bw() { return hubSwipe() ? dp(new int[]{18, 14, 24}[hubSize()]) : hubSide() ? dp(new int[]{26, 22, 32}[hubSize()]) : dp(new int[]{50, 42, 60}[hubSize()]); }
    private int bh() { return hubSwipe() ? dp(new int[]{110, 84, 150}[hubSize()]) : hubSide() ? dp(new int[]{96, 80, 116}[hubSize()]) : bw(); }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(v, Math.max(lo, hi))); }

    /** which: 0 = status bar + cutout, 1 = navigation bar, 2 = zona gestur sistem. Return {left, top, right, bottom} dalam px. */
    private int[] insets(int which) {
        if (Build.VERSION.SDK_INT >= 30 && wm != null) {
            try {
                int types = which == 0 ? (android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.displayCutout())
                        : which == 1 ? android.view.WindowInsets.Type.navigationBars() : android.view.WindowInsets.Type.systemGestures();
                android.graphics.Insets i = wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(types);
                return new int[]{i.left, i.top, i.right, i.bottom};
            } catch (Throwable ignored) { }
        }
        return new int[]{0, 0, 0, 0};
    }
    private boolean gestureSafe() { return sp.getBoolean("hub_gesture_safe", true); }
    private int safeTop() { return gestureSafe() ? insets(0)[1] + dp(2) : 0; }
    private int safeBottom() { return gestureSafe() ? Math.max(insets(1)[3], insets(2)[3]) + dp(6) : 0; }

    /** Jarak sidebar dari tepi layar supaya tidak bentrok dengan gestur back (usap dari tepi). */
    private int gestureEdge(boolean left) {
        if (!gestureSafe()) return 0;
        int g = insets(2)[left ? 0 : 2];
        if (g <= 0) {
            try { if (Settings.Secure.getInt(getContentResolver(), "navigation_mode", 0) == 2) g = dp(24); } catch (Throwable ignored) { }
        }
        return g + dp(4);
    }

    /** Jarak sidebar dari tepi: 0 bila "nempel paling pojok" aktif (tap saja, tidak perlu usap), selain itu menjauhi zona gestur back. */
    private int edgeGap(boolean left) { return sp.getBoolean("hub_flush", true) ? 0 : gestureEdge(left); }

    private GradientDrawable bubbleBg;

    /** Sudut luar sidebar rata dengan tepi layar, sudut dalam membulat (seperti handle Game Tools Samsung). */
    private void shapeSidebar() {
        if (bubbleBg == null || !hubSide()) return;
        boolean left = sp.getInt("bside", 1) == 0;
        float r = dp(14);
        if (sp.getBoolean("hub_flush", true))
            bubbleBg.setCornerRadii(left ? new float[]{0, 0, r, r, r, r, 0, 0} : new float[]{r, r, 0, 0, 0, 0, r, r});
        else bubbleBg.setCornerRadius(r);
    }

    private boolean sideDocked() { return hubSide() && sp.getBoolean("hub_snap", true); }

    /** Panel menempel di sisi yang sama dengan sidebar dan sejajar vertikal dengan handle-nya. */
    private void dockPanelPos(int w, int h) {
        boolean left = sp.getInt("bside", 1) == 0;
        int gap = dp(6);
        pp.x = left ? gap : sw() - w - gap;
        pp.y = clamp(bp.y + bh() / 2 - h / 2, safeTop(), sh() - h - safeBottom());
    }

    private void clampBubble() {
        bp.x = clamp(bp.x, 0, sw() - bw());
        bp.y = clamp(bp.y, safeTop(), sh() - bh() - safeBottom());
    }

    /** Sidebar menempel di sisi tersimpan (0 = kiri, 1 = kanan). Tombol bulat bebas di mana saja. */
    private void dockSidebar() {
        if (bp == null || !hubSide() || !sp.getBoolean("hub_snap", true)) return;
        boolean left = sp.getInt("bside", 1) == 0;
        bp.x = left ? edgeGap(true) : sw() - bw() - edgeGap(false);
        shapeSidebar();
    }

    private void snapBubble() {
        if (bp == null || !hubSide() || !sp.getBoolean("hub_snap", true)) return;
        boolean left = bp.x + bw() / 2 < sw() / 2;
        sp.edit().putInt("bside", left ? 0 : 1).apply();
        shapeSidebar();
        final int to = left ? edgeGap(true) : sw() - bw() - edgeGap(false);
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(bp.x, to);
        va.setDuration(160);
        va.addUpdateListener(an -> { bp.x = (Integer) an.getAnimatedValue(); pushBubble(); });
        va.start();
        ui.postDelayed(this::savePos, 200);
    }

    /** Minta sistem tidak menganggap usapan di atas hub sebagai gestur back/home. */
    private void excludeGestures(View v) {
        if (Build.VERSION.SDK_INT < 29 || v == null) return;
        v.addOnLayoutChangeListener((vv, l, t, r, b, ol, ot, orr, ob) -> {
            try { vv.setSystemGestureExclusionRects(java.util.Collections.singletonList(new android.graphics.Rect(0, 0, r - l, b - t))); } catch (Throwable ignored) { }
        });
    }

    /** Ganti bentuk hub (tombol / sidebar) dan ukuran tanpa menutup panel. */
    private void applyHubStyle() {
        if (bp != null) savePos();
        rebuildBubble();
        if (panel != null && bubble != null) bubble.setVisibility(View.GONE);
    }

    private void savePos() {
        sp.edit().putFloat("bxr", bp.x / (float) sw()).putFloat("byr", bp.y / (float) sh()).apply();
    }

    private void pushBubble() {
        try { if (bubble != null && bubble.isAttachedToWindow()) wm.updateViewLayout(bubble, bp); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ bubble
    private void showBubble() {
        final boolean side = hubSide();
        final int w = bw(), h = bh();
        FrameLayout b = new FrameLayout(this);
        GradientDrawable g;
        if (side) {
            g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF3A3A3F, 0xFF060607});
            g.setCornerRadius(dp(14)); g.setStroke(dp(1), 0xB3FFFFFF);
            b.setBackground(g);
            bubbleBg = g;
            if (hubSwipe()) {
                // Handle swipe: pil tipis di pojok layar, hanya garis pegangan (tanpa logo) seperti handle Game Tools.
                View bar = new View(this);
                GradientDrawable bg2 = new GradientDrawable(); bg2.setCornerRadius(dp(3)); bg2.setColor(0xCCFFFFFF);
                bar.setBackground(bg2);
                b.addView(bar, new FrameLayout.LayoutParams(Math.max(dp(3), w / 4), Math.round(h * .45f), Gravity.CENTER));
            }
            LinearLayout grip = new LinearLayout(this);
            grip.setOrientation(LinearLayout.VERTICAL); grip.setGravity(Gravity.CENTER);
            int li = Math.max(dp(12), w - dp(8));
            grip.addView(new IconView(this).set(IconView.LOGO, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(li, li));
            for (int i = 0; i < 3; i++) {
                View d = new View(this);
                GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(0x88FFFFFF);
                d.setBackground(dg);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(3), dp(3)); dl.topMargin = dp(5);
                grip.addView(d, dl);
            }
            if (!hubSwipe()) b.addView(grip, new FrameLayout.LayoutParams(-1, -1));
        } else {
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF343438, 0xFF060607});
            g.setShape(GradientDrawable.OVAL); g.setStroke(dp(1), 0xB3FFFFFF);
            b.setBackground(g);
            bubbleBg = null;
            IconView ic = new IconView(this).set(IconView.LOGO, 0xFFFFFFFF, true);
            int li = Math.round(w * .56f);
            b.addView(ic, new FrameLayout.LayoutParams(li, li, Gravity.CENTER));
        }
        bubble = b;
        excludeGestures(bubble);
        bp = lp(w, h);
        bp.x = sp.contains("bxr") ? Math.round(sp.getFloat("bxr", 0f) * sw()) : dp(4);
        bp.y = sp.contains("byr") ? Math.round(sp.getFloat("byr", .3f) * sh()) : dp(220);
        if (side && !sp.contains("bxr")) { sp.edit().putInt("bside", 1).apply(); bp.y = sh() / 3; }
        dockSidebar();
        clampBubble();
        int f0 = sp.getInt("fps", -1);
        if (f0 >= 0) FpsEngine.prefer(bp, this, FpsEngine.pick(this, f0));
        applyAwake();
        bubble.setAlpha(.92f);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty; boolean moved, swiped;
            @Override public boolean onTouch(View v, MotionEvent e) {
                final boolean swipeMode = hubSwipe();
                final boolean left = sp.getInt("bside", 1) == 0;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = bp.x; sy = bp.y; tx = e.getRawX(); ty = e.getRawY(); moved = false; swiped = false; bubble.setAlpha(1f); return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - tx, dy = e.getRawY() - ty;
                        if (swipeMode) {
                            if (swiped) return true;
                            float inward = left ? dx : -dx;
                            if (!moved && Math.abs(dy) > dp(10) && Math.abs(dy) > Math.abs(dx)) moved = true;   // geser vertikal = pindah posisi di tepi
                            if (moved) { bp.y = sy + (int) dy; clampBubble(); pushBubble(); return true; }
                            if (hubTrigger() != 1) {
                                // Handle ikut tertarik sedikit mengikuti jari, lalu menu terbuka begitu panjang swipe tercapai.
                                float pull = Math.max(0f, Math.min(inward, dp(22)));
                                bubble.setTranslationX(left ? pull : -pull);
                                if (inward >= dp(swipeLenDp())) {
                                    swiped = true; bubble.setTranslationX(0);
                                    try { bubble.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); } catch (Throwable ignored) { }
                                    playOnOpen = true; curTab = TAB_MENU; openPanel();
                                }
                            }
                            return true;
                        }
                        if (Math.abs(dx) + Math.abs(dy) > dp(hubSide() ? 10 : 6)) moved = true;
                        if (moved) { bp.x = sx + (int) dx; bp.y = sy + (int) dy; clampBubble(); pushBubble(); }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        bubble.setAlpha(.92f); bubble.setTranslationX(0);
                        if (swiped) { swiped = false; return true; }
                        if (swipeMode) {
                            if (!moved) { if (hubTrigger() != 2) { playOnOpen = true; curTab = TAB_MENU; openPanel(); } }   // ketuk = semua menu (kecuali mode swipe saja)
                            else { clampBubble(); pushBubble(); snapBubble(); savePos(); }
                            return true;
                        }
                        if (!moved) { playOnOpen = true; if (hubSide() && sp.getBoolean("hub_open_menu", true)) curTab = TAB_MENU; openPanel(); }   // satu ketuk = semua menu langsung terbuka
                        else { clampBubble(); pushBubble(); snapBubble(); savePos(); } // sidebar merapat ke tepi terdekat; tombol tetap di tempat dilepas
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        bubble.setAlpha(.92f); bubble.setTranslationX(0); swiped = false; clampBubble(); pushBubble(); snapBubble(); savePos(); return true;
                    default: return true;
                }
            }
        });
        try {
            wm.addView(bubble, bp);
        } catch (Throwable e) {
            android.util.Log.e("IMZYY-Floating", "Initial bubble attach failed", e);
            bubble = null;
            ui.postDelayed(() -> {
                if (running && Settings.canDrawOverlays(this) && bubble == null) rebuildBubble();
            }, 1500);
        }
    }

    /** Rebuild a detached bubble instead of reusing a possibly-invalid WindowManager view token. */
    private void rebuildBubble() {
        if (wm == null || !Settings.canDrawOverlays(this)) return;
        View old = bubble;
        bubble = null;
        if (old != null) {
            try { if (old.isAttachedToWindow()) wm.removeViewImmediate(old); } catch (Throwable ignored) { }
        }
        showBubble();
    }

    // ------------------------------------------------------------ panel shell
    private int[] panelSize() {
        boolean land = sw() > sh();
        int w = Math.min(dp(316), (int) (sw() * .90f));
        int h = Math.min((int) (sh() * (land ? .88f : .58f)), dp(440));
        if (hubSide()) h = Math.min((int) (sh() * (land ? .90f : .74f)), dp(520));   // sidebar: panel lebih tinggi, semua menu terlihat
        h = Math.min(h, sh() - safeTop() - safeBottom() - dp(8));
        return new int[]{w, h};
    }

    private void openPanel() {
        if (panel != null) return;
        bubble.setVisibility(View.GONE);
        if (review != null && sp.getBoolean("review_hide_with_panel", false)) review.setSuppressed(true);
        int[] sz = panelSize();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11), dp(9), dp(11), dp(8));
        GradientDrawable shell = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF51A1A1D, 0xF5060607, 0xF5000000});
        shell.setCornerRadius(dp(22)); shell.setStroke(dp(1), 0x40FFFFFF);
        box.setBackground(shell);

        // header = drag handle
        LinearLayout head = row();
        head.setPadding(0, 0, 0, dp(6));
        headIcon = new IconView(this).set(IconView.LOGO, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(30), dp(30)); llp.rightMargin = dp(9);
        headIcon.setPadding(dp(3), dp(3), dp(3), dp(3));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        headTitle = txt("Game Turbo", 15, 0xFFFFFFFF, true);
        headSub = txt("IMZYY KNOX  •  tahan & geser untuk memindahkan", 7, MUTE, false);
        titles.addView(headTitle); titles.addView(headSub);
        headIcon.setOnClickListener(v -> { if (curTab != TAB_MENU) showTab(TAB_MENU); else showTab(TAB_INFO); });
        head.addView(headIcon, llp); head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        IconView min = new IconView(this).set(IconView.MIN, TXT, false);
        min.setPadding(dp(5), dp(5), dp(5), dp(5)); min.setOnClickListener(v -> closePanel());
        IconView close = new IconView(this).set(IconView.CLOSE, 0xFFFF8080, false);
        close.setPadding(dp(5), dp(5), dp(5), dp(5)); close.setOnClickListener(v -> {
            // X adalah perintah tutup permanen: watchdog/alarm tidak boleh menghidupkan kembali hub.
            userStop();
        });
        head.addView(min, new LinearLayout.LayoutParams(dp(30), dp(30)));
        head.addView(close, new LinearLayout.LayoutParams(dp(30), dp(30)));
        head.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (pp == null || panel == null) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = pp.x; sy = pp.y; tx = e.getRawX(); ty = e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        pp.x = clamp(sx + (int) (e.getRawX() - tx), 0, sw() - pp.width);
                        pp.y = clamp(sy + (int) (e.getRawY() - ty), safeTop(), sh() - pp.height - safeBottom());
                        // bubble ikut posisi panel supaya saat di-minimize muncul di sini, bukan di tempat lama
                        bp.x = pp.x; bp.y = pp.y; clampBubble();
                        try { wm.updateViewLayout(panel, pp); } catch (Exception ignored) { }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        bp.x = pp.x; bp.y = pp.y; clampBubble(); savePos();
                        return true;
                    default: return true;
                }
            }
        });
        box.addView(head);
        box.addView(sheen());

        status = txt("SIAP", 10, TXT, true);
        status.setPadding(dp(9), dp(6), dp(9), dp(6));
        // Respon tidak lagi terpotong satu baris: sampai 3 baris (2 di layar miring), ketuk untuk membuka semuanya, tahan untuk menyalin.
        final int statusLines = sw() > sh() ? 2 : 3;
        status.setSingleLine(false); status.setMaxLines(statusLines); status.setEllipsize(TextUtils.TruncateAt.END);
        status.setOnClickListener(v -> { if (status == null) return; boolean open = status.getMaxLines() > statusLines; status.setMaxLines(open ? statusLines : 12); });
        status.setOnLongClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && status != null) { cm.setPrimaryClip(ClipData.newPlainText("Game Turbo status", status.getText())); Toast.makeText(this, "Pesan status disalin", Toast.LENGTH_SHORT).show(); }
            } catch (Throwable ignored) { }
            return true;
        });
        status.setBackground(bg(0xFF1D1D20, 0xFF111113, 10, 0x22FFFFFF));
        box.addView(status, mt(6));
        // Kipas CPU | FPS | GPU menyatu dalam satu strip di bawah SIAP. FPS diukur dari frame overlay;
        // beban CPU/GPU dibaca berkala oleh hdrTick. Semua kipas berputar terus (animasi visual).
        LinearLayout fpsStrip = new LinearLayout(this);
        fpsStrip.setOrientation(LinearLayout.VERTICAL);
        fpsStrip.setPadding(dp(8), dp(6), dp(8), dp(6));
        fpsStripBox = fpsStrip;
        fpsStrip.setBackground(bg(0xFF171719, 0xFF09090A, 12, 0x33FFFFFF));
        hdrCpu = new FanView(this); hdrCpu.setLabel("CPU"); hdrCpu.setCompact(true); hdrCpu.spinUp();
        hdrGpu = new FanView(this); hdrGpu.setLabel("GPU"); hdrGpu.setCompact(true); hdrGpu.spinUp();
        miniFan = new FanView(this); miniFan.setLabel("VSYNC"); miniFan.setCompact(true); miniFan.spinUp();
        applyFanAccent(sp.getInt("gaming_profile", 1));
        hdrCpuTxt = txt("--", 10, 0xFFFFFFFF, true); hdrCpuSub = txt("CPU", 7, MUTE, false);
        hdrGpuTxt = txt("--", 10, 0xFFFFFFFF, true); hdrGpuSub = txt("GPU", 7, MUTE, false);
        miniFpsNow = txt("VSYNC  -- Hz", 10, TXT, true);
        miniFpsPrev = txt("VSYNC SEBELUM --", 7, MUTE, false);
        miniFpsDelta = txt("VSYNC SEKARANG --  •  Δ --", 7, MUTE, false);
        miniThermalLine = txt("RPM N/A  •  --°C", 7, MUTE, false);
        LinearLayout fanRow = new LinearLayout(this);
        fanRow.setOrientation(LinearLayout.HORIZONTAL);
        fanRow.setGravity(Gravity.CENTER_VERTICAL);
        fanRow.addView(headFan(hdrCpu, fanDp(52), hdrCpuTxt, hdrCpuSub), new LinearLayout.LayoutParams(0, -2, 1f));
        fanRow.addView(headFan(miniFan, fanDp(66), miniFpsNow, null), new LinearLayout.LayoutParams(0, -2, 1.35f));
        fanRow.addView(headFan(hdrGpu, fanDp(52), hdrGpuTxt, hdrGpuSub), new LinearLayout.LayoutParams(0, -2, 1f));
        fpsStrip.addView(fanRow, new LinearLayout.LayoutParams(-1, -2));
        View sep = new View(this); sep.setBackgroundColor(0x22FFFFFF);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(-1, dp(1)); sepLp.topMargin = dp(5); sepLp.bottomMargin = dp(4);
        fpsStrip.addView(sep, sepLp);
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        for (TextView t : new TextView[]{miniFpsPrev, miniFpsDelta, miniThermalLine}) {
            t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END); t.setGravity(Gravity.CENTER);
            infoRow.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        fpsStrip.addView(infoRow, new LinearLayout.LayoutParams(-1, -2));
        box.addView(fpsStrip, mt(4));

        content = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1);
        clp.topMargin = dp(4);
        box.addView(content, clp);

        navItems.clear(); navScroll = null;

        panel = box;
        pp = lp(sz[0], sz[1]);
        // Panel muncul tepat di tempat bubble berada (tidak lompat ke tengah lagi).
        final boolean docked = sideDocked();
        if (docked) dockPanelPos(sz[0], sz[1]);
        else {
            pp.x = clamp(bp.x, 0, sw() - sz[0]);
            pp.y = clamp(bp.y, safeTop(), sh() - sz[1] - safeBottom());
            bp.x = pp.x; bp.y = pp.y; clampBubble();
        }
        applyAwake();
        int f0 = sp.getInt("fps", -1);
        if (f0 >= 0) FpsEngine.prefer(pp, this, FpsEngine.pick(this, f0));
        box.setAlpha(0f); box.setScaleX(.95f); box.setScaleY(.95f);
        if (docked) box.setTranslationX((sp.getInt("bside", 1) == 0 ? -1f : 1f) * sz[0] * .6f);   // meluncur keluar dari sisi sidebar
        try {
            wm.addView(panel, pp);
        } catch (Exception e) {
            panel = null; pp = null; status = null; content = null;
            if (bubble != null) bubble.setVisibility(View.VISIBLE);
            status("Panel gagal ditampilkan • cek izin tampil di atas aplikasi", BAD);
            return;
        }
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).setDuration(docked ? 220 : 180).start();
        if (playOnOpen) { playOnOpen = false; }
        sp.edit().putBoolean("panel_open", true).apply();
        showTab(curTab);
        ui.removeCallbacks(tick);
        ui.post(tick);
        ui.removeCallbacks(hdrTick);
        ui.post(hdrTick);
    }

    private void closePanel() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hdrTick);
        hdrCpu = hdrGpu = null; hdrCpuTxt = hdrCpuSub = hdrGpuTxt = hdrGpuSub = null; miniFan = null;
        stopLive();
        if (panel != null) {
            // minimize: bubble muncul di posisi panel terakhir
            if (pp != null && hubSide()) {
                // Sidebar: sisi mengikuti letak panel (bila digeser), tinggi handle sejajar tengah panel.
                sp.edit().putInt("bside", pp.x + pp.width / 2 < sw() / 2 ? 0 : 1).apply();
                bp.y = pp.y + pp.height / 2 - bh() / 2;
                dockSidebar(); clampBubble(); shapeSidebar(); savePos();
            } else if (pp != null) { bp.x = pp.x; bp.y = pp.y; dockSidebar(); clampBubble(); savePos(); }
            try { wm.removeView(panel); } catch (Exception ignored) { }
        }
        panel = null; pp = null; status = null; content = null;
        headTitle = null; headSub = null; headIcon = null;
        navItems.clear(); navScroll = null;
        sp.edit().putBoolean("panel_open", false).apply();
        if (review != null) review.setSuppressed(false);
        if (bubble != null) { pushBubble(); bubble.setVisibility(View.VISIBLE); }
    }

    private void stopLive() {
        stopFpsSampler();
        meter.stop();
        tRam = tBat = tTemp = tTherm = tHz = null;
        barFill = barRest = null;
        appList = null; logBox = null; gpuName = null; gpuSub = null; optLine = null; optSub = null; menuStat = null; appSummary = null; loadingApps = false;
        netLine = null; netSub = null;
        coreFill = null;
        fpsBtns.clear(); toggles.clear(); fxChips.clear();
        scanGen++;
    }

    /** Header panel: ikon kembali + judul halaman saat bukan di menu utama. */
    private void paintHeader() {
        if (headTitle == null || headIcon == null) return;
        boolean menu = curTab == TAB_MENU;
        headTitle.setText(curTab >= 0 && curTab < PAGE_TITLE.length ? PAGE_TITLE[curTab] : "Game Turbo");
        headSub.setText(menu ? "IMZYY KNOX  •  tahan & geser untuk memindahkan" : "Ketuk panah untuk kembali ke menu");
        headIcon.set(menu ? IconView.LOGO : IconView.BACK, 0xFFFFFFFF, menu);
    }

    private void showTab(int i) {
        if (content == null) return;
        curTab = i;
        sp.edit().putInt("tab", i).apply();
        stopLive();
        content.removeAllViews();
        paintHeader();
        switch (i) {
            case TAB_MENU: pageMenu(); break;
            case TAB_CLEANER: pageCleaner(); break;
            case 6: pageVoice(); break;
            case 7: pageGpu(); break;
            case TAB_NET: pageNet(); break;
            case TAB_FAN: pageFan(); break;
            case 0: pageHome(); break;
            case 1: pageFps(); break;
            case 2: pageStyle(); break;
            case 14: pageBatteryManager(); break;
            case 15: pageSidebarSwipe(); break;
            case 3: pageTools(); break;
            case TAB_APPS: pageApps(true); break;
            case TAB_CACHE: pageApps(false); break;
            default: pageInfo(); break;
        }
        startFpsSampler();
    }


    // ------------------------------------------------------------ MENU UTAMA ala Game Space (versi Samsung One UI)
    private static final int ONEUI_BLUE = 0xFF3E91FF, ROW_BG = 0xFF1C1C1F;

    private LinearLayout menuGroup() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(bg(ROW_BG, ROW_BG, 16, 0));
        g.setPadding(0, dp(3), 0, dp(3));
        return g;
    }

    private void tintBlue(Switch sw) {
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{0xFFFFFFFF, 0xFFB5B5BC}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{ONEUI_BLUE, 0xFF3A3A40}));
    }

    /** Satu baris menu: ikon, judul, keterangan satu baris, dan tampilan di kanan (switch atau panah). Return TextView keterangan. */
    private TextView menuRow(LinearLayout group, int ic, String name, String sub, View trailing, final Runnable click) {
        if (group.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(0x14FFFFFF);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            dl.leftMargin = dp(52);
            group.addView(d, dl);
        }
        LinearLayout r = row();
        r.setPadding(dp(10), dp(8), dp(10), dp(8));
        FrameLayout box = new FrameLayout(this);
        box.setBackground(bg(0xFF2A2A2E, 0xFF2A2A2E, 10, 0));
        box.addView(new IconView(this).set(ic, 0xFFFFFFFF, false), new FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(32), dp(32)); bl.rightMargin = dp(10);
        r.addView(box, bl);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        TextView t = txt(name, 11, 0xFFFFFFFF, true); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        TextView sb = txt(sub, 8, MUTE, false); sb.setSingleLine(true); sb.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t); col.addView(sb);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) r.addView(trailing);
        if (click != null) r.setOnClickListener(v -> click.run());
        group.addView(r);
        return sb;
    }

    private TextView chevron() {
        TextView t = txt("›", 20, MUTE, false);
        t.setPadding(dp(6), 0, dp(2), 0);
        return t;
    }

    /** Baris yang membuka halaman lain di dalam panel. */
    private TextView menuPage(LinearLayout g, int ic, String name, String sub, final int tab) {
        return menuRow(g, ic, name, sub, chevron(), () -> showTab(tab));
    }

    /** Baris aksi sekali tekan. */
    private TextView menuAction(LinearLayout g, int ic, String name, String sub, Runnable a) {
        return menuRow(g, ic, name, sub, chevron(), a);
    }

    /** Baris dengan switch. Status akhir dibaca dari penyimpanan: bila aksi gagal atau menunggu izin, switch kembali sendiri. */
    private void menuSwitch(LinearLayout g, final String key, int ic, String name, String sub, final java.util.function.Consumer<Boolean> act) {
        final Switch sw = new Switch(this);
        tintBlue(sw);
        sw.setChecked(sp.getBoolean(key, false));
        final boolean[] guard = new boolean[1];
        sw.setOnCheckedChangeListener((v, on) -> {
            if (guard[0]) return;
            act.accept(on);
            boolean actual = sp.getBoolean(key, false);
            if (actual != on) { guard[0] = true; sw.setChecked(actual); guard[0] = false; }
        });
        menuRow(g, ic, name, sub, sw, () -> sw.toggle());
    }

    private View profileTile(String label, int icon, int color, int mode) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(9), dp(5), dp(8));
        boolean selected = sp.getInt("gaming_profile", 1) == mode;
        tile.setBackground(bg(selected ? 0xFF24262B : 0xFF141416, 0xFF09090A, 13, selected ? color : 0x22FFFFFF));
        FrameLayout emblem = new FrameLayout(this);
        emblem.setBackground(bg(color & 0x24FFFFFF, color & 0x18FFFFFF, 99, color & 0x66FFFFFF));
        emblem.addView(new IconView(this).set(icon, color, true), new FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER));
        tile.addView(emblem, new LinearLayout.LayoutParams(dp(43), dp(43)));
        TextView caption = txt(label, 8, TXT, true); caption.setGravity(Gravity.CENTER); caption.setSingleLine(false); caption.setMaxLines(2);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2); cp.topMargin = dp(5); tile.addView(caption, cp);
        tile.setOnClickListener(v -> applyGamingProfile(mode));
        return tile;
    }

    private void applyGamingProfile(int mode) {
        if (mode == 3 && thermalBlocked()) return;
        SharedPreferences.Editor ed = sp.edit().putInt("gaming_profile", mode);
        switch (mode) {
            case 0: // prioritas ketahanan baterai
                ed.putInt("gpu_profile", 3).putBoolean("battery_profile", true); ed.apply();
                if (!ProfileEngine.batteryBalance(this, true)) { sp.edit().putBoolean("battery_profile", false).apply(); status("Profil hemat: butuh izin ubah setelan untuk Battery Balance", WARN); }
                setFps(60); applyGpuPreset(); break;
            case 1: // standard: restore sistem
                ed.putInt("gpu_profile", 0).putBoolean("battery_profile", false); ed.apply();
                ProfileEngine.batteryBalance(this, false); FpsEngine.reset(this); GpuEngine.setAnim(this, 1f); sp.edit().remove("fps").apply(); status("Profil Standart diterapkan", OK); break;
            case 2: // balanced
                ed.putInt("gpu_profile", 2).putBoolean("battery_profile", false); ed.apply();
                ProfileEngine.batteryBalance(this, false); setFps(90); applyGpuPreset(); status("Profil Balanced aktif", OK); break;
            default: // ImzzBoosted
                ed.putInt("gpu_profile", 1).putBoolean("battery_profile", false); ed.apply();
                ProfileEngine.batteryBalance(this, false); applyGpuPreset(); boost(0); netBoost(false); status("IMZZ BOOSTED aktif • proteksi suhu tetap jalan", OK); break;
        }
        applyFanAccent(mode);
        if (content != null && (curTab == TAB_MENU || curTab == 14)) showTab(curTab);
        addLog("PROFIL GAME • " + new String[]{"Prioritas Ketahanan Battery", "Standart", "Balanced", "ImzzBoosted"}[mode]);
    }

    private void applyFanAccent(int mode) {
        int color = new int[]{0xFF45D5A5, 0xFFE0E0E5, 0xFF62A8FF, 0xFFFF8B45}[Math.max(0, Math.min(3, mode))];
        if (hdrCpu != null) { hdrCpu.setProfileAccent(color); hdrCpu.setProfileMode(mode); }
        if (hdrGpu != null) { hdrGpu.setProfileAccent(color); hdrGpu.setProfileMode(mode); }
        if (miniFan != null) { miniFan.setProfileAccent(color); miniFan.setProfileMode(mode); }
    }

    private View quickIcon(int icon, String caption, Runnable action) {
        LinearLayout tile = new LinearLayout(this); tile.setOrientation(LinearLayout.VERTICAL); tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(9), dp(5), dp(8)); tile.setBackground(bg(0xFF171719, 0xFF09090A, 13, 0x24FFFFFF));
        FrameLayout emblem = new FrameLayout(this); emblem.setBackground(bg(0xFF25262A, 0xFF111113, 99, 0x22FFFFFF));
        emblem.addView(new IconView(this).set(icon, 0xFFE8E8EC, true), new FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER));
        tile.addView(emblem, new LinearLayout.LayoutParams(dp(43), dp(43)));
        TextView cap = txt(caption, 8, TXT, true); cap.setGravity(Gravity.CENTER); tile.addView(cap, mt(5)); tile.setOnClickListener(v -> action.run()); return tile;
    }

    private void pageMenu() {
        LinearLayout c = newPage();
        menuStat = txt("IMZYY KNOX • GAME BOOST", 8, MUTE, true);
        menuStat.setPadding(dp(4), dp(2), dp(4), dp(2)); menuStat.setSingleLine(true);
        c.addView(menuStat, mt(0));

        c.addView(title("PROFIL GAMING"), mt(4));
        LinearLayout profiles = row(); profiles.setGravity(Gravity.CENTER);
        int[] icons = {IconView.BATTERY, IconView.SHIELD, IconView.STYLE, IconView.BOLT};
        int[] colors = {0xFF45D5A5, 0xFFE0E0E5, 0xFF62A8FF, 0xFFFF8B45};
        String[] names = {"Battery", "Standart", "Balanced", "ImzzBoost"};
        for (int i=0;i<4;i++) profiles.addView(profileTile(names[i], icons[i], colors[i], i), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(profiles, mt(3));

        c.addView(title("OPTIMASI • SEKALI TAP"), mt(7));
        LinearLayout quick = row(); quick.setGravity(Gravity.CENTER);
        quick.addView(quickIcon(IconView.CACHE, "MEMORY", this::clearAllCache), new LinearLayout.LayoutParams(0, -2, 1f));
        quick.addView(quickIcon(IconView.FPS, "FPS", this::applyQuickFps), new LinearLayout.LayoutParams(0, -2, 1f));
        quick.addView(quickIcon(IconView.PING, "NETWORK", () -> netBoost(true)), new LinearLayout.LayoutParams(0, -2, 1f));
        quick.addView(quickIcon(IconView.STYLE, "TOUCH", () -> touchBoost(true, true)), new LinearLayout.LayoutParams(0, -2, 1f));
        quick.addView(quickIcon(IconView.FAN, "FAN", () -> showTab(TAB_FAN)), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(quick, mt(3));

        c.addView(title("PANEL INTI"), mt(7));
        LinearLayout core = menuGroup();
        menuPage(core, IconView.CACHE, "Cache & Memory", "Bersihkan otomatis dari panel", TAB_CACHE);
        menuPage(core, IconView.FPS, "FPS Optimizer", "Apply refresh / profil performa", 1);
        menuPage(core, IconView.PING, "Network Optimize", "TCP latency, DNS TLS, dan Wi-Fi low-latency", TAB_NET);
        menuPage(core, IconView.FAN, "Fan & Performance", "Fan visual dan optimizer", TAB_FAN);
        menuPage(core, IconView.FLOAT, "Jendela Mengambang", "Buka aplikasi dalam mode freeform", TAB_APPS);
        menuPage(core, IconView.STYLE, "Sidebar & Handle", "Atur tombol dan gerakan di tepi layar", 15);
        c.addView(core, mt(3));
        smartSwitchDef(c, "review_panel", false, IconView.FPS, "MONITOR LIVE MENGAMBANG",
                "FPS / CPU / GPU / suhu • geser untuk memindahkan, ketuk untuk mode kipas");

        c.addView(note("Optimasi menerapkan pengaturan yang tersedia langsung. Jika fitur dibatasi Android, hasil ditulis di log kecil—tidak ada toggle penolakan atau layar pengaturan yang dibuka otomatis."), mt(5));
        refresh(); applyFanAccent(sp.getInt("gaming_profile", 1));
    }

    private void pageCleaner() {
        LinearLayout c = newPage();
        boolean on = CleanerService.enabled();
        c.addView(title("STATUS"), mt(0));
        c.addView(txt(on ? "IMZYY Cleaner AKTIF" : "IMZYY Cleaner belum aktif", 13, on ? OK : WARN, true), mt(2));
        c.addView(note(on ? "Force Stop dan Clear Cache per aplikasi siap dipakai." :
                AccessibilityGuard.restricted(this) ? "Terdeteksi: setelan terbatas masih memblokir. Lakukan langkah 2." :
                "Kalau di Setelan tertulis 'tidak tersedia', itu pembatasan Android 13+ untuk APK instal manual, bukan rusak."), mt(4));
        c.addView(title("LANGKAH"), mt(6));
        c.addView(note("1. Buka Aksesibilitas > IMZYY Cleaner > Aktif.\n2. Bila diblokir: Info Aplikasi > titik tiga > Izinkan setelan terbatas (menu ini muncul setelah langkah 1 dicoba sekali).\n3. Ulangi langkah 1. Panel ini ikut berubah otomatis begitu aktif.\nPermanen: izin adb sekali, lalu Cleaner dijaga otomatis (cek tiap 15 menit dan setelah reboot).\nAgar Samsung tidak mematikannya: Baterai > Tanpa batasan, matikan 'Pause app activity if unused' di Info Aplikasi, dan masukkan ke 'Aplikasi yang tidak pernah tidur' di Perawatan perangkat > Baterai."), mt(2));
        bigButton(c, IconView.TOOLS, "BUKA AKSESIBILITAS", () -> {
            sp.edit().putBoolean("pending_cleaner", true).apply(); armPending();
            if (GpuEngine.canSecure(this) && CleanerService.enableViaSecure(this)) { status("Diaktifkan lewat izin adb", OK); return; }
            try { open(new Intent("com.samsung.accessibility.installed_service")); } catch (Throwable e) { open(AccessibilityGuard.settingsIntent(this)); }
        }, true);
        bigButton(c, IconView.SHIELD, "INFO APLIKASI (SETELAN TERBATAS)", () -> open(AccessibilityGuard.appInfoIntent(this)), false);
        bigButton(c, IconView.BATTERY, "ANTI KILL (BATERAI TANPA BATASAN)", this::antiKill, false);
        bigButton(c, IconView.TOOLS, "SALIN PERINTAH ADB", this::copyAdb, false);
    }

    private void refreshOptText() {
        if (optLine != null) optLine.setText(optText());
        if (optSub != null) optSub.setText(optText());
    }

    /** Dipanggil dari MainActivity saat saklar "Layar tetap menyala" diubah supaya langsung berlaku di panel. */
    static void reapplyAwake() {
        final FloatingService f = inst;
        if (f == null) return;
        f.ui.post(f::applyAwake);
    }

    // ------------------------------------------------------------ pages
    private void pageHome() {
        LinearLayout c = newPage();
        LinearLayout oc = row(); oc.setPadding(dp(10), dp(8), dp(10), dp(8));
        oc.setBackground(bg(0xFF1D1D20, 0xFF0C0C0D, 12, 0x33FFFFFF));
        oc.addView(icon(IconView.CHECK, 14, 0xFFFFFFFF));
        optLine = txt(optText(), 9, 0xFFFFFFFF, true); optLine.setPadding(dp(8), 0, 0, 0); oc.addView(optLine);
        c.addView(oc, mt(2));
        LinearLayout r1 = row(); tRam = tile(r1, IconView.RAM, "RAM", true); tBat = tile(r1, IconView.BATTERY, "BATERAI", false); c.addView(r1, mt(2));
        LinearLayout r2 = row(); tTemp = tile(r2, IconView.THERMO, "SUHU", true); tTherm = tile(r2, IconView.FPS, "THERMAL", false); c.addView(r2, mt(4));
        bigButton(c, IconView.CACHE, "OPTIMIZE MEMORY + CACHE", this::optimizePhone, true);
        LinearLayout q = row(); q.setGravity(Gravity.CENTER);
        q.addView(quickIcon(IconView.FPS, "FPS", this::applyQuickFps), new LinearLayout.LayoutParams(0, -2, 1f));
        q.addView(quickIcon(IconView.PING, "NETWORK", () -> netBoost(true)), new LinearLayout.LayoutParams(0, -2, 1f));
        q.addView(quickIcon(IconView.STYLE, "TOUCH", () -> touchBoost(true, true)), new LinearLayout.LayoutParams(0, -2, 1f));
        q.addView(quickIcon(IconView.FAN, "FAN", () -> showTab(TAB_FAN)), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(q, mt(5));
        refresh();
    }

    private void applyQuickFps() {
        if (thermalBlocked()) return;
        int mode = sp.getInt("gaming_profile", 1);
        int target = mode == 0 ? 60 : mode == 3 ? 0 : mode == 2 ? 90 : -1;
        if (target < 0) { FpsEngine.reset(this); sp.edit().remove("fps").apply(); status("FPS Optimizer • pengaturan standar sistem", OK); }
        else { setFps(target); status("FPS Optimizer diterapkan • " + (target == 0 ? "MAX" : target + " Hz"), OK); }
        addLog("FPS OPTIMIZER • profil " + new String[]{"Battery", "Standart", "Balanced", "ImzzBoost"}[mode]);
    }

    // ------------------------------------------------------------ FAN: mesin + optimizer
    private volatile boolean engReading;
    private long lastFanOptAt, lastFanCacheAt;

    private float fanVolume() { return new float[]{.30f, .55f, .85f}[Math.max(0, Math.min(2, sp.getInt("fan_vol", 1)))]; }

    private void startFanEngine() {
        FanSound.startEngine(fanVolume());
        ui.removeCallbacks(engineTick);
        ui.post(engineTick);
    }

    private void stopFanEngine() { FanSound.stopEngine(); }

    /** Jalan selama suara mesin atau Fan Optimizer aktif, juga saat panel ditutup (service tetap hidup). */
    private final Runnable engineTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!FanSound.engineOn() && !sp.getBoolean("fan_opt", false)) return;
            if (!engReading) {
                engReading = true;
                try {
                    statsExec.execute(() -> {
                        SysStats.Snap snap = null;
                        try { snap = SysStats.read(FloatingService.this); } catch (Throwable ignored) { }
                        final SysStats.Snap fs = snap;
                        engReading = false;
                        ui.post(() -> onFanSnap(fs));
                    });
                } catch (Throwable e) { engReading = false; }
            }
            ui.postDelayed(this, 600);
        }
    };

    private void onFanSnap(SysStats.Snap s) {
        if (s == null || !running) return;
        float load = Math.max(s.cpuLoad, s.gpuLoad);
        if (FanSound.engineOn()) FanSound.setEngineLoad(load);
        if (panel != null) applyFan(s);
        if (sp.getBoolean("fan_opt", false)) fanOptimizeCycle(s);
    }

    /** Fan Optimizer baru dinyalakan: optimasi langsung sekali, lalu dijaga otomatis oleh engineTick. */
    private void fanOptKick() {
        int scope = sp.getInt("fan_scope", 0);
        addLog("FAN OPTIMIZER aktif • " + (scope == 0 ? "fokus game" : "semua aplikasi"));
        lastFanOptAt = SystemClock.elapsedRealtime();
        if (scope == 0) optimizeGame(); else optimizePhone();
        ui.removeCallbacks(engineTick);
        ui.post(engineTick);
    }

    /** Beban tinggi atau RAM menipis: trim RAM otomatis (maks sekali per 2,5 menit). Mode SEMUA APP juga bersihkan cache tiap 10 menit. */
    private void fanOptimizeCycle(SysStats.Snap s) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFanOptAt < 150_000L) return;
        if (optimizing || reclaiming) return;
        ActivityManager.MemoryInfo m = mem();
        float free = m.totalMem > 0 ? m.availMem / (float) m.totalMem : 1f;
        boolean hot = Math.max(s.cpuLoad, s.gpuLoad) > 0.72f;
        if (!hot && free > 0.18f) return;
        lastFanOptAt = now;
        int scope = sp.getInt("fan_scope", 0);
        addLog("FAN: " + (hot ? "beban tinggi" : "RAM menipis") + " • " + (scope == 0 ? "trim RAM untuk game" : "bersihkan semua aplikasi"));
        boost(1);
        if (scope == 1 && now - lastFanCacheAt > 600_000L && hasUsageAccess()) { lastFanCacheAt = now; clearAllCache(); }
    }

    // ------------------------------------------------------------ FAN: CPU & GPU live (header)
    /** Membaca CPU/GPU berkala selama panel terbuka, di semua halaman, supaya kipas header selalu hidup. */
    private final Runnable hdrTick = new Runnable() {
        @Override public void run() {
            if (!running || panel == null || hdrCpu == null) return;
            if (!fanReading) {
                fanReading = true;
                try {
                    statsExec.execute(() -> {
                        SysStats.Snap snap = null;
                        try {
                            if (gpuRenderer.isEmpty()) gpuRenderer = GpuEngine.info()[0];
                            snap = SysStats.read(FloatingService.this);
                        } catch (Throwable ignored) { }
                        final SysStats.Snap fs = snap;
                        fanReading = false;
                        ui.post(() -> applyFan(fs));
                    });
                } catch (Throwable e) { fanReading = false; }
            }
            ui.postDelayed(this, 700);
        }
    };

    private boolean landscape() { return sw() > sh(); }

    /** Ukuran kipas header: penuh saat tegak, diperkecil seimbang saat layar miring supaya panel dan menu tetap lapang. */
    private int fanDp(int base) { return landscape() ? Math.max(30, Math.round(base * .6f)) : base; }

    /** Dipanggil saat layar berputar: kipas dan strip header disesuaikan tanpa membangun ulang panel. */
    private void applyFanSizes() {
        try {
            FanView[] f = {hdrCpu, miniFan, hdrGpu};
            int[] base = {52, 66, 52};
            for (int i = 0; i < f.length; i++) {
                if (f[i] == null) continue;
                android.view.ViewGroup.LayoutParams p = f[i].getLayoutParams();
                if (p == null) continue;
                p.width = dp(fanDp(base[i])); p.height = dp(fanDp(base[i]));
                f[i].setLayoutParams(p);
            }
            if (fpsStripBox != null) {
                boolean l = landscape();
                fpsStripBox.setPadding(dp(8), dp(l ? 3 : 6), dp(8), dp(l ? 3 : 6));
            }
            if (status != null) status.setMaxLines(landscape() ? 2 : 3);
        } catch (Throwable ignored) { }
    }

    /** Satu kolom header: kipas berukuran tetap, teks utama, dan teks kecil (boleh null) di bawahnya. */
    private LinearLayout headFan(FanView fan, int sizeDp, TextView t, TextView sub) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(fan, new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        t.setGravity(Gravity.CENTER); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null) {
            sub.setGravity(Gravity.CENTER); sub.setSingleLine(true); sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        return col;
    }

    private void pageFan() {
        LinearLayout c = newPage();
        c.addView(note("Fan visual CPU / FPS / GPU selalu beranimasi. Warna mengikuti profil gaming, kecepatan mengikuti beban. Fan fisik hanya dilaporkan jika sensor tersedia."), mt(0));
        c.addView(note("Floating window guard tetap aktif di background. Panel hanya menampilkan fan visual dan optimizer inti."), mt(3));
        int n = Math.min(16, Runtime.getRuntime().availableProcessors());
        LinearLayout cb = row(); cb.setGravity(Gravity.BOTTOM); cb.setPadding(dp(8), dp(6), dp(8), dp(6));
        cb.setBackground(bg(0xFF141416, 0xFF0A0A0B, 10, 0x1FFFFFFF)); coreFill = new View[n];
        for (int i=0;i<n;i++) { LinearLayout cell = new LinearLayout(this); cell.setGravity(Gravity.BOTTOM); View f = new View(this); f.setBackground(bg(CY, PU, 2, 0)); coreFill[i]=f; cell.addView(f,new LinearLayout.LayoutParams(-1,dp(2))); LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(0,dp(26),1); if(i>0)cl.leftMargin=dp(3); cb.addView(cell,cl); }
        c.addView(title("INTI CPU"), mt(5)); c.addView(cb, mt(2));
        c.addView(title("OPTIMIZER"), mt(5));
        c.addView(note("AUTO MEMORY OPTIMIZER • aktif otomatis. Hanya trim RAM saat beban tinggi / RAM menipis; cache tidak dibersihkan otomatis berulang agar tidak mengganggu game."), mt(3));
        sp.edit().putInt("fan_scope", 0).putBoolean("fan_opt", true).apply();
        LinearLayout actions=row(); ibtn(actions,IconView.BOLT,"OPTIMIZE NOW",true,this::optimizePhone); c.addView(actions,mt(4));
        ui.removeCallbacks(hdrTick); ui.post(hdrTick);
    }

    private void applyFan(SysStats.Snap s) {
        if (s == null || hdrCpu == null || hdrGpu == null || hdrCpuTxt == null) return;
        int cp = Math.round(s.cpuLoad * 100), gp = Math.round(s.gpuLoad * 100);
        hdrCpu.setLoad(s.cpuLoad, !s.cpuUsageReal);
        hdrGpu.setLoad(s.gpuLoad, !s.gpuUsageReal);
        if (miniFan != null) miniFan.setLoad(Math.max(s.cpuLoad, s.gpuLoad), !(s.cpuUsageReal && s.gpuUsageReal));
        hdrCpuTxt.setText((s.cpuUsageReal ? "" : "~") + cp + "%");
        hdrCpuSub.setText("CPU" + (s.cpuCurMhz > 0 ? "  " + SysStats.ghz(s.cpuCurMhz) : "  LOAD"));
        hdrGpuTxt.setText((s.gpuUsageReal ? "" : "~") + gp + "%");
        hdrGpuSub.setText("GPU" + (s.gpuCurMhz > 0 ? "  " + SysStats.ghz(s.gpuCurMhz) : "  LOAD"));
        if (coreFill != null) {
            for (int i = 0; i < coreFill.length && i < 16; i++) {
                float r = (s.curKhz[i] > 0 && s.maxKhz[i] > 0) ? s.curKhz[i] / (float) s.maxKhz[i] : (s.cpuReal ? 0f : s.cpuLoad);
                android.view.ViewGroup.LayoutParams lp = coreFill[i].getLayoutParams();
                lp.height = dp(2) + Math.round(dp(22) * Math.min(1f, r));
                coreFill[i].setLayoutParams(lp);
            }
        }
    }

    private void pageFps() {
        LinearLayout c = newPage();
        c.addView(title("FPS OPTIMIZER"), mt(0));
        c.addView(note("Apply langsung ke refresh rate yang tersedia. Bila izin atau firmware membatasi, panel dan game tetap berjalan dengan mode best-effort."), mt(2));
        LinearLayout r=row();
        ibtn(r,IconView.FPS,"60 Hz",true,()->setFps(60));
        ibtn(r,IconView.FPS,"90 Hz",false,()->setFps(90));
        ibtn(r,IconView.BOLT,"MAX",false,()->setFps(0));
        ibtn(r,IconView.BACK,"AUTO",false,()->{ FpsEngine.reset(this); sp.edit().remove("fps").apply(); paintFps(); status("FPS kembali otomatis",OK); });
        c.addView(r,mt(3));
        c.addView(title("APPLY PROFIL"),mt(5));
        bigButton(c,IconView.BOLT,"APPLY FPS SESUAI PROFIL GAMING",this::applyQuickFps,true);
        btn1(c,"BREVENT IMZZ • SALIN COMMAND ELEVATED",this::copyBreventBoost);
        c.addView(note(FpsEngine.elevatedAccessHint(this)),mt(5));
        paintFps();
    }

    private void copyBreventBoost() {
        int profile = sp.getInt("gaming_profile", 1);
        int target = profile == 0 ? 60 : profile == 2 ? 90 : profile == 3 ? 0 : Math.round(FpsEngine.current(this));
        String commands = FpsEngine.breventCommands(this, target > 0 ? FpsEngine.pick(this, target) : 0);
        ClipboardManager cm = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Imzz Brevent Boost", commands));
        status("Command elevated disalin • jalankan sekali di Brevent untuk izin ekstra", WARN);
        addLog("BREVENT IMZZ • command disalin (belum dieksekusi)");
    }

    private void openPkg(String pkg, String name) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { status(name + " tidak terpasang di HP ini", WARN); return; }
        open(i);
    }

    private void applyGpuPreset() {
        int p = sp.getInt("gpu_profile", 0);
        if (p == 0) {
            FpsEngine.reset(this);
            GpuEngine.setAnim(this, 1f);
            sp.edit().remove("fps").apply();
            addLog("GPU AUTO: kembali ke bawaan sistem");
            status("GPU AUTO: bawaan sistem dipulihkan", OK);
            return;
        }
        float anim = p == 1 ? .5f : p == 2 ? .75f : 1f;
        boolean animOk = GpuEngine.setAnim(this, anim);
        setFps(p == 1 ? 0 : p == 2 ? 90 : 60);
        addLog("GPU " + GPU_NAMES[p] + (animOk ? " • animasi " + anim + "x" : " • animasi butuh izin adb"));
    }

    private void optimizeGame() {
        if (thermalBlocked()) return;
        sp.edit().putInt("gpu_profile", 1).apply();
        addLog("Optimasi game: GPU PERFORMA + boost RAM + network");
        applyGpuPreset();
        boost(0);
        netBoost(false);
    }

    // ------------------------------------------------------------ NETWORK GAME
    private void pageNet() {
        LinearLayout c = newPage();
        c.addView(title("NETWORK OPTIMIZER"), mt(0));
        netLine = txt("READY", 15, 0xFFFFFFFF, true);
        netSub = txt("Wi-Fi low-latency + uji TLS DNS + latency TCP", 8, MUTE, false);
        c.addView(netLine, mt(2)); c.addView(netSub, mt(1));
        String last = sp.getString("net_last", "");
        if (!last.isEmpty()) { String[] parts=last.split("\\|",2); netLine.setText(parts[0]); if(parts.length>1)netSub.setText(parts[1]); }
        bigButton(c,IconView.PING,"OPTIMIZE NETWORK • APPLY",()->netBoost(true),true);
        c.addView(note("Pilih DNS dari hasil TLS handshake DNS-over-TLS dan aktifkan Wi-Fi low-latency bila tersedia. Angka latency adalah TCP connect ke endpoint publik, bukan ping internal server game."),mt(4));
    }

    private void showQuality(NetEngine.Quality q) {
        String head = q.avg >= 0 ? q.rating + " • " + q.avg + " ms" : q.rating;
        StringBuilder sb = new StringBuilder();
        if (q.avg >= 0) sb.append("Probe TCP terbaik ").append(q.best).append(" ms • jitter ").append(q.jitter).append(" ms • timeout ").append(q.loss).append("%\n");
        sb.append(q.link);
        if (!q.tip.isEmpty()) sb.append("\n").append(q.tip);
        sp.edit().putString("net_last", head + "|" + sb).apply();
        if (netLine != null) netLine.setText(head);
        if (netSub != null) netSub.setText(sb.toString());
    }

    private void netTest() {
        if (netBusy) { status("Sedang menguji…", WARN); return; }
        netBusy = true;
        status("Menguji latency jaringan…", WARN);
        new Thread(() -> {
            final NetEngine.Quality q = NetEngine.measure(this);
            ui.post(() -> {
                netBusy = false;
                showQuality(q);
                String m = q.avg >= 0 ? "Jaringan " + q.rating + " • TCP " + q.avg + " ms • timeout " + q.loss + "%" : "Tidak ada internet";
                addLog(m);
                status(m, q.avg >= 0 ? OK : BAD);
            });
        }).start();
    }

    /** Optimasi network game: Wi-Fi low-latency + DNS pribadi tercepat (bila ada izin adb) + uji ulang. */
    private void netBoost(final boolean announce) {
        if (netBusy) { if (announce) status("Sedang berjalan…", WARN); return; }
        netBusy = true;
        boolean lock = false;
        try { lock = NetEngine.lockOn(this); } catch (IllegalArgumentException ignored) { } catch (Throwable ignored) { }
        final boolean held = lock;
        sp.edit().putBoolean("net_boost", held).apply();
        if (announce) status("Mengoptimalkan network game…", WARN);
        new Thread(() -> {
            String dns;
            try {
                if (NetEngine.canSecure(this)) {
                    int[] best = NetEngine.fastestDns();
                    if (best != null && NetEngine.applyDns(this, NetEngine.DNS_HOST[best[0]])) dns = "DNS " + NetEngine.DNS_NAME[best[0]] + " " + best[1] + " ms";
                    else dns = "DNS tidak diubah";
                } else dns = "DNS: izin elevated tidak tersedia";
            } catch (IllegalArgumentException e) { dns = "DNS tidak didukung firmware"; }
            catch (Throwable e) { dns = "DNS best-effort gagal"; }
            final String dnsMsg = dns;
            final NetEngine.Quality q = NetEngine.measure(this);
            ui.post(() -> {
                netBusy = false;
                String msg = (held ? "Wi-Fi low-latency ON" : "Wi-Fi lock tidak aktif") + " • " + dnsMsg + " • " + q.rating + (q.avg >= 0 ? " " + q.avg + " ms" : "");
                addLog(msg);
                if (announce) status(msg, q.avg >= 0 ? OK : WARN);
                showQuality(q);
                if (curTab == TAB_NET) showTab(TAB_NET);
            });
        }).start();
    }

    private void netDns() {
        if (!NetEngine.canSecure(this)) { copyAdb(); status("DNS butuh izin adb sekali • perintah disalin, atau atur manual: DNS Pribadi", WARN); return; }
        if (netBusy) { status("Sedang berjalan…", WARN); return; }
        netBusy = true;
        status("Mencari DNS tercepat…", WARN);
        new Thread(() -> {
            final int[] best = NetEngine.fastestDns();
            final boolean ok = best != null && NetEngine.applyDns(this, NetEngine.DNS_HOST[best[0]]);
            ui.post(() -> {
                netBusy = false;
                String m = ok ? "DNS pribadi: " + NetEngine.DNS_NAME[best[0]] + " (" + best[1] + " ms)" : "Semua DNS gagal dijangkau / izin ditolak";
                addLog(m);
                status(m, ok ? OK : WARN);
                if (curTab == TAB_NET) showTab(TAB_NET);
            });
        }).start();
    }

    private void netDnsRestore() {
        boolean ok = NetEngine.restoreDns(this);
        String m = ok ? "DNS pribadi dipulihkan ke pengaturan semula" : "Tidak ada perubahan DNS dari IMZYY untuk dipulihkan";
        addLog(m);
        status(m, ok ? OK : MUTE);
        if (curTab == TAB_NET) showTab(TAB_NET);
    }

    private void pageInfo() {
        LinearLayout c = newPage();
        IconView logo = new IconView(this).set(IconView.LOGO, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams lg = new LinearLayout.LayoutParams(dp(54), dp(54)); lg.gravity = Gravity.CENTER_HORIZONTAL; lg.topMargin = dp(8);
        c.addView(logo, lg);
        TextView n = txt("GAME TURBO", 18, 0xFFFFFFFF, true); n.setGravity(Gravity.CENTER); n.setLetterSpacing(.14f);
        c.addView(n, mt(6));
        TextView by = txt("BY IMZYYKNOX", 11, CY, true); by.setGravity(Gravity.CENTER); by.setLetterSpacing(.22f);
        c.addView(by, mt(2));
        TextView ver = txt("Galaxy Performance Suite • v9.1", 8, MUTE, false); ver.setGravity(Gravity.CENTER);
        c.addView(ver, mt(4));
        c.addView(title("CREDIT"), mt(10));
        c.addView(txt("Game Turbo by ImzyyKnox\nDesain, kode & konsep: ImzyyKnox", 10, 0xFFC9C9CF, false), mt(0));
        c.addView(title("VERSI ANDROID TERDETEKSI"), mt(6));
        c.addView(note(AndroidInfo.summary() + "\nSemua fitur memakai kemampuan nyata versi ini (Android 8 sampai 17 ke atas), bukan syarat satu versi tertentu."), mt(0));
        c.addView(title("CATATAN"), mt(6));
        c.addView(note("• Posisi floating tersimpan: bubble dan panel saling mengikuti, tidak balik ke tempat lama.\n• Refresh rate layar: nyata, via izin Ubah setelan sistem.\n• Optimalkan Ponsel, Touch boost, FPS, GPU, Voice: jalan langsung dari floating window.\n• Touch boost berlapis: key Touch sensitivity Samsung (bila ada), layar dikunci refresh maksimum, sentuh-tahan cepat dan animasi ringan (dua terakhir butuh izin adb sekali).\n• Clear cache & Force Stop per aplikasi: lewat IMZYY Cleaner (Aksesibilitas), progres tampil di LOG.\n• Jendela mengambang antar aplikasi: perlu opsi jendela bentuk bebas di Opsi Pengembang (sekali saja).\n• Daftar app aktif memakai Usage Access (aplikasi yang dipakai ≤ 2 jam terakhir).\n• Aplikasi tidak bisa mengubah renderer atau FPS internal game tanpa root."), mt(0));
    }

    // ------------------------------------------------------------ apps / cache pages
    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) { return false; }
    }

    private String sizeText(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.US, "%.0f KB", b / 1024d);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024d * 1024));
        return String.format(Locale.US, "%.2f GB", b / (1024d * 1024 * 1024));
    }

    private ArrayList<String> getFavs() {
        ArrayList<String> out = new ArrayList<>();
        String raw = sp.getString("float_pkgs", "");
        PackageManager pm = getPackageManager();
        for (String x : raw.split(",")) {
            if (x.isEmpty() || out.contains(x)) continue;
            if (pm.getLaunchIntentForPackage(x) != null) out.add(x);
        }
        return out;
    }

    private void setFavs(List<String> l) { sp.edit().putString("float_pkgs", TextUtils.join(",", l)).apply(); }

    private String labelOf(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (Exception e) { return pkg; }
    }

    private void pageApps(final boolean runningMode) {
        if (!runningMode) {
            LinearLayout cachePage = newPage();
            cachePage.addView(title("MEMORY + CACHE"), mt(0));
            bigButton(cachePage, IconView.CACHE, "OPTIMIZE MEMORY + CACHE", this::optimizePhone, true);
            bigButton(cachePage, IconView.CACHE, "CLEAR CACHE OTOMATIS", this::clearAllCache, false);
            cachePage.addView(note("Aksi dilakukan dari floating window. Tidak membuka Settings atau halaman aplikasi. Android menentukan cache lain yang aman dilepas; cache privat aplikasi tidak dapat dipaksa oleh aplikasi biasa."), mt(4));
            cachePage.addView(title("LOG AKTIVITAS"), mt(6));
            logBox = new LinearLayout(this); logBox.setOrientation(LinearLayout.VERTICAL); cachePage.addView(logBox, mt(2)); renderLog();
            return;
        }
        LinearLayout c = newPage();
        if (CleanerService.enabled()) {
            c.addView(txt("IMZYY Cleaner aktif • CLEAR/STOP otomatis", 8, OK, true), mt(0));
        } else {
            TextView warn = txt("Aktifkan IMZYY Cleaner (Aksesibilitas) agar CLEAR/STOP berjalan otomatis. Ketuk di sini.", 8, TXT, true);
            warn.setPadding(dp(8), dp(6), dp(8), dp(6));
            warn.setBackground(bg(0xFF2A2A2D, 0xFF1A1A1C, 10, 0x66FFFFFF));
            warn.setOnClickListener(v -> enableCleaner(true));
            c.addView(warn, mt(2));
        }
        if (runningMode) {
            c.addView(title("JENDELA APLIKASI • UKURAN"), mt(2));
            chips(c, "float_size", new String[]{"SEDANG", "KECIL", "BESAR"});
            c.addView(note("Mode freeform bergantung dukungan Android/One UI perangkat. Jika aplikasi tetap fullscreen, aktifkan opsi jendela bentuk bebas atau paksa aktivitas dapat diubah ukurannya di Opsi Pengembang bila tersedia."), mt(3));
            c.addView(title("APLIKASI PILIHANMU"), mt(2));
            ArrayList<String> favs = getFavs();
            if (favs.isEmpty()) c.addView(note("Belum ada aplikasi. Tap TAMBAH untuk memilih aplikasi yang mau dibuka di jendela mengambang."), mt(2));
            int idx = 0;
            for (final String pkg : favs) {
                final String label = labelOf(pkg); final int at = idx++;
                LinearLayout item = row();
                item.setPadding(dp(8), dp(6), dp(5), dp(6));
                item.setBackground(bg(0xFF1A1A1D, 0xFF0E0E10, 10, 0x22FFFFFF));
                TextView nm = txt(label, 10, 0xFFFFFFFF, true); nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END);
                item.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
                item.addView(iconBtn(IconView.FLOAT, 0xFFFFFFFF, () -> launchFloating(pkg, label, at, true)));
                item.addView(iconBtn(IconView.CLOSE, 0xFFFF8080, () -> {
                    ArrayList<String> l = getFavs(); l.remove(pkg); setFavs(l); showTab(TAB_APPS); addLog("Dihapus dari jendela: " + label);
                }));
                c.addView(item, mt(4));
            }
            LinearLayout fa = row();
            ibtn(fa, IconView.PLUS, "TAMBAH", true, this::pageFloatPicker);
            ibtn(fa, IconView.FLOAT, "BUKA SEMUA", false, this::floatAll);
            c.addView(fa, mt(6));
            c.addView(title("AKTIF ≤ 2 JAM • FLOAT / STOP / CLEAR"), mt(6));
        } else {
            c.addView(title("MEMORY + CACHE"), mt(2));
            bigButton(c, IconView.CACHE, "OPTIMIZE MEMORY + CACHE", this::optimizePhone, true);
            bigButton(c, IconView.CACHE, "CLEAR CACHE OTOMATIS", this::clearAllCache, false);
            c.addView(note("Semua aksi dipicu dari floating panel. Sistem Android yang menentukan cache aplikasi lain yang aman dilepas; tidak ada perpindahan ke Settings atau konfirmasi manual."), mt(4));
        }
        LinearLayout acts = row();
        ibtn(acts, IconView.PLAY, "REFRESH", true, this::reloadApps);
        if (runningMode) ibtn(acts, IconView.STOP, "STOP SEMUA", false, this::stopAll);

        ibtn(acts, IconView.CLOSE, "BATAL", false, () -> CleanerService.cancel());
        c.addView(acts, mt(6));
        c.addView(title("LOG"), mt(6));
        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(logBox, mt(2));
        renderLog();
        appSummary = txt("Memuat…", 8, CY, true);
        c.addView(appSummary, mt(8));
        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        c.addView(appList, mt(2));
        lastRows.clear();
        reloadApps();
    }

    private void addLog(String m) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        LOG.add(t + "  " + m);
        while (LOG.size() > 80) LOG.remove(0);
        renderLog();
    }

    private void renderLog() {
        if (logBox == null) return;
        logBox.removeAllViews();
        logBox.setPadding(dp(8), dp(6), dp(8), dp(6));
        logBox.setBackground(bg(0xFF0E0E10, 0xFF070708, 10, 0x22FFFFFF));
        if (LOG.isEmpty()) { logBox.addView(note("Log optimasi memory, cache, FPS, network, dan touch muncul di sini.")); return; }
        int from = Math.max(0, LOG.size() - 7);
        for (int i = LOG.size() - 1; i >= from; i--) {
            TextView t = txt(LOG.get(i), 8, i == LOG.size() - 1 ? TXT : MUTE, false);
            t.setTypeface(Typeface.MONOSPACE); t.setSingleLine(true); t.setEllipsize(TextUtils.TruncateAt.END);
            logBox.addView(t);
        }
    }

    /** Pilih aplikasi apa saja yang mau dipakai di jendela mengambang (tap untuk tambah / hapus). */
    private void pageFloatPicker() {
        if (content == null) return;
        stopLive();
        content.removeAllViews();
        LinearLayout c = newPage();
        LinearLayout head = row();
        LinearLayout back = row(); back.setPadding(0, dp(4), dp(10), dp(4));
        back.addView(icon(IconView.BACK, 13, CY));
        TextView bt = txt("SELESAI", 9, CY, true); bt.setPadding(dp(3), 0, 0, 0); back.addView(bt);
        back.setOnClickListener(v -> showTab(TAB_APPS));
        head.addView(back); head.addView(txt("TAMBAH APLIKASI JENDELA", 10, 0xFFFFFFFF, true));
        c.addView(head, mt(0));
        final PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collections.sort(apps, (x, y) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(x.loadLabel(pm)), String.valueOf(y.loadLabel(pm))));
        final ArrayList<String> favs = getFavs();
        HashSet<String> seen = new HashSet<>();
        for (ResolveInfo ri : apps) {
            final String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            final String label = String.valueOf(ri.loadLabel(pm));
            LinearLayout item = row();
            item.setPadding(dp(10), dp(9), dp(10), dp(9));
            item.setBackground(bg(0xFF1A1A1D, 0xFF0E0E10, 10, 0x22FFFFFF));
            TextView nm = txt(label, 10, 0xFFFFFFFF, true); nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END);
            item.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
            final IconView mark = new IconView(this).set(favs.contains(pkg) ? IconView.CHECK : IconView.PLUS, favs.contains(pkg) ? 0xFFFFFFFF : MUTE, favs.contains(pkg));
            item.addView(mark, new LinearLayout.LayoutParams(dp(16), dp(16)));
            item.setOnClickListener(v -> {
                if (favs.contains(pkg)) { favs.remove(pkg); mark.set(IconView.PLUS, MUTE, false); status("Dihapus: " + label, MUTE); }
                else { favs.add(pkg); mark.set(IconView.CHECK, 0xFFFFFFFF, true); status("Ditambahkan: " + label, OK); }
                setFavs(favs);
            });
            c.addView(item, mt(4));
        }
    }

    // ------------------------------------------------------------ voice changer di dalam floating window
    private int voiceFx() { return sp.getInt("voice_fx", 1); }

    private String voiceFxName() { int f = voiceFx(); return f >= 0 && f < VoiceEngine.FX_NAME.length ? VoiceEngine.FX_NAME[f] : "PITCH " + String.format(Locale.US, "%.2fx", customPitch); }

    private void paintFx() { paintChips(fxChips, voiceFx()); }

    private void pickFx(int idx) {
        sp.edit().putInt("voice_fx", idx).apply();
        paintFx();
        if (voice.isLive()) { voice.setLive(idx, customPitch); status("LIVE • efek " + voiceFxName(), OK); }
        else status("Efek " + voiceFxName() + " dipilih", MUTE);
    }

    private void pageVoice() {
        LinearLayout c = newPage();
        c.addView(title("VOICE CHANGER LIVE"), mt(0));
        LinearLayout l1 = row();
        ibtn(l1, IconView.MIC, "LIVE ON", true, this::requestLive);
        ibtn(l1, IconView.STOP, "LIVE OFF", false, this::stopLiveVoice);
        c.addView(l1, mt(2));
        c.addView(title("EFEK SUARA"), mt(4));
        fxChips.clear();
        for (int rIdx = 0; rIdx < (VoiceEngine.FX_NAME.length + 3) / 4; rIdx++) {
            LinearLayout line = row();
            for (int k = 0; k < 4; k++) {
                final int idx = rIdx * 4 + k;
                if (idx >= VoiceEngine.FX_NAME.length) { line.addView(new View(this), w1(k == 0)); continue; }
                TextView t = txt(VoiceEngine.FX_NAME[idx], 7, TXT, true);
                t.setGravity(Gravity.CENTER); t.setPadding(0, dp(9), 0, dp(9)); t.setMaxLines(1);
                t.setOnClickListener(v -> pickFx(idx));
                fxChips.add(t); line.addView(t, w1(k == 0));
            }
            c.addView(line, mt(rIdx == 0 ? 2 : 4));
        }
        paintFx();
        c.addView(title("AUDIO EKSTERNAL • MIC & KELUARAN"), mt(4));
        LinearLayout dv = row();
        ibtn(dv, IconView.MIC, "MIC: " + deviceName(true), true, () -> cycleDevice(true));
        ibtn(dv, IconView.PLAY, "OUT: " + deviceName(false), false, () -> cycleDevice(false));
        c.addView(dv, mt(2));
        smartSwitchDef(c, "voice_gate", true, IconView.SHIELD, "NOISE GATE", "Mic yang diam tidak membawa desis ke game. Mati bila suara pelan terpotong.");
        c.addView(title("PITCH BEBAS"), mt(4));
        final TextView readout = txt(String.format(Locale.US, "%.2fx", customPitch), 16, CY, true);
        c.addView(readout, mt(0));
        SeekBar sb = new SeekBar(this);
        sb.setMax(120); sb.setProgress(Math.round((customPitch - 0.6f) * 100));
        sb.setProgressTintList(ColorStateList.valueOf(CY));
        sb.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                customPitch = 0.6f + p / 100f;
                readout.setText(String.format(Locale.US, "%.2fx", customPitch));
                if (!user) return;
                sp.edit().putInt("voice_fx", -1).apply();
                paintFx();
                if (voice.isLive()) voice.setLive(-1, customPitch);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        c.addView(sb, mt(2));
        c.addView(title("VOLUME KELUARAN"), mt(4));
        SeekBar vol = new SeekBar(this);
        vol.setMax(100); vol.setProgress(Math.round((sp.getFloat("voice_gain", 1f) - 0.2f) / 2.3f * 100));
        vol.setProgressTintList(ColorStateList.valueOf(CY));
        vol.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                float g = 0.2f + p / 100f * 2.3f;
                sp.edit().putFloat("voice_gain", g).apply();
                voice.setGain(g);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        c.addView(vol, mt(2));
        voice.setGain(sp.getFloat("voice_gain", 1f));
        c.addView(title("REKAM & PUTAR ULANG"), mt(4));
        LinearLayout r1 = row();
        ibtn(r1, IconView.MIC, "REC", true, this::requestRec);
        ibtn(r1, IconView.STOP, "STOP REC", false, () -> {
            voice.stop();
            status(voice.hasAudio() ? "Rekaman siap • " + voice.seconds() + " detik" : "Belum ada rekaman", voice.hasAudio() ? OK : WARN);
        });
        c.addView(r1, mt(2));
        LinearLayout r4 = row();
        ibtn(r4, IconView.PLAY, "PUTAR EFEK", true, this::playVoice);
        ibtn(r4, IconView.STOP, "STOP PUTAR", false, () -> { voice.stopPlay(); status("Playback dihentikan", MUTE); });
        c.addView(r4, mt(5));
        c.addView(note("LIVE: suaramu diubah real-time (jeda sekitar 30 ms). MIC memilih sumber (mic HP, headset kabel, USB, Bluetooth), OUT memilih tujuan (speaker, headset, USB, Bluetooth); ketuk untuk ganti, langsung berlaku saat LIVE. Pakai earphone supaya tidak berdengung. Jujur: Android tidak mengizinkan aplikasi biasa menggantikan mikrofon game atau panggilan telepon, jadi suara hasil efek keluar ke perangkat OUT, bukan masuk langsung ke mic game. Itu butuh root atau perangkat audio eksternal yang menyambungkan keluaran ke masukan."), mt(6));
        if (voice.isLive()) status("LIVE aktif • efek " + voiceFxName(), OK);
    }

    private void launchMicPermission(int purpose) {
        micFor = purpose;
        Intent i = new Intent(this, MicPermissionActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { startActivity(i); } catch (Exception e) { status("Buka izin mikrofon dari IZIN APP", WARN); }
    }

    private void requestLive() {
        if (!hasMic()) {
            if (Perm.first(this, "mic")) { status("Izinkan mikrofon (sekali saja)…", WARN); launchMicPermission(2); }
            else status("Izin mikrofon belum aktif • aktifkan di TOOLS > IZIN", WARN);
            return;
        }
        startLive();
    }

    private void startLive() {
        if (!foreground(true)) {
            status("LIVE tidak bisa dimulai dari kondisi background • buka Game Turbo lalu coba lagi", WARN);
            return;
        }
        voice.configure(this, sp.getInt("voice_in", 0), sp.getInt("voice_out", 0), sp.getBoolean("voice_gate", true));
        String err = voice.startLive(voiceFx(), customPitch);
        if (err != null) { status(err, BAD); return; }
        if (voice.routeNote != null) { status(voice.routeNote, WARN); addLog("Voice: " + voice.routeNote); }
        boolean hs = VoiceEngine.hasHeadset(this);
        status(hs ? "LIVE • efek " + voiceFxName() + " • keluar di earphone" : "LIVE • efek " + voiceFxName() + " • tanpa earphone bisa berdengung", hs ? OK : WARN);
        addLog("Voice LIVE: " + voiceFxName());
    }

    /** Nama perangkat terpilih untuk tombol MIC / OUT. Perangkat yang tercabut kembali ke OTOMATIS. */
    private String deviceName(boolean input) {
        int id = sp.getInt(input ? "voice_in" : "voice_out", 0);
        android.media.AudioDeviceInfo d = VoiceEngine.find(this, input, id);
        if (d == null) return "AUTO";
        String l = VoiceEngine.label(d);
        return l.length() > 14 ? l.substring(0, 14) : l;
    }

    /** Ketuk = pindah ke perangkat berikutnya (AUTO, lalu tiap mic/keluaran yang terpasang). Langsung berlaku saat LIVE. */
    private void cycleDevice(boolean input) {
        List<android.media.AudioDeviceInfo> ds = VoiceEngine.devices(this, input);
        String key = input ? "voice_in" : "voice_out";
        int cur = sp.getInt(key, 0), next = 0;
        if (cur == 0) { if (!ds.isEmpty()) next = ds.get(0).getId(); }
        else {
            for (int i = 0; i < ds.size(); i++) if (ds.get(i).getId() == cur) { next = i + 1 < ds.size() ? ds.get(i + 1).getId() : 0; break; }
        }
        sp.edit().putInt(key, next).apply();
        voice.configure(this, sp.getInt("voice_in", 0), sp.getInt("voice_out", 0), sp.getBoolean("voice_gate", true));
        String note = voice.isLive() ? voice.applyRoutes() : null;
        showTab(6);
        status((input ? "Mic: " : "Keluaran: ") + deviceName(input) + (ds.isEmpty() ? " • tidak ada perangkat eksternal terpasang" : "") + (note != null ? " • " + note : ""), note != null ? WARN : OK);
    }

    private void stopLiveVoice() {
        if (!voice.isLive()) { status("LIVE tidak aktif", MUTE); return; }
        voice.stopLive();
        status("LIVE dihentikan", MUTE);
        addLog("Voice LIVE berhenti");
    }

    private void requestRec() {
        if (!hasMic()) {
            if (Perm.first(this, "mic")) { status("Izinkan mikrofon (sekali saja)…", WARN); launchMicPermission(1); }
            else status("Izin mikrofon belum aktif • aktifkan di TOOLS > IZIN", WARN);
            return;
        }
        startRec();
    }

    private void startRec() {
        if (!foreground(true)) {
            status("Rekam tidak bisa dimulai dari kondisi background • buka Game Turbo lalu coba lagi", WARN);
            return;
        }
        String err = voice.start();
        if (err != null) { status(err, BAD); return; }
        ui.removeCallbacks(recTick);
        ui.post(recTick);
    }

    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (!voice.isRecording()) return;
            status("Merekam… " + voice.seconds() + " dtk • tap STOP REC", WARN);
            ui.postDelayed(this, 500);
        }
    };

    private void playVoice() {
        String err = voice.play(voiceFx(), customPitch);
        if (err != null) status(err, WARN); else status("Memutar efek " + voiceFxName(), OK);
    }

    private void reloadApps() {
        if (appList == null || loadingApps) return;
        if (!hasUsageAccess()) {
            appList.removeAllViews();
            if (appSummary != null) appSummary.setText("Usage Access belum aktif");
            LinearLayout b = row(); b.setGravity(Gravity.CENTER); b.setPadding(0, dp(12), 0, dp(12)); b.setBackground(bg(CY, PU, 12, 0));
            TextView t = txt("IZINKAN USAGE ACCESS", 10, DARK, true);
            b.addView(t);
            b.setOnClickListener(v -> open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));
            appList.addView(b, mt(4));
            return;
        }
        loadingApps = true;
        final int gen = ++scanGen;
        final boolean runningMode = curTab == TAB_APPS;
        new Thread(() -> {
            final ArrayList<AppRow> rows = scanApps(runningMode);
            ui.post(() -> {
                loadingApps = false;
                if (gen != scanGen || appList == null) return;
                lastRows.clear(); lastRows.addAll(rows);
                renderApps(runningMode);
            });
        }).start();
    }

    private ArrayList<AppRow> scanApps(boolean runningMode) {
        PackageManager pm = getPackageManager();
        StorageStatsManager sm = (StorageStatsManager) getSystemService(STORAGE_STATS_SERVICE);
        UsageStatsManager um = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        HashMap<String, Long> last = new HashMap<>();
        try {
            List<UsageStats> us = um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24L * 3600 * 1000, now);
            if (us != null) for (UsageStats u : us) {
                Long old = last.get(u.getPackageName());
                if (old == null || u.getLastTimeUsed() > old) last.put(u.getPackageName(), u.getLastTimeUsed());
            }
        } catch (Exception ignored) { }
        ArrayList<String> pkgs = new ArrayList<>();
        if (runningMode) {
            for (Map.Entry<String, Long> en : last.entrySet())
                if (now - en.getValue() <= 2L * 3600 * 1000 && en.getValue() > 0) pkgs.add(en.getKey());
        } else {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) pkgs.add(ai.packageName);
        }
        ArrayList<AppRow> out = new ArrayList<>();
        for (String pkg : pkgs) {
            if (pkg.equals(getPackageName())) continue;
            try {
                if (runningMode && pm.getLaunchIntentForPackage(pkg) == null) continue;
                long cache = 0;
                try {
                    StorageStats st = sm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, android.os.Process.myUserHandle());
                    cache = st.getCacheBytes();
                } catch (Exception ignored) { }
                if (!runningMode && cache <= 0) continue;
                String label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
                Long lt = last.get(pkg);
                out.add(new AppRow(label, pkg, cache, lt == null ? 0 : lt));
            } catch (Exception ignored) { }
        }
        if (runningMode) Collections.sort(out, (x, y) -> Long.compare(y.last, x.last));
        else Collections.sort(out, (x, y) -> Long.compare(y.cache, x.cache));
        return out;
    }

    private void renderApps(boolean runningMode) {
        if (appList == null) return;
        appList.removeAllViews();
        rowSub.clear();
        long total = 0;
        for (AppRow r : lastRows) total += r.cache;
        if (appSummary != null) appSummary.setText(lastRows.size() + " aplikasi • total cache " + cacheText(total));
        if (lastRows.isEmpty()) { appList.addView(note(runningMode ? "Tidak ada aplikasi aktif terdeteksi." : "Tidak ada cache terdeteksi."), mt(6)); return; }
        long now = System.currentTimeMillis();
        int shown = 0;
        for (AppRow r : lastRows) {
            if (shown++ >= 50) break;
            final AppRow ar = r;
            LinearLayout item = row();
            item.setPadding(dp(8), dp(6), dp(5), dp(6));
            item.setBackground(bg(0xFF1A1A1D, 0xFF0E0E10, 10, 0x22FFFFFF));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView nm = txt(ar.label, 10, 0xFFFFFFFF, true); nm.setSingleLine(true); nm.setEllipsize(TextUtils.TruncateAt.END);
            String sub = "cache " + cacheText(ar.cache);
            if (runningMode && ar.last > 0) sub += " • " + Math.max(0, (now - ar.last) / 60000) + " mnt lalu";
            TextView sb = txt(sub, 8, 0xFF9A9AA2, false); sb.setSingleLine(true);
            col.addView(nm); col.addView(sb);
            rowSub.put(ar.pkg, sb);
            item.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            if (runningMode) {
                item.addView(iconBtn(IconView.FLOAT, TXT, () -> launchFloating(ar.pkg, ar.label, 0, true)));
                item.addView(iconBtn(IconView.STOP, 0xFFFF8080, () -> stopApp(ar.pkg, ar.label), () -> forceStopApp(ar.pkg, ar.label)));
            }
            item.addView(iconBtn(IconView.CACHE, 0xFFFFFFFF, () -> clearCache(ar.pkg, ar.label), () -> clearCacheSilentAll(ar.label)));
            appList.addView(item, mt(4));
        }
    }

    private View iconBtn(int ic, int color, Runnable a) { return iconBtn(ic, color, a, null); }

    private View iconBtn(int ic, int color, Runnable a, Runnable longA) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(bg(0xFF2C2C30, 0xFF18181A, 9, 0x2AFFFFFF));
        IconView v = new IconView(this).set(ic, color, false);
        f.addView(v, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        f.setOnClickListener(x -> a.run());
        if (longA != null) f.setOnLongClickListener(x -> { longA.run(); return true; });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(32), dp(32));
        p.leftMargin = dp(4);
        f.setLayoutParams(p);
        return f;
    }

    private void stopApp(String pkg, String label) {
        try { ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).killBackgroundProcesses(pkg); } catch (Exception ignored) { }
        addLog("STOP senyap: " + label);
        status("STOP " + label + " • RAM dilepas tanpa pindah layar (tahan tombol = Force Stop penuh)", OK);
    }

    /** Tahan tombol STOP: Force Stop penuh lewat Cleaner. Ini membuka Info Aplikasi sebentar, jadi hanya atas permintaan eksplisit. */
    private void forceStopApp(String pkg, String label) {
        if (!CleanerService.enabled()) { enableCleaner(true); return; }
        CleanerService.enqueue(pkg, CleanerService.FORCE_STOP, label);
        addLog("Antri force stop penuh: " + label);
        status("Force Stop " + label + " • diproses…", WARN);
    }

    /** Angka cache untuk daftar: di bawah 1 KB ditampilkan 0 MB. */
    private String cacheText(long b) { return b < 1024 ? "0 MB" : sizeText(b); }

    /** Ubah angka MB satu aplikasi di daftar tanpa memuat ulang seluruh daftar. */
    private void setRowCache(String pkg, long bytes) {
        long total = 0;
        for (AppRow r : lastRows) { if (r.pkg.equals(pkg)) r.cache = bytes; total += r.cache; }
        TextView t = rowSub.get(pkg);
        if (t != null) t.setText("cache " + cacheText(bytes) + (bytes < 1024 ? "  ✓ bersih" : ""));
        if (appSummary != null) appSummary.setText(lastRows.size() + " aplikasi • total cache " + cacheText(total));
    }

    private long rowCache(String pkg) {
        for (AppRow r : lastRows) if (r.pkg.equals(pkg)) return r.cache;
        return -1;
    }

    /** Aplikasi yang tadi di depan (game), supaya Cleaner bisa membukanya lagi setelah selesai. */
    private String foregroundPkg() {
        try {
            if (!hasUsageAccess()) return null;
            UsageStatsManager um = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = um.queryEvents(now - 15 * 60_000L, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String last = null;
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == 1 && !getPackageName().equals(e.getPackageName())) last = e.getPackageName();   // 1 = pindah ke depan
            }
            return last;
        } catch (Throwable t) { return null; }
    }

    // ---- tirai: menutup layar sebentar selama Cleaner menekan "Clear cache" di Setelan
    private void showCurtain(String text) {
        if (wm == null || !Settings.canDrawOverlays(this)) return;
        ui.removeCallbacks(curtainOff);
        if (curtain == null) {
            FrameLayout f = new FrameLayout(this);
            f.setBackgroundColor(0xF2050507);
            f.setOnTouchListener((v, ev) -> true);   // sentuhan nyasar tidak sampai ke halaman Setelan
            TextView t = txt(text, 13, 0xFFFFFFFF, true);
            t.setGravity(Gravity.CENTER);
            f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            WindowManager.LayoutParams p = lp(-1, -1);
            p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            try { wm.addView(f, p); curtain = f; curtainTxt = t; } catch (Throwable e) { curtain = null; curtainTxt = null; return; }
        } else if (curtainTxt != null) curtainTxt.setText(text);
        ui.postDelayed(curtainOff, 25000);   // jaring pengaman: tirai tidak pernah tertinggal
    }

    private void hideCurtain() {
        ui.removeCallbacks(curtainOff);
        View v = curtain; curtain = null; curtainTxt = null;
        if (v != null) { try { wm.removeView(v); } catch (Throwable ignored) { } }
    }

    /**
     * Tap ikon sapu: bersihkan cache HANYA aplikasi ini, tanpa membuka aplikasinya.
     * Cleaner menekan "Clear cache" di Setelan (ditutup tirai), lalu ukuran dibaca ulang dari sistem dan MB di daftar berubah jadi 0.
     * Bila izin WRITE_SECURE_SETTINGS sudah di-grant, Cleaner dinyalakan otomatis tanpa membuka halaman Aksesibilitas.
     */
    private void clearCache(final String pkg, final String label) {
        if (reclaiming || CleanerService.pending() > 0) { status("Sedang membersihkan…", WARN); return; }
        if (CleanerService.enabled()) { startCleanOne(pkg, label); return; }
        AccessibilityGuard.markWanted(this, true);
        if (!CleanerService.enableViaSecure(this)) { clearCacheFallback(pkg, label); return; }
        status("Menyalakan Cleaner…", WARN);
        waitCleaner(pkg, label, 0);
    }

    private void waitCleaner(final String pkg, final String label, final int n) {
        if (!running) return;
        if (CleanerService.enabled()) { startCleanOne(pkg, label); return; }
        if (n >= 14) { clearCacheFallback(pkg, label); return; }   // sekitar 3,5 detik
        ui.postDelayed(() -> waitCleaner(pkg, label, n + 1), 250);
    }

    private void startCleanOne(String pkg, String label) {
        watchPkgs.clear(); watchPkgs.add(pkg);
        cleanBefore.put(pkg, rowCache(pkg));
        CleanerService.returnTo(foregroundPkg());
        if (sp.getBoolean("cache_curtain", true)) showCurtain("Membersihkan cache " + label + "…");
        addLog("Bersihkan cache " + label + " (hanya app ini)");
        status("Membersihkan cache " + label + "…", WARN);
        CleanerService.enqueue(pkg, CleanerService.CLEAR_CACHE, label);
    }

    /** Setelah Cleaner selesai: baca ulang cache aplikasi yang dibersihkan dan tampilkan angka sebenarnya di daftar. */
    private void verifyWatched() {
        if (watchPkgs.isEmpty()) return;
        final ArrayList<String> pk = new ArrayList<>(watchPkgs);
        watchPkgs.clear();
        new Thread(() -> {
            for (final String p : pk) {
                long after = CacheEngine.cacheOf(this, p);
                for (int i = 0; i < 4 && after > 4096; i++) { SystemClock.sleep(350); after = CacheEngine.cacheOf(this, p); }
                final long a = after;
                final Long b = cleanBefore.get(p);
                final String lbl = labelOf(p);
                ui.post(() -> {
                    long shown = a < 0 ? 0 : a;   // tidak terbaca = tombol Clear cache sudah ditekan Cleaner
                    setRowCache(p, shown);
                    String line = lbl + ": " + (b != null && b >= 0 ? cacheText(b) + " → " : "") + cacheText(shown);
                    addLog(line);
                    status(line, OK);
                });
            }
        }).start();
    }

    /** Cadangan bila Cleaner tidak bisa dinyalakan: sistem melepas cache SEMUA app (senyap), lalu angka app ini dibaca ulang. */
    private void clearCacheFallback(final String pkg, final String label) {
        if (reclaiming) { status("Sedang membersihkan…", WARN); return; }
        reclaiming = true;
        status("Cleaner belum aktif • membersihkan lewat sistem…", WARN);
        new Thread(() -> {
            final long before = CacheEngine.cacheOf(this, pkg);
            final CacheEngine.Result res = CacheEngine.reclaim(this);
            final long after = CacheEngine.cacheOf(this, pkg);
            ui.post(() -> {
                reclaiming = false;
                String line;
                if (!res.ok) line = res.msg + " • aktifkan Cleaner (Alat & izin) untuk bersihkan per app";
                else if (after >= 0) { setRowCache(pkg, after); line = label + ": " + (before >= 0 ? cacheText(before) + " → " : "") + cacheText(after) + " • cache app lain ikut dilepas sistem"; }
                else line = "Cache dilepas sistem" + (res.freed > 0 ? " • bebas +" + sizeText(res.freed) : "") + " • aktifkan Usage Access untuk lihat per app";
                addLog(line);
                status(line, res.ok ? OK : WARN);
            });
        }).start();
    }

    /** Tahan ikon sapu: sistem melepas cache semua app sekaligus (senyap, tanpa Setelan). */
    private void clearCacheSilentAll(String label) {
        status("Tahan sapu • bersihkan cache semua app dari sini", WARN);
        clearAllCache();
    }

    private void stopAll() {
        if (lastRows.isEmpty()) { status("Belum ada aplikasi di daftar", WARN); return; }
        int n = 0;
        addLog("Mulai force stop " + lastRows.size() + " aplikasi");
        for (AppRow r : new ArrayList<>(lastRows)) { stopApp(r.pkg, r.label); n++; }
        status(n + " aplikasi diantrikan untuk STOP", WARN);
    }

    private String optText() {
        long t = sp.getLong("last_opt", 0);
        if (t == 0) return "Belum dioptimalkan • tap OPTIMALKAN PONSEL";
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        String info = sp.getString("last_opt_info", "");
        return "Dioptimalkan " + (m < 1 ? "baru saja" : m + " mnt lalu") + (info.isEmpty() ? "" : " • " + info);
    }

    private volatile boolean optimizing;

    /** OPTIMALKAN PONSEL: berjalan penuh di dalam floating window (RAM + cache semua aplikasi), tidak membuka aplikasi lain. */
    private void optimizePhone() {
        if (optimizing || reclaiming) { status("Optimasi sedang berjalan…", WARN); return; }
        optimizing = true; status("Optimasi memory & cache berjalan…", WARN); addLog("OPTIMIZE • memory + system cache");
        new Thread(() -> {
            long before = Boost.mem(this).availMem;
            CacheEngine.Result cache = CacheEngine.reclaim(this);
            int killed = Boost.killBg(this);
            long own = Boost.size(getCacheDir()); Boost.wipe(getCacheDir(), null); getCacheDir().mkdirs();
            try { Thread.sleep(600); } catch (InterruptedException ignored) { }
            long gained = Math.max(0, (Boost.mem(this).availMem-before)>>20);
            String result = "Memory optimize selesai • RAM +" + gained + " MB • cache: " + (cache.ok ? cache.msg : cache.msg) + " • cache IMZYY " + sizeText(own) + " • " + RamManager.note(this, killed);
            ui.post(() -> { optimizing=false; addLog(result); status(result, cache.ok || gained>0 ? OK : WARN); refreshOptText(); refresh(); });
        }).start();
    }

    private volatile boolean reclaiming;

    private long totalCacheNow() {
        if (!hasUsageAccess()) return -1;
        long t = 0;
        for (AppRow r : scanApps(false)) t += r.cache;
        return t;
    }

    /** Satu tap, langsung di floating window: sistem melepas cache semua aplikasi sekaligus, tanpa pindah ke aplikasi lain. */
    private void clearAllCache() {
        if (reclaiming || optimizing) { status("Optimasi sedang berjalan…", WARN); return; }
        reclaiming = true; status("Cache dibersihkan otomatis…", WARN); addLog("CACHE • proses otomatis dari floating panel");
        new Thread(() -> {
            long free0 = new android.os.StatFs(getFilesDir().getPath()).getAvailableBytes();
            CacheEngine.Result res = CacheEngine.reclaim(this);
            long junk = CacheEngine.junk(this);
            long free1 = new android.os.StatFs(getFilesDir().getPath()).getAvailableBytes();
            long freed = Math.max(0, free1-free0);
            String msg = res.ok ? (freed > 0 ? "Cache sistem • ruang +" + sizeText(freed) : "Cache sistem diproses (ruang tidak berubah terukur)") + " • sampah aman " + sizeText(junk) : "Cache: " + res.msg + " • sampah aman " + sizeText(junk);
            ui.post(() -> { reclaiming=false; addLog(msg); status(msg, res.ok || junk>0 ? OK : WARN); if(curTab==TAB_CACHE) reloadApps(); });
        }).start();
    }

    /**
     * BERSIHKAN TUNTAS: dialog resmi Android "bersihkan cache semua aplikasi", dikonfirmasi otomatis oleh Cleaner.
     * Satu dialog untuk semua aplikasi, tidak ada halaman Info Aplikasi. Butuh Android 11+ dan Akses semua file (sekali).
     */
    private void systemClean() {
        if (Build.VERSION.SDK_INT < 30) { status("Bersihkan tuntas butuh Android 11+ • pakai CLEAR CACHE SEKARANG", WARN); return; }
        if (!hasAllFiles()) {
            if (Perm.first(this, "allfiles")) {
                open(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                status("Izinkan 'Akses semua file' (sekali), lalu tap lagi", WARN);
            } else status("Akses semua file belum aktif • aktifkan di TOOLS > IZIN", WARN);
            return;
        }
        if (sysCleaning) { status("Sedang berjalan…", WARN); return; }
        sysCleaning = true;
        status(CleanerService.enabled() ? "Membersihkan tuntas… dikonfirmasi otomatis" : "Dialog sistem muncul • tap Hapus/Clear (aktifkan IMZYY Cleaner agar otomatis)", WARN);
        addLog("Bersihkan tuntas (dialog sistem)");
        new Thread(() -> {
            sysBefore = totalCacheNow();
            ui.post(() -> {
                Intent i = new Intent(this, CacheClearActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                try { startActivity(i); }
                catch (Exception e) { sysCleaning = false; status("Gagal membuka dialog pembersihan sistem", BAD); }
            });
        }).start();
    }

    private void onSystemCleanDone(final int code) {
        new Thread(() -> {
            final long after = totalCacheNow();
            ui.post(() -> {
                sysCleaning = false;
                String line;
                if (code == android.app.Activity.RESULT_OK) {
                    line = sysBefore >= 0 && after >= 0 ? "Cache " + sizeText(sysBefore) + " menjadi " + sizeText(after) : "Cache semua aplikasi dibersihkan sistem";
                } else if (code == -2) {
                    line = "Firmware ini tidak punya dialog bersihkan cache sistem • pakai CLEAR CACHE SEKARANG";
                } else {
                    line = "Dibatalkan atau ditolak sistem • tap lagi, atau pakai CLEAR CACHE SEKARANG";
                }
                addLog(line);
                status(line, code == android.app.Activity.RESULT_OK ? OK : WARN);
                if (curTab == TAB_CACHE || curTab == TAB_APPS) reloadApps();
            });
        }).start();
    }

    /** PER APP: bersihkan per aplikasi lewat Cleaner (membuka Info Aplikasi satu per satu, lebih tuntas tapi lebih lama). */
    private void deepClean() {
        if (!CleanerService.enabled()) { enableCleaner(true); return; }
        if (CleanerService.pending() > 0) { status("Pembersihan sedang berjalan…", WARN); return; }
        status("Memindai cache semua aplikasi…", WARN);
        new Thread(() -> {
            final boolean usage = hasUsageAccess();
            final ArrayList<AppRow> rows = new ArrayList<>();
            if (usage) {
                for (AppRow r : scanApps(false)) if (r.cache >= MIN_CACHE_BYTES) rows.add(r);
            } else {
                PackageManager pm = getPackageManager();
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(getPackageName()) || pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                    rows.add(new AppRow(String.valueOf(pm.getApplicationLabel(ai)), ai.packageName, 0, 0));
                }
            }
            ui.post(() -> {
                if (rows.isEmpty()) { status("Tidak ada cache yang perlu dibersihkan", OK); return; }
                addLog("Mulai clear cache " + rows.size() + " aplikasi");
                for (AppRow r : rows) CleanerService.enqueue(r.pkg, CleanerService.CLEAR_CACHE, r.label);
                status(rows.size() + " aplikasi dibersihkan otomatis…", WARN);
            });
        }).start();
    }

    private void onCleaner(String msg, boolean ok, boolean done) {
        String prog = CleanerService.total() > 1 ? " (" + CleanerService.done() + "/" + CleanerService.total() + ")" : "";
        addLog(msg + prog);
        status(msg + prog + (done ? "" : "…"), ok ? OK : WARN);
        refreshOptText();
        if (done) {
            ui.postDelayed(this::hideCurtain, 1100);   // beri waktu Cleaner menekan BACK dan membuka lagi game
            if (!ok && msg.startsWith("Dilewati") && watchPkgs.size() == 1) {
                // Tombol Clear cache tidak ketemu di Setelan: jangan suruh pengguna membersihkan sendiri, langsung lepas cache lewat sistem.
                final String p = watchPkgs.get(0);
                watchPkgs.clear();
                ui.postDelayed(() -> { if (running) clearCacheFallback(p, labelOf(p)); }, 1300);
                return;
            }
            verifyWatched();
            if (watchPkgs.isEmpty() && (curTab == TAB_APPS || curTab == TAB_CACHE)) reloadApps();
        }
    }

    private void floatAll() {
        final ArrayList<String> favs = getFavs();
        if (favs.isEmpty()) { status("Tambah aplikasi dulu", WARN); return; }
        closePanel();
        for (int i = 0; i < favs.size(); i++) {
            final int at = i; final String pkg = favs.get(i);
            ui.postDelayed(() -> launchFloating(pkg, labelOf(pkg), at, false), i * 500L);
        }
    }

    /** Buka aplikasi di jendela mengambang (freeform). Ukuran dari pilihan KECIL/SEDANG/BESAR, bertumpuk rapi per urutan. */
    private void launchFloating(String pkg, String label, int idx, boolean closeAfter) {
        if (pkg == null || pkg.isEmpty()) { status("Pilih game/aplikasi dulu", WARN); return; }
        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) { status("Aplikasi tidak bisa dibuka", WARN); return; }
        li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        DisplayMetrics m = getResources().getDisplayMetrics();
        boolean land = m.widthPixels > m.heightPixels;
        int sz = sp.getInt("float_size", 0); // 0 sedang, 1 kecil, 2 besar
        float[] pw = land ? new float[]{.5f, .38f, .66f} : new float[]{.80f, .62f, .94f};
        float[] ph = land ? new float[]{.80f, .62f, .92f} : new float[]{.46f, .34f, .62f};
        int w = (int) (m.widthPixels * pw[sz]), h = (int) (m.heightPixels * ph[sz]);
        int off = (idx % 4) * dp(26);
        int l = clamp((m.widthPixels - w) / 2 + off - dp(26), 0, m.widthPixels - w);
        int t = clamp((int) (m.heightPixels * .08f) + off, 0, m.heightPixels - h);
        ActivityOptions o = ActivityOptions.makeBasic();
        o.setLaunchBounds(new Rect(l, t, l + w, t + h));
        boolean freeform = false;
        try { ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class).invoke(o, 5); freeform = true; } catch (Throwable ignored) { }
        try {
            startActivity(li, o.toBundle());
            addLog("Jendela: " + label);
            if (closeAfter) closePanel();
        } catch (Exception e) {
            status("Gagal membuka jendela mengambang", BAD);
            return;
        }
        if (!freeform && status != null) status("Dibuka: " + label + " • aktifkan jendela bentuk bebas di Opsi Pengembang", WARN);
    }

    // ------------------------------------------------------------ live stats
    private ActivityManager.MemoryInfo mem() {
        ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(m);
        return m;
    }

    private void refresh() {
        if (panel != null && curTab == TAB_MENU && menuStat != null) {
            ActivityManager.MemoryInfo m0 = mem();
            int u0 = (int) (100 - m0.availMem * 100 / Math.max(1, m0.totalMem));
            Intent b0 = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            String bat = "", tmp = "";
            if (b0 != null) {
                bat = "  •  Baterai " + (b0.getIntExtra("level", 0) * 100 / Math.max(1, b0.getIntExtra("scale", 100))) + "%";
                tmp = "  •  " + (b0.getIntExtra("temperature", 0) / 10f) + "°C";
            }
            menuStat.setText("RAM " + u0 + "%" + tmp + bat + "  •  " + Math.round(FpsEngine.current(this)) + " Hz");
            return;
        }
        if (panel == null || curTab != 0 || tRam == null) return;
        ActivityManager.MemoryInfo mi = mem();
        int used = (int) (100 - mi.availMem * 100 / Math.max(1, mi.totalMem));
        tRam.setText(used + "%"); tRam.setTextColor(used > 85 ? BAD : used > 70 ? WARN : OK);
        if (barFill != null && barRest != null) {
            ((LinearLayout.LayoutParams) barFill.getLayoutParams()).weight = Math.max(1, used);
            ((LinearLayout.LayoutParams) barRest.getLayoutParams()).weight = Math.max(1, 100 - used);
            barFill.requestLayout();
        }
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int lv = b.getIntExtra("level", 0) * 100 / Math.max(1, b.getIntExtra("scale", 100));
            float t = b.getIntExtra("temperature", 0) / 10f;
            tBat.setText(lv + "%"); tTemp.setText(t + "°"); tTemp.setTextColor(t >= 42 ? BAD : t >= 38 ? WARN : OK);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            int ts = ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus();
            tTherm.setText(ts <= 0 ? "COOL" : ts == 1 ? "WARM" : ts == 2 ? "WARM+" : ts == 3 ? "HOT" : "CRIT");
            tTherm.setTextColor(ts <= 0 ? OK : ts <= 2 ? WARN : BAD);
        } else tTherm.setText("N/A");
    }

    // ------------------------------------------------------------ actions
    /** Android 13 ke bawah: killBackgroundProcesses. Android 14+: Force Stop terpilih lewat IMZYY Cleaner (lihat RamManager). */
    private int killBg() { return Boost.killBg(this); }

    private boolean thermalBlocked() {
        if (!sp.getBoolean("thermal_guard", false)) return false;
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        float temp = battery == null ? 0 : battery.getIntExtra("temperature", 0) / 10f;
        if (temp >= 44f) { status("THERMAL GUARD: ditahan • suhu " + temp + "°C", WARN); return true; }
        if (temp >= 42f) status("THERMAL GUARD: suhu tinggi " + temp + "°C", WARN);
        return false;
    }

    private void boost(final int mode) { // 0 = turbo, 1 = RAM only, 2 = cache only
        if (thermalBlocked()) return;
        status("Boosting…", WARN);
        new Thread(() -> {
            long before = mem().availMem;
            int n = mode < 2 ? killBg() : 0;
            long kb = 0;
            if (mode != 1) { kb = fileSize(getCacheDir()) >> 10; wipe(getCacheDir()); }
            try { Thread.sleep(900); } catch (Exception ignored) { }
            final long mb = Math.max(0, (mem().availMem - before) >> 20);
            final int fn = n; final long fk = kb;
            ui.post(() -> {
                if (mode == 0) {
                    awake(true);
                    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (nm.isNotificationPolicyAccessGranted() && !sp.getBoolean("dnd", false)) dnd(true);
                    if (TouchEngine.canApply(FloatingService.this) && !sp.getBoolean("touch_profile", false)) touchBoost(true, false);
                    paintAll();
                }
                String note = RamManager.note(FloatingService.this, fn);
                String message;
                if (mode == 1) message = "RAM • " + note + " • ~" + mb + " MB";
                else if (mode == 2) message = "CACHE IMZYY " + fk + " KB dibersihkan";
                else message = "TURBO selesai • " + note + " • ~" + mb + " MB • " + fk + " KB";
                status(message, OK);
                refresh();
            });
        }).start();
    }

    private void ping() {
        status("Mengetes latency…", WARN);
        new Thread(() -> {
            long best = 9999, tot = 0; int good = 0;
            for (int i = 0; i < 3; i++) {
                try {
                    long t0 = SystemClock.elapsedRealtime();
                    HttpURLConnection conn = (HttpURLConnection) new URL("https://www.google.com/generate_204").openConnection();
                    conn.setConnectTimeout(3000); conn.setReadTimeout(3000); conn.getResponseCode(); conn.disconnect();
                    long d = SystemClock.elapsedRealtime() - t0;
                    best = Math.min(best, d); tot += d; good++;
                } catch (Exception ignored) { }
            }
            final String m = good == 0 ? "Tidak ada internet" : "Latency koneksi rata-rata " + (tot / good) + " ms • terbaik " + best + " ms";
            final int col = good == 0 ? BAD : OK;
            ui.post(() -> status(m, col));
        }).start();
    }

    private void dnd(boolean on) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (!nm.isNotificationPolicyAccessGranted()) {
            sp.edit().putBoolean("dnd", false).apply();
            sp.edit().putBoolean("pending_dnd", true).apply();
            open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            status("Izinkan akses DND sekali • DND menyala otomatis setelahnya", WARN);
            armPending();
            return;
        }
        if (on) {
            sp.edit().putInt("prevf", nm.getCurrentInterruptionFilter()).putBoolean("dnd", true).apply();
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
            status("DND aktif (alarm tetap bunyi)", OK);
        } else {
            nm.setInterruptionFilter(sp.getInt("prevf", NotificationManager.INTERRUPTION_FILTER_ALL));
            sp.edit().putBoolean("dnd", false).apply();
            status("DND dikembalikan", MUTE);
        }
    }

    private void bright(boolean on) {
        if (!Settings.System.canWrite(this)) {
            sp.edit().putBoolean("bright", false).putString("pending_feature", "bright").apply();
            askWrite();
            return;
        }
        ContentResolver cr = getContentResolver();
        try {
            if (on) {
                sp.edit().putInt("pb", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128))
                        .putInt("pm", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)).putBoolean("bright", true).apply();
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 255);
                status("Brightness maksimal", OK);
            } else {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, sp.getInt("pb", 128));
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, sp.getInt("pm", 0));
                sp.edit().putBoolean("bright", false).apply();
                status("Brightness dikembalikan", MUTE);
            }
        } catch (Exception e) { status("Gagal ubah brightness", BAD); }
    }

    /** Battery sensor temp (°C). Device surface/SoC temperature is not universally exposed. */
    private float readBatteryTempC() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) { int raw = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1); if (raw > 0) return raw / 10f; }
        } catch (Throwable ignored) { }
        return Float.NaN;
    }

    private void startFpsSampler() {
        fpsSamplerOn = true; fpsSampleFrames = 0; fpsSampleStart = 0L;
        Choreographer.getInstance().removeFrameCallback(fpsFrameCallback);
        Choreographer.getInstance().postFrameCallback(fpsFrameCallback);
    }
    private void stopFpsSampler() {
        fpsSamplerOn = false;
        Choreographer.getInstance().removeFrameCallback(fpsFrameCallback);
    }
    private final Choreographer.FrameCallback fpsFrameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!fpsSamplerOn || panel == null) return;
            if (fpsSampleStart == 0L) fpsSampleStart = frameTimeNanos;
            fpsSampleFrames++;
            long elapsed = frameTimeNanos - fpsSampleStart;
            if (elapsed >= 1_000_000_000L) {
                int measured = Math.round(fpsSampleFrames * 1_000_000_000f / elapsed);
                liveFps = measured;
                if (miniFpsNow != null) miniFpsNow.setText("VSYNC  " + measured + " Hz");
                if (miniFpsPrev != null) miniFpsPrev.setText("VSYNC SEBELUM  " + (previousFps > 0 ? String.valueOf(previousFps) : "--"));
                if (miniFpsDelta != null) {
                    String delta = previousFps > 0 ? ((measured - previousFps >= 0 ? "+" : "") + (measured - previousFps)) : "--";
                    miniFpsDelta.setText("VSYNC SEKARANG  " + measured + "  •  Δ " + delta);
                }
                if (miniFan != null) {
                    float cap = Math.max(60f, FpsEngine.current(FloatingService.this));
                    miniFan.setVisualFps(measured); // Animasi visual berdasarkan FPS; RPM fisik tetap hanya dari sensor nyata.
                    float tempC = readBatteryTempC();
                    miniFan.setTemperatureC(tempC);
                    if (miniThermalLine != null) {
                        String tempText = Float.isNaN(tempC) ? "--°C" : String.format(java.util.Locale.US, "%.1f°C", tempC);
                        miniThermalLine.setText((miniFan.physicalRpm() >= 0 ? miniFan.physicalRpm() + " RPM" : "RPM N/A") + "  •  " + tempText);
                    }
                }
                fpsSampleFrames = 0; fpsSampleStart = frameTimeNanos;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private void markFpsBeforeChange() {
        previousFps = liveFps > 0 ? liveFps : Math.round(FpsEngine.current(this));
        if (miniFpsPrev != null) miniFpsPrev.setText("SEBELUM  " + previousFps);
    }

    private void paintFps() {
        int sel = sp.getInt("fps", -1);
        for (int i = 0; i < fpsBtns.size(); i++) {
            TextView t = fpsBtns.get(i); boolean on = TARGETS[i] == sel;
            t.setTextColor(on ? DARK : TXT);
            t.setBackground(on ? bg(CY, PU, 11, 0) : bg(0xFF262629, 0xFF161618, 11, 0x33FFFFFF));
        }
    }

    private void preferOnWindows(int hz) {
        try {
            FpsEngine.prefer(bp, this, hz); pushBubble();
            if (panel != null && pp != null) { FpsEngine.prefer(pp, this, hz); wm.updateViewLayout(panel, pp); }
        } catch (Exception ignored) { }
    }

    private void setFps(final int target) {
        markFpsBeforeChange();
        final int hz = FpsEngine.pick(this, target);
        if (!FpsEngine.apply(this, hz)) {
            // Tidak membuka halaman izin otomatis; tetap apply mode jendela best-effort.
            // Izin tambahan bisa diberikan manual lewat Brevent/ADB bila ingin kontrol refresh global.
            sp.edit().putInt("fps", target).apply();
            preferOnWindows(hz);
            paintFps();
            status("Mode jendela " + hz + " Hz aktif • " + FpsEngine.friendly(), WARN);
            return;
        }
        sp.edit().putInt("fps", target).apply();
        preferOnWindows(hz);
        paintFps(); status("Menerapkan " + hz + " Hz…", WARN);
        ui.postDelayed(() -> { String m = FpsEngine.verify(this, target, hz); status(m, m.startsWith("Layar berjalan") ? OK : WARN); }, 900);
    }

    private void applyRenderProfile() {
        markFpsBeforeChange();
        int profile = sp.getInt("render_profile", 0);
        if (profile == 0) {
            FpsEngine.reset(this);
            status("SYSTEM AUTO: refresh rate dikembalikan", OK);
            return;
        }
        List<Integer> rates = FpsEngine.rates(this);
        if (rates.isEmpty()) { status("Mode refresh rate tidak terbaca", WARN); return; }
        int target = profile == 1 ? rates.get(0) : profile == 2 ? FpsEngine.pick(this, 60) : rates.get(rates.size() - 1);
        if (!FpsEngine.apply(this, target)) {
            if (!Settings.System.canWrite(this) && !GpuEngine.canSecure(this)) { askWrite(); return; }
            sp.edit().putInt("fps", target).apply();
            preferOnWindows(target);
            paintFps();
            status("Mode jendela " + target + " Hz aktif • " + FpsEngine.friendly(), WARN);
            return;
        }
        sp.edit().putInt("fps", target).apply();
        preferOnWindows(target);
        paintFps();
        status("Profil diterapkan • " + target + " Hz", OK);
    }

    private void startGameSession() {
        String pkg = sp.getString("saved_game_package", "");
        if (pkg.isEmpty()) { pageGamePicker(); return; }
        if (thermalBlocked()) return;
        status("Menyiapkan sesi game…", WARN);
        applyRenderProfile();
        boost(0);
        netBoost(false);
        ui.postDelayed(this::launchSavedGame, 1500);
    }

    private void launchSavedGame() {
        String pkg = sp.getString("saved_game_package", "");
        if (pkg.isEmpty()) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) { status("Game tidak ditemukan. Pilih ulang.", WARN); return; }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void awake(boolean on) { sp.edit().putBoolean("awake", on).apply(); applyAwake(); }

    private void applyAwake() {
        boolean on = sp.getBoolean("awake", false);
        for (WindowManager.LayoutParams p : new WindowManager.LayoutParams[]{bp, pp}) {
            if (p == null) continue;
            if (on) p.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON; else p.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        }
        try {
            pushBubble();
            if (panel != null && pp != null) wm.updateViewLayout(panel, pp);
        } catch (Exception ignored) { }
    }

    /** Rotasi layar / ganti ukuran: posisi dihitung ulang dari rasio tersimpan dan area aman gestur, sidebar tetap menempel di tepinya. */
    private void reflow() {
        if (wm == null || bp == null) return;
        if (review != null) review.reflow();
        applyFanSizes();
        if (panel == null) {
            bp.x = Math.round(sp.getFloat("bxr", 0f) * sw());
            bp.y = Math.round(sp.getFloat("byr", .3f) * sh());
            dockSidebar();
        }
        clampBubble();
        if (panel != null && pp != null) {
            int[] sz = panelSize();
            pp.width = sz[0]; pp.height = sz[1];
            if (sideDocked()) dockPanelPos(sz[0], sz[1]);
            else {
                pp.x = clamp(Math.round(sp.getFloat("bxr", 0f) * sw()), 0, sw() - sz[0]);
                pp.y = clamp(Math.round(sp.getFloat("byr", .3f) * sh()), safeTop(), sh() - sz[1] - safeBottom());
                bp.x = pp.x; bp.y = pp.y; clampBubble();
            }
            try { wm.updateViewLayout(panel, pp); } catch (Exception ignored) { }
        } else pushBubble();
    }

    private long fileSize(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0; File[] a = f.listFiles();
        if (a != null) for (File x : a) n += fileSize(x);
        return n;
    }

    private void wipe(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) { File[] a = f.listFiles(); if (a != null) for (File x : a) wipe(x); }
        if (!f.equals(getCacheDir())) f.delete();
    }





    private void pageBatteryManager() {
        LinearLayout c = newPage();
        c.addView(title("BATTERY • GAME PROFILE"), mt(0));
        Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
        int st = bi == null ? -1 : bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        String charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL ? "CHARGING" : "ON BATTERY";
        float temp = readBatteryTempC();
        LinearLayout monitor = row(); monitor.setGravity(Gravity.CENTER_VERTICAL); monitor.setPadding(dp(11), dp(10), dp(11), dp(10));
        monitor.setBackground(bg(0xFF171719, 0xFF080809, 14, 0x33FFFFFF));
        FrameLayout batIcon = new FrameLayout(this); batIcon.setBackground(bg(0xFF22252A, 0xFF101012, 99, 0x33FFFFFF));
        batIcon.addView(new IconView(this).set(IconView.BATTERY, 0xFF62A8FF, true), new FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER));
        monitor.addView(batIcon, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout stats = new LinearLayout(this); stats.setOrientation(LinearLayout.VERTICAL); stats.setPadding(dp(8), 0, 0, 0);
        stats.addView(txt((pct >= 0 ? pct + "%" : "--") + "  •  " + charging, 13, TXT, true));
        stats.addView(txt("BATTERY TEMP  " + (temp > 0 ? String.format(Locale.US, "%.1f°C", temp) : "N/A"), 8, MUTE, true));
        monitor.addView(stats, new LinearLayout.LayoutParams(0, -2, 1f)); c.addView(monitor, mt(3));

        c.addView(title("PILIH PROFIL"), mt(7));
        LinearLayout profiles = row(); profiles.setGravity(Gravity.CENTER);
        int[] icons = {IconView.BATTERY, IconView.SHIELD, IconView.STYLE, IconView.BOLT};
        int[] colors = {0xFF45D5A5, 0xFFE0E0E5, 0xFF62A8FF, 0xFFFF8B45};
        String[] names = {"Prioritas Ketahanan", "Standart", "Balanced", "ImzzBoosted"};
        for (int i=0;i<4;i++) profiles.addView(profileTile(names[i], icons[i], colors[i], i), new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(profiles, mt(2));

        c.addView(title("SAKLAR INTI"), mt(7));
        smartSwitch(c, "thermal_guard", IconView.THERMO, "THERMAL GUARD", "Proteksi suhu tetap diutamakan.");
        smartSwitch(c, "battery_profile", IconView.BATTERY, "BATTERY BALANCE", "Brightness dan refresh rate hemat.");
        smartSwitch(c, "awake", IconView.BOLT, "KEEP SCREEN ON", "Layar tidak mati saat sesi game.");
        c.addView(note("Profil mengubah aksen warna dan kecepatan kipas animasi. Tidak melakukan overclock, mengubah voltase, atau menonaktifkan proteksi suhu bawaan."), mt(5));
        applyFanAccent(sp.getInt("gaming_profile", 1));
    }




    private void pageSidebarSwipe() {
        LinearLayout c = newPage();
        c.addView(title("SIDEBAR / SWIPE BUTTON"), mt(0));
        c.addView(note("Pilih handle tipis di sisi layar. Seret ke dalam untuk membuka panel, atau geser naik/turun untuk mengubah posisinya. Panel dan handle dapat dipindah sisi kiri/kanan."), mt(4));
        c.addView(title("MODE HUB"), mt(6));
        chips(c, "hub_mode", new String[]{"TOMBOL", "SIDEBAR", "SWIPE"}, this::applyHubStyle);
        c.addView(title("SISI HANDLE"), mt(6));
        chips(c, "bside", new String[]{"KIRI", "KANAN"}, () -> applyHubStyle());
        c.addView(title("RESPONS SWIPE"), mt(6));
        chips(c, "hub_trigger", new String[]{"SWIPE / KETUK", "KETUK SAJA", "SWIPE SAJA"});
        c.addView(title("PANJANG TARIK"), mt(6));
        chips(c, "hub_swipe_len", new String[]{"SEDANG", "PENDEK", "PANJANG"});
        c.addView(title("UKURAN HANDLE"), mt(6));
        chips(c, "hub_size", new String[]{"SEDANG", "KECIL", "BESAR"}, this::applyHubStyle);
        smartSwitchDef(c, "hub_snap", true, IconView.FLOAT, "MENEMPEL KE TEPI", "Handle merapat ke sisi layar setelah dilepas.");
        smartSwitchDef(c, "hub_flush", true, IconView.FLOAT, "NEMPEL PALING POJOK", "Handle rapat ke tepi layar.");
        c.addView(note("Tip: pilih SWIPE, lalu tarik handle ke dalam layar untuk membuka panel. Posisi handle tersimpan. Ini memakai sistem sidebar/swipe yang sudah ada, bukan membuat overlay baru."), mt(6));
    }



        private boolean touchBoost(boolean on, boolean announce) {
        TouchEngine.Result r = TouchEngine.apply(this, on, sp.getInt("touch_hold", 1));
        if (r.needWrite && !Settings.System.canWrite(this)) { if (on) sp.edit().putString("pending_feature", "touch_profile").apply(); askWrite(); }
        sp.edit().putBoolean("touch_profile", on ? r.ok : !r.ok).apply();
        addLog("Touch boost " + (on ? "ON" : "OFF") + ": " + r.msg);
        if (announce) status("Touch: " + r.msg, r.ok ? OK : WARN);
        paintAll();
        return r.ok;
    }



        private void copyAdb() {
        try {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("adb",
                    "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
            status("Perintah adb disalin • jalankan sekali, lalu semua otomatis", OK);
        } catch (Exception e) { status("Gagal menyalin. Perintah ada di tab GPU.", WARN); }
    }




    private void antiKill() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) { status("Sudah kebal hemat baterai • hub tidak mudah dimatikan", OK); return; }
        open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        status("Izinkan 'Tanpa batasan' supaya hub tidak di-kill sistem", WARN);
    }



        private void pageGpu() {
        LinearLayout c = newPage();
        c.addView(title("GPU PERANGKAT"), mt(0));
        LinearLayout card = row();
        card.setPadding(dp(10), dp(9), dp(10), dp(9));
        card.setBackground(bg(0xFF1D1D20, 0xFF0C0C0D, 12, 0x33FFFFFF));
        IconView gi = new IconView(this).set(IconView.GPU, 0xFFFFFFFF, true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(dp(26), dp(26)); glp.rightMargin = dp(10);
        card.addView(gi, glp);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        gpuName = txt("Membaca GPU…", 12, 0xFFFFFFFF, true);
        gpuSub = txt("", 8, MUTE, false);
        col.addView(gpuName); col.addView(gpuSub);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(card, mt(2));
        DisplayMetrics m = getResources().getDisplayMetrics();
        List<Integer> rates = FpsEngine.rates(this);
        final String disp = Math.max(m.widthPixels, m.heightPixels) + "x" + Math.min(m.widthPixels, m.heightPixels)
                + (rates.isEmpty() ? "" : " • hingga " + rates.get(rates.size() - 1) + " Hz");
        new Thread(() -> {
            final String[] g = GpuEngine.info();
            ui.post(() -> {
                if (gpuName == null) return;
                gpuName.setText(g[0]);
                gpuSub.setText(g[1] + " • " + g[2] + "\n" + disp);
            });
        }).start();

        bigButton(c, IconView.BOLT, "OPTIMASI GAME SEKARANG", this::optimizeGame, true);

        c.addView(title("PRESET GPU / PERFORMA"), mt(6));
        chips(c, "gpu_profile", GPU_NAMES);
        btn1(c, "TERAPKAN PRESET", this::applyGpuPreset);
        c.addView(note("PERFORMA: refresh rate maksimum + animasi 0.5x + bersihkan RAM. SEIMBANG: 90 Hz. HEMAT: 60 Hz. AUTO: kembali ke bawaan sistem."), mt(4));

        c.addView(title("ANIMASI SISTEM (BEBAN GPU UI)"), mt(6));
        c.addView(txt(GpuEngine.canSecure(this) ? "Izin animasi: AKTIF" : "Izin animasi: belum (lihat catatan)", 8, GpuEngine.canSecure(this) ? OK : WARN, true), mt(0));
        chips(c, "anim_scale", new String[]{"1.0x", "0.5x", "OFF"});
        btn1(c, "TERAPKAN ANIMASI", () -> {
            float[] v = {1f, .5f, 0f};
            int i = sp.getInt("anim_scale", 0);
            if (GpuEngine.setAnim(this, v[i])) { addLog("Animasi sistem " + (i == 2 ? "OFF" : v[i] + "x")); status("Animasi sistem " + (i == 2 ? "OFF" : v[i] + "x"), OK); }
            else status("Butuh izin sekali lewat adb (lihat catatan)", WARN);
        });

        btn1(c, "SALIN PERINTAH ADB", this::copyAdb);
        c.addView(note("Catatan jujur: aplikasi biasa tidak bisa overclock GPU atau ganti driver per game tanpa root. Yang dilakukan di sini nyata: kunci refresh rate, kurangi beban animasi, bersihkan RAM/cache, jaga suhu dan lapor GPU asli.\n\nIzin animasi (sekali): sambungkan debugging lalu jalankan\nadb shell pm grant com.imzyyknox.gameturbo android.permission.WRITE_SECURE_SETTINGS"), mt(6));
    }



    private void enableCleaner(boolean force) {
        AccessibilityGuard.markWanted(this, true);
        if (CleanerService.enableViaSecure(this)) { status("IMZYY Cleaner diaktifkan langsung • tap aksinya lagi", OK); return; }
        sp.edit().putBoolean("pending_cleaner", true).apply();
        if (content != null) showTab(TAB_CLEANER); else open(AccessibilityGuard.setupIntent(this));
        status("Ikuti panduan • bila tertulis 'tidak tersedia': Izinkan setelan terbatas", WARN);
        armPending();
    }




    private void pageGamePicker() {
        if (content == null) return;
        stopLive();
        content.removeAllViews();
        LinearLayout c = newPage();
        LinearLayout head = row();
        LinearLayout back = row(); back.setPadding(0, dp(4), dp(10), dp(4));
        back.addView(icon(IconView.BACK, 13, CY));
        TextView bt = txt("KEMBALI", 9, CY, true); bt.setPadding(dp(3), 0, 0, 0); back.addView(bt);
        back.setOnClickListener(v -> showTab(3));
        head.addView(back); head.addView(txt("PILIH GAME", 10, 0xFFFFFFFF, true));
        c.addView(head, mt(0));
        final PackageManager pm = getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Collections.sort(apps, (x, y) -> String.CASE_INSENSITIVE_ORDER.compare(String.valueOf(x.loadLabel(pm)), String.valueOf(y.loadLabel(pm))));
        HashSet<String> seen = new HashSet<>();
        for (ResolveInfo ri : apps) {
            final String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            final String label = String.valueOf(ri.loadLabel(pm));
            TextView t = txt(label, 10, 0xFFFFFFFF, true);
            t.setPadding(dp(10), dp(10), dp(10), dp(10));
            t.setBackground(bg(0xFF1A1A1D, 0xFF0E0E10, 10, 0x22FFFFFF));
            t.setOnClickListener(v -> {
                sp.edit().putString("saved_game_package", pkg).putString("saved_game_name", label).apply();
                showTab(3);
                status("Tersimpan: " + label, OK);
            });
            c.addView(t, mt(4));
        }
    }



    private void pageStyle() { pageMenu(); }



    private void pageTools() { pageCleaner(); }

}
