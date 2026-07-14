package com.lumasr.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticCalibrationPatternTest {
    @Test
    fun sameParametersGenerateIdenticalRgbBytes() {
        val first = SyntheticCalibrationPattern.generate(64, 48, seed = 0x1234L)
        val second = SyntheticCalibrationPattern.generate(64, 48, seed = 0x1234L)

        assertArrayEquals(first.copyBytes(), second.copyBytes())
    }

    @Test
    fun seedAndDimensionsAffectGeneratedPattern() {
        val baseline = SyntheticCalibrationPattern.generate(32, 24, seed = 1L)
        val differentSeed = SyntheticCalibrationPattern.generate(32, 24, seed = 2L)
        val differentSize = SyntheticCalibrationPattern.generate(33, 24, seed = 1L)

        assertFalse(baseline.copyBytes().contentEquals(differentSeed.copyBytes()))
        assertNotEquals(baseline.byteCount, differentSize.byteCount)
    }

    @Test
    fun extremeDimensionsAreRejectedBeforeLongOrArrayOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            SyntheticCalibrationPattern.generate(Int.MAX_VALUE, Int.MAX_VALUE, seed = 1L)
        }
    }

    @Test
    fun patternContainsNonTrivialChannelAndHighFrequencyContent() {
        val image = SyntheticCalibrationPattern.generate(96, 64, seed = 7L)
        val bytes = image.copyBytes()
        val red = mutableSetOf<Int>()
        val green = mutableSetOf<Int>()
        val blue = mutableSetOf<Int>()
        var unequalChannelPixels = 0
        var strongHorizontalEdges = 0

        for (pixel in 0 until image.width * image.height) {
            val offset = pixel * 3
            val r = bytes[offset].toInt() and 0xff
            val g = bytes[offset + 1].toInt() and 0xff
            val b = bytes[offset + 2].toInt() and 0xff
            red += r
            green += g
            blue += b
            if (r != g || g != b) unequalChannelPixels++
            if (pixel % image.width != 0) {
                val previousRed = bytes[offset - 3].toInt() and 0xff
                if (kotlin.math.abs(r - previousRed) >= 24) strongHorizontalEdges++
            }
        }

        assertTrue(red.size > 64)
        assertTrue(green.size > 64)
        assertTrue(blue.size > 64)
        assertTrue(unequalChannelPixels > image.width * image.height / 2)
        assertTrue(strongHorizontalEdges > image.width)
    }
}
