// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.hypot

class SignReaderTest {
    private val white = 0xFFF0F0F0.toInt()
    private val red = 0xFFC8201E.toInt()
    private val black = 0xFF141414.toInt()

    /** A sign: red ring on a grey road scene, digits from the 5 x 7 font, slightly bolded. */
    private fun sign(value: Int, size: Int = 320): IntArray {
        val px = IntArray(size * size) { 0xFF7A8088.toInt() }
        val c = size / 2.0; val r = size * 0.42
        for (y in 0 until size) for (x in 0 until size) {
            val d = hypot(x - c, y - c)
            if (d <= r) px[y * size + x] = if (d >= r * 0.82) red else white
        }
        val s = value.toString()
        val ch = (size * 0.40 / 7).toInt()                       // digits about 40% of the sign high
        val cw = (size * (if (s.length == 3) 0.50 else 0.42) / (s.length * 6 - 1)).toInt()
        val gap = cw
        val total = s.length * 5 * cw + (s.length - 1) * gap
        var left = (size - total) / 2; val top = (size - 7 * ch) / 2
        for (d in s) {
            val rows = SignReader.FONT[d - '0']
            for (gy in 0 until 7) for (gx in 0 until 5) if (rows[gy][gx] == '1')
                for (y in 0 until ch) for (x in 0 until cw) px[(top + gy * ch + y) * size + left + gx * cw + x] = black
            left += 5 * cw + gap
        }
        return px
    }

    @Test fun readsEveryLegalLimit() {
        for (v in SignReader.VALID) assertEquals("limit $v", v, SignReader.read(sign(v), 320, 320))
    }

    @Test fun noSignNoNumber() {
        assertNull(SignReader.read(IntArray(320 * 320) { 0xFF7A8088.toInt() }, 320, 320))
    }

    @Test fun redDiscIsNotASign() {
        val px = IntArray(200 * 200) { 0xFF7A8088.toInt() }
        for (y in 0 until 200) for (x in 0 until 200) if (hypot(x - 100.0, y - 100.0) < 80) px[y * 200 + x] = red
        assertNull(SignReader.read(px, 200, 200))
    }
}
