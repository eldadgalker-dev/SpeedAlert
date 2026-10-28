package il.speedalert

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var alertView: TextView
    private lateinit var infoView: TextView
    private lateinit var toggle: Button
    private var afterGrant: (() -> Unit)? = null
    private var updateText = ""
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            statusView.text = AlertService.status
            alertView.text = AlertService.lastAlert
            infoView.text = listOf(AlertService.dataInfo, updateText).filter { it.isNotEmpty() }.joinToString("\n")
            toggle.text = getString(if (AlertService.running) R.string.stop else R.string.start)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        statusView = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER }
        alertView = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) }
        infoView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(0, pad, 0, 0) }
        toggle = Button(this).apply { textSize = 22f; setOnClickListener { onToggle() } }
        val test = Button(this).apply {
            setText(R.string.test_voice)
            setOnClickListener { withPermissions { AlertService.start(this@MainActivity, AlertService.ACTION_TEST) } }
        }
        val update = Button(this).apply {
            setText(R.string.check_update)
            setOnClickListener { checkAppUpdate() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(pad, pad, pad, pad)
            addView(statusView)
            addView(alertView)
            addView(toggle)
            addView(test)
            addView(update)
            addView(infoView)
        })
        checkDataAtLaunch()
    }

    /** Launch-time data check; while the service runs it repeats every 30 minutes. */
    private fun checkDataAtLaunch() {
        if (AlertService.running) return
        Thread {
            AlertService.dataInfo = runCatching {
                val changed = DataUpdate.run(this)
                val n = Database.cameras(this).size
                (if (changed) "מאגר עודכן: " else "מאגר עדכני: ") + "$n מצלמות (${Database.fetched(this)})"
            }.getOrElse { "בדיקת מאגר נכשלה, בשימוש המאגר הקיים (${Database.fetched(this)})" }
        }.start()
    }

    private fun checkAppUpdate() {
        if (!AppUpdate.canInstall(this)) {
            updateText = getString(R.string.update_allow)
            startActivity(AppUpdate.unknownSourcesIntent(this))
            return
        }
        updateText = getString(R.string.update_checking)
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
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
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
}
