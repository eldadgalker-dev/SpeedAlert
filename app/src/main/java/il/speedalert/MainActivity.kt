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
    private lateinit var toggle: Button
    private var afterGrant: (() -> Unit)? = null
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            statusView.text = AlertService.status
            alertView.text = AlertService.lastAlert
            toggle.text = getString(if (AlertService.running) R.string.stop else R.string.start)
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        statusView = TextView(this).apply { textSize = 20f; gravity = Gravity.CENTER }
        alertView = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(0, pad, 0, pad) }
        toggle = Button(this).apply { textSize = 22f; setOnClickListener { onToggle() } }
        val test = Button(this).apply {
            setText(R.string.test_voice)
            setOnClickListener { withPermissions { AlertService.start(this@MainActivity, AlertService.ACTION_TEST) } }
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
        })
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
