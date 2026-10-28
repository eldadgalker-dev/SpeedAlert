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
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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
    private lateinit var tts: TextToSpeech
    private lateinit var audio: AudioManager
    private var ttsReady = false
    private var pending: String? = null
    private var savedVolume = -1
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
        tts = TextToSpeech(this, this)
        startInForeground()
        running = true
        status = "פועל. ${cameras.size} מצלמות במאגר."
        startLocation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            speak("בדיקה. מצלמת מהירות בעוד 500 מטר. הורד 12 קילומטר לשעה. קנס 250 שקלים, ללא נקודות.")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        tts.shutdown()
        restoreVolume()
        running = false
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
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) = restoreVolume()
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = restoreVolume()
        })
        ttsReady = true
        pending?.let { speak(it); pending = null }
    }

    private fun onLocation(loc: Location) {
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val heading = if (loc.hasBearing() && speedKmh >= CameraWatcher.MIN_SPEED_KMH) loc.bearing.toDouble() else null
        status = "מהירות ${speedKmh.toInt()} קמ\"ש" + (heading?.let { ", כיוון ${it.toInt()}°" } ?: "")
        watcher.onLocation(loc.latitude, loc.longitude, speedKmh, heading)?.let {
            lastAlert = it
            speak(it)
        }
    }

    /** Speaks over other audio (Waze, music) at maximum media volume, then restores it. */
    private fun speak(text: String) {
        if (!ttsReady) { pending = text; return }
        if (savedVolume < 0) savedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).build()
                .also { audio.requestAudioFocus(it) }
        }
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "alert-${System.currentTimeMillis()}")
    }

    private fun restoreVolume() {
        if (savedVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
        savedVolume = -1
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
        @Volatile var running = false
        @Volatile var status = "מופסק"
        @Volatile var lastAlert = ""

        fun start(ctx: Context, action: String? = null) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, AlertService::class.java).setAction(action))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AlertService::class.java))
    }
}
