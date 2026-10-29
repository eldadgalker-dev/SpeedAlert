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

    /** CPU time of this app only, as a share of the whole processor (all cores) since it started, percent. */
    fun cpuPercent(): Double {
        val alive = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return if (alive <= 0) 0.0 else 100.0 * Process.getElapsedCpuTime() / alive / cores
    }

    /**
     * Estimated battery use of this app only, percent of the battery per hour. Android gives
     * per-app energy only to system apps, so it is computed from what the app itself used:
     * its CPU time and the time GPS ran at full accuracy or in power-saving mode, times typical
     * currents ([CPU_MA], [GPS_MA], [SAVING_MA]), over the battery capacity.
     */
    fun appDrainPerHour(ctx: Context, gpsMs: Long, savingMs: Long): Double? {
        val aliveMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        if (aliveMs < 60_000) return null
        val mAh = Process.getElapsedCpuTime() / 3_600_000.0 * CPU_MA +
            gpsMs / 3_600_000.0 * GPS_MA + savingMs / 3_600_000.0 * SAVING_MA
        return 100.0 * mAh / capacityMah(ctx) / (aliveMs / 3_600_000.0)
    }

    /** Battery capacity from the charge counter and the level; [DEFAULT_MAH] when the phone does not report it. */
    private fun capacityMah(ctx: Context): Double {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val uAh = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val level = batteryLevel(ctx)
        val mah = if (uAh > 0 && level in 5..100) uAh / 1000.0 / (level / 100.0) else 0.0
        return if (mah in 1000.0..10000.0) mah else DEFAULT_MAH
    }

    private const val CPU_MA = 200.0     // one core busy
    private const val GPS_MA = 40.0      // GPS at full accuracy, once a second
    private const val SAVING_MA = 3.0    // balanced (network) location every 15 s
    private const val DEFAULT_MAH = 4500.0
}
