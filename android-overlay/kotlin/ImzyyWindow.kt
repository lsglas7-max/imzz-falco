package id.imzyy.xl

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kr.co.donghyun.flamelauncher.presentation.MinecraftActivity

/**
 * Keep game-window sizing under the upstream MinecraftActivity/SDL owner.
 * SurfaceView/SDL needs the exact viewport it measured when its native renderer starts;
 * changing decor fitting or display-cutout policy from a lifecycle callback can desync
 * that viewport on some Android builds. This class only installs IMZYY lifecycle hooks.
 */
object ImzyyWindow {
    private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: Bundle?) {
                if (a is MinecraftActivity) runCatching { ImzyyBootGuard.onGameCreated(a) }
            }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {
                if (a is MinecraftActivity) {
                    // Attach only after the upstream game decor and SurfaceView are laid out.
                    a.window.decorView.post { ImzyyPad.attach(a) }
                }
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
}
