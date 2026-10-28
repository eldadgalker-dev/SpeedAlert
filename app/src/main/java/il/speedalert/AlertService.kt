package il.speedalert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale

/** Foreground service: follows GPS and speaks camera warnings at full volume. */
class AlertService : Service(), TextToSpeech.OnInitListener {
    private lateinit var watcher: CameraWatcher
    private lateinit var speeding: SpeedingWatcher
    private lateinit var busLanes: BusLaneWatcher
    private val once = ArrayDeque<String>()          // one-time alerts waiting for the voice
    private var beepsUntil = 0L                      // the voice waits for the beeps to end
    private lateinit var tts: TextToSpeech
    private lateinit var audio: AudioManager
    private var ttsReady = false
    private var pending: String? = null
    private val saved = mutableMapOf<Int, Int>()   // stream -> volume before the alert
    private var focus: AudioFocusRequest? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val cameras = Database.cameras(this)
        watcher = CameraWatcher(cameras, Database.penalties(this))
        speeding = SpeedingWatcher(Database.penalties(this))
        busLanes = BusLaneWatcher(runCatching { Database.busLanes(this) }.getOrDefault(emptyList()))
        Thread { runCatching { speeding.roads = Database.roads(this) } }.start()
        tts = TextToSpeech(this, this)
        startInForeground()
        running = true
        startedAt = System.currentTimeMillis()
        startBattery = Usage.batteryLevel(this)
        status = "פועל. ${cameras.size} מצלמות במאגר."
        startLocation()
        main.post(dataCheck)
    }

    private val main = Handler(Looper.getMainLooper())

    /** Checks GitHub for newer camera data now and every [DataUpdate.INTERVAL_MS] while running. */
    private val dataCheck = object : Runnable {
        override fun run() {
            Thread {
                val changed = runCatching { DataUpdate.run(this@AlertService) }.getOrDefault(false)
                if (changed) {
                    val cams = Database.cameras(this@AlertService)
                    watcher.replaceTables(cams, Database.penalties(this@AlertService))
                    speeding.penalties = Database.penalties(this@AlertService)
                    runCatching { busLanes.lanes = Database.busLanes(this@AlertService) }
                    runCatching { speeding.roads = Database.roads(this@AlertService) }
                    dataInfo = "עודכן: ${cams.size} מצלמות (${Database.fetched(this@AlertService)})"
                }
            }.start()
            main.postDelayed(this, DataUpdate.INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            beepThenSpeak("בדיקה. מצלמת מהירות בעוד 500 מטר. מהירות נוכחית 102. מותרת 90. הוֹרֵד 12 קילומטר לשעה. קנס 250 שקלים, ללא נקודות.")
            main.postDelayed({ if (!tts.isSpeaking) restoreVolume() }, 8000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(dataCheck)
        fused.removeLocationUpdates(callback)
        tts.shutdown()
        restoreVolume()
        running = false
        speed = -1.0
        status = "מופסק"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            status = "שגיאה: מנוע הדיבור לא נטען"
            return
        }
        val he = tts.setLanguage(Locale.forLanguageTag("he-IL"))
        if (he == TextToSpeech.LANG_MISSING_DATA || he == TextToSpeech.LANG_NOT_SUPPORTED) {
            status = "אזהרה: אין קול עברי במנוע הדיבור. התקן Speech Services by Google וקול עברי."
        }
        // Alarm stream: plays at its own volume even when the ringer is silent or on vibrate.
        tts.setAudioAttributes(ALERT_AUDIO)
        ttsReady = true
        pending?.let { speak(it); pending = null }
    }

    private fun onLocation(loc: Location) {
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val heading = if (loc.hasBearing() && speedKmh >= CameraWatcher.MIN_SPEED_KMH) loc.bearing.toDouble() else null
        speed = speedKmh
        status = when {
            silencedByDnd() -> "אזהרה: מצב \"נא לא להפריע\" בשקט מוחלט חוסם את ההתראות"
            heading == null -> "ממתין לתנועה"
            else -> "בנסיעה, כיוון ${heading.toInt()}°"
        }
        val lat = loc.latitude; val lon = loc.longitude
        // Speeding without a camera is checked only when no camera alert is due.
        val camera = watcher.onLocation(lat, lon, speedKmh, heading)
        val alerts = listOfNotNull(camera, busLanes.onLocation(lat, lon, speedKmh, heading),
            if (camera?.repeat == true) null else speeding.onLocation(lat, lon, speedKmh, heading))
        alerts.filter { !it.repeat }.forEach { once.addLast(it.text) }
        val repeating = alerts.firstOrNull { it.repeat }?.text
        if ((ttsReady && tts.isSpeaking) || System.currentTimeMillis() < beepsUntil) return
        // Continuous: the next sentence (with fresh distance and speed) starts as soon as the last ends.
        val text = once.removeFirstOrNull() ?: repeating
        if (text == null) {
            if (ttsReady) restoreVolume()
            return
        }
        lastAlert = text
        // Three beeps open every announcement, repeats included (user decision 2026-10-28).
        beepThenSpeak(text)
    }

    /** Three loud, harsh beeps on the alarm stream (user request 2026-10-28), then the sentence. */
    private fun beepThenSpeak(text: String) {
        maximise()
        beepsUntil = System.currentTimeMillis() + BEEPS * BEEP_GAP_MS
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        for (i in 0 until BEEPS) main.postDelayed({ tone?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, BEEP_MS) }, i * BEEP_GAP_MS)
        main.postDelayed({ tone?.release(); speak(text) }, BEEPS * BEEP_GAP_MS)
    }

    /** "Do not disturb" set to total silence blocks even the alarm stream; report it. */
    private fun silencedByDnd(): Boolean =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_NONE

    /**
     * Speaks over other audio (Waze, music) at maximum volume whatever the phone is set to:
     * unmutes and maximises the alarm and media streams, and keeps them so until the alert ends.
     */
    private fun speak(text: String) {
        if (!ttsReady) { pending = text; return }
        maximise()
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "alert-${System.currentTimeMillis()}")
    }

    private fun maximise() {
        for (stream in intArrayOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_MUSIC)) {
            saved.getOrPut(stream) { audio.getStreamVolume(stream) }
            runCatching {
                audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                audio.setStreamVolume(stream, audio.getStreamMaxVolume(stream), 0)
            }
        }
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ALERT_AUDIO).build()
                .also { audio.requestAudioFocus(it) }
        }
    }

    /** Puts the volumes back once no alert is due (camera passed, speed back under the limit). */
    private fun restoreVolume() {
        for ((stream, v) in saved) runCatching { audio.setStreamVolume(stream, v, 0) }
        saved.clear()
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            status = "אין הרשאת מיקום"
            stopSelf()
            return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, 1, n, type)
    }

    companion object {
        const val CHANNEL = "camera-alerts"
        const val ACTION_TEST = "il.speedalert.TEST"
        private const val BEEPS = 3
        private const val BEEP_MS = 180
        private const val BEEP_GAP_MS = 300L
        private val ALERT_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        @Volatile var running = false
        @Volatile var status = "מופסק"
        @Volatile var lastAlert = ""
        @Volatile var dataInfo = ""
        @Volatile var speed = -1.0          // km/h; -1 when not running
        @Volatile var startedAt = 0L
        @Volatile var startBattery = -1

        fun start(ctx: Context, action: String? = null) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, AlertService::class.java).setAction(action))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AlertService::class.java))
    }
}
