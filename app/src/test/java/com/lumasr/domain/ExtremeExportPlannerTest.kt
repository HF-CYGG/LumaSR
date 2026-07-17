package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExtremeExportPlannerTest {
    @Test
    fun usesRealEsrganCoreTileAndOverlap() {
        val plan = ExtremeExportPlanner.plan(
            imageWidth = 1200,
            imageHeight = 900,
            scale = 4,
            engine = SuperResEngine.REAL_ESRGAN
        )

        assertEquals(512, plan.coreTileWidth)
        assertTrue(plan.coreBandInputHeight > 0)
        assertEquals(32L * 1024L * 1024L, plan.rowBandBudgetBytes)
        assertEquals(64, plan.overlap)
        assertEquals(4800, plan.outputWidth)
        assertEquals(3600, plan.outputHeight)
        assertTrue(plan.tiles.size > 1)
        assertEquals(0, plan.tiles.first().regionX)
        assertEquals(0, plan.tiles.first().regionY)
        assertEquals(0, plan.tiles.first().outputX)
        assertEquals(0, plan.tiles.first().outputY)
        assertEquals(512 * 4, plan.tiles.first().outputW)
    }

    @Test
    fun shrinksHighScaleRealEsrganTilesToLimitNativeOutputMemory() {
        val plan = ExtremeExportPlanner.plan(
            imageWidth = 1200,
            imageHeight = 900,
            scale = 16,
            engine = SuperResEngine.REAL_ESRGAN
        )

        assertTrue(plan.coreTileWidth < 512)
        assertTrue(plan.overlap < 64)
        plan.tiles.forEach { tile ->
            assertTrue(tile.regionW * plan.scale <= ExtremeExportPlanner.MAX_NATIVE_REGION_OUTPUT_SIDE)
            assertTrue(tile.regionH * plan.scale <= ExtremeExportPlanner.MAX_NATIVE_REGION_OUTPUT_SIDE)
        }
    }

    @Test
    fun edgeTilesClampRegionAndKeepCoreCoverage() {
        val plan = ExtremeExportPlanner.plan(
            imageWidth = 1025,
            imageHeight = 700,
            scale = 2,
            engine = SuperResEngine.WAIFU2X
        )
        val last = plan.tiles.last()

        assertEquals(768, plan.coreTileWidth)
        assertEquals(1025, last.coreX + last.coreW)
        assertEquals(700, last.coreY + last.coreH)
        assertTrue(last.regionX >= 0)
        assertTrue(last.regionY >= 0)
        assertTrue(last.regionX + last.regionW <= 1025)
        assertTrue(last.regionY + last.regionH <= 700)
        assertEquals((last.coreX - last.regionX) * 2, last.outputCropLeft)
        assertEquals((last.coreY - last.regionY) * 2, last.outputCropTop)
    }

    @Test
    fun generatedTilesCoverOutputWithoutGapsOrOverlap() {
        val plan = ExtremeExportPlanner.plan(
            imageWidth = 1300,
            imageHeight = 1000,
            scale = 3,
            engine = SuperResEngine.REAL_CUGAN
        )
        val covered = BooleanArray(plan.outputWidth * plan.outputHeight)

        plan.tiles.forEach { tile ->
            for (y in tile.outputY until tile.outputY + tile.outputH) {
                for (x in tile.outputX until tile.outputX + tile.outputW) {
                    val index = y * plan.outputWidth + x
                    assertTrue("overlap at $x,$y", !covered[index])
                    covered[index] = true
                }
            }
        }

        assertTrue(covered.all { it })
    }

    @Test
    fun selectsFixedRowBandBudgetAtMemoryBoundaries() {
        val cases = listOf(
            ProcessingResourceProfile(1024, 1024, availableMemoryBytes = null) to 32L,
            ProcessingResourceProfile(1024, 1024, availableMemoryBytes = 768L * MIB - 1L) to 16L,
            ProcessingResourceProfile(1024, 1024, availableMemoryBytes = 768L * MIB) to 32L,
            ProcessingResourceProfile(1024, 1024, availableMemoryBytes = 1536L * MIB - 1L) to 32L,
            ProcessingResourceProfile(1024, 1024, availableMemoryBytes = 1536L * MIB) to 64L,
            ProcessingResourceProfile(1024, 1024, availableMemoryBytes = Long.MAX_VALUE) to 64L,
            ProcessingResourceProfile(1024, 1024, isLowRamDevice = true, availableMemoryBytes = Long.MAX_VALUE) to 16L
        )

        cases.forEach { (profile, expectedMib) ->
            val plan = ExtremeExportPlanner.plan(
                imageWidth = 1024,
                imageHeight = 1024,
                scale = 4,
                engine = SuperResEngine.REAL_ESRGAN,
                resourceProfile = profile
            )

            assertEquals(expectedMib * MIB, plan.rowBandBudgetBytes)
            assertTrue(plan.rowBandBudgetBytes <= 64L * MIB)
        }
    }

    @Test
    fun createsRectangularBandsInStrictRowMajorOrder() {
        val plan = ExtremeExportPlanner.plan(
            imageWidth = 4096,
            imageHeight = 1024,
            scale = 4,
            engine = SuperResEngine.REAL_ESRGAN,
            resourceProfile = ProcessingResourceProfile(
                imageWidth = 4096,
                imageHeight = 1024,
                isLowRamDevice = true,
                availableMemoryBytes = Long.MAX_VALUE
            )
        )

        assertEquals(512, plan.coreTileWidth)
        assertTrue(plan.coreBandInputHeight < plan.coreTileWidth)
        assertEquals(plan.tiles.sortedWith(compareBy<ExtremeExportTileSpec> { it.outputY }.thenBy { it.outputX }), plan.tiles)
        plan.tiles.zipWithNext().forEach { (current, next) ->
            assertTrue(
                current.outputY < next.outputY ||
                    (current.outputY == next.outputY && current.outputX < next.outputX)
            )
        }
        assertEquals(plan.coreBandInputHeight, plan.tiles.first().coreH)
        assertTrue(plan.tiles.all { it.outputW > 0 && it.outputH > 0 })
    }

    @Test
    fun derivesBandHeightFromScaledOutputRowsWithinBudget() {
        val plan = ExtremeExportPlanner.plan(
            imageWidth = 4096,
            imageHeight = 1024,
            scale = 4,
            engine = SuperResEngine.REAL_ESRGAN,
            resourceProfile = ProcessingResourceProfile(
                imageWidth = 4096,
                imageHeight = 1024,
                isLowRamDevice = true
            )
        )

        assertEquals(85, plan.coreBandInputHeight)
        val bandBytes = plan.coreBandInputHeight.toLong() * plan.outputWidth * plan.scale * 3L
        assertTrue(bandBytes <= plan.rowBandBudgetBytes)
    }

    @Test
    fun rejectsPathologicalTileCountBeforeAllocatingTileList() {
        try {
            ExtremeExportPlanner.plan(
                imageWidth = 1_000_000,
                imageHeight = 1_000_000,
                scale = 1,
                engine = SuperResEngine.WAIFU2X
            )
            fail("Expected pathological tile count failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("tiles", ignoreCase = true))
        }
    }

    @Test
    fun rejectsOutputDimensionOverflowInsteadOfWrapping() {
        try {
            ExtremeExportPlanner.plan(
                imageWidth = Int.MAX_VALUE,
                imageHeight = 2,
                scale = 2,
                engine = SuperResEngine.WAIFU2X
            )
            fail("Expected checked output dimension failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("overflow", ignoreCase = true))
        }
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
