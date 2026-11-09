// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Real sign photos in src/test/resources/signs, named "<value>-<anything>.ppm" (tools/sign_photo_to_ppm.py). */
class SignPhotosTest {
    @Test fun readsRealPhotos() {
        val dir = File("src/test/resources/signs")
        val files = dir.listFiles { f -> f.extension == "ppm" }?.sortedBy { it.name } ?: return
        val wrong = mutableListOf<String>()
        for (f in files) {
            val img = ppm(f)
            val log = StringBuilder()
            SignReader.trace = { log.append("  ").append(it).append('\n') }
            val got = try { SignReader.read(img.px, img.width, img.height) } finally { SignReader.trace = null }
            val want = f.name.substringBefore('-').toInt()
            println("${f.name} ${img.width}x${img.height}: read $got\n$log")
            if (got != want) wrong += "${f.name}: $got"
        }
        assertEquals("wrong reads", emptyList<String>(), wrong)
    }

    class Img(val width: Int, val height: Int, val px: IntArray)

    /** Binary PPM (P6, maxval 255) to ARGB pixels. */
    private fun ppm(f: File): Img {
        val b = f.readBytes(); var i = 0
        fun token(): String {
            while (b[i].toInt().toChar().isWhitespace() || b[i] == '#'.code.toByte()) {
                if (b[i] == '#'.code.toByte()) while (b[i] != 10.toByte()) i++
                i++
            }
            val s = i; while (!b[i].toInt().toChar().isWhitespace()) i++
            return String(b, s, i - s)
        }
        require(token() == "P6")
        val w = token().toInt(); val h = token().toInt(); token(); i++
        val px = IntArray(w * h) { k ->
            val o = i + k * 3
            (0xFF shl 24) or ((b[o].toInt() and 255) shl 16) or ((b[o + 1].toInt() and 255) shl 8) or (b[o + 2].toInt() and 255)
        }
        return Img(w, h, px)
    }
}
