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

    /** CPU, GPS and saving-mode time at one moment, ms. */
    private class Sample(val at: Long, val cpu: Long, val gps: Long, val saving: Long)
    private val samples = ArrayDeque<Sample>()

    /** App-only figures over the last minutes: [cpuPercent] of the whole processor, [drainPerHour] percent of the battery (estimate). */
    class Recent(val minutes: Int, val cpuPercent: Double, val drainPerHour: Double)

    /**
     * Figures of this app only over about the last [WINDOW_MS] (user request 2026-10-29: the
     * average since start hid the saving mode). Android gives per-app energy only to system
     * apps, so the battery is estimated from what the app itself used: its CPU time and the time
     * GPS ran at full accuracy or in the network fallback, times typical currents ([CPU_MA],
     * [GPS_MA], [SAVING_MA]), over the battery capacity. Saving with the motion sensor
     * ([cheapSaving]) costs no location power. Samples are kept while the screen reads them, so
     * after a long time away the window covers that whole time. Null for the first minute.
     */
    fun recent(ctx: Context, gpsMs: Long, savingMs: Long, cheapSaving: Boolean): Recent? {
        val now = Sample(SystemClock.elapsedRealtime(), Process.getElapsedCpuTime(), gpsMs, savingMs)
        val first = synchronized(samples) {
            if (samples.isEmpty()) samples.addLast(Sample(Process.getStartElapsedRealtime(), 0, 0, 0))
            if (now.at - samples.last().at >= STEP_MS) samples.addLast(now)
            while (samples.size > 2 && now.at - samples[1].at >= WINDOW_MS) samples.removeFirst()
            samples.first()
        }
        val ms = now.at - first.at
        if (ms < 60_000) return null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpu = (now.cpu - first.cpu).toDouble()
        val mAh = (cpu * CPU_MA + (now.gps - first.gps) * GPS_MA +
            (now.saving - first.saving) * (if (cheapSaving) 0.0 else SAVING_MA)) / 3_600_000.0
        return Recent((ms / 60_000).toInt(), 100.0 * cpu / ms / cores, 100.0 * mAh / capacityMah(ctx) / (ms / 3_600_000.0))
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
    private const val WINDOW_MS = 10 * 60_000L
    private const val STEP_MS = 30_000L
}
