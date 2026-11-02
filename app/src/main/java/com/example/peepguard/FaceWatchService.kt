package com.example.peepguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

class FaceWatchService : LifecycleService() {

    companion object {
        private const val ACTION_FRIEND = "com.example.peepguard.ACTION_FRIEND"
        private const val ACTION_IGNORE = "com.example.peepguard.ACTION_IGNORE"
        private const val CHANNEL_MAIN = "peepguard"
        private const val CHANNEL_ALERT = "peepguard_alert"
        private const val NOTIF_MAIN = 1
        private const val NOTIF_ALERT = 2
        private const val WATCHDOG_MS = 2000L
        private const val NO_FRAME_LIMIT_MS = 4000L

        // Timer list: (label, milliseconds)
        private val TIMER_OPTIONS = listOf(
            "30 second" to 30_000L,
            "1 minute" to 60_000L,
            "2 minute" to 120_000L,
            "5 minute" to 300_000L,
            "10 minute" to 600_000L
        )
    }

    private enum class Mode { ALERT, TIMER }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var cameraProvider: ProcessCameraProvider? = null

    private var lastRun = 0L
    @Volatile private var lastFrameAt = 0L
    private var multiFaceFrames = 0

    private var cameraWanted = false      // protected app abhi screen pe hai?
    private var alertPending = false      // Friend/Ignore ka jawab abhi baaki hai
    private var suppressUntil = 0L        // is time tak naya alert nahi

    private var overlayRoot: LinearLayout? = null

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.15f) // bahut chhote/door ke faces ignore
                .build()
        )
    }

    /** Har 2 sec check: camera frames aana band ho gaye to camera dobara chalu karo. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (cameraWanted &&
                System.currentTimeMillis() - lastFrameAt > NO_FRAME_LIMIT_MS
            ) {
                startCamera()
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MAIN, "PeepGuard", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "PeepGuard Alert", NotificationManager.IMPORTANCE_HIGH)
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_MAIN)
            .setContentTitle("PeepGuard chalu hai")
            .setContentText("Protected app khulne par camera check karega")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_MAIN, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )

        Guard.reset()
        Guard.onActiveChanged = { active ->
            if (active) startCamera() else stopCamera()
        }
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_FRIEND -> if (alertPending) renderOverlay(Mode.TIMER, "Friend")
            ACTION_IGNORE -> if (alertPending) renderOverlay(Mode.TIMER, "Ignore")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Guard.onActiveChanged = null
        Guard.reset()
        handler.removeCallbacksAndMessages(null)
        stopCamera()
        detector.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------- camera

    private fun startCamera() {
        cameraWanted = true
        lastFrameAt = System.currentTimeMillis() // watchdog ko thodi grace do
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!cameraWanted) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                e.printStackTrace() // watchdog 2-4 sec baad dobara try karega
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraWanted = false
        cameraProvider?.unbindAll()
        dismissAlert() // protected app se bahar gaye to blur/notification hata do
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        lastFrameAt = now
        val media = proxy.image
        if (now - lastRun < 400 || media == null) { // ~2-3 FPS, battery bachane ke liye
            proxy.close()
            return
        }
        lastRun = now

        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces -> onFaces(faces.size) } // main thread
            .addOnCompleteListener { proxy.close() }
    }

    private fun onFaces(count: Int) {
        // 2 baar lagataar 2+ faces mile tabhi alert (false alarm kam)
        multiFaceFrames = if (count >= 2) multiFaceFrames + 1 else 0
        if (multiFaceFrames >= 2 &&
            cameraWanted &&
            !alertPending &&
            System.currentTimeMillis() >= suppressUntil
        ) {
            triggerAlert()
        }
    }

    // -------------------------------------------------------------------- alert

    private fun triggerAlert() {
        alertPending = true
        showOverlay()
        renderOverlay(Mode.ALERT)
        postAlertNotification()
    }

    private fun applyChoice(who: String, label: String, millis: Long) {
        suppressUntil = System.currentTimeMillis() + millis
        dismissAlert()
        Toast.makeText(this, "$who: $label tak alert band", Toast.LENGTH_SHORT).show()
    }

    private fun dismissAlert() {
        alertPending = false
        multiFaceFrames = 0
        removeOverlay()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
    }

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, FaceWatchService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun postAlertNotification() {
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Third person detected")
            .setContentText("Ye kaun hai? Friend ya Ignore chuno")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(0, "Friend", actionIntent(ACTION_FRIEND, 10))
            .addAction(0, "Ignore", actionIntent(ACTION_IGNORE, 11))
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
    }

    // ------------------------------------------------------------------ overlay

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Full-screen blur (Android 12+ jahan supported ho), warna gehra dark cover. */
    private fun showOverlay() {
        if (overlayRoot != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val blurOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            wm.isCrossWindowBlurEnabled

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(
                if (blurOk) Color.argb(110, 0, 0, 0) else Color.argb(245, 15, 15, 15)
            )
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        if (blurOk) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.blurBehindRadius = 80
        }

        try {
            wm.addView(root, lp)
            overlayRoot = root
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay() {
        overlayRoot?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayRoot = null
    }

    private fun addText(root: LinearLayout, text: String, size: Float, bold: Boolean) {
        root.addView(TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun addButton(root: LinearLayout, label: String, onClick: () -> Unit) {
        root.addView(Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    private fun renderOverlay(mode: Mode, who: String = "") {
        val root = overlayRoot ?: return
        root.removeAllViews()
        when (mode) {
            Mode.ALERT -> {
                addText(root, "⚠️ Third person detected", 24f, true)
                addText(root, "Screen blur kar di gayi hai.\nYe kaun hai?", 16f, false)
                addButton(root, "Friend") { renderOverlay(Mode.TIMER, "Friend") }
                addButton(root, "Ignore") { renderOverlay(Mode.TIMER, "Ignore") }
            }
            Mode.TIMER -> {
                addText(root, "$who — kitni der ke liye?", 22f, true)
                for ((label, ms) in TIMER_OPTIONS) {
                    addButton(root, label) { applyChoice(who, label, ms) }
                }
                addButton(root, "← Wapas") { renderOverlay(Mode.ALERT) }
            }
        }
    }
}
