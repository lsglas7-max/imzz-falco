package id.imzyy.xl

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kr.co.donghyun.flamelauncher.presentation.MinecraftActivity

/**
 * Real fullscreen for the game: draws into the display cutout (no black bar on the notch side)
 * and hides system bars. Applied to every activity except the IMZYY hub and the launcher menu.
 */
object ImzyyWindow {
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                apply(a)
                if (a is MinecraftActivity) runCatching { ImzyyBootGuard.onGameCreated(a) }
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                apply(a)
                if (a is MinecraftActivity) a.window.decorView.post { ImzyyPad.attach(a) }
            }
            override fun onActivityPaused(a: Activity) {
                if (a is MinecraftActivity) ImzyyPad.releaseAll(a)
            }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                if (a is MinecraftActivity) runCatching { ImzyyBootGuard.onGameDestroyed(a) }
            }
        })
    }

    private fun isMenu(a: Activity): Boolean {
        val n = a.javaClass.name
        return n.endsWith("ImzyyHubActivity") || n == ImzyyConfig.MAIN_ACTIVITY
    }

    fun apply(a: Activity) {
        if (isMenu(a)) return
        try {
            val w = a.window
            if (Build.VERSION.SDK_INT >= 28) {
                val lp = w.attributes
                lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                w.attributes = lp
            }
            WindowCompat.setDecorFitsSystemWindows(w, false)
            val c = WindowInsetsControllerCompat(w, w.decorView)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Throwable) {
        }
    }
}
