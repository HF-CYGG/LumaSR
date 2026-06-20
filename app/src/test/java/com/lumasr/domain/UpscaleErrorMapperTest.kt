package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UpscaleErrorMapperTest {
    @Test
    fun mapsVulkanFailureToCpuFallbackMessage() {
        val message = UpscaleErrorMapper.userMessage(UpscaleErrorCode.VULKAN_RUNTIME_FAILED)

        assertEquals("GPU acceleration failed. Switched to CPU retry.", message)
    }

    @Test
    fun mapsTileOutputMismatchToArtifactPreventionMessage() {
        val message = UpscaleErrorMapper.userMessage(UpscaleErrorCode.TILE_OUTPUT_MISMATCH)

        assertEquals("Model output size was inconsistent. Processing stopped to avoid striped exports.", message)
    }
}
