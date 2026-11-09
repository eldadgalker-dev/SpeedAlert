// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads the number on a photo of a speed-limit sign, on the phone, with no library and no
 * network (user request 2026-11-08; ML Kit was not used because it reports usage to Google):
 * the red ring is found by its colour, the dark shapes inside it are compared with a small digit
 * font, and only a legal limit ([VALID]) is accepted. The user confirms or corrects the number.
 *
 * Input: ARGB pixels of a downscaled photo (about 480 px on the long side).
 */
object SignReader {
    val VALID = listOf(10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)

    class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val count: Int) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
    }

    /** Debug output of the steps (unit tests on real photos); null on the phone. */
    var trace: ((String) -> Unit)? = null

    fun read(px: IntArray, w: Int, h: Int): Int? {
        val red = BooleanArray(w * h) { isRed(px[it]) }
        val minSide = minOf(w, h) / 12
        val all = components(red, w, 0, 0, w - 1, h - 1)
        trace?.invoke("red shapes ${all.size}, big: " + all.filter { it.w >= minSide && it.h >= minSide }
            .joinToString { "${it.x0},${it.y0} ${it.w}x${it.h} fill=${"%.2f".format(it.count.toDouble() / (it.w * it.h))} hollow=${hollow(red, w, it)}" })
        val rings = all
            .filter { b ->
                b.w >= minSide && b.h >= minSide && b.w.toDouble() / b.h in 0.6..1.6 &&
                    b.count.toDouble() / (b.w * b.h) in 0.12..0.65 && hollow(red, w, b)
            }
            .sortedByDescending { it.w * it.h }
        for (ring in rings.take(4)) readInside(px, red, w, ring)?.let { return it }
        return null
    }

    // Dark red of a shaded ring: too dark for isRed, but not the black of a digit.
    private fun reddish(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return r >= 40 && r > g * 3 / 2 && r > b * 7 / 5
    }

    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
        return r >= 90 && r > g * 3 / 2 && r > b * 7 / 5
    }

    private fun lum(c: Int) = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    /** A ring, not a red disc: the middle of the box is (almost) free of red. */
    private fun hollow(red: BooleanArray, w: Int, b: Box): Boolean {
        val x0 = b.x0 + b.w * 3 / 10; val x1 = b.x1 - b.w * 3 / 10
        val y0 = b.y0 + b.h * 3 / 10; val y1 = b.y1 - b.h * 3 / 10
        var n = 0; var r = 0
        for (y in y0..y1) for (x in x0..x1) { n++; if (red[y * w + x]) r++ }
        return n > 0 && r * 10 < n
    }

    private fun readInside(px: IntArray, red: BooleanArray, w: Int, ring: Box): Int? {
        // The white disc: the largest bright shape inside the middle of the ring (thick rings and photos at an
        // angle put it off the box centre); else the box shrunk by 17% on each side.
        val bright = BooleanArray(red.size)
        for (y in ring.y0..ring.y1) for (x in ring.x0..ring.x1) { val i = y * w + x; bright[i] = !red[i] && lum(px[i]) >= BRIGHT }
        val disc = components(bright, w, ring.x0, ring.y0, ring.x1, ring.y1)
            .filter { it.x0 > ring.x0 && it.y0 > ring.y0 && it.x1 < ring.x1 && it.y1 < ring.y1 }
            .maxByOrNull { it.count }
            ?.takeIf { it.w >= ring.w / 3 && it.h >= ring.h / 3 }
        val x0 = disc?.x0 ?: (ring.x0 + ring.w * 17 / 100); val x1 = disc?.x1 ?: (ring.x1 - ring.w * 17 / 100)
        val y0 = disc?.y0 ?: (ring.y0 + ring.h * 17 / 100); val y1 = disc?.y1 ?: (ring.y1 - ring.h * 17 / 100)
        if (x1 - x0 < 10 || y1 - y0 < 10) return null
        // Otsu threshold on the brightness inside the ring.
        val hist = IntArray(256); var n = 0
        for (y in y0..y1) for (x in x0..x1) { val i = y * w + x; if (!red[i]) { hist[lum(px[i])]++; n++ } }
        val t = otsu(hist, n) ?: return null
        // Also darker than its neighbourhood: a shadow across the disc is below Otsu's threshold but not much
        // darker than the disc around it, while a digit is.
        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1; val rad = maxOf(3, minOf(bw, bh) / 4)
        val sum = LongArray((bw + 1) * (bh + 1))
        for (y in 0 until bh) for (x in 0 until bw)
            sum[(y + 1) * (bw + 1) + x + 1] = lum(px[(y0 + y) * w + x0 + x]) + sum[y * (bw + 1) + x + 1] +
                sum[(y + 1) * (bw + 1) + x] - sum[y * (bw + 1) + x]
        val dark = BooleanArray(px.size)
        for (y in y0..y1) for (x in x0..x1) {
            val i = y * w + x; val l = lum(px[i])
            if (red[i] || reddish(px[i]) || l >= t) continue
            val ax = maxOf(0, x - x0 - rad); val bx = minOf(bw, x - x0 + rad + 1)
            val ay = maxOf(0, y - y0 - rad); val by = minOf(bh, y - y0 + rad + 1)
            val s = sum[by * (bw + 1) + bx] - sum[ay * (bw + 1) + bx] - sum[by * (bw + 1) + ax] + sum[ay * (bw + 1) + ax]
            dark[i] = l * 5L * (bx - ax) * (by - ay) < s * 4
        }
        val ih = y1 - y0 + 1; val iw = x1 - x0 + 1
        val shapes = components(dark, w, x0, y0, x1, y1)
        trace?.invoke("inner $x0,$y0..$x1,$y1 dark shapes: " + shapes.filter { it.count >= 12 }.joinToString { "${it.x0},${it.y0} ${it.w}x${it.h} n=${it.count}" })
        // A digit: tall enough, not the whole inner box (a shadow of the ring), not running from top to bottom edge.
        val digits = shapes
            .filter { b ->
                b.h >= ih / 4 && b.h <= ih * 9 / 10 && b.w <= iw * 9 / 10 && b.count >= 12 &&
                    !(b.y0 <= y0 && b.y1 >= y1)
            }
            .flatMap { split(dark, w, it) }     // digits that touch in a blurred photo
            .filter { it.w <= iw * 6 / 10 }
            .let { parts -> val top = parts.maxOfOrNull { it.h } ?: 0; parts.filter { it.h * 10 >= top * 7 } }  // digits of one number share a height; drops ring fragments
            .sortedBy { it.x0 }
        trace?.invoke("ring ${ring.x0},${ring.y0} ${ring.w}x${ring.h}: threshold $t, digits ${digits.size}: " +
            digits.joinToString { "${it.x0},${it.y0} ${it.w}x${it.h}" })
        trace?.let { tr -> for (d in digits) for (y in d.y0..d.y1) tr("      " + (d.x0..d.x1).joinToString("") { if (dark[y * w + it]) "#" else "." }) }
        if (digits.size !in 2..3) return null
        val grids = digits.map { grid(dark, w, it) }
        var best: Int? = null; var bestScore = Double.MAX_VALUE
        for (v in VALID) {
            val s = v.toString()
            if (s.length != digits.size) continue
            val score = s.indices.sumOf { k ->
                if (digits[k].w * 100 / digits[k].h < 35) (if (s[k] == '1') 0.0 else 1.0)   // a thin stroke is a 1
                else TEMPLATES[s[k] - '0'].minOf { distance(grids[k], it) }
            } / s.length
            trace?.invoke("  $v ${"%.3f".format(score)}")
            if (score < bestScore) { bestScore = score; best = v }
        }
        trace?.invoke("best $best score ${"%.3f".format(bestScore)}")
        return if (bestScore <= MAX_SCORE) best else null
    }

    /**
     * Splits a shape as wide as two or three digits at its thinnest columns (digits that blur into
     * each other). A digit is about 0.4-0.75 of its height wide; a "1" is narrower.
     */
    private fun split(mask: BooleanArray, w: Int, b: Box): List<Box> {
        val n = (b.w / (b.h * 0.55)).roundToInt().coerceIn(1, 3)
        if (n == 1) return listOf(b)
        val cols = IntArray(b.w) { dx -> (b.y0..b.y1).count { y -> mask[y * w + b.x0 + dx] } }
        val cuts = (1 until n).map { k ->
            val mid = b.w * k / n; val r = b.w / (n * 3)
            (maxOf(1, mid - r)..minOf(b.w - 2, mid + r)).minByOrNull { cols[it] } ?: mid
        }
        val edges = listOf(0) + cuts + listOf(b.w)
        return edges.zipWithNext().mapNotNull { (a, z) -> tight(mask, w, b.x0 + a, b.y0, b.x0 + z - 1, b.y1) }
    }

    /** Bounds of the set pixels inside a box; null when too few. */
    private fun tight(mask: BooleanArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Box? {
        var ax = x1; var bx = x0; var ay = y1; var by = y0; var c = 0
        for (y in y0..y1) for (x in x0..x1) if (mask[y * w + x]) {
            c++; if (x < ax) ax = x; if (x > bx) bx = x; if (y < ay) ay = y; if (y > by) by = y
        }
        return if (c < 12) null else Box(ax, ay, bx, by, c)
    }

    private fun otsu(hist: IntArray, n: Int): Int? {
        if (n == 0) return null
        val sum = (0..255).sumOf { it.toDouble() * hist[it] }
        var sumB = 0.0; var wB = 0; var best = 0.0; var t = -1
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = n - wB; if (wF == 0) break
            sumB += i.toDouble() * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; t = i }
        }
        return if (t < 0) null else t + 1
    }

    /** Dark share of each cell of a [GW] x [GH] grid over the shape's box. */
    private fun grid(mask: BooleanArray, w: Int, b: Box): DoubleArray {
        val g = DoubleArray(GW * GH)
        for (cy in 0 until GH) for (cx in 0 until GW) {
            val xa = b.x0 + cx * b.w / GW; val xb = maxOf(xa, b.x0 + (cx + 1) * b.w / GW - 1)
            val ya = b.y0 + cy * b.h / GH; val yb = maxOf(ya, b.y0 + (cy + 1) * b.h / GH - 1)
            var c = 0; var d = 0
            for (y in ya..yb) for (x in xa..xb) { c++; if (mask[y * w + x]) d++ }
            g[cy * GW + cx] = if (c == 0) 0.0 else d.toDouble() / c
        }
        return g
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { abs(a[it] - b[it]) } / a.size

    /** Connected shapes (8-neighbour) of [mask] inside the box, with their bounds and size. */
    fun components(mask: BooleanArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): List<Box> {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val out = ArrayList<Box>()
        for (y in y0..y1) for (x in x0..x1) {
            val s = y * w + x
            if (!mask[s] || seen[s]) continue
            var top = 0; stack[top++] = s; seen[s] = true
            var ax = x; var bx = x; var ay = y; var by = y; var count = 0
            while (top > 0) {
                val i = stack[--top]; val cx = i % w; val cy = i / w; count++
                if (cx < ax) ax = cx; if (cx > bx) bx = cx; if (cy < ay) ay = cy; if (cy > by) by = cy
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < x0 || nx > x1 || ny < y0 || ny > y1) continue
                    val j = ny * w + nx
                    if (mask[j] && !seen[j]) { seen[j] = true; stack[top++] = j }
                }
            }
            out += Box(ax, ay, bx, by, count)
        }
        return out
    }

    private const val GW = 5
    private const val GH = 7
    private const val MAX_SCORE = 0.32
    private const val BRIGHT = 140

    /** 5 x 7 digit font, rows top to bottom. Also used by the tests to draw signs. */
    val FONT = listOf(
        listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
        listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
        listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"),
        listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
        listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
        listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
        listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
        listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"),
    )

    /** Road-sign shapes that differ from [FONT]: "1" with a flag and no base, bold round digits. */
    private val SIGN_FONT = mapOf(
        1 to listOf("00110", "01110", "11110", "00110", "00110", "00110", "00110"),
        5 to listOf("01111", "11100", "11000", "11111", "00011", "00011", "11110"),
        0 to listOf("01110", "11011", "11011", "11011", "11011", "11011", "01110"),
        2 to listOf("01110", "11011", "00011", "00110", "01100", "11000", "11111"),
        3 to listOf("01110", "11011", "00011", "00110", "00011", "11011", "01110"),
        4 to listOf("00011", "00111", "01111", "11011", "11111", "00011", "00011"),
        6 to listOf("01110", "11000", "11110", "11011", "11011", "11011", "01110"),
        7 to listOf("11111", "00011", "00110", "00110", "01100", "01100", "01100"),
        8 to listOf("01110", "11011", "11011", "01110", "11011", "11011", "01110"),
        9 to listOf("01110", "11011", "11011", "01111", "00011", "00110", "01100"),
    )

    /** Each glyph cropped to its own box and sampled like a photographed digit; variants per digit. */
    private val TEMPLATES: List<List<DoubleArray>> = (0..9).map { d ->
        listOfNotNull(FONT[d], SIGN_FONT[d]).map { template(it) }
    }

    private fun template(rows: List<String>): DoubleArray {
        val scale = 10
        val gw = GW * scale; val gh = GH * scale
        val m = BooleanArray(gw * gh) { i -> rows[(i / gw) / scale][(i % gw) / scale] == '1' }
        val b = components(m, gw, 0, 0, gw - 1, gh - 1).let { cs ->
            Box(cs.minOf { it.x0 }, cs.minOf { it.y0 }, cs.maxOf { it.x1 }, cs.maxOf { it.y1 }, 0)
        }
        return grid(m, gw, b)
    }
}
