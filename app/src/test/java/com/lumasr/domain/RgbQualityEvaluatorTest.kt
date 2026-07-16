package com.lumasr.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RgbQualityEvaluatorTest {
    @Test
    fun exactRgbImagesHavePerfectMetrics() {
        val image = RgbImage(
            width = 2,
            height = 2,
            bytes = byteArrayOf(
                0, 10, 20,
                30, 40, 50,
                60, 70, 80,
                90, 100, 110
            )
        )

        val metrics = RgbQualityEvaluator.evaluate(image, image)

        requireNotNull(metrics)
        assertEquals(1.0, metrics.ssim, 0.0)
        assertEquals(Double.POSITIVE_INFINITY, metrics.psnrDb, 0.0)
        assertEquals(0, metrics.p99ChannelError)
    }

    @Test
    fun shapeMismatchFailsClosed() {
        val reference = RgbImage(1, 2, ByteArray(6))
        val candidate = RgbImage(2, 1, ByteArray(6))

        assertNull(RgbQualityEvaluator.evaluate(reference, candidate))
    }

    @Test
    fun constantImagesProduceFiniteDeterministicSsim() {
        val reference = RgbImage(2, 2, ByteArray(12) { 100.toByte() })
        val candidate = RgbImage(2, 2, ByteArray(12) { 101.toByte() })

        val metrics = requireNotNull(RgbQualityEvaluator.evaluate(reference, candidate))

        assertTrue(metrics.ssim.isFinite())
        assertEquals(0.9999505, metrics.ssim, 0.000001)
        assertEquals(48.1308036, metrics.psnrDb, 0.000001)
        assertEquals(1, metrics.p99ChannelError)
    }

    @Test
    fun strongDifferenceProducesWorstChannelError() {
        val black = RgbImage(2, 2, ByteArray(12))
        val white = RgbImage(2, 2, ByteArray(12) { 0xff.toByte() })

        val metrics = requireNotNull(RgbQualityEvaluator.evaluate(black, white))

        assertTrue(metrics.ssim < 0.001)
        assertEquals(0.0, metrics.psnrDb, 0.0)
        assertEquals(255, metrics.p99ChannelError)
    }

    @Test
    fun p99UsesNearestRankAcrossEveryRgbChannel() {
        val reference = RgbImage(34, 1, ByteArray(102))
        val candidateBytes = ByteArray(102)
        candidateBytes[99] = 1
        candidateBytes[100] = 2
        candidateBytes[101] = 255.toByte()

        val metrics = requireNotNull(
            RgbQualityEvaluator.evaluate(reference, RgbImage(34, 1, candidateBytes))
        )

        // ceil(0.99 * 102) == 101, so the 101st sorted error is 2 rather than 255.
        assertEquals(2, metrics.p99ChannelError)
    }

    @Test
    fun rgbImageOwnsItsBytesAndReturnsCopies() {
        val source = byteArrayOf(1, 2, 3)
        val image = RgbImage(1, 1, source)
        source[0] = 99

        val firstRead = image.copyBytes()
        firstRead[1] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3), image.copyBytes())
    }

    @Test
    fun rgbImageRejectsInvalidDimensionsAndByteCountsBeforeAllocation() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbImage(0, 1, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbImage(1, -1, ByteArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbImage(2, 2, ByteArray(11))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbImage(Int.MAX_VALUE, 2, ByteArray(0))
        }
    }
}
