package id.imzyy.xl

import android.app.Activity
import android.os.Build
import android.view.Window
import kotlin.math.roundToInt

/** Frame-clock helper for the launcher UI only; the Minecraft Surface/renderer is untouched. */
object ImzyyFramePacing {
    @Volatile
    var refreshRateHz: Float = 60f
        private set

    @Suppress("DEPRECATION")
    fun syncToDisplay(window: Window, activity: Activity) {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null
        val detected = display?.refreshRate ?: activity.windowManager.defaultDisplay.refreshRate
        val rate = detected.takeIf { it.isFinite() && it >= 30f } ?: 60f
        refreshRateHz = rate

        // preferredRefreshRate is a hint to Android's compositor, not an FPS guarantee.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val attrs = window.attributes
                attrs.preferredRefreshRate = rate
                window.attributes = attrs
            }
        }
    }

    /** Round a motion duration to a whole number of frames at the active display rate. */
    fun durationMs(baseMillis: Int): Int {
        val rate = refreshRateHz.takeIf { it.isFinite() && it >= 30f } ?: 60f
        val frames = (baseMillis.coerceAtLeast(1) * rate / 1000f).roundToInt().coerceAtLeast(1)
        return (frames * 1000f / rate).roundToInt().coerceAtLeast(1)
    }
}
