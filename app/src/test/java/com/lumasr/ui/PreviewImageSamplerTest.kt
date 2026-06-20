package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewImageSamplerTest {
    @Test
    fun calculatesPowerOfTwoSampleSizeForLargePreview() {
        val sampleSize = PreviewImageSampler.calculateInSampleSize(
            width = 6000,
            height = 4000,
            maxDimension = 1800
        )

        assertEquals(2, sampleSize)
    }

    @Test
    fun keepsOriginalSizeWhenImageAlreadyFitsPreview() {
        val sampleSize = PreviewImageSampler.calculateInSampleSize(
            width = 1280,
            height = 960,
            maxDimension = 1800
        )

        assertEquals(1, sampleSize)
    }
}
