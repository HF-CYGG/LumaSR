package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NoiseSliderMappingTest {
    @Test
    fun mapsDraggedValueToNearestNoiseLevel() {
        val level = nearestNoiseLevel(listOf(0, 1, 2, 3), 1.7f)

        assertEquals(2, level)
    }

    @Test
    fun supportsSparseNoiseLevels() {
        val level = nearestNoiseLevel(listOf(-1, 0, 3), 2.1f)

        assertEquals(3, level)
    }
}
