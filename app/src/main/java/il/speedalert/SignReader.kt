// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import kotlin.math.abs

/**
 * Reads the number on a photo of a speed-limit sign, on the phone, with no library and no
 * network (user request 2026-11-08; ML Kit was not used because it reports usage to Google):
 * the red ring is found by its colour, the dark shapes inside it are compared with a small digit
 * font, and only a legal limit ([VALID]) is accepted. The user confirms or corrects the number.
 *
 * Input: ARGB pixels of a downscaled photo (about 480 px on the long side).
 */
object SignReader {
    val VALID = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)

    class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val count: Int) {
        val w get() = x1 - x0 + 1
        val h get() = y1 - y0 + 1
    }

    fun read(px: IntArray, w: Int, h: Int): Int? {
        val red = BooleanArray(w * h) { isRed(px[it]) }
        val minSide = minOf(w, h) / 12
        val rings = components(red, w, 0, 0, w - 1, h - 1)
            .filter { b ->
                b.w >= minSide && b.h >= minSide && b.w.toDouble() / b.h in 0.6..1.6 &&
                    b.count.toDouble() / (b.w * b.h) in 0.12..0.65 && hollow(red, w, b)
            }
            .sortedByDescending { it.w * it.h }
        for (ring in rings.take(4)) readInside(px, red, w, ring)?.let { return it }
        return null
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
        val x0 = ring.x0 + ring.w * 17 / 100; val x1 = ring.x1 - ring.w * 17 / 100
        val y0 = ring.y0 + ring.h * 17 / 100; val y1 = ring.y1 - ring.h * 17 / 100
        if (x1 - x0 < 10 || y1 - y0 < 10) return null
        // Otsu threshold on the brightness inside the ring.
        val hist = IntArray(256); var n = 0
        for (y in y0..y1) for (x in x0..x1) { val i = y * w + x; if (!red[i]) { hist[lum(px[i])]++; n++ } }
        val t = otsu(hist, n) ?: return null
        val dark = BooleanArray(px.size)
        for (y in y0..y1) for (x in x0..x1) { val i = y * w + x; dark[i] = !red[i] && lum(px[i]) < t }
        val ih = y1 - y0 + 1; val iw = x1 - x0 + 1
        val digits = components(dark, w, x0, y0, x1, y1)
            .filter { b ->
                b.h >= ih / 4 && b.h <= ih * 9 / 10 && b.w <= iw * 6 / 10 && b.count >= 12 &&
                    b.x0 > x0 && b.x1 < x1 && b.y0 > y0 && b.y1 < y1
            }
            .sortedBy { it.x0 }
        if (digits.size !in 2..3) return null
        val grids = digits.map { grid(dark, w, it) }
        var best: Int? = null; var bestScore = Double.MAX_VALUE
        for (v in VALID) {
            val s = v.toString()
            if (s.length != digits.size) continue
            val score = s.indices.sumOf { k ->
                if (digits[k].w * 100 / digits[k].h < 35) (if (s[k] == '1') 0.0 else 1.0)   // a thin stroke is a 1
                else distance(grids[k], TEMPLATES[s[k] - '0'])
            } / s.length
            if (score < bestScore) { bestScore = score; best = v }
        }
        return if (bestScore <= MAX_SCORE) best else null
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

    /** Each glyph cropped to its own box and sampled like a photographed digit. */
    private val TEMPLATES: List<DoubleArray> = FONT.map { rows ->
        val scale = 10
        val gw = GW * scale; val gh = GH * scale
        val m = BooleanArray(gw * gh) { i -> rows[(i / gw) / scale][(i % gw) / scale] == '1' }
        val b = components(m, gw, 0, 0, gw - 1, gh - 1).let { cs ->
            Box(cs.minOf { it.x0 }, cs.minOf { it.y0 }, cs.maxOf { it.x1 }, cs.maxOf { it.y1 }, 0)
        }
        grid(m, gw, b)
    }
}
