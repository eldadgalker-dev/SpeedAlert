// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CaptureRequest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The app's own camera for speed signs (user request 2026-11-09: photos from a moving car came
 * out blurred and no number was read). Pinch to zoom; a touch on the sign focuses there and
 * reads only around that point; the number read is shown live. The shutter takes a burst of
 * [BURST] frames (short exposure, 30 frames a second), reads each, keeps the majority number and
 * saves the sharpest frame. Everything runs on the phone; CameraX sends nothing.
 *
 * In: extra [EXTRA_PHOTO] (file to write). Out: RESULT_OK with [EXTRA_READ] (-1 when none),
 * [EXTRA_VOTES] and [EXTRA_FRAMES]; RESULT_CANCELED with [EXTRA_ERROR] when the photo failed
 * (no extra when the user cancelled). No failure is silent (review 2026-11-14).
 */
class SignCameraActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var marker: View
    private lateinit var shutter: Button
    private var camera: Camera? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var tap: PointF? = null          // 0..1 on the preview (same as the frame: one viewport)
    @Volatile private var burst: Burst? = null
    private var lastLive = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Touched only on [exec], so a burst ends once: by its last frame or by the time limit. */
    private class Burst {
        var done = false
        val reads = ArrayList<Int?>()
        var best: Bitmap? = null
        var bestSharp = -1.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        marker = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(px(3), Color.YELLOW) }
            visibility = View.INVISIBLE
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt()); setPadding(px(12), px(10), px(12), px(10))
            text = "גע בשלט כדי לכוון, שתי נגיעות מהירות בעיגול לצילום"
        }
        shutter = Button(this).apply {
            text = "צלם שלט"; textSize = 22f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(0xFF1565C0.toInt()) }
            setOnClickListener { shoot() }
        }
        val cancel = Button(this).apply {
            text = "בטל"; textSize = 18f
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(px(12), px(12), px(12), px(20))
            addView(cancel, LinearLayout.LayoutParams(0, px(72), 1f).apply { marginEnd = px(10) })
            addView(shutter, LinearLayout.LayoutParams(0, px(72), 2.5f))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(marker, FrameLayout.LayoutParams(px(MARK_DP), px(MARK_DP)))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        setContentView(root)
        // Android 15 draws edge to edge: keep the status line and the buttons clear of the
        // status bar, the camera cutout and the navigation bar (their touch area is the system's).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        touch()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
    }

    @Deprecated("Activity API")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else fail("אין הרשאת מצלמה. אשר אותה בהגדרות הטלפון: אפליקציות, SpeedAlert, הרשאות.")
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        exec.shutdown()
    }

    /** Closes the screen with the reason, which the main screen shows. */
    private fun fail(reason: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, reason)); finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun touch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean {
                val c = camera ?: return false
                val z = c.cameraInfo.zoomState.value ?: return false
                c.cameraControl.setZoomRatio((z.zoomRatio * g.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tap = PointF(e.x / preview.width, e.y / preview.height)
                marker.x = e.x - marker.width / 2f; marker.y = e.y - marker.height / 2f
                marker.visibility = View.VISIBLE
                camera?.cameraControl?.startFocusAndMetering(
                    FocusMeteringAction.Builder(preview.meteringPointFactory.createPoint(e.x, e.y)).build())
                return true
            }

            // Two quick taps on the yellow circle take the photo (user request 2026-11-09):
            // one tap aims, a double tap on the aimed circle shoots.
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val r = marker.width / 2f
                val onCircle = marker.visibility == View.VISIBLE &&
                    Math.hypot((e.x - marker.x - r).toDouble(), (e.y - marker.y - r).toDouble()) <= r * 1.3
                if (onCircle) shoot()
                return true
            }
        })
        preview.setOnTouchListener { _, e -> scale.onTouchEvent(e); if (!scale.isInProgress) taps.onTouchEvent(e); true }
    }

    private fun shoot() {
        if (burst != null) return
        if (camera == null) { status.text = "המצלמה עדיין לא מוכנה"; return }
        val b = Burst()
        burst = b; shutter.text = "מצלם…"; shutter.isEnabled = false
        // The burst never hangs: after [BURST_MS] it ends with the frames it has.
        main.postDelayed({ runCatching { exec.execute { finishBurst(b) } } }, BURST_MS)
    }

    private fun start() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            preview.post {
                try { bind(f.get()) } catch (e: Throwable) { status.text = "המצלמה לא נפתחה: ${e.message ?: e.javaClass.simpleName}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bind(provider: ProcessCameraProvider) {
        if (isFinishing || isDestroyed) return
        // A camera that refuses the fixed frame rate is opened again without it.
        var error: Throwable? = null
        for (fixedRate in listOf(true, false)) {
            try {
                val prev = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val ab = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                // 30 frames a second at least: exposure at most 1/30 s, less blur from the moving car.
                if (fixedRate) Camera2Interop.Extender(ab)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                val analysis = ab.build().also { it.setAnalyzer(exec, ::analyze) }
                val group = UseCaseGroup.Builder().addUseCase(prev).addUseCase(analysis)
                preview.viewPort?.let { group.setViewPort(it) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                return
            } catch (e: Throwable) {
                error = e
            }
        }
        status.text = "המצלמה לא נפתחה: ${error?.message ?: error?.javaClass?.simpleName}"
    }

    private fun analyze(img: ImageProxy) {
        val b = burst
        try {
            val now = SystemClock.elapsedRealtime()
            if (b == null && now - lastLive < LIVE_MS) return
            val frame = upright(img)
            val small = SignFrames.scaled(SignFrames.region(frame, tap), SignFrames.READ_SIDE)
            val read = runCatching { SignLog.readNumber(small) }.getOrNull()
            if (b == null) {
                lastLive = now
                runOnUiThread { status.text = if (read != null) "זוהה: $read. שתי נגיעות בעיגול לצילום" else "לא זוהה שלט. גע בשלט או הגדל" }
                return
            }
            if (b.done) return
            // The frame is kept before anything else can fail: a burst with one frame still saves a photo.
            if (b.best == null) b.best = frame
            val sharp = SignFrames.sharpness(small)
            b.reads += read
            if (sharp > b.bestSharp) { b.bestSharp = sharp; b.best = frame }
        } catch (e: Throwable) {   // also OutOfMemoryError: the screen stays usable and says why
            if (b != null && !b.done) b.reads += null
            runOnUiThread { status.text = "שגיאה בניתוח התמונה: ${e.message ?: e.javaClass.simpleName}" }
        } finally {
            img.close()
        }
        if (b != null && !b.done && b.reads.size >= BURST) finishBurst(b)
    }

    /** The frame as shown: the viewport's crop, turned upright. */
    private fun upright(img: ImageProxy): Bitmap {
        val raw = img.toBitmap()
        val r = img.cropRect
        val m = Matrix().apply { postRotate(img.imageInfo.rotationDegrees.toFloat()) }
        // The full frame is freed at once, not left to the collector (11 MB every 350 ms, review 2026-11-09).
        return Bitmap.createBitmap(raw, r.left, r.top, r.width(), r.height(), m, true).also { if (it !== raw) raw.recycle() }
    }

    /** Runs on [exec], once per burst: after its last frame, or when its time is up. */
    private fun finishBurst(b: Burst) {
        if (b.done) return
        b.done = true
        if (burst === b) burst = null
        val best = b.best
        if (best == null) {   // no frame arrived in time: stay on the screen, let the user try again
            runOnUiThread {
                status.text = "לא התקבלה תמונה מהמצלמה. נסה שוב."
                shutter.text = "צלם שלט"; shutter.isEnabled = true
            }
            return
        }
        val (value, votes) = SignFrames.vote(b.reads)
        val path = intent.getStringExtra(EXTRA_PHOTO)
        val saved = path != null && runCatching {
            File(path).outputStream().use { best.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.getOrDefault(false)
        runOnUiThread {
            if (!saved) return@runOnUiThread fail("התמונה לא נשמרה בטלפון.")
            setResult(RESULT_OK, Intent().putExtra(EXTRA_READ, value ?: -1).putExtra(EXTRA_VOTES, votes)
                .putExtra(EXTRA_FRAMES, b.reads.size))
            finish()
        }
    }

    companion object {
        const val EXTRA_PHOTO = "photo"
        const val EXTRA_READ = "read"
        const val EXTRA_VOTES = "votes"
        const val EXTRA_FRAMES = "frames"
        const val EXTRA_ERROR = "error"
        const val BURST = 12
        /** Longest burst: a stalled camera must not hold the screen. */
        const val BURST_MS = 5000L
        private const val LIVE_MS = 350L
        private const val MARK_DP = 140
    }
}

/** Pure helpers of the sign camera (unit tested). */
object SignFrames {
    const val READ_SIDE = 480
    /** Share of the shorter side read around a touched point. */
    const val TAP_SHARE = 0.45

    fun region(frame: Bitmap, tap: PointF?): Bitmap {
        if (tap == null) return frame
        val (x, y, s) = square(frame.width, frame.height, tap.x.toDouble(), tap.y.toDouble())
        return Bitmap.createBitmap(frame, x, y, s, s)
    }

    /** Square (left, top, side) around a point given as 0..1 of the frame, kept inside it. */
    fun square(w: Int, h: Int, fx: Double, fy: Double): Triple<Int, Int, Int> {
        val s = (minOf(w, h) * TAP_SHARE).toInt().coerceAtLeast(1)
        val x = ((fx * w) - s / 2).toInt().coerceIn(0, w - s)
        val y = ((fy * h) - s / 2).toInt().coerceIn(0, h - s)
        return Triple(x, y, s)
    }

    fun scaled(b: Bitmap, side: Int): Bitmap {
        val k = side.toFloat() / maxOf(b.width, b.height)
        return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    fun sharpness(b: Bitmap): Double {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return laplacianVariance(px, b.width, b.height)
    }

    /** Variance of the Laplacian of the brightness: higher is sharper. */
    fun laplacianVariance(px: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        fun l(i: Int) = (((px[i] shr 16) and 255) * 299 + ((px[i] shr 8) and 255) * 587 + (px[i] and 255) * 114) / 1000.0
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = l(i - 1) + l(i + 1) + l(i - w) + l(i + w) - 4 * l(i)
            sum += v; sq += v * v; n++
        }
        val mean = sum / n
        return sq / n - mean * mean
    }

    /** Majority number of the burst and its votes; null when no frame was read. */
    fun vote(reads: List<Int?>): Pair<Int?, Int> {
        val best = reads.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null to 0
        return best.key to best.value
    }
}
