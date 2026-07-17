package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResolutionTierTest {
    @Test
    fun classifiesStrictOutputPixelBoundaries() {
        assertEquals(ResolutionTier.SMALL, ResolutionTier.fromOutputPixels(7_999_999L))
        assertEquals(ResolutionTier.STANDARD, ResolutionTier.fromOutputPixels(8_000_000L))
        assertEquals(ResolutionTier.STANDARD, ResolutionTier.fromOutputPixels(24_000_000L))
        assertEquals(ResolutionTier.LARGE, ResolutionTier.fromOutputPixels(24_000_001L))
    }

    @Test
    fun missingOutputPixelsFallsBackToStandardAndNegativeValuesAreRejected() {
        assertEquals(ResolutionTier.STANDARD, ResolutionTier.fromOutputPixels(null))
        assertThrows(IllegalArgumentException::class.java) {
            ResolutionTier.fromOutputPixels(-1L)
        }
    }

    @Test
    fun accepts64AndRejects65CpuAndPostprocessThreadCounts() {
        val upperBound = CalibratedCpuProfile(
            ResolutionTier.STANDARD,
            tileSize = 256,
            cpuThreads = 64,
            openmpBlocktimeMs = 20,
            postprocessThreads = 64
        )

        assertEquals(64, upperBound.cpuThreads)
        assertEquals(64, upperBound.postprocessThreads)
        assertThrows(IllegalArgumentException::class.java) {
            CalibratedCpuProfile(
                ResolutionTier.STANDARD,
                tileSize = 256,
                cpuThreads = 65,
                openmpBlocktimeMs = 20,
                postprocessThreads = 2
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalibratedCpuProfile(
                ResolutionTier.STANDARD,
                tileSize = 256,
                cpuThreads = 4,
                openmpBlocktimeMs = 20,
                postprocessThreads = 65
            )
        }
    }
}
