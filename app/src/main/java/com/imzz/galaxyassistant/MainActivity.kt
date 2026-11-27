package com.imzz.galaxyassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.imzz.galaxyassistant.accessibility.AccessibilityController
import com.imzz.galaxyassistant.accessibility.GalaxyAccessibilityService
import com.imzz.galaxyassistant.apps.AppInfo
import com.imzz.galaxyassistant.apps.AppLauncher
import com.imzz.galaxyassistant.apps.AppScanner
import com.imzz.galaxyassistant.apps.GameNavigationEngine
import com.imzz.galaxyassistant.apps.PackageResolver
import com.imzz.galaxyassistant.autopilot.AutopilotEngine
import com.imzz.galaxyassistant.autopilot.AiVisionApiClient
import com.imzz.galaxyassistant.mascot.CatBrain
import com.imzz.galaxyassistant.mascot.CatMascotView
import com.imzz.galaxyassistant.mascot.MeowSynth
import com.imzz.galaxyassistant.screen.ScreenCaptureManager
import com.imzz.galaxyassistant.screen.ScreenCaptureService
import com.imzz.galaxyassistant.storage.ControlProfile
import com.imzz.galaxyassistant.storage.GameMemory
import com.imzz.galaxyassistant.voice.VoiceFeedback
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val apps = mutableListOf<AppInfo>()
    private var activeApp: AppInfo? = null
    private var latestOcr = ""

    private lateinit var statusText: TextView
    private lateinit var permissionText: TextView
    private lateinit var activeAppText: TextView
    private lateinit var appCountText: TextView
    private lateinit var appList: LinearLayout
    private lateinit var activityLog: TextView
    private lateinit var screenText: TextView
    private lateinit var commandInput: EditText
    private lateinit var screenButton: Button
    private lateinit var cat: CatMascotView
    private val catHistory = mutableListOf<Pair<String, String>>()

    private val screenPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureManager.startService(this, result.resultCode, data)
            updatePermissionStatus()
            setStatus("Screen Share dimulai. Layar akan dianalisis sesuai izin yang lu berikan.")
        } else {
            setStatus("Izin Screen Share tidak diberikan. Analisis layar belum aktif.")
        }
    }

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = words?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                commandInput.setText(spoken)
                processCommand(spoken)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenCaptureService.ACTION_OCR_UPDATE -> {
                    latestOcr = intent.getStringExtra("text").orEmpty()
                    AutopilotEngine.updateOcr(latestOcr, intent.getStringExtra("layout").orEmpty())
                    screenText.text = if (latestOcr.isBlank()) "Belum ada teks yang terbaca dari layar." else latestOcr.take(850)
                }
                ScreenCaptureService.ACTION_CAPTURE_STATUS -> updatePermissionStatus()
                GalaxyAccessibilityService.ACTION_FOREGROUND_APP -> {
                    val pkg = intent.getStringExtra("packageName").orEmpty()
                    if (pkg.isNotBlank()) {
                        val found = apps.firstOrNull { it.packageName == pkg }
                        activeApp = found ?: runCatching {
                            val info = packageManager.getApplicationInfo(pkg, 0)
                            val label = packageManager.getApplicationLabel(info).toString()
                            AppInfo(pkg, label, "", com.imzz.galaxyassistant.apps.GameDetectionEngine.isProbablyGame(info, pkg, label))
                        }.getOrNull()
                        updatePermissionStatus()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 13, 24)
        window.navigationBarColor = Color.rgb(8, 13, 24)
        VoiceFeedback.initialize(this)
        buildInterface()
        registerScreenReceiver()
        setupCat()
        AutopilotEngine.setListener { state, message ->
            runOnUiThread {
                statusText.text = "${state.name.replace('_', ' ')}  •  $message"
                appendLog("${state.name}: $message")
                reactToAutopilot(state)
            }
        }
        updatePermissionStatus()
        scanApps()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        AutopilotEngine.setListener(null)
        VoiceFeedback.speakingListener = null
        VoiceFeedback.textListener = null
        runCatching { unregisterReceiver(screenReceiver) }
        backgroundExecutor.shutdown()
        if (isFinishing) VoiceFeedback.shutdown()
        super.onDestroy()
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(8, 13, 24))
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }
        scroll.addView(content)

        content.addView(label("GALAXY  /  IMZZ SYSTEM", 12, Color.rgb(151, 139, 255), true))
        content.addView(label("ASISTEN GALAXY IMZZ", 27, Color.WHITE, true).apply {
            setPadding(0, dp(5), 0, dp(3))
        })
        content.addView(label("AI GAME & APP AUTOPILOT", 13, Color.rgb(158, 171, 195), false))

        content.addView(buildCatCard(), marginParams(top = 16))

        val statusCard = card()
        statusCard.addView(label("AUTOPILOT STATUS", 11, Color.rgb(156, 169, 197), true))
        statusText = label("STOPPED  •  Siap menerima perintah bre.", 14, Color.WHITE, true)
        statusText.setPadding(0, dp(9), 0, 0)
        statusCard.addView(statusText)
        permissionText = label("Accessibility: belum aktif  •  Screen Share: belum aktif", 11, Color.rgb(156, 169, 197), false)
        permissionText.setPadding(0, dp(8), 0, 0)
        statusCard.addView(permissionText)
        activeAppText = label("Aplikasi aktif: belum terdeteksi", 11, Color.rgb(156, 169, 197), false)
        activeAppText.setPadding(0, dp(6), 0, 0)
        statusCard.addView(activeAppText)
        content.addView(statusCard, marginParams(top = 18, bottom = 12))

        val topActions = row()
        topActions.addView(actionButton("SCAN APPS") { scanApps() }, weightParams())
        topActions.addView(actionButton("API STATUS") { showAiSettings() }, weightParams(left = 8))
        content.addView(topActions)

        val permissionActions = row()
        permissionActions.addView(actionButton("ACCESSIBILITY") { AccessibilityController.openSettings(this) }, weightParams())
        screenButton = actionButton("SCREEN SHARE") { toggleScreenCapture() }
        permissionActions.addView(screenButton, weightParams(left = 8))
        content.addView(permissionActions, marginParams(top = 8))

        content.addView(sectionTitle("CONTROL CENTER"), marginParams(top = 22, bottom = 8))
        val controls = card()
        val first = row()
        first.addView(actionButton("▶ AUTOPILOT") { requestAutopilot(assist = false) }, weightParams())
        first.addView(actionButton("AI ASSIST") { requestAutopilot(assist = true) }, weightParams(left = 8))
        controls.addView(first)
        val second = row()
        second.addView(actionButton("PAUSE") { AutopilotEngine.pause("Dijeda dari dashboard.") }, weightParams())
        second.addView(actionButton("RESUME") { AutopilotEngine.resume() }, weightParams(left = 8))
        second.addView(actionButton("MANUAL") {
            AutopilotEngine.stop("Mode Manual Control aktif. Kendali dikembalikan ke pengguna.")
            setStatus("MANUAL CONTROL  •  AI tidak mengirim gesture.")
        }, weightParams(left = 8))
        controls.addView(second, marginParams(top = 8))
        controls.addView(actionButton("EMERGENCY STOP", danger = true) { emergencyStop() }, marginParams(top = 8))
        content.addView(controls)

        content.addView(sectionTitle("VOICE & CHAT COMMAND"), marginParams(top = 22, bottom = 8))
        val chatCard = card()
        val inputRow = row()
        commandInput = EditText(this).apply {
            hint = "Contoh: Buka Roblox / cari DDS / stop"
            setHintTextColor(Color.rgb(113, 128, 155))
            setTextColor(Color.WHITE)
            textSize = 13f
            singleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.rgb(25, 35, 55), Color.rgb(47, 62, 91), 12)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    processCommand(text.toString())
                    true
                } else false
            }
        }
        inputRow.addView(commandInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        inputRow.addView(actionButton("SEND") { processCommand(commandInput.text.toString()) }, LinearLayout.LayoutParams(dp(72), dp(48)).apply { leftMargin = dp(8) })
        chatCard.addView(inputRow)
        chatCard.addView(actionButton("🎙  VOICE COMMAND") { startVoiceInput() }, marginParams(top = 8))
        content.addView(chatCard)

        content.addView(sectionTitle("SMART APP & GAME SCANNER"), marginParams(top = 22, bottom = 8))
        appCountText = label("Belum ada hasil scan.", 12, Color.rgb(156, 169, 197), false)
        content.addView(appCountText)
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(appList, marginParams(top = 8))

        content.addView(sectionTitle("SCREEN UNDERSTANDING / OCR"), marginParams(top = 22, bottom = 8))
        val screenCard = card()
        screenText = label("Aktifkan Screen Share untuk membaca teks layar. Screenshot hanya diambil setelah persetujuan sistem Android.", 12, Color.rgb(196, 207, 225), false)
        screenText.maxLines = 12
        screenCard.addView(screenText)
        content.addView(screenCard)

        content.addView(sectionTitle("ACTIVITY LOG"), marginParams(top = 22, bottom = 8))
        val logCard = card()
        activityLog = label("Galaxy siap. Scan aplikasi untuk memulai.", 11, Color.rgb(173, 188, 212), false)
        logCard.addView(activityLog)
        content.addView(logCard)

        content.addView(actionButton("CONTROL TUNING") { showControlTuning() }, marginParams(top = 18))
        content.addView(label("Kontrol otomatis dibatasi izin Android, batas sesi 10 menit / 120 aksi, dan emergency stop. Tidak ada bypass anti-cheat atau aksi sensitif otomatis.", 10, Color.rgb(116, 132, 159), false).apply {
            setPadding(dp(2), dp(16), dp(2), 0)
        })

        setContentView(scroll)
    }

    private fun scanApps() {
        setStatus("SCANNING APPS  •  Lagi memindai aplikasi dan game terinstal bre.")
        appCountText.text = "Sedang memindai launcher aplikasi..."
        backgroundExecutor.execute {
            val found = runCatching { AppScanner.scan(applicationContext) }.getOrElse { emptyList() }
            runOnUiThread {
                apps.clear()
                apps.addAll(found)
                appCountText.text = "${found.size} aplikasi ditemukan  •  ${found.count { it.isGame }} terdeteksi sebagai game"
                renderApps(found)
                appendLog("Scan selesai: ${found.size} aplikasi.")
                setStatus("SCAN COMPLETE  •  Database lokal diperbarui.")
            }
        }
    }

    private fun renderApps(found: List<AppInfo>) {
        appList.removeAllViews()
        found.take(35).forEach { app ->
            val item = row().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.rgb(17, 26, 43), Color.rgb(34, 48, 72), 15)
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
                runCatching { setImageDrawable(packageManager.getApplicationIcon(app.packageName)) }
            }
            item.addView(icon)
            val textColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(5), 0)
            }
            textColumn.addView(label(app.label, 13, Color.WHITE, true))
            textColumn.addView(label("${if (app.isGame) "GAME" else "APP"}  •  ${app.packageName}", 9, Color.rgb(137, 153, 181), false))
            item.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val open = TextView(this).apply {
                text = "OPEN"
                setTextColor(Color.rgb(178, 168, 255))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(9), dp(9), dp(5), dp(9))
                setOnClickListener { launchApp(app) }
            }
            item.addView(open)
            item.setOnClickListener { launchApp(app) }
            appList.addView(item, marginParams(bottom = 7))
        }
    }

    private fun processCommand(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        val addressed = Regex("^(skylen|sky|len|meong|neko|kucing|cat)[,!:\\s]+(.+)$", RegexOption.IGNORE_CASE).find(trimmed)
        val addressedToCat = addressed != null
        val command = addressed?.groupValues?.get(2)?.trim()?.ifBlank { trimmed } ?: trimmed
        appendLog("YOU: $trimmed")
        commandInput.setText("")
        val lower = command.lowercase(Locale.ROOT)

        when {
            lower in listOf("stop", "berhenti", "berhenti dulu", "stop dulu", "emergency stop") -> {
                emergencyStop()
                return
            }
            lower in listOf("pause", "jeda", "pause dulu") -> {
                AutopilotEngine.pause("Dijeda lewat voice/chat command.")
                return
            }
            lower in listOf("resume", "lanjut", "lanjutkan") -> {
                AutopilotEngine.resume()
                return
            }
            lower.contains("scan aplikasi") || lower == "scan apps" -> {
                scanApps()
                return
            }
        }

        val openMatch = Regex("^(buka|open|jalankan|launch)\\s+(.+)$", RegexOption.IGNORE_CASE).find(command)
        if (openMatch != null) {
            val fullTarget = openMatch.groupValues[2].trim()
            val composite = Regex("^(.+?)\\s+(?:dan|and)\\s+(.+)$", RegexOption.IGNORE_CASE).find(fullTarget)
            val query = composite?.groupValues?.get(1)?.trim() ?: fullTarget
            val followUp = composite?.groupValues?.get(2)?.trim()
            val matches = PackageResolver.resolve(query, apps)
            when {
                matches.isEmpty() -> {
                    setStatus("Aplikasi '$query' belum ditemukan. Tekan SCAN APPS dan pastikan sudah terinstal.")
                    VoiceFeedback.speak("Bre, aplikasi $query belum ketemu. Gw scan ulang dulu ya.")
                    scanApps()
                }
                matches.size > 1 -> showAppChoices(query, matches.take(6), followUp)
                else -> launchApp(matches.first(), followUp)
            }
            return
        }

        val current = activeApp?.takeIf { it.packageName != packageName }
        if (!addressedToCat && current != null && current.label.contains("roblox", ignoreCase = true) && !isGenericGameplayCommand(lower)) {
            val goal = GameNavigationEngine.contextualGoal(current.label, command)
            requestAutopilotWithGoal(goal, assist = false)
            return
        }

        if (lower.contains("mainin") || lower.contains("main bebas") || lower.contains("jalan-jalan") || lower.contains("keliling map") || lower.contains("spawn") || lower.contains("cari mobil") || lower.contains("play for me")) {
            requestAutopilotWithGoal(command, assist = false)
            return
        }

        if (current != null && !addressedToCat && !looksLikeChat(lower)) {
            requestAutopilotWithGoal("Di aplikasi ${current.label}, lakukan instruksi pengguna dengan aman: $command", assist = true)
        } else {
            chatWithCat(command)
        }
    }

    private fun looksLikeChat(lower: String): Boolean =
        lower.endsWith("?") || Regex("\\b(halo|hai|hi|hello|woi|makasih|terima kasih|siapa|apa kabar|joget|lompat|jalan|tidur)\\b").containsMatchIn(lower)

    // ---------------------------------------------------------------- SKYLEN (maskot kucing putih)

    private fun buildCatCard(): LinearLayout {
        val catCard = card().apply { setPadding(dp(8), dp(8), dp(8), dp(10)) }
        cat = CatMascotView(this)
        val saved = getSharedPreferences("skylen", Context.MODE_PRIVATE).getString("outfit", null)
        runCatching { CatMascotView.Outfit.valueOf(saved.orEmpty()) }.getOrNull()?.let { cat.setOutfit(it) }
        catCard.addView(cat, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))

        val buttons = row()
        buttons.addView(actionButton("👕 GAYA") { changeOutfit() }, weightParams())
        buttons.addView(actionButton("✨ AKSI") {
            val picked = cat.playRandomAction()
            if (picked == CatMascotView.Action.DANCE) cat.setMood(CatMascotView.Mood.HAPPY, 3400L)
        }, weightParams(left = 8))
        buttons.addView(actionButton("🐾 MEOW") {
            MeowSynth.meow(0.9f + (Math.random().toFloat() * 0.3f))
            cat.playAction(CatMascotView.Action.HOP)
            cat.say("Meow~", 1600L)
        }, weightParams(left = 8))
        catCard.addView(buttons, marginParams(top = 6))
        catCard.addView(
            label("Usap kepala ${CatBrain.NAME}, ketuk badannya, atau ketik: \"skylen, joget\" / \"skylen, apa kabar?\"", 10, Color.rgb(116, 132, 159), false)
                .apply { setPadding(dp(4), dp(8), dp(4), 0) }
        )
        return catCard
    }

    private fun showLongAnswer(full: String) {
        val body = TextView(this).apply {
            text = full
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("${CatBrain.NAME} menjawab")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Salin") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Skylen", full))
                Toast.makeText(this, "Jawaban disalin.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun setupCat() {
        cat.onPet = { count ->
            MeowSynth.purr()
            if (count % 3 == 1) cat.say(CatBrain.petLines.random(), 2600L)
        }
        cat.onBodyTap = {
            MeowSynth.meow(1.1f)
            cat.say(listOf("Meow!", "Hup!", "Nyaa~", "Eh, kaget gw bre!").random(), 1600L)
        }
        VoiceFeedback.speakingListener = { on -> runOnUiThread { cat.setSpeaking(on) } }
        VoiceFeedback.textListener = { text ->
            runOnUiThread {
                cat.say(text)
                CatBrain.moodForText(text)?.let { cat.setMood(it, 3500L) }
            }
        }
        android.os.Handler(mainLooper).postDelayed({
            cat.playAction(CatMascotView.Action.WAVE)
            cat.setMood(CatMascotView.Mood.HAPPY, 3000L)
            VoiceFeedback.speak("Meow! Halo bre, gw ${CatBrain.NAME}. Usap kepala gw atau ketik skylen, lalu pesan lu, buat ngobrol ya!", decorate = false)
        }, 1400L)
    }

    private fun changeOutfit() {
        val outfit = cat.cycleOutfit()
        getSharedPreferences("skylen", Context.MODE_PRIVATE).edit().putString("outfit", outfit.name).apply()
        cat.setMood(CatMascotView.Mood.HAPPY, 2200L)
        cat.say("Gaya baru: ${outfit.label}! Cakep kan bre?", 2600L)
    }

    private fun reactToAutopilot(state: AutopilotEngine.State) {
        if (!::cat.isInitialized) return
        val mood = when (state) {
            AutopilotEngine.State.PLANNING -> CatMascotView.Mood.THINKING
            AutopilotEngine.State.RUNNING -> CatMascotView.Mood.ALERT
            AutopilotEngine.State.PAUSED -> CatMascotView.Mood.SLEEPY
            AutopilotEngine.State.WAITING_FOR_USER -> CatMascotView.Mood.SURPRISED
            AutopilotEngine.State.ERROR -> CatMascotView.Mood.SAD
            AutopilotEngine.State.STOPPED -> CatMascotView.Mood.IDLE
        }
        cat.setMood(mood)
        if (state == AutopilotEngine.State.WAITING_FOR_USER) cat.playAction(CatMascotView.Action.HEAD_TILT)
    }

    private fun chatWithCat(message: String) {
        val instant = CatBrain.quickAction(message)
        if (instant != null) cat.playAction(instant)
        cat.setMood(if (instant == CatMascotView.Action.DANCE) CatMascotView.Mood.HAPPY else CatMascotView.Mood.THINKING, 7000L)
        catHistory.add("user" to message)
        CatBrain.chat(message, catHistory.takeLast(6)) { reply ->
            runOnUiThread {
                catHistory.add("cat" to reply.text)
                while (catHistory.size > 12) catHistory.removeAt(0)
                VoiceFeedback.speak(reply.text, decorate = false)
                reply.mood?.let { cat.setMood(it, 4000L) }
                reply.action?.let { cat.playAction(it) }
                if (reply.fullText.length > reply.text.length + 20) showLongAnswer(reply.fullText)
                appendLog("${CatBrain.NAME.uppercase()}${if (reply.fromApi) "" else " (offline)"}: ${reply.text}")
            }
        }
    }

    private fun isGenericGameplayCommand(command: String): Boolean =
        listOf("mainin", "main bebas", "spawn", "jalan", "keliling", "stop", "berhenti", "pause", "resume").any { command.contains(it) }

    private fun launchApp(app: AppInfo, followUpCommand: String? = null) {
        setStatus("OPENING APPLICATION  •  Membuka ${app.label}...")
        val result = AppLauncher.launch(this, app)
        if (result.success) {
            activeApp = app
            updatePermissionStatus()
            setStatus("APPLICATION REQUESTED  •  ${app.label} sudah dikirim ke Android untuk dibuka. Verifikasi foreground bergantung pada sistem.")
            appendLog("OPEN: ${app.label} (${app.packageName})")
            if (app.label.contains("roblox", ignoreCase = true)) {
                VoiceFeedback.speak("Baik bre, Roblox udah gw buka nih. Lu mau main game apa? Drag Drive Simulator, Untitled Boxing Game, atau yang lainnya?")
            } else {
                VoiceFeedback.speak("Baik bre, ${app.label} udah gw buka nih.")
            }
            if (!followUpCommand.isNullOrBlank()) {
                android.os.Handler(mainLooper).postDelayed({
                    requestAutopilotWithGoal("Setelah membuka ${app.label}, lakukan perintah ini lewat UI yang terlihat: $followUpCommand", assist = false)
                }, 1800L)
            }
        } else {
            setStatus(result.message)
            VoiceFeedback.speak(result.message)
        }
    }

    private fun showAppChoices(query: String, choices: List<AppInfo>, followUpCommand: String? = null) {
        AlertDialog.Builder(this)
            .setTitle("Pilih aplikasi untuk '$query'")
            .setItems(choices.map { "${it.label}  (${it.packageName})" }.toTypedArray()) { _, which -> launchApp(choices[which], followUpCommand) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun requestAutopilot(assist: Boolean) {
        val appName = activeApp?.label ?: "aplikasi yang sedang terbuka"
        requestAutopilotWithGoal("Bantu navigasi $appName berdasarkan tampilan yang terlihat. Amati layar sebelum setiap aksi.", assist)
    }

    private fun requestAutopilotWithGoal(goal: String, assist: Boolean) {
        if (!ScreenCaptureService.isRunning) {
            setStatus("Screen Share belum aktif. Tekan SCREEN SHARE dan setujui dialog Android terlebih dahulu.")
            VoiceFeedback.speak("Aktifkan Screen Share dulu bre, biar gw bisa lihat layar.")
            return
        }
        if (!AiVisionApiClient.isConfigured()) {
            showAiSettings()
            setStatus("Endpoint AI API belum dikonfigurasi. Autopilot belum bisa dimulai.")
            return
        }
        if (!assist && !GalaxyAccessibilityService.isAvailable()) {
            setStatus("Accessibility Service belum aktif. Mode Autopilot perlu izin gesture Android.")
            AccessibilityController.openSettings(this)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (assist) "Mulai AI Assist?" else "Mulai Galaxy Autopilot?")
            .setMessage("Teks OCR layar (plus screenshot kalau API-nya mendukung gambar) akan dikirim ke endpoint AI API yang dikonfigurasi. Aplikasi tidak meminta atau menyimpan API key. AI hanya boleh menjalankan navigasi yang tidak sensitif. Batas sesi 10 menit / 120 aksi. Tetap pantau layar dan gunakan Emergency Stop kapan saja.\n\nTujuan: $goal")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Setuju & mulai") { _, _ ->
                AutopilotEngine.start(this, goal, assist)
                appendLog(if (assist) "AI ASSIST STARTED" else "AUTOPILOT STARTED")
            }
            .show()
    }

    private fun toggleScreenCapture() {
        if (ScreenCaptureService.isRunning) {
            AutopilotEngine.stop("Screen Share dihentikan oleh pengguna.")
            stopService(Intent(this, ScreenCaptureService::class.java))
            setStatus("Screen Share dihentikan.")
            updatePermissionStatus()
        } else {
            screenPermissionLauncher.launch(ScreenCaptureManager.createPermissionIntent(this))
        }
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_REQUEST)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ucapkan perintah untuk Galaxy Autopilot")
        }
        runCatching { voiceLauncher.launch(intent) }.onFailure {
            Toast.makeText(this, "Voice recognition tidak tersedia di perangkat ini.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAiSettings() {
        fun host(value: String): String =
            if (value.isBlank()) "belum diisi" else runCatching { java.net.URL(value).host }.getOrDefault("URL tidak valid")
        val count = AiVisionApiClient.endpoints().size
        AlertDialog.Builder(this)
            .setTitle("AI API STATUS")
            .setMessage(
                if (count > 0) {
                    "$count endpoint aktif.\n\n" +
                        "API #1 (Autopilot, baca layar lewat OCR): ${host(AiVisionApiClient.endpoint())}\n" +
                        "API #2 (cadangan + ngobrol ${CatBrain.NAME}): ${host(AiVisionApiClient.endpoint2())}\n\n" +
                        "Autopilot memakai API #1 lalu pindah otomatis ke API #2 kalau gagal. " +
                        "${CatBrain.NAME} ngobrol lewat API #2 dulu, lalu API #1, dan kalau dua-duanya mati dia tetap jawab offline. " +
                        "Tidak ada kolom API key di aplikasi."
                } else {
                    "Belum ada endpoint API yang diatur di build ini.\n\n" +
                        "Isi galaxyAiApiUrl (API #1) dan galaxyAiApiUrl2 (API #2) di Gradle, atau variable GALAXY_AI_API_URL dan " +
                        "GALAXY_AI_API_URL_2 di GitHub Actions. ${CatBrain.NAME} tetap bisa bicara dan bergerak tanpa API (mode offline)."
                }
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showControlTuning() {
        val profilePackage = activeApp?.packageName
        val existing = GameMemory.profile(this, profilePackage)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(5), dp(20), 0)
        }
        val tapLabel = label("Tap duration: ${existing.tapDurationMs} ms", 12, Color.WHITE, false)
        val tap = SeekBar(this).apply { max = 760; progress = (existing.tapDurationMs - 40L).toInt() }
        tap.setOnSeekBarChangeListener(simpleSeek { progress -> tapLabel.text = "Tap duration: ${progress + 40} ms" })
        val swipeLabel = label("Swipe duration: ${existing.swipeDurationMs} ms", 12, Color.WHITE, false)
        val swipe = SeekBar(this).apply { max = 1720; progress = (existing.swipeDurationMs - 80L).toInt() }
        swipe.setOnSeekBarChangeListener(simpleSeek { progress -> swipeLabel.text = "Swipe duration: ${progress + 80} ms" })
        val sensitivityLabel = label("Touch sensitivity: ${"%.1f".format(existing.sensitivity)}x", 12, Color.WHITE, false)
        val sensitivity = SeekBar(this).apply { max = 16; progress = ((existing.sensitivity - 0.4f) * 10).toInt() }
        sensitivity.setOnSeekBarChangeListener(simpleSeek { progress -> sensitivityLabel.text = "Touch sensitivity: ${"%.1f".format(0.4f + progress / 10f)}x" })
        layout.addView(tapLabel); layout.addView(tap)
        layout.addView(swipeLabel); layout.addView(swipe)
        layout.addView(sensitivityLabel); layout.addView(sensitivity)
        AlertDialog.Builder(this)
            .setTitle("Game Control Profile")
            .setView(layout)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                GameMemory.saveProfile(
                    this,
                    ControlProfile(
                        tapDurationMs = tap.progress + 40L,
                        swipeDurationMs = swipe.progress + 80L,
                        sensitivity = 0.4f + sensitivity.progress / 10f
                    ),
                    profilePackage
                )
                setStatus("Control profile tersimpan lokal.")
            }
            .show()
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun emergencyStop() {
        AutopilotEngine.stop("EMERGENCY STOP aktif. Kontrol otomatis dihentikan.")
        if (::cat.isInitialized) cat.setMood(CatMascotView.Mood.ANGRY, 3000L)
        stopService(Intent(this, ScreenCaptureService::class.java))
        VoiceFeedback.speak("Stop bre. Semua kontrol otomatis berhenti.")
        setStatus("EMERGENCY STOP  •  Semua aksi otomatis dihentikan.")
        appendLog("EMERGENCY STOP")
        updatePermissionStatus()
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_OCR_UPDATE)
            addAction(ScreenCaptureService.ACTION_CAPTURE_STATUS)
            addAction(GalaxyAccessibilityService.ACTION_FOREGROUND_APP)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun updatePermissionStatus() {
        val accessibilityOn = GalaxyAccessibilityService.isAvailable()
        val screenOn = ScreenCaptureService.isRunning
        if (::permissionText.isInitialized) {
            permissionText.text = "Accessibility: ${if (accessibilityOn) "aktif" else "belum aktif"}  •  Screen Share: ${if (screenOn) "aktif" else "belum aktif"}"
            permissionText.setTextColor(if (accessibilityOn && screenOn) Color.rgb(112, 230, 177) else Color.rgb(156, 169, 197))
        }
        if (::screenButton.isInitialized) screenButton.text = if (screenOn) "STOP SCREEN" else "SCREEN SHARE"
        if (::activeAppText.isInitialized) {
            val name = activeApp?.label ?: GalaxyAccessibilityService.currentPackageName.takeIf { it.isNotBlank() } ?: "belum terdeteksi"
            activeAppText.text = "Aplikasi aktif: $name"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
        appendLog(message)
    }

    private fun appendLog(message: String) {
        if (!::activityLog.isInitialized) return
        val old = activityLog.text.toString().lineSequence().takeLast(7).joinToString("\n")
        activityLog.text = (old + "\n• " + message).takeLast(1700)
    }

    private fun sectionTitle(value: String) = label(value, 12, Color.rgb(155, 147, 255), true)
    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.rgb(18, 28, 47), Color.rgb(39, 53, 80), 18)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun actionButton(text: String, danger: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        minHeight = dp(46)
        minimumHeight = dp(46)
        background = rounded(
            if (danger) Color.rgb(119, 43, 67) else Color.rgb(57, 54, 111),
            if (danger) Color.rgb(190, 73, 100) else Color.rgb(117, 103, 204),
            13
        )
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = dp(radius).toFloat()
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun weightParams(left: Int = 0) = LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(left) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCREEN_PERMISSION_REQUEST = 7302
        private const val AUDIO_PERMISSION_REQUEST = 7303
        private const val NOTIFICATION_PERMISSION_REQUEST = 7304
    }
}
