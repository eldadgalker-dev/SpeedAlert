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
     * Downloads the tables, keeps them only when they parse; says what changed, so the service
     * re-parses roads.txt (heavy) only when it changed.
     * roads.txt (~12 MB) is downloaded only when roads.version (its sha256) changed.
     */
    fun run(ctx: Context): Changed {
        var changed = false
        var roadsChanged = false
        for (name in listOf(Database.CAMERAS, Database.PENALTIES, Database.BUS_LANES)) {
            val text = Http.text("${BuildConfig.DATA_URL}/$name")
            validate(name, text)
            if (text == Database.read(ctx, name)) continue
            save(ctx, name, text)
            changed = true
        }
        val version = Http.text("${BuildConfig.DATA_URL}/$ROADS_VERSION").trim().lowercase()
        if (version != runCatching { Database.read(ctx, ROADS_VERSION).trim() }.getOrNull()) {
            val roads = Http.text("${BuildConfig.DATA_URL}/${Database.ROADS}")
            val sha = MessageDigest.getInstance("SHA-256").digest(roads.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (sha != version) throw IOException("roads.txt does not match roads.version")
            validate(Database.ROADS, roads)
            save(ctx, Database.ROADS, roads)
            save(ctx, ROADS_VERSION, version)
            roadsChanged = true
        }
        return Changed(changed, roadsChanged)
    }

    class Changed(val tables: Boolean, val roads: Boolean) {
        val any get() = tables || roads
    }

    private fun save(ctx: Context, name: String, text: String) {
        val tmp = File(ctx.filesDir, "$name.${Thread.currentThread().id}.tmp")
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
            Database.ROADS -> require(Roads.parse(text).segments >= MIN_ROAD_SEGMENTS) { "too few roads" }
        }
    }

    private const val MIN_CAMERAS = 50
    private const val MIN_ROAD_SEGMENTS = 100_000
    private const val ROADS_VERSION = "roads.version"
}

/** Release contract (release.yml): latest Release has SpeedAlert.apk and version.json {versionCode, versionName, sha256}. */
data class RemoteVersion(val versionCode: Int, val versionName: String, val sha256: String)

object AppUpdate {
    const val APK = "SpeedAlert.apk"

    /** The remote version when newer than the installed one, else null. */
    fun check(): RemoteVersion? {
        val o = JSONObject(Http.text("${BuildConfig.RELEASE_URL}/version.json"))
        val r = RemoteVersion(o.getInt("versionCode"), o.getString("versionName"), o.getString("sha256").lowercase())
        return if (r.versionCode > BuildConfig.VERSION_CODE) r else null
    }

    /** Downloads the APK to cache/updates and verifies its SHA-256 before it may be installed. */
    fun download(ctx: Context, r: RemoteVersion): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = File(dir, APK)
        Http.open("${BuildConfig.RELEASE_URL}/$APK").inputStream.use { i -> apk.outputStream().use { i.copyTo(it) } }
        if (Http.sha256(apk) != r.sha256) {
            apk.delete()
            throw SecurityException("APK hash mismatch")
        }
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
