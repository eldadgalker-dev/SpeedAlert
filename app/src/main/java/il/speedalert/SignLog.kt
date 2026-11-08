// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Speed-sign log (user request 2026-11-08): photos of speed-limit signs taken by the user, each
 * with the exact location, the number read from the photo, the number the user confirmed and the
 * limit the road table gives there, so that corrections to the database can be checked. Kept on
 * the phone in filesDir/signs; the app never sends it, the user hands it to the share sheet.
 *
 * Line: `time,version,lat,lon,accuracy_m,bearing,fix_age_s,read_kmh,sign_kmh,db_limit,differs,photo`.
 */
object SignLog {
    const val HEADER = "time,version,lat,lon,accuracy_m,bearing,fix_age_s,read_kmh,sign_kmh,db_limit,differs,photo"
    private const val FILE = "signs-log.csv"
    const val MAX_SIDE = 1600

    fun dir(ctx: Context) = File(ctx.filesDir, "signs").apply { mkdirs() }
    fun file(ctx: Context) = File(dir(ctx), FILE)

    fun newPhoto(ctx: Context) =
        File(dir(ctx), "sign-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".jpg")

    class Entry(
        val lat: Double, val lon: Double, val accuracy: Float, val bearing: Float?, val fixAgeS: Long,
        val read: Int?, val sign: Int, val dbLimits: List<Int>, val photo: String,
    )

    @Synchronized fun add(ctx: Context, e: Entry) {
        val f = file(ctx)
        if (!f.exists()) f.writeText(HEADER + "\n")
        val time = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT).format(Date())
        val db = e.dbLimits.joinToString("/")
        val differs = if (e.dbLimits.isEmpty()) "unknown" else if (e.sign in e.dbLimits) "no" else "yes"
        f.appendText(listOf(time, BuildConfig.VERSION_NAME, "%.6f".format(Locale.ROOT, e.lat), "%.6f".format(Locale.ROOT, e.lon),
            "%.0f".format(Locale.ROOT, e.accuracy), e.bearing?.let { "%.0f".format(Locale.ROOT, it) } ?: "",
            e.fixAgeS.toString(), e.read?.toString() ?: "", e.sign.toString(), db, differs, e.photo).joinToString(",") + "\n")
    }

    fun lines(ctx: Context): List<String> =
        runCatching { file(ctx).readLines() }.getOrDefault(emptyList()).drop(1).filter { it.isNotBlank() }

    fun report(ctx: Context): String {
        val l = lines(ctx)
        if (l.isEmpty()) return "היומן ריק. צלם שלט מהירות בכפתור \"צילום שלט מהירות\" במסך הראשי."
        val differ = l.count { it.split(",").getOrNull(10) == "yes" }
        return buildString {
            append("${l.size} שלטים, $differ מהם שונים מהמאגר.\n\n$HEADER\n")
            l.takeLast(200).forEach { append(it).append('\n') }
        }
    }

    /** Photos named in the log that still exist, newest last. */
    fun photos(ctx: Context): List<File> =
        lines(ctx).mapNotNull { it.split(",").getOrNull(11) }.map { File(dir(ctx), it) }.filter { it.exists() }

    /**
     * Turns the camera's photo upright, shrinks it to [MAX_SIDE] and saves it in place;
     * returns a small copy (about 480 px) for reading the number.
     */
    fun prepare(photo: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val turn = when (runCatching { ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height))
        if (turn != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(turn) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        photo.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val small = 480f / maxOf(bmp.width, bmp.height)
        return if (small >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * small).toInt(), (bmp.height * small).toInt(), true)
    }

    fun readNumber(small: Bitmap): Int? {
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        return SignReader.read(px, small.width, small.height)
    }
}
