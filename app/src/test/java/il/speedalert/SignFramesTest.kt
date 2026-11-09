// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
package il.speedalert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignFramesTest {
    @Test fun majorityOfTheBurst() {
        assertEquals(90 to 3, SignFrames.vote(listOf(90, null, 80, 90, 90, null)))
        assertEquals(null to 0, SignFrames.vote(listOf(null, null)))
        assertNull(SignFrames.vote(emptyList()).first)
    }

    @Test fun squareStaysInsideTheFrame() {
        assertEquals(Triple(0, 0, 450), SignFrames.square(1000, 1000, 0.0, 0.0))
        assertEquals(Triple(550, 550, 450), SignFrames.square(1000, 1000, 1.0, 1.0))
        assertEquals(Triple(275, 475, 450), SignFrames.square(1000, 1400, 0.5, 0.5))
    }

    @Test fun sharpEdgesScoreHigherThanBlur() {
        val w = 40; val h = 40
        val sharp = IntArray(w * h) { if ((it % w) < 20) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val blur = IntArray(w * h) { val v = ((it % w) * 255 / (w - 1)); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertTrue(SignFrames.laplacianVariance(sharp, w, h) > 10 * SignFrames.laplacianVariance(blur, w, h) + 1)
    }
}
