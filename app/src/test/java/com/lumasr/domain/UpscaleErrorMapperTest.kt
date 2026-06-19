package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UpscaleErrorMapperTest {
    @Test
    fun mapsVulkanFailureToCpuFallbackMessage() {
        val message = UpscaleErrorMapper.userMessage(UpscaleErrorCode.VULKAN_RUNTIME_FAILED)

        assertEquals("GPU acceleration failed. Switched to CPU retry.", message)
    }
}
