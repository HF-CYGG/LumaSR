package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaOutputNamerTest {
    @Test
    fun createsLocalSrGalleryName() {
        val name = MediaOutputNamer.create(
            timestampMillis = 1_718_792_096_000L,
            modelName = "RealCUGAN Standard",
            scale = 2,
            format = OutputFormat.PNG
        )

        assertEquals("LocalSR_20240619_181456_RealCUGAN_Standard_2x.png", name)
    }
}
