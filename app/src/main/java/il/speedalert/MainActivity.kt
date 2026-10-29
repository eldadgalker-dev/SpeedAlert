// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.Manifest
import android.app.Activity
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
        super.onCreate(savedInstanceState)
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

        // Secondary actions
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(getString(R.string.test_voice), pal.card, pal.text, 15f) {
                withPermissions { AlertService.start(this@MainActivity, AlertService.ACTION_TEST) }
            }, LinearLayout.LayoutParams(0, px(52), 1f).apply { marginEnd = px(8) })
            addView(button(getString(R.string.check_update), pal.card, pal.text, 15f) { checkAppUpdate() },
                LinearLayout.LayoutParams(0, px(52), 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
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
        updateView.text = updateText
        updateView.visibility = if (updateText.isEmpty()) View.GONE else View.VISIBLE

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
        if (renders++ % 10 == 0) show(K_MEMORY, "${Usage.memoryMb(this)} MB")
        show(K_UPTIME, if (running) minutes(System.currentTimeMillis() - AlertService.startedAt) else "–")
        show(K_DATA, AlertService.dataInfo.ifEmpty { "בודק…" }, AlertService.dataNote)
        val newer = AppUpdate.available
        show(K_VERSION, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            if (newer == null) "" else "גרסה ${newer.versionName} זמינה: לחץ \"בדוק עדכון\"")
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

    private fun show(key: String, value: String, note: String = "") {
        info.getValue(key).text = value
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
    private fun checkVersion() {
        val now = System.currentTimeMillis()
        if (now - lastVersionCheck < 60_000) return
        lastVersionCheck = now
        Thread { runCatching { AppUpdate.check() }.onFailure { lastVersionCheck = 0 } }.start()
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
        )
    }
}
