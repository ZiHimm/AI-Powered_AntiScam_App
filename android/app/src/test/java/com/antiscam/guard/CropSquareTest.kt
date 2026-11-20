package com.antiscam.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropSquareTest {

    /** Brian's retest (21 Nov): faces touching the edge crashed the crop ("x + width must be <= bitmap.width()"). */
    @Test fun alwaysInsideTheBitmap() {
        val rnd = Random(42)
        repeat(200_000) {
            val w = rnd.nextInt(24, 1500); val h = rnd.nextInt(24, 3000)
            val fw = rnd.nextInt(1, 2 * w); val fh = rnd.nextInt(1, 2 * h)
            val cx = rnd.nextInt(-w, 2 * w); val cy = rnd.nextInt(-h, 2 * h)   // centres even off-screen
            val r = cropSquare(cx, cy, fw, fh, w, h) ?: return@repeat
            val (l, t, side) = r
            assertTrue("left $l", l >= 0); assertTrue("top $t", t >= 0)
            assertTrue("right ${l + side} > $w", l + side <= w)
            assertTrue("bottom ${t + side} > $h", t + side <= h)
            assertTrue(side >= 24)
        }
    }

    @Test fun oddSideAtRightAndBottomEdge() {
        // the exact case that overflowed by 1 px before: odd side, face centred on the edge
        val (l, t, side) = cropSquare(cx = 1079, cy = 2399, faceW = 301, faceH = 301, w = 1080, h = 2400)!!
        assertEquals(301, side); assertEquals(1080 - 301, l); assertEquals(2400 - 301, t)
    }

    @Test fun centredFaceIsCentred() {
        val (l, t, side) = cropSquare(cx = 500, cy = 800, faceW = 200, faceH = 240, w = 1080, h = 2400)!!
        assertEquals(240, side); assertEquals(380, l); assertEquals(680, t)
    }

    @Test fun tinyFaceIsSkipped() = assertNull(cropSquare(100, 100, 10, 12, 1080, 2400))
}
