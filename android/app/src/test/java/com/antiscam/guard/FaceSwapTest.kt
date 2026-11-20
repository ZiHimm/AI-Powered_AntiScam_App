package com.antiscam.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSwapTest {
    /** Real-phone test: Son on camera, then Daughter stepped into the frame next to him. That's a JOIN, not a swap. */
    @Test fun daughterJoinsSonIsNotASwap() =
        assertFalse(isFaceSwap("son", "daughter", listOf(setOf("son"), setOf("son", "daughter"), setOf("son", "daughter"))))

    @Test fun sonStillVisibleInOneRecentFrameIsNotASwap() =
        assertFalse(isFaceSwap("son", "daughter", listOf(setOf("daughter"), setOf("son", "daughter"), setOf("daughter"))))

    /** The attack: start with one face, then switch to a deepfake of another family member. */
    @Test fun sonGoneDaughterThereIsASwap() =
        assertTrue(isFaceSwap("daughter", "son", listOf(setOf("son"), setOf("son"), setOf("son"))))

    @Test fun sameAsAskedIsNotASwap() = assertFalse(isFaceSwap("son", "son", listOf(setOf("son"))))
    @Test fun nobodyAskedYetIsNotASwap() = assertFalse(isFaceSwap(null, "son", listOf(setOf("son"))))
}
