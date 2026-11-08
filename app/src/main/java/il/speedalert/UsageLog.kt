// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.content.Context
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Usage log kept on the phone across starts (user request 2026-11-08: the figures on the screen
 * start from zero at every start). Every [INTERVAL_MS] while the service runs, and at stop, one
 * line with the app version, the battery, the app's CPU share and the estimated battery drain of
 * the interval. The app never sends it anywhere; the screen shows it and hands it to the phone's
 * own share sheet. Kept to [MAX_LINES].
 *
 * Line: `time,version,event,minutes,cpu%,drain%/h,battery%,charging,mode`.
 */
object UsageLog {
    const val INTERVAL_MS = 15 * 60_000L
    private const val FILE = "usage-log.csv"
    private const val MAX_LINES = 5000
    const val HEADER = "time,version,event,minutes,cpu%,drain%/h,battery%,charging,mode"

    private var at = 0L
    private var cpu = 0L
    private var gps = 0L
    private var saving = 0L

    fun file(ctx: Context) = File(File(ctx.filesDir, "logs").apply { mkdirs() }, FILE)

    private fun now() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date())

    @Synchronized private fun append(ctx: Context, line: String) {
        val f = file(ctx)
        runCatching {
            if (!f.exists()) f.writeText(HEADER + "\n")
            f.appendText(line + "\n")
            val lines = f.readLines()
            if (lines.size > MAX_LINES + 200) f.writeText((listOf(HEADER) + lines.takeLast(MAX_LINES)).joinToString("\n", postfix = "\n"))
        }
    }

    private fun battery(ctx: Context) = "${Usage.batteryLevel(ctx)},${if (Usage.charging(ctx)) "yes" else "no"}"

    /** Start, stop and other events; starts a new interval. */
    @Synchronized fun event(ctx: Context, what: String, gpsMs: Long, savingMs: Long) {
        append(ctx, "${now()},${BuildConfig.VERSION_NAME},$what,,,,${battery(ctx)},")
        at = SystemClock.elapsedRealtime(); cpu = Process.getElapsedCpuTime(); gps = gpsMs; saving = savingMs
    }

    /** The interval since the last line: the app's CPU share of the whole processor and the estimated drain. */
    @Synchronized fun sample(ctx: Context, gpsMs: Long, savingMs: Long, cheapSaving: Boolean, mode: String) {
        val t = SystemClock.elapsedRealtime(); val c = Process.getElapsedCpuTime()
        val ms = t - at
        if (at == 0L || ms < 60_000) return
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpuMs = (c - cpu).toDouble()
        val mAh = Usage.estimateMah(cpuMs, (gpsMs - gps).toDouble(), (savingMs - saving).toDouble(), cheapSaving)
        val drain = 100.0 * mAh / Usage.capacityMah(ctx) / (ms / 3_600_000.0)
        append(ctx, "${now()},${BuildConfig.VERSION_NAME},sample,${ms / 60_000}," +
            "%.3f,%.3f,".format(Locale.ROOT, 100.0 * cpuMs / ms / cores, drain) + "${battery(ctx)},$mode")
        at = t; cpu = c; gps = gpsMs; saving = savingMs
    }

    /** Totals over the whole log and per version, then the last [tail] lines. */
    fun report(ctx: Context, tail: Int = 150): String {
        val lines = runCatching { file(ctx).readLines() }.getOrDefault(emptyList()).drop(1).filter { it.isNotBlank() }
        if (lines.isEmpty()) return "היומן ריק. שורה נרשמת כל ${INTERVAL_MS / 60_000} דקות בזמן שההתראות פועלות."
        class Acc { var min = 0.0; var cpu = 0.0; var drain = 0.0; var starts = 0 }
        val all = Acc(); val byVersion = linkedMapOf<String, Acc>()
        for (l in lines) {
            val p = l.split(",")
            if (p.size < 9) continue
            val v = byVersion.getOrPut(p[1]) { Acc() }
            if (p[2] == "start") { v.starts++; all.starts++ }
            val m = p[3].toDoubleOrNull() ?: continue
            val cp = p[4].toDoubleOrNull() ?: continue
            val dr = p[5].toDoubleOrNull() ?: continue
            for (a in listOf(all, v)) { a.min += m; a.cpu += cp * m; a.drain += dr * m }
        }
        fun Acc.line() = if (min <= 0) "אין מדידות" else
            "%.1f שעות, מעבד %.2f%%, סוללה %.2f%% לשעה (הערכה), %d הפעלות".format(min / 60, cpu / min, drain / min, starts)
        return buildString {
            append("מתחילת היומן (${lines.first().substringBefore(",")}): ${all.line()}\n\n")
            for ((v, a) in byVersion) append("גרסה $v: ${a.line()}\n")
            append("\nשורות אחרונות:\n$HEADER\n")
            lines.takeLast(tail).forEach { append(it).append('\n') }
        }
    }
}
