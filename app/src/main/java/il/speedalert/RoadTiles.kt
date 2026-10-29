// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * Road speed limits by tile (data/roads/, built by tools/tile_roads.py; user request 2026-10-29:
 * 82 MB of memory with the whole table loaded). Only the tiles around the car are parsed and
 * kept ([KEEP] at most); a tile is read from the APK when its sha256 in the current index
 * matches the APK's index, else from a downloaded copy, else downloaded now (only that tile).
 * Loading runs on one background thread; [at] answers null for a tile still loading.
 */
class RoadTiles(private val ctx: Context, private val download: Boolean = true) : RoadLookup {
    private val loaded = object : LinkedHashMap<String, Roads>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Roads>?) = size > KEEP
    }
    private val pending = mutableSetOf<String>()
    private val failedAt = mutableMapOf<String, Long>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var index: Map<String, String> = emptyMap()
    private val apkIndex: Map<String, String> by lazy { parseIndex(ctx.assets.open(INDEX).bufferedReader().use { it.readText() }) }

    init { reload() }

    /** Index changed (data update): forget parsed tiles, read the new index. */
    fun reload() {
        index = runCatching { parseIndex(File(ctx.filesDir, INDEX).readText()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: apkIndex
        synchronized(this) { loaded.clear(); failedAt.clear() }
    }

    /** Drops every parsed tile (not driving, or the system asks for memory). */
    fun trim() = synchronized(this) { loaded.clear() }

    override fun at(lat: Double, lon: Double, heading: Double): Roads.Match? {
        val here = name(lat, lon)
        // Neighbours too, so a tile is ready before the car crosses into it.
        val y = floor(lat / TILE).toInt(); val x = floor(lon / TILE).toInt()
        for (dy in -1..1) for (dx in -1..1) request("${y + dy}_${x + dx}")
        val r = synchronized(this) { loaded[here] } ?: return null
        return r.at(lat, lon, heading)
    }

    private fun request(t: String) {
        val sha = index[t] ?: return
        synchronized(this) {
            if (t in loaded || t in pending) return
            if (System.currentTimeMillis() - (failedAt[t] ?: 0) < RETRY_MS) return
            pending += t
        }
        worker.execute {
            val r = runCatching { Roads.parse(text(t, sha)) }.getOrNull()
            synchronized(this) {
                pending -= t
                if (r != null) loaded[t] = r else failedAt[t] = System.currentTimeMillis()
            }
        }
    }

    private fun text(t: String, sha: String): String {
        if (apkIndex[t] == sha) return ctx.assets.open("$DIR/$t.txt").bufferedReader().use { it.readText() }
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val local = File(dir, "$t-${sha.take(12)}.txt")
        if (local.isFile) return local.readText()
        if (!download) throw java.io.IOException("not downloaded")
        val bytes = Http.open("${BuildConfig.DATA_URL}/$DIR/$t.txt").inputStream.use { it.readBytes() }
        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(got == sha) { "tile $t does not match the index" }
        dir.listFiles { f -> f.name.startsWith("$t-") }?.forEach { it.delete() }
        val tmp = File(dir, "$t.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(local)
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        const val DIR = "roads"
        const val INDEX = "roads/index.txt"
        const val TILE = 0.1
        private const val KEEP = 9
        private const val RETRY_MS = 60_000L

        fun name(lat: Double, lon: Double) = "${floor(lat / TILE).toInt()}_${floor(lon / TILE).toInt()}"

        /** `name sha256 bytes` lines -> name to sha256. */
        fun parseIndex(text: String): Map<String, String> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().split(' ') }
            .onEach { require(it.size == 3 && it[1].length == 64) { "bad index line" } }
            .associate { it[0] to it[1] }
    }
}
