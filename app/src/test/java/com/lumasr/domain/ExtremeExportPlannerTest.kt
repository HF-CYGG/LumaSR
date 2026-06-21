package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        assertEquals(512, plan.coreTileSize)
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

        assertTrue(plan.coreTileSize < 512)
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

        assertEquals(768, plan.coreTileSize)
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
}
