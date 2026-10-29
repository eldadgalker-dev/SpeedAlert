// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Plain HTTPS GET helpers (blocking: call from a background thread). */
object Http {
    fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "SpeedAlert/${BuildConfig.VERSION_NAME}")
        if (responseCode != 200) throw IOException("HTTP $responseCode: $url")
    }

    fun text(url: String): String = open(url).inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Keeps cameras.json / penalties.json current from GitHub (data/ on main, refreshed hourly
 * by .github/workflows/data.yml). The app checks at launch and every [INTERVAL_MS] while running.
 */
object DataUpdate {
    const val INTERVAL_MS = 30 * 60_000L

    /**
     * Downloads the tables, keeps them only when they parse; says what changed. For the roads
     * only the tile index (~40 KB) is downloaded; [RoadTiles] fetches a changed tile when the
     * car comes near it (user request 2026-10-29: download only what is needed).
     */
    fun run(ctx: Context): Changed {
        var changed = false
        var roadsChanged = false
        for (name in listOf(Database.CAMERAS, Database.PENALTIES, Database.BUS_LANES, Database.SECTIONS)) {
            val text = Http.text("${BuildConfig.DATA_URL}/$name")
            validate(name, text)
            if (text == Database.read(ctx, name)) continue
            save(ctx, name, text)
            changed = true
        }
        val index = Http.text("${BuildConfig.DATA_URL}/${RoadTiles.INDEX}")
        validate(RoadTiles.INDEX, index)
        if (index != Database.read(ctx, RoadTiles.INDEX)) {
            File(ctx.filesDir, RoadTiles.DIR).mkdirs()
            save(ctx, RoadTiles.INDEX, index)
            roadsChanged = true
        }
        return Changed(changed, roadsChanged)
    }

    class Changed(val tables: Boolean, val roads: Boolean) {
        val any get() = tables || roads
    }

    private fun save(ctx: Context, name: String, text: String) {
        val tmp = File(ctx.filesDir, "$name.${Thread.currentThread().id}.tmp")
        tmp.parentFile?.mkdirs()
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(File(ctx.filesDir, name))) throw IOException("cannot save $name")
    }

    /** A truncated or broken download must never replace a working table. */
    fun validate(name: String, text: String) {
        when (name) {
            Database.CAMERAS -> require(Database.parseCameras(text).size >= MIN_CAMERAS) { "too few cameras" }
            Database.PENALTIES -> Penalties.parse(text).let { p ->
                require(p.forExcess(25, true) != null && p.forExcess(25, false) != null) { "penalty table incomplete" }
            }
            Database.BUS_LANES -> require(Database.parseBusLanes(text).isNotEmpty()) { "no bus lanes" }
            Database.SECTIONS -> Database.parseSections(text)
            RoadTiles.INDEX -> require(RoadTiles.parseIndex(text).size >= MIN_ROAD_TILES) { "too few road tiles" }
        }
    }

    private const val MIN_CAMERAS = 50
    private const val MIN_ROAD_TILES = 300
}

/** Release contract (release.yml): latest Release has SpeedAlert.apk and version.json {versionCode, versionName, sha256}. */
data class RemoteVersion(val versionCode: Int, val versionName: String, val sha256: String)

object AppUpdate {
    const val APK = "SpeedAlert.apk"

    /** Newer version found by the last check (at every launch and every data check; user request 2026-10-29). */
    @Volatile var available: RemoteVersion? = null

    /** The remote version when newer than the installed one, else null. */
    fun check(): RemoteVersion? {
        val o = JSONObject(Http.text("${BuildConfig.RELEASE_URL}/version.json"))
        val r = RemoteVersion(o.getInt("versionCode"), o.getString("versionName"), o.getString("sha256").lowercase())
        return (if (r.versionCode > BuildConfig.VERSION_CODE) r else null).also { available = it }
    }

    /**
     * Downloads the APK to cache/updates and verifies its SHA-256 before it may be installed.
     * Hashed while it streams and written under a name of its own, then renamed: a second
     * download, or the system clearing the cache, can no longer remove the file between the
     * write and the check (field report 2026-10-29: "open failed: ENOENT").
     */
    @Synchronized
    fun download(ctx: Context, r: RemoteVersion): File {
        val dir = File(ctx.cacheDir, "updates")
        val apk = File(dir, "SpeedAlert-${r.versionCode}.apk")
        val part = File(dir, "${apk.name}.part")
        dir.listFiles()?.forEach { if (it != apk) it.delete() }
        if (apk.isFile && Http.sha256(apk) == r.sha256) return apk
        dir.mkdirs()
        val md = MessageDigest.getInstance("SHA-256")
        Http.open("${BuildConfig.RELEASE_URL}/$APK").inputStream.use { i ->
            part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n); o.write(buf, 0, n) }
            }
        }
        if (md.digest().joinToString("") { "%02x".format(it) } != r.sha256) {
            part.delete()
            throw SecurityException("APK hash mismatch")
        }
        apk.delete()
        if (!part.renameTo(apk)) throw IOException("cannot keep the downloaded APK")
        return apk
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))

    /** The system installer rejects an APK signed with a different key than the installed app. */
    fun installIntent(ctx: Context, apk: File): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk),
                "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
