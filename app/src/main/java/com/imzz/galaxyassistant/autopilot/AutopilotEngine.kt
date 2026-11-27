package com.imzz.galaxyassistant.autopilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.imzz.galaxyassistant.accessibility.GalaxyAccessibilityService
import com.imzz.galaxyassistant.screen.ScreenCaptureService
import com.imzz.galaxyassistant.voice.VoiceFeedback
import java.util.concurrent.Executors

object AutopilotEngine {
    enum class State { STOPPED, PLANNING, RUNNING, PAUSED, WAITING_FOR_USER, ERROR }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var state = State.STOPPED
    @Volatile private var listener: ((State, String) -> Unit)? = null
    private var appContext: Context? = null
    private var goal = ""
    private var latestOcr = ""
    private var latestLayout = ""
    private var latestScene = ""
    private var history = mutableListOf<String>()
    private var startedAt = 0L
    private var actionCount = 0
    private var assistOnly = false

    private const val MAX_DURATION_MS = 10 * 60 * 1000L
    private const val MAX_ACTIONS = 120

    fun setListener(callback: ((State, String) -> Unit)?) {
        listener = callback
    }

    fun start(context: Context, userGoal: String, assist: Boolean = false) {
        val app = context.applicationContext
        if (!ScreenCaptureService.isRunning) {
            publish(State.ERROR, "Aktifkan Screen Share terlebih dahulu.")
            return
        }
        if (!assist && GalaxyAccessibilityService.instance == null) {
            publish(State.ERROR, "Aktifkan Galaxy Accessibility di pengaturan Android.")
            return
        }
        if (!AiVisionApiClient.isConfigured()) {
            publish(State.ERROR, "AI API endpoint belum dikonfigurasi di build.")
            return
        }
        appContext = app
        goal = userGoal.ifBlank { "Bantu navigasi aplikasi yang sedang terbuka." }
        assistOnly = assist
        startedAt = System.currentTimeMillis()
        actionCount = 0
        history.clear()
        state = State.PLANNING
        publish(State.PLANNING, if (assist) "AI Assist sedang menganalisis layar melalui API." else "AI Autopilot mulai menyusun langkah melalui API.")
        scheduleStep(500L)
    }

    fun updateOcr(text: String, layout: String = "", scene: String = "") {
        latestScene = scene.take(1_200)
        latestOcr = text.take(12_000)
        latestLayout = layout.take(6_000)
    }

    fun pause(reason: String = "Dijeda oleh pengguna.") {
        if (state == State.RUNNING || state == State.PLANNING) {
            state = State.PAUSED
            mainHandler.removeCallbacksAndMessages(null)
            publish(State.PAUSED, reason)
        }
    }

    fun resume() {
        if (state == State.PAUSED || state == State.WAITING_FOR_USER) {
            state = State.PLANNING
            publish(State.PLANNING, "AI melanjutkan analisis layar.")
            scheduleStep(300L)
        }
    }

    fun stop(reason: String = "Dihentikan.") {
        mainHandler.removeCallbacksAndMessages(null)
        state = State.STOPPED
        publish(State.STOPPED, reason)
    }

    fun currentState(): State = state

    private fun scheduleStep(delay: Long) {
        mainHandler.postDelayed({ step() }, delay)
    }

    private fun step() {
        val context = appContext ?: return stop("Konteks aplikasi tidak tersedia.")
        if (state == State.PAUSED || state == State.STOPPED || state == State.WAITING_FOR_USER) return
        if (System.currentTimeMillis() - startedAt > MAX_DURATION_MS || actionCount >= MAX_ACTIONS) {
            stop("Batas sesi tercapai. Kontrol otomatis dihentikan.")
            VoiceFeedback.speak("Batas sesi autopilot tercapai bre. Gw berhenti dulu.")
            return
        }
        val frame = ScreenCaptureService.latestFrame(context)
        if (!frame.exists() || frame.length() == 0L || System.currentTimeMillis() - frame.lastModified() > 12_000L) {
            state = State.RUNNING
            publish(State.RUNNING, "Menunggu screenshot baru dari Screen Share.")
            scheduleStep(1500L)
            return
        }

        state = State.PLANNING
        publish(State.PLANNING, "OBSERVE → UNDERSTAND → PLAN")
        worker.execute {
            try {
                val planner = AutopilotPlanner(context)
                val planned = planner.next(goal, latestOcr, latestLayout, latestScene, history.takeLast(8).joinToString("\n"))
                mainHandler.post { applyPlan(context, planned) }
            } catch (error: Exception) {
                mainHandler.post {
                    state = State.ERROR
                    publish(State.ERROR, "AI gagal menganalisis: ${error.message ?: "kesalahan jaringan"}")
                    VoiceFeedback.speak("Analisis AI gagal bre. Cek koneksi dan endpoint API dulu.")
                }
            }
        }
    }

    private fun applyPlan(context: Context, action: PlannedAction) {
        if (state == State.PAUSED || state == State.STOPPED) return
        if (action.speech.isNotBlank()) VoiceFeedback.speak(action.speech)
        history += "${action.type}: ${action.reason}".take(350)

        if (assistOnly) {
            val message = action.speech.ifBlank { action.reason.ifBlank { "Layar dianalisis. Belum ada saran tambahan." } }
            state = State.WAITING_FOR_USER
            publish(State.WAITING_FOR_USER, "AI Assist: $message")
            VoiceFeedback.speak(message)
            return
        }

        when (action.type) {
            "stop" -> stop(action.reason.ifBlank { "AI memutuskan berhenti." })
            "ask_user" -> {
                state = State.WAITING_FOR_USER
                val message = action.reason.ifBlank { "AI butuh konfirmasi sebelum melanjutkan." }
                publish(State.WAITING_FOR_USER, message)
                VoiceFeedback.speak(message)
            }
            "wait" -> {
                state = State.RUNNING
                publish(State.RUNNING, "Menunggu perubahan layar: ${action.reason}")
                scheduleStep(action.waitMs)
            }
            else -> {
                when (val result = ActionExecutor.execute(context, action, latestOcr, latestLayout)) {
                    ActionExecutor.Result.Success -> {
                        actionCount++
                        state = State.RUNNING
                        publish(State.RUNNING, "VERIFY: ${action.reason.ifBlank { "Aksi dikirim; memeriksa perubahan layar." }} (aksi $actionCount/$MAX_ACTIONS)")
                        scheduleStep(1600L)
                    }
                    is ActionExecutor.Result.Failed -> {
                        state = State.ERROR
                        publish(State.ERROR, result.message)
                        VoiceFeedback.speak(result.message)
                    }
                    is ActionExecutor.Result.NeedsHuman -> {
                        state = State.WAITING_FOR_USER
                        publish(State.WAITING_FOR_USER, result.message)
                        VoiceFeedback.speak(result.message)
                    }
                }
            }
        }
    }

    private fun publish(newState: State, message: String) {
        state = newState
        mainHandler.post { listener?.invoke(newState, message) }
    }
}
