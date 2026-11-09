// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity
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
    private lateinit var average: AverageSpeedWatcher
    private lateinit var roadTiles: RoadTiles
    private var notifiedVersion = 0
    private val once = ArrayDeque<Alert>()           // one-time alerts waiting for the voice
    private var beepsUntil = 0L                      // the voice waits for the beeps to end
    private var speakingKey: String? = null          // event of the announcement being beeped or spoken
    private val beeps = EventBeeps()
    @Volatile private var talkingUntil = 0L          // own record of the sentence under way (onDone clears it)
    @Volatile private var talkingId: String? = null
    private fun ended(id: String?) { if (id == talkingId) { talkingUntil = 0; retried = false } }
    private val timing = SpeechTiming(beepGapMs = BEEP_GAP_MS)
    @Volatile private var ttsStartedAt = 0L          // the engine began the sentence under way

    /** Learns the engine's start and pace from a sentence that was spoken to its end. */
    private fun learnTiming(id: String?) {
        val text = talkingText ?: return
        if (id != talkingId || ttsStartedAt <= spokeAt) return
        timing.learn(text, ttsStartedAt - spokeAt, System.currentTimeMillis() - ttsStartedAt)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(K_TTS_START, timing.startMs.toFloat()).putFloat(K_TTS_PACE, timing.msPerUnit.toFloat()).apply()
    }
    private var talkingText: String? = null
    private var retried = false                      // one reconnect of the speech engine per sentence
    private var ttsAt = 0L                           // engine (re)connected
    private var spokeAt = 0L                         // last sentence handed to the engine

    /**
     * Field report 2026-11-08: after some days unused the first alert gave the beeps without the
     * words; the speech engine's connection had gone stale. The engine is connected anew when
     * driving starts after a long pause, and when a sentence fails (once), then says it.
     */
    private fun connectTts() {
        ttsReady = false
        if (::tts.isInitialized) runCatching { tts.shutdown() }
        ttsAt = System.currentTimeMillis()
        lateinit var t: TextToSpeech
        t = TextToSpeech(this) { r -> main.post { if (tts === t) onInit(r) } }
        tts = t
    }

    private fun failed(text: String?) {
        if (text == null || retried) { talkingUntil = 0; return }
        retried = true
        pending = text
        talkingUntil = System.currentTimeMillis() + MAX_TALK_MS   // quiet while reconnecting
        connectTts()
    }
    private var tone: ToneGenerator? = null
    private lateinit var tts: TextToSpeech
    private lateinit var audio: AudioManager
    private var ttsReady = false
    private var pending: String? = null
    private val saved = mutableMapOf<Int, Int>()   // stream -> volume before the alert
    private var focus: AudioFocusRequest? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var lastDriving = 0L                    // last fix at driving speed
    private var savingFrom: Location? = null        // first fix in power-saving mode (no motion sensor)
    private var lastFix: Location? = null           // for the speed of a fix that has none
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)   // one data check at a time
    private var fast = true                         // GPS once a second (near a camera or alerting)
    private var wokeByMotion = false                // GPS back on a motion trigger, no driving seen yet
    private val sensors by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val motion: Sensor? by lazy { sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) { main.post { if (saving) wake() } }
    }

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
        watcher.timing = timing.apply {
            val p = getSharedPreferences(PREFS, MODE_PRIVATE)
            startMs = p.getFloat(K_TTS_START, startMs.toFloat()).toDouble()
            msPerUnit = p.getFloat(K_TTS_PACE, msPerUnit.toFloat()).toDouble()
        }
        speeding = SpeedingWatcher(Database.penalties(this))
        busLanes = BusLaneWatcher(runCatching { Database.busLanes(this) }.getOrDefault(emptyList()))
        average = AverageSpeedWatcher(runCatching { Database.sections(this) }.getOrDefault(emptyList()))
        roadTiles = RoadTiles(this)
        speeding.roads = roadTiles
        beepsOnly = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(K_BEEPS_ONLY, false)
        connectTts()
        startInForeground()
        running = true
        startedAt = System.currentTimeMillis()
        startBattery = Usage.batteryLevel(this)
        status = "פועל. ${cameras.size} מצלמות במאגר."
        startLocation()
        startActivityRecognition()
        instance = this
        main.post(dataCheck)
        UsageLog.event(this, "start", gpsMs(), savingMs())
        main.postDelayed(logTick, UsageLog.INTERVAL_MS)
    }

    private val activityIntent by lazy {
        PendingIntent.getBroadcast(this, 2, Intent(this, ActivityReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
    }

    /**
     * Driving start and end from the phone's own activity recognition (Google Play services,
     * user request 2026-10-29): the phone already tracks it for itself, so the app gets it for
     * free. Optional: without the permission the speed and the motion sensor decide alone.
     */
    @SuppressLint("MissingPermission")
    private fun startActivityRecognition() {
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED) return
        val transitions = listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(it).build()
        }
        runCatching { ActivityRecognition.getClient(this).requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), activityIntent) }
            .onSuccess { recognition = true }
    }

    /** In a vehicle: GPS back at once. Left the vehicle: saving at once instead of after [IDLE_AFTER_MS]. */
    private fun vehicle(enter: Boolean) {
        if (enter) { if (saving) wake(); return }
        if (!saving && speed < DRIVING_KMH && once.isEmpty() && !(ttsReady && tts.isSpeaking)) enterSaving()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Checks GitHub for newer camera data now and every [DataUpdate.INTERVAL_MS] while running. */
    /** One line in the usage log every [UsageLog.INTERVAL_MS] (user request 2026-11-08). */
    private val logTick = object : Runnable {
        override fun run() {
            UsageLog.sample(this@AlertService, gpsMs(), savingMs(), motionSensor, if (saving) "saving" else "active")
            main.postDelayed(this, UsageLog.INTERVAL_MS)
        }
    }

    private val dataCheck = object : Runnable {
        override fun run() {
            // A slow check never overlaps the next one (review 2026-11-09).
            if (checking.compareAndSet(false, true)) Thread {
                try {
                val changed = runCatching { DataUpdate.run(this@AlertService) }.getOrNull()
                if (changed?.any == true) {
                    val cams = Database.cameras(this@AlertService)
                    watcher.replaceTables(cams, Database.penalties(this@AlertService))
                    speeding.penalties = Database.penalties(this@AlertService)
                    runCatching { busLanes.lanes = Database.busLanes(this@AlertService) }
                    runCatching { average.sections = Database.sections(this@AlertService) }
                    if (changed.roads) roadTiles.reload()
                    dataInfo = "${cams.size} מצלמות"
                    dataNote = "עודכן, נתונים מ-${Database.fetched(this@AlertService)}"
                }
                runCatching { AppUpdate.check() }.getOrNull()?.let { main.post { notifyVersion(it) } }
                } finally { checking.set(false) }
            }.start()
            main.postDelayed(this, DataUpdate.INTERVAL_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            beepThenSpeak("בדיקה. מצלמת מהירות בעוד 500 מטר. מהירות נוכחית 102. מותרת 90. הוֹרֵד 12 קילומטר לשעה. קנס 250 שקלים.")
            // Then the camera-pass sound, so it can be heard once.
            main.postDelayed({ if (!tts.isSpeaking) passSound() }, 9000)
            main.postDelayed({ if (!tts.isSpeaking) restoreVolume() }, 11_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacks(dataCheck)
        main.removeCallbacks(logTick)
        UsageLog.sample(this, gpsMs(), savingMs(), motionSensor, if (saving) "saving" else "active")
        UsageLog.event(this, "stop", gpsMs(), savingMs())
        if (recognition) runCatching { ActivityRecognition.getClient(this).removeActivityTransitionUpdates(activityIntent) }
        fused.removeLocationUpdates(callback)
        motion?.let { sensors.cancelTriggerSensor(trigger, it) }
        markMode(null)
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
        // isSpeaking is not reliable on every engine (false for a moment after speak(), or stuck):
        // the end of each sentence is taken from the engine's own callbacks, with [MAX_TALK_MS] as a cap.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { if (id == talkingId) ttsStartedAt = System.currentTimeMillis() }
            override fun onDone(id: String?) { learnTiming(id); ended(id) }
            @Deprecated("API 21") override fun onError(id: String?) { main.post { if (id == talkingId) failed(talkingText) } }
            override fun onError(id: String?, code: Int) { main.post { if (id == talkingId) failed(talkingText) } }
            override fun onStop(id: String?, interrupted: Boolean) = ended(id)
        })
        ttsReady = true
        pending?.let { speak(it); pending = null }
    }

    private fun onLocation(loc: Location) {
        // A fix without speed takes it from the previous fix, so a moving car is never taken as idle (review 2026-11-09).
        val prev = lastFix; lastFix = loc
        val dt = if (prev == null) 0L else (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000
        val speedKmh = when {
            loc.hasSpeed() -> loc.speed * 3.6
            prev != null && dt in 500..10_000 -> prev.distanceTo(loc) / dt * 3600.0
            else -> 0.0
        }
        if (powerSaving(loc, speedKmh)) return
        val heading = if (loc.hasBearing() && speedKmh >= CameraWatcher.MIN_SPEED_KMH) loc.bearing.toDouble() else null
        speed = speedKmh
        status = when {
            silencedByDnd() -> "אזהרה: מצב \"נא לא להפריע\" בשקט מוחלט חוסם את ההתראות"
            heading == null -> "ממתין לתנועה"
            else -> "בנסיעה, כיוון ${heading.toInt()}°"
        }
        val lat = loc.latitude; val lon = loc.longitude
        // Speeding without a camera is checked only when no camera alert is due.
        timing.fixAgeMs = ((SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000).coerceIn(0, 5000)
        val camera = watcher.onLocation(lat, lon, speedKmh, heading)
        // The pass sound 10 m before the camera, timed from the fix's age (user requests 2026-11-02, 2026-11-09).
        watcher.passAt?.let { (_, ms) ->
            val age = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
            HandlerCompat.postDelayed(main, { passSound() }, PASS, (ms - age).coerceAtLeast(0))
        }
        val alerts = listOfNotNull(camera, busLanes.onLocation(lat, lon, speedKmh, heading),
            average.onLocation(lat, lon, speedKmh, heading, loc.time),
            if (camera?.repeat == true) null else speeding.onLocation(lat, lon, speedKmh, heading))
        alerts.filter { !it.repeat }.forEach { once.addLast(it) }
        // GPS once a second only near a camera or while an alert lasts; every [SLOW_MS] elsewhere.
        val wantFast = alerts.isNotEmpty() || watcher.nearestM < if (fast) FAST_EXIT_M else FAST_ENTER_M
        if (wantFast != fast) { fast = wantFast; requestLocation(saving = false) }
        val repeating = alerts.firstOrNull { it.repeat }
        val now = System.currentTimeMillis()
        beeps.tick(once.map { it.key } + repeating?.key + speakingKey, now)
        // A camera silences the speed messages at once, until it is passed (user request 2026-11-02).
        if (camera?.repeat == true && speakingKey in SPEED_KEYS) {
            cancelVoice()
            once.removeAll { it.key == SpeedingWatcher.KEY }
        }
        if (now < talkingUntil || now < beepsUntil) return
        // Continuous: the next sentence (with fresh distance and speed) starts as soon as the last ends.
        val next = once.removeFirstOrNull() ?: repeating
        if (next == null) {
            speakingKey = null
            if (ttsReady) restoreVolume()
            return
        }
        lastAlert = next.text
        // Three beeps open every event, one beep before each further sentence of it (user requests 2026-11-02, 2026-11-08).
        speakingKey = next.key
        beepThenSpeak(next.text, beeps.forNext(next, now))
    }

    /** Loud, harsh beeps on the alarm stream (user request 2026-10-28), then the sentence. */
    private fun beepThenSpeak(text: String, beeps: Int = EventBeeps.FIRST) {
        maximise()
        beepsUntil = System.currentTimeMillis() + beeps * BEEP_GAP_MS
        val t = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME) }.getOrNull()
        tone?.let { runCatching { it.release() } }
        tone = t
        for (i in 0 until beeps) HandlerCompat.postDelayed(main, { t?.startTone(ToneGenerator.TONE_CDMA_HIGH_L, BEEP_MS) }, VOICE, i * BEEP_GAP_MS)
        // Beeps only (switch on the main screen, user request 2026-11-08): no sentence, a pause instead.
        if (beepsOnly) beepsUntil += BEEPS_ONLY_PAUSE_MS
        HandlerCompat.postDelayed(main, { runCatching { t?.release() }; if (tone === t) tone = null; if (!beepsOnly) speak(text) },
            VOICE, beeps * BEEP_GAP_MS)
    }

    /** Stops the beeps and the sentence under way. */
    private fun cancelVoice() {
        main.removeCallbacksAndMessages(VOICE)
        tone?.let { runCatching { it.release() } }; tone = null
        if (ttsReady) tts.stop()
        talkingUntil = 0
        pending = null
        beepsUntil = 0
        speakingKey = null
    }

    /**
     * 10 m before the camera (user requests 2026-11-02, 2026-11-09): a long two-tone square wave, another sound
     * than the beeps and louder to the ear at the same volume; it cuts off whatever is playing.
     */
    private fun passSound() {
        cancelVoice()
        maximise()
        val rate = 22050
        val n = rate * PASS_MS / 1000
        val pcm = ShortArray(n) { i ->
            val f = if (i * 4 / rate % 2 == 0) 1400 else 1000   // alternates every 250 ms
            if (i * 2 * f / rate % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
        }
        val track = runCatching {
            AudioTrack.Builder().setAudioAttributes(ALERT_AUDIO)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build()
                .apply { write(pcm, 0, n); play() }
        }.getOrNull() ?: return
        beepsUntil = System.currentTimeMillis() + PASS_MS + 200
        main.postDelayed({ track.release() }, PASS_MS + 300L)
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
        val id = "alert-${System.currentTimeMillis()}"
        talkingId = id
        talkingText = text
        spokeAt = System.currentTimeMillis()
        talkingUntil = spokeAt + MAX_TALK_MS
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) }
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) failed(text)
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

    /**
     * Saves power when not driving (user requests 2026-10-29): after [IDLE_AFTER_MS] below
     * [DRIVING_KMH] with no alert due, location is switched off and the phone's significant-motion
     * sensor (a low-power hardware trigger, no app code runs while it waits) wakes the app when
     * the phone starts to move; full-accuracy GPS once a second then comes back at once, so the
     * alerts work from the first fix above [DRIVING_KMH]. A wake that is not followed by driving
     * (walking, a bump) goes back to saving after [WAKE_IDLE_MS]. Phones without the sensor
     * fall back to a balanced (network) fix every [SAVING_INTERVAL_MS] and wake after [WAKE_M].
     * Returns true while saving.
     */
    private fun powerSaving(loc: Location, speedKmh: Double): Boolean {
        val now = System.currentTimeMillis()
        if (saving) {
            val from = savingFrom ?: run { savingFrom = loc; return true }
            if (speedKmh < DRIVING_KMH && from.distanceTo(loc) <= WAKE_M) return true
            wake()
            return false
        }
        if (speedKmh >= DRIVING_KMH || lastDriving == 0L) { lastDriving = now; wokeByMotion = false }
        val idle = if (wokeByMotion) WAKE_IDLE_MS else IDLE_AFTER_MS
        if (now - lastDriving > idle && once.isEmpty() && !(ttsReady && tts.isSpeaking)) {
            enterSaving()
            return true
        }
        return false
    }

    /** Tells once per version that a newer one is out; tapping opens the app (update button). */
    private fun notifyVersion(r: RemoteVersion) {
        if (r.versionCode == notifiedVersion) return
        notifiedVersion = r.versionCode
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(UPDATES, "עדכוני גרסה", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, NotificationCompat.Builder(this, UPDATES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("גרסה ${r.versionName} זמינה")
            .setContentText("פתח את SpeedAlert ולחץ \"בדוק עדכון\".")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (saving) roadTiles.trim()
    }

    private fun enterSaving() {
        roadTiles.trim()   // not driving: no road tiles in memory
        val m = motion
        if (m != null && sensors.requestTriggerSensor(trigger, m)) {
            fused.removeLocationUpdates(callback)
            markMode(true)
            motionSensor = true
            status = "חיסכון בחשמל: ה-GPS כבוי. חיישן התנועה יפעיל אותו כשהרכב יזוז."
        } else {
            requestLocation(saving = true)
            status = "חיסכון בחשמל: לא בנסיעה. ההתראות יחזרו כשהרכב יזוז."
        }
        speed = 0.0
        if (ttsReady) restoreVolume()
    }

    /** Back to full-accuracy GPS after a motion trigger or movement in the fallback mode. */
    private fun wake() {
        lastDriving = System.currentTimeMillis()
        wokeByMotion = true
        if (ttsReady && lastDriving - maxOf(ttsAt, spokeAt) > STALE_TTS_MS) connectTts()
        fast = true
        requestLocation(saving = false)
        status = "ממתין לתנועה"
    }

    private fun startLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            status = "אין הרשאת מיקום"
            stopSelf()
            return
        }
        requestLocation(saving = false)
    }

    /** Switches between full-accuracy GPS (every second or [SLOW_MS]) and the power-saving balanced mode. */
    private fun requestLocation(saving: Boolean) {
        motion?.let { sensors.cancelTriggerSensor(trigger, it) }
        motionSensor = false
        markMode(saving)
        savingFrom = null
        val req = if (saving) LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, SAVING_INTERVAL_MS).build()
            else LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, if (fast) 1000L else SLOW_MS)
                .setMinUpdateIntervalMillis(500L).build()
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
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
        private const val UPDATES = "app-updates"
        const val ACTION_TEST = "il.speedalert.TEST"
        private const val PASS_MS = 1500
        private const val MAX_TALK_MS = 20_000L
        private const val STALE_TTS_MS = 30 * 60_000L
        private const val BEEPS_ONLY_PAUSE_MS = 4000L
        const val PREFS = "settings"
        const val K_BEEPS_ONLY = "beeps_only"
        private const val K_TTS_START = "tts_start_ms"
        private const val K_TTS_PACE = "tts_ms_per_unit"
        /** Beeps without the sentence (switch on the main screen). */
        @Volatile var beepsOnly = false

        fun setBeepsOnly(ctx: Context, on: Boolean) {
            beepsOnly = on
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_BEEPS_ONLY, on).apply()
        }

        /** Reset: the service stopped and started again, all state and the speech engine fresh. */
        fun restart(ctx: Context) {
            stop(ctx)
            Handler(Looper.getMainLooper()).postDelayed({ start(ctx) }, 800)
        }
        private val SPEED_KEYS = setOf(SpeedingWatcher.KEY, AverageSpeedWatcher.KEY)
        private val VOICE = Any()   // token of the beeps and the sentence, for cancelVoice()
        private val PASS = Any()
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
        @Volatile var dataNote = ""
        @Volatile var speed = -1.0          // km/h; -1 when not running
        @Volatile var startedAt = 0L
        @Volatile var startBattery = -1
        @Volatile var saving = false        // power-saving location mode (not driving)
        @Volatile var motionSensor = false  // saving with GPS off, waiting for the motion sensor
        @Volatile var recognition = false   // activity recognition (in a vehicle or not) is on
        @Volatile private var instance: AlertService? = null

        /** From [ActivityReceiver]: the phone entered or left a vehicle. */
        fun onVehicle(enter: Boolean) {
            val s = instance ?: return
            s.main.post { s.vehicle(enter) }
        }
        @Volatile private var modeSince = 0L
        @Volatile private var gpsTotal = 0L
        @Volatile private var savingTotal = 0L
        private const val DRIVING_KMH = 15.0
        private const val IDLE_AFTER_MS = 3 * 60_000L
        private const val SAVING_INTERVAL_MS = 15_000L
        private const val WAKE_M = 80f
        private const val WAKE_IDLE_MS = 90_000L
        private const val SLOW_MS = 2000L
        private const val FAST_ENTER_M = 1500.0  // 500 m alert range + 30 s at 120 km/h
        private const val FAST_EXIT_M = 2000.0

        /** Closes the time of the current location mode; [next] null when location stops. */
        private fun markMode(next: Boolean?) {
            val now = System.currentTimeMillis()
            if (modeSince > 0) { if (saving) savingTotal += now - modeSince else gpsTotal += now - modeSince }
            modeSince = if (next == null) 0L else now
            saving = next ?: false
        }

        /** Time with full-accuracy GPS on, ms, in this process. */
        fun gpsMs() = gpsTotal + if (!saving && modeSince > 0) System.currentTimeMillis() - modeSince else 0
        /** Time in power-saving location mode, ms, in this process. */
        fun savingMs() = savingTotal + if (saving && modeSince > 0) System.currentTimeMillis() - modeSince else 0

        fun start(ctx: Context, action: String? = null) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, AlertService::class.java).setAction(action))

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, AlertService::class.java))
    }
}
