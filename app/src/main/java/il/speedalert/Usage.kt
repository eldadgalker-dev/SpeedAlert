// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock

/** Battery and resource figures for the main screen. */
object Usage {
    fun batteryLevel(ctx: Context): Int =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun charging(ctx: Context): Boolean =
        (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging

    /** Memory used by this app (PSS), MB. */
    fun memoryMb(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info: Debug.MemoryInfo = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0]
        return info.totalPss / 1024
    }

    /** CPU time used by the app as a share of the time it has been alive, percent. */
    fun cpuPercent(): Double {
        val alive = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        return if (alive <= 0) 0.0 else 100.0 * Process.getElapsedCpuTime() / alive
    }

    /**
     * Battery drop per hour since alerts were started, or null while charging, before
     * 10 minutes of data, or when the level did not change yet.
     * This is the whole phone's drain (screen, navigation, music included), not only this app.
     */
    fun drainPerHour(startLevel: Int, now: Int, startedAt: Long, charging: Boolean): Double? {
        val hours = (System.currentTimeMillis() - startedAt) / 3_600_000.0
        if (charging || startLevel < 0 || hours < 1.0 / 6 || now >= startLevel) return null
        return (startLevel - now) / hours
    }
}
