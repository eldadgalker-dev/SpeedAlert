// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.location.Location
import android.text.InputType
import androidx.core.content.FileProvider
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    /** Colours for light and dark system theme. */
    private class Palette(val bg: Int, val card: Int, val text: Int, val muted: Int, val line: Int)

    private lateinit var pal: Palette
    private lateinit var stateChip: TextView
    private lateinit var speedView: TextView
    private lateinit var statusView: TextView
    private lateinit var alertView: TextView
    private lateinit var alertCard: LinearLayout
    private lateinit var toggle: Button
    private lateinit var modeButton: Button
    private lateinit var updateView: TextView
    private val info = mutableMapOf<String, TextView>()
    private val notes = mutableMapOf<String, TextView>()
    private var afterGrant: (() -> Unit)? = null
    @Volatile private var updateText = ""
    private val ui = Handler(Looper.getMainLooper())
    private var renders = 0
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        AlertService.beepsOnly = getSharedPreferences(AlertService.PREFS, MODE_PRIVATE).getBoolean(AlertService.K_BEEPS_ONLY, false)
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(K_SIGN_PHOTO)?.let { signPhoto = File(it) }   // the camera may have closed the app
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        pal = if (dark) Palette(0xFF121212.toInt(), 0xFF1E1E1E.toInt(), 0xFFECECEC.toInt(), 0xFFA0A0A0.toInt(), 0xFF333333.toInt())
              else Palette(0xFFF2F2F5.toInt(), Color.WHITE, 0xFF1D1D1F.toInt(), 0xFF5F6368.toInt(), 0xFFE0E0E0.toInt())
        window.statusBarColor = RED
        window.navigationBarColor = pal.bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pal.bg)
        }
        val header = header()
        root.addView(header)
        // Android 15 draws every app edge to edge (targetSdk 35): the red title bar runs under the
        // status bar and its content starts below it (field report 2026-10-29: title under the clock).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            header.setPadding(px(20) + b.left, px(14) + b.top, px(20) + b.right, px(14))
            v.setPadding(0, 0, 0, b.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = !dark
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(24))
        }

        // Speed and state
        stateChip = TextView(this).apply {
            textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(px(14), px(4), px(14), px(4))
        }
        speedView = TextView(this).apply {
            textSize = 64f; setTypeface(typeface, Typeface.BOLD); setTextColor(pal.text); gravity = Gravity.CENTER
        }
        // Fixed minimum heights: a status or alert of another length must not move the screen
        // up and down every second (field report 2026-10-29).
        statusView = text(15f, pal.muted).apply { gravity = Gravity.CENTER; minLines = 3 }
        body.addView(card().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(stateChip.apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            addView(speedView)
            addView(text(14f, pal.muted, "קמ\"ש").apply { gravity = Gravity.CENTER })
            addView(statusView.apply { setPadding(0, px(8), 0, 0) })
        })

        // Last alert
        alertView = text(17f, pal.text).apply { setTypeface(typeface, Typeface.BOLD); minLines = 4 }
        alertCard = card(accent = RED).apply {
            addView(text(13f, RED, "התראה אחרונה").apply { setTypeface(typeface, Typeface.BOLD) })
            addView(alertView)
        }
        body.addView(alertCard)

        // Main action
        toggle = button("", GREEN, Color.WHITE, 20f) { onToggle() }
        body.addView(toggle, LinearLayout.LayoutParams(-1, px(64)).apply { topMargin = px(4) })

        // Speed-sign photo with the exact location, to check corrections to the database (user request 2026-11-08).
        body.addView(button("צילום שלט מהירות", BLUE, Color.WHITE, 20f) { startSignPhoto() },
            LinearLayout.LayoutParams(-1, px(64)).apply { topMargin = px(10) })
        body.addView(text(12f, pal.muted, "לצלם רק בעצירה או על ידי נוסע. המספר נקרא מהתמונה בטלפון עצמו ונשמר ביומן השלטים.").apply {
            gravity = Gravity.CENTER; setPadding(0, px(4), 0, 0)
        })

        // Secondary actions
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(getString(R.string.test_voice), pal.card, pal.text, 15f) {
                withPermissions { AlertService.start(this@MainActivity, AlertService.ACTION_TEST) }
            }, LinearLayout.LayoutParams(0, px(52), 1f).apply { marginEnd = px(8) })
            addView(button(getString(R.string.check_update), pal.card, pal.text, 15f) { checkAppUpdate() },
                LinearLayout.LayoutParams(0, px(52), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        // Beeps only / beeps and sentence, reset, full shut-down (user request 2026-11-08).
        modeButton = button("", pal.card, pal.text, 15f) {
            AlertService.setBeepsOnly(this, !AlertService.beepsOnly); render()
        }
        body.addView(modeButton, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(10) })
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("איפוס", pal.card, pal.text, 15f) {
                withPermissions { AlertService.restart(this@MainActivity) }
            }, LinearLayout.LayoutParams(0, px(52), 1f).apply { marginEnd = px(8) })
            addView(button("כיבוי מלא", pal.card, RED, 15f) {
                AlertService.stop(this@MainActivity)
                finishAndRemoveTask()
            }, LinearLayout.LayoutParams(0, px(52), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        body.addView(button("יומן שימוש: מעבד וסוללה", pal.card, pal.text, 15f) { showUsageLog() },
            LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(10) })
        body.addView(button("יומן שלטי מהירות", pal.card, pal.text, 15f) { showSignLog() },
            LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(10) })
        body.addView(button(getString(R.string.help_page), pal.card, pal.text, 15f) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HELP_URL)))
        }, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(10) })
        updateView = text(14f, pal.muted).apply { gravity = Gravity.CENTER; setPadding(0, px(8), 0, 0) }
        body.addView(updateView)

        // Device and app info
        body.addView(card().apply {
            addView(text(13f, pal.muted, "מצב המכשיר והאפליקציה").apply {
                setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, px(6))
            })
            for (key in listOf(K_BATTERY, K_DRAIN, K_MEMORY, K_CPU, K_UPTIME, K_DATA, K_VERSION)) addView(row(key))
        })
        // Data update table (user request 2026-11-08): when each table last changed on this phone.
        body.addView(card().apply {
            addView(text(13f, pal.muted, "עדכון מאגרי המידע בטלפון").apply {
                setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, px(6))
            })
            for (key in DATA_ROWS.keys) addView(row(key))
            addView(row(K_CHECKED))
        })
        // Copyright and data credits, one item per line, centred (user requests 2026-10-29);
        // each line keeps the direction of its language so bidi cannot reorder it.
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(4), px(12), px(4), px(8))
            for (line in COPYRIGHT_LINES) addView(text(12f, pal.muted, line).apply {
                textDirection = if (line.first() in 'א'..'ת') View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
                gravity = Gravity.CENTER_HORIZONTAL; textAlignment = View.TEXT_ALIGNMENT_CENTER
            }, LinearLayout.LayoutParams(-1, -2))
        })

        root.addView(ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER   // no stretch/bounce at the ends
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        checkDataAtLaunch()
    }

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(RED)
        setPadding(px(20), px(14), px(20), px(14))
        addView(TextView(context).apply {
            text = getString(R.string.app_name); textSize = 22f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            text = "גרסה ${BuildConfig.VERSION_NAME}"; textSize = 13f; setTextColor(0xCCFFFFFF.toInt())
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(12) })
    }

    private fun render() {
        val running = AlertService.running
        stateChip.text = if (running) "פועל" else "מופסק"
        stateChip.background = round(if (running) GREEN else GREY, 50)
        speedView.text = if (running && AlertService.speed >= 0) AlertService.speed.toInt().toString() else "–"
        statusView.text = AlertService.status
        alertView.text = AlertService.lastAlert
        alertCard.visibility = if (AlertService.lastAlert.isEmpty()) View.GONE else View.VISIBLE
        toggle.text = getString(if (running) R.string.stop else R.string.start)
        toggle.background = round(if (running) RED else GREEN, 14)
        modeButton.text = if (AlertService.beepsOnly) "מצב: צפצופים בלבד (החלף להודעות)" else "מצב: צפצופים והודעות (החלף לצפצופים בלבד)"
        // A new version waiting stands out (user request 2026-11-09); progress lines stay quiet.
        val newer0 = AppUpdate.available
        val shownUpdate = updateText.ifEmpty { newer0?.let { "גרסה ${it.versionName} זמינה להורדה: לחץ \"בדוק עדכון\"" } ?: "" }
        updateView.text = shownUpdate
        updateView.visibility = if (shownUpdate.isEmpty()) View.GONE else View.VISIBLE
        val loud = newer0 != null && (updateText.isEmpty() || updateText.contains(newer0.versionName))
        updateView.textSize = if (loud) 17f else 14f
        updateView.setTypeface(null, if (loud) Typeface.BOLD else Typeface.NORMAL)
        updateView.setTextColor(if (loud) Color.WHITE else pal.muted)
        updateView.background = if (loud) round(ORANGE, 14) else null
        updateView.setPadding(px(12), px(if (loud) 12 else 8), px(12), px(if (loud) 12 else 0))

        val level = Usage.batteryLevel(this)
        val charging = Usage.charging(this)
        show(K_BATTERY, "$level%", if (charging) "בטעינה" else "")
        val recent = Usage.recent(this, AlertService.gpsMs(), AlertService.savingMs(), AlertService.motionSensor)
        val saving = if (running && AlertService.saving) "\nחיסכון: לא בנסיעה" else ""
        if (recent == null) {
            show(K_DRAIN, "נמדד…", saving.trim())
            show(K_CPU, "נמדד…")
        } else {
            show(K_DRAIN, "%.2f%% לשעה".format(recent.drainPerHour), "הערכה, ב-${recent.minutes} הדקות האחרונות$saving")
            show(K_CPU, "%.2f%%".format(recent.cpuPercent), "מהמעבד כולו, ב-${recent.minutes} הדקות האחרונות")
        }
        // Memory info is a slow system call: every 10th refresh only.
        if (renders++ % 10 == 0) show(K_MEMORY, "${Usage.memoryMb(this)} MB", "MB: ${Usage.memoryParts(this)}")
        show(K_UPTIME, if (running) minutes(System.currentTimeMillis() - AlertService.startedAt) else "–")
        show(K_DATA, AlertService.dataInfo.ifEmpty { "בודק…" }, AlertService.dataNote)
        val newer = AppUpdate.available
        if (renders % 10 == 1) showDataDates()
        show(K_VERSION, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            if (newer == null) "" else "גרסה ${newer.versionName} זמינה: לחץ \"בדוק עדכון\"")
    }

    /** The data date written at the head of a table ("updated" or "fetched"), as dd/MM/yyyy. */
    private fun dataDate(name: String): String? = runCatching {
        val local = File(filesDir, name)
        val head = (if (local.exists()) local.inputStream() else assets.open(name)).use { i ->
            val b = ByteArray(4096); val n = i.read(b).coerceAtLeast(0); String(b, 0, n, Charsets.UTF_8)
        }
        Regex(""""(?:updated|fetched)"\s*:\s*"(\d{4})-(\d\d)-(\d\d)""").find(head)
            ?.destructured?.let { (y, m, d) -> "$d/$m/$y" }
    }.getOrNull()

    private fun date(ms: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date(ms))

    /**
     * Each table: the date of its data (user request 2026-11-09: "updated" or "fetched" in the file,
     * the day the content last changed at the source side), and when it reached this phone.
     */
    /**
     * One clear state per data set (user request 2026-11-09: "updated or not?"). A successful
     * check compares every table with the server and downloads what differs, so after it every
     * table equals the server: "מעודכן", also a table still as packed in the APK (nothing newer
     * existed). Without a successful check in [FRESH_MS] the state is "לא נבדק" in orange.
     * The second line is the date of the data itself (or of the download, for the road index).
     */
    private fun showDataDates() {
        val at = getSharedPreferences(AlertService.PREFS, MODE_PRIVATE).getLong(DataUpdate.CHECKED_AT, 0)
        val fresh = at > 0 && System.currentTimeMillis() - at < FRESH_MS
        for ((key, name) in DATA_ROWS) {
            val f = File(filesDir, name)
            val of = dataDate(name)
            val note = when {
                of != null -> "נתונים מ-$of"
                f.exists() -> "הורד ב-${day(f.lastModified())}"
                else -> "כפי שבגרסת האפליקציה"
            } + if (name == Database.SECTIONS) ". מתעדכן ידנית, אין עדיין מקור ציבורי" else ""
            show(key, if (fresh) "מעודכן" else "לא נבדק", note, if (fresh) GREEN else ORANGE)
        }
        show(K_CHECKED, if (at == 0L) "עדיין לא" else date(at),
            if (fresh) "כל המאגרים זהים לשרת. בדיקה כל 30 דקות כשההתראות פועלות, ובכל פתיחה."
            else "אין בדיקה מוצלחת ב-24 השעות האחרונות: בדוק חיבור לאינטרנט.")
    }

    private fun day(ms: Long) = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(Date(ms))

    // ---- speed-sign photos (user request 2026-11-08) ----

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        signPhoto?.let { out.putString(K_SIGN_PHOTO, it.path) }
    }

    private var signPhoto: File? = null
    private var signFix: Location? = null
    private var signRoads: RoadTiles? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val signFixes = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.lastLocation?.let { signFix = it; onSignFix?.invoke(it) } }
    }
    /** Fixes that arrive while the confirm dialog is open (a cold GPS when alerts are off). */
    private var onSignFix: ((Location) -> Unit)? = null
    private fun stopSignFixes() { onSignFix = null; runCatching { fused.removeLocationUpdates(signFixes) } }

    /** Opens the phone's camera; the location is followed at full accuracy until the photo comes back. */
    private fun startSignPhoto() = withPermissions {
        val f = SignLog.newPhoto(this)
        signPhoto = f; signFix = null
        signRoads = RoadTiles(this)
        runCatching {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build(),
                signFixes, Looper.getMainLooper())
            fused.lastLocation.addOnSuccessListener { l ->
                if (signFix == null && l != null && System.currentTimeMillis() - l.time < 30_000) signFix = l
            }
        }
        // The app's own camera (zoom, touch focus, burst): the phone's camera app gave blurred photos.
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SignCameraActivity::class.java)
            .putExtra(SignCameraActivity.EXTRA_PHOTO, f.path), REQ_SIGN)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SIGN) return
        val fix = signFix
        val photo = signPhoto ?: return stopSignFixes()
        if (resultCode != RESULT_OK || !photo.exists() || photo.length() == 0L) { stopSignFixes(); photo.delete(); return }
        val roads = signRoads ?: RoadTiles(this)
        Thread {
            val small = runCatching { SignLog.prepare(photo) }.getOrNull()
            val burst = data?.getIntExtra(SignCameraActivity.EXTRA_READ, -1)?.takeIf { it > 0 }
            val read = burst ?: small?.let { runCatching { SignLog.readNumber(it) }.getOrNull() }
            val limits = if (fix == null || roads == null) emptyList() else dbLimits(roads, fix)
            ui.post { confirmSign(photo, small, read, fix, limits, roads) }
        }.start()
    }

    /** Road table limits at the photo's place: along the direction of travel, else in any direction. */
    private fun dbLimits(roads: RoadTiles, fix: Location): List<Int> {
        val headings = if (fix.hasBearing() && fix.hasSpeed() && fix.speed > 2) listOf(fix.bearing.toDouble())
                       else listOf(0.0, 90.0, 180.0, 270.0)
        repeat(30) {   // the tile may still be downloading (up to about 3 s)
            val found = headings.mapNotNull { roads.at(fix.latitude, fix.longitude, it)?.limit }.distinct()
            if (found.isNotEmpty()) return found
            Thread.sleep(100)
        }
        return emptyList()
    }

    private fun confirmSign(photo: File, small: android.graphics.Bitmap?, read: Int?, fix0: Location?, limits0: List<Int>,
                            roads: RoadTiles?) {
        if (isFinishing || isDestroyed) return stopSignFixes()
        var fix = fix0; var limits = limits0
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(20), px(8), px(20), 0) }
        small?.let { box.addView(ImageView(this).apply { setImageBitmap(it); adjustViewBounds = true },
            LinearLayout.LayoutParams(-1, px(220))) }
        val got = if (read == null) "לא זוהה מספר בתמונה: הקלד את המספר שעל השלט." else "זוהה בתמונה: $read"
        fun info(): String {
            val f = fix
            val where = if (f == null) "אין מיקום עדיין: ממתין ל-GPS."
                        else "מיקום: דיוק ${f.accuracy.toInt()} מטר" + (if (f.hasBearing()) ", כיוון ${f.bearing.toInt()}°" else "") +
                            (if (f.accuracy > GOOD_FIX_M) " (משתפר...)" else "")
            val db = if (limits.isEmpty()) "במאגר: לא ידוע" else "במאגר: ${limits.joinToString(" / ")}"
            return "$got\n$db\n$where"
        }
        val infoView = text(15f, pal.text, info()).apply { setPadding(0, px(8), 0, px(8)) }
        box.addView(infoView)
        // A cold GPS (alerts off) gives a coarse first fix: keep a better one taken at the same place.
        onSignFix = { l ->
            val f = fix
            if (f == null || (l.accuracy < f.accuracy && l.distanceTo(f) <= f.accuracy + 30)) {
                val wasCoarse = f == null || f.accuracy > GOOD_FIX_M
                fix = l; infoView.text = info()
                if (roads != null && wasCoarse) Thread {
                    val found = dbLimits(roads, l)
                    ui.post { if (fix === l) { limits = found; infoView.text = info() } }
                }.start()
            }
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "המהירות שעל השלט"
            setText(read?.toString() ?: ""); textSize = 22f
        }
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle("שלט מהירות")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("שמור") { _, _ ->
                val v = input.text.toString().toIntOrNull()
                if (v == null || v !in 5..130) { photo.delete(); return@setPositiveButton }
                SignLog.add(this, SignLog.Entry(fix?.latitude ?: 0.0, fix?.longitude ?: 0.0, fix?.accuracy ?: -1f,
                    fix?.takeIf { it.hasBearing() }?.bearing, fix?.let { (System.currentTimeMillis() - it.time) / 1000 } ?: -1,
                    read, v, limits, photo.name))
            }
            .setNegativeButton("בטל") { _, _ -> photo.delete() }
            .setOnCancelListener { photo.delete() }
            .setOnDismissListener { stopSignFixes() }
            .show()
    }

    /** The sign log; "שלח" hands the table and the photos to the phone's share sheet. */
    private fun showSignLog() {
        val view = TextView(this).apply {
            text = SignLog.report(this@MainActivity)
            textSize = 12f; setTextIsSelectable(true); setPadding(px(16), px(8), px(16), px(8))
        }
        AlertDialog.Builder(this)
            .setTitle("יומן שלטי מהירות")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("שלח") { _, _ -> shareSignLog() }
            .setNegativeButton("סגור", null)
            .show()
    }

    private fun shareSignLog() {
        val log = SignLog.file(this)
        if (!log.exists()) return
        val uris = ArrayList<Uri>()
        (listOf(log) + SignLog.photos(this).takeLast(MAX_SHARED_PHOTOS)).forEach {
            uris += FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "SpeedAlert speed signs ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, SignLog.report(this@MainActivity))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "שליחת יומן השלטים"))
    }

    private fun minutes(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "$m דקות" else "${m / 60} שעות ו-${m % 60} דקות"
    }

    // ---- view helpers ----

    private fun text(size: Float, color: Int, value: String = "") = TextView(this).apply {
        textSize = size; setTextColor(color); text = value; setLineSpacing(0f, 1.2f)
    }

    private fun round(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radiusDp).toFloat()
        stroke?.let { setStroke(px(1), it) }
    }

    private fun card(accent: Int? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(16))
        background = round(pal.card, 16, accent ?: pal.line)
        elevation = px(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(12) }
    }

    private fun button(label: String, bg: Int, fg: Int, size: Float, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = size; setTextColor(fg); isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        background = round(bg, 14, if (bg == pal.card) pal.line else null)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    /**
     * One table row (user request 2026-10-29): the label at the start and a short value at the
     * end of one line, with notes under them across the whole width.
     */
    private fun row(key: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(6), 0, px(6))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(15f, pal.muted, key).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(15f, pal.text).apply {
                setTypeface(typeface, Typeface.BOLD); maxLines = 1; info[key] = this
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(12) })
        })
        addView(text(13f, pal.muted).apply { visibility = View.GONE; notes[key] = this })
    }

    private fun show(key: String, value: String, note: String = "", color: Int = pal.text) {
        info.getValue(key).apply { text = value; setTextColor(color) }
        notes.getValue(key).apply { text = note; visibility = if (note.isEmpty()) View.GONE else View.VISIBLE }
    }

    // ---- actions ----

    /**
     * Launch-time data check when the service is not running (it checks every 30 minutes itself);
     * the app version is checked in [checkVersion].
     */
    private fun checkDataAtLaunch() {
        Thread {
            if (AlertService.running) return@Thread
            AlertService.dataInfo = "${Database.cameras(this).size} מצלמות"
            AlertService.dataNote = runCatching {
                val changed = DataUpdate.run(this).any
                AlertService.dataInfo = "${Database.cameras(this).size} מצלמות"
                (if (changed) "עודכן, " else "") + "נתונים מ-${Database.fetched(this)}"
            }.getOrElse { "נתונים מ-${Database.fetched(this)}, הבדיקה נכשלה" }
        }.start()
    }

    @Volatile private var updating = false

    /**
     * Version check whenever the screen opens or comes back (user request 2026-10-29: at every
     * launch), at most once a minute; the service also checks with every data check.
     */
    /**
     * Every time the app comes to the screen (user request 2026-11-02): asks for the version and,
     * when a newer one exists, offers it in a dialog instead of only a note in the version row.
     */
    private fun checkVersion() {
        val now = System.currentTimeMillis()
        if (now - lastVersionCheck < 5_000) return   // two resumes in a row (permission screens)
        lastVersionCheck = now
        Thread {
            val r = runCatching { AppUpdate.check() }.onFailure { lastVersionCheck = 0 }.getOrNull() ?: return@Thread
            ui.post { offerUpdate(r) }
        }.start()
    }

    private var updateDialog: AlertDialog? = null

    /**
     * The usage log kept on the phone (user request 2026-11-08). The app sends nothing itself:
     * "שלח" opens the phone's own share sheet (mail, WhatsApp, Drive) with the file.
     */
    private fun showUsageLog() {
        val view = TextView(this).apply {
            text = UsageLog.report(this@MainActivity)
            textSize = 12f; setTextIsSelectable(true); setPadding(px(16), px(8), px(16), px(8))
        }
        AlertDialog.Builder(this)
            .setTitle("יומן שימוש")
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton("שלח") { _, _ -> shareUsageLog() }
            .setNegativeButton("סגור", null)
            .show()
    }

    private fun shareUsageLog() {
        val f = UsageLog.file(this)
        if (!f.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "SpeedAlert usage log ${BuildConfig.VERSION_NAME}")
            putExtra(Intent.EXTRA_TEXT, UsageLog.report(this@MainActivity, tail = 0))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "שליחת יומן השימוש"))
    }

    private fun offerUpdate(r: RemoteVersion) {
        if (isFinishing || isDestroyed || updating || updateDialog?.isShowing == true) return
        updateDialog = AlertDialog.Builder(this)
            .setTitle("עדכון זמין")
            .setMessage("גרסה ${r.versionName} זמינה. מותקנת ${BuildConfig.VERSION_NAME}.")
            .setPositiveButton("עדכן") { _, _ -> checkAppUpdate() }
            .setNegativeButton("לא עכשיו", null)
            .show()
    }

    private fun checkAppUpdate() {
        if (updating) return   // a second tap while downloading
        if (!AppUpdate.canInstall(this)) {
            updateText = getString(R.string.update_allow)
            startActivity(AppUpdate.unknownSourcesIntent(this))
            return
        }
        updateText = getString(R.string.update_checking)
        updating = true
        Thread {
            updateText = try {
                val remote = AppUpdate.check()
                if (remote == null) {
                    getString(R.string.update_none, BuildConfig.VERSION_NAME)
                } else {
                    updateText = getString(R.string.update_downloading, remote.versionName)
                    val apk = AppUpdate.download(this, remote)
                    startActivity(AppUpdate.installIntent(this, apk))
                    ""
                }
            } catch (e: Exception) {
                getString(R.string.update_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                updating = false
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        checkVersion()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    /** The sign photo's full-accuracy GPS never outlives the screen (review 2026-11-09). */
    override fun onDestroy() {
        stopSignFixes()
        super.onDestroy()
    }

    private fun onToggle() {
        if (AlertService.running) AlertService.stop(this) else withPermissions { AlertService.start(this) }
    }

    private fun withPermissions(action: () -> Unit) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // Optional: driving start and end from the phone's activity recognition (saves power).
            if (Build.VERSION.SDK_INT >= 29) add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            action()
        } else {
            afterGrant = action
            requestPermissions(needed.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            afterGrant?.invoke()
        } else {
            AlertService.status = "נדרשת הרשאת מיקום מדויק"
        }
        afterGrant = null
    }

    companion object {
        @Volatile private var lastVersionCheck = 0L
        const val HELP_URL = "https://eldadgalker-dev.github.io/SpeedAlert/"
        private const val RED = 0xFFC62828.toInt()
        private const val GREEN = 0xFF2E7D32.toInt()
        private const val GREY = 0xFF757575.toInt()
        private const val BLUE = 0xFF1565C0.toInt()
        private const val ORANGE = 0xFFE65100.toInt()
        private const val REQ_SIGN = 7
        private const val GOOD_FIX_M = 20f
        private const val K_SIGN_PHOTO = "sign_photo"
        private const val MAX_SHARED_PHOTOS = 50
        private val DATA_ROWS = linkedMapOf(
            "מצלמות" to Database.CAMERAS,
            "מהירות בכבישים" to RoadTiles.INDEX,
            "קנסות ונקודות" to Database.PENALTIES,
            "נתיבי תחבורה ציבורית" to Database.BUS_LANES,
            "קטעי מהירות ממוצעת" to Database.SECTIONS,
        )
        private const val K_CHECKED = "בדיקה אחרונה מול השרת"
        private const val FRESH_MS = 24 * 3600_000L
        private const val K_BATTERY = "סוללה"
        private const val K_DRAIN = "סוללה (האפליקציה)"
        private const val K_MEMORY = "זיכרון האפליקציה"
        private const val K_CPU = "מעבד (האפליקציה)"
        private const val K_UPTIME = "זמן פעילות"
        private const val K_DATA = "מאגר מצלמות"
        private const val K_VERSION = "גרסת תוכנה"
        private val COPYRIGHT_LINES = listOf(
            "Copyright (c) 1986-2026 Eldad Galker",
            "eldad@galker.com",
            "https://www.galker.com/software/",
            "רישיון: ⁦BSD-3-Clause⁩",
            "נתוני מפה: ⁦© OpenStreetMap contributors, ODbL⁩",
            "נתיבי תחבורה ציבורית: משרד התחבורה, ⁦data.gov.il, CC-BY⁩",
        )
    }
}
