package com.imzz.galaxyassistant.screen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.imzz.galaxyassistant.EmergencyStopReceiver
import com.imzz.galaxyassistant.MainActivity
import com.imzz.galaxyassistant.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastFrameAt = 0L
    private val processing = AtomicBoolean(false)
    private var latestFile: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, ActivityResult.RESULT_CANCELED)
            ?: ActivityResult.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != ActivityResult.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startProjection(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val channelId = "galaxy_screen_capture"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Galaxy screen analysis", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val stopIntent = PendingIntent.getBroadcast(
            this, 41,
            Intent(this, EmergencyStopReceiver::class.java).setAction(ACTION_EMERGENCY_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 42, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Galaxy Autopilot aktif")
            .setContentText("Analisis layar berjalan atas izin lu.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtLeast(1)
            val height = metrics.heightPixels.coerceAtLeast(1)
            val density = metrics.densityDpi

            handlerThread = HandlerThread("GalaxyScreenReader").apply { start() }
            handler = Handler(handlerThread!!.looper)
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader -> onFrame(reader.acquireLatestImage()) }, handler)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, handler)
            latestFile = File(cacheDir, "galaxy_latest_screen.png")
            virtualDisplay = projection?.createVirtualDisplay(
                "GalaxyAutopilotDisplay", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
            isRunning = true
            sendBroadcast(Intent(ACTION_CAPTURE_STATUS).setPackage(packageName).putExtra("running", true))
        } catch (error: Exception) {
            isRunning = false
            stopSelf()
        }
    }

    private fun onFrame(image: Image?) {
        image ?: return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 1200L || !processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastFrameAt = now
        val bitmap = try {
            image.toBitmap()
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
        if (bitmap == null) {
            processing.set(false)
            return
        }

        try {
            latestFile?.let { file ->
                val temporary = File(cacheDir, "galaxy_latest_screen.tmp")
                FileOutputStream(temporary).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
                if (file.exists()) file.delete()
                temporary.renameTo(file)
            }
            val scene = runCatching { SceneSummarizer.summarize(bitmap) }.getOrDefault("")
            OcrProcessor.recognize(bitmap) { text, layout ->
                sendBroadcast(
                    Intent(ACTION_OCR_UPDATE).setPackage(packageName)
                        .putExtra("text", text)
                        .putExtra("layout", layout)
                        .putExtra("scene", scene)
                        .putExtra("capturedAt", System.currentTimeMillis())
                )
                bitmap.recycle()
                processing.set(false)
            }
        } catch (_: Exception) {
            bitmap.recycle()
            processing.set(false)
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        handlerThread?.quitSafely()
        sendBroadcast(Intent(ACTION_CAPTURE_STATUS).setPackage(packageName).putExtra("running", false))
        super.onDestroy()
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val full = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
        full.recycle()
        return cropped
    }

    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val ACTION_OCR_UPDATE = "com.imzz.galaxyassistant.OCR_UPDATE"
        const val ACTION_CAPTURE_STATUS = "com.imzz.galaxyassistant.CAPTURE_STATUS"
        const val ACTION_STOP = "com.imzz.galaxyassistant.CAPTURE_STOP"
        const val ACTION_EMERGENCY_STOP = "com.imzz.galaxyassistant.EMERGENCY_STOP"
        private const val NOTIFICATION_ID = 7301
        @Volatile var isRunning: Boolean = false
            private set

        fun latestFrame(context: Context): File = File(context.cacheDir, "galaxy_latest_screen.png")
    }

    private object ActivityResult {
        const val RESULT_CANCELED = 0
        const val RESULT_OK = -1
    }
}
