package com.lumasr.processor

import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeModelFileSelectorTest {
    @Test
    fun selectsWaifu2xScaleOnlyModelWhenNoiseDisabled() {
        val files = NativeModelFileSelector.select(
            engine = SuperResEngine.WAIFU2X,
            modelDir = "cache/models/waifu2x-photo",
            scale = 2,
            noise = -1
        )

        assertEquals("cache/models/waifu2x-photo/scale2.0x_model.param", files.paramPath)
        assertEquals("cache/models/waifu2x-photo/scale2.0x_model.bin", files.binPath)
    }

    @Test
    fun selectsWaifu2xNoiseAndScaleModelForDenoise() {
        val files = NativeModelFileSelector.select(
            engine = SuperResEngine.WAIFU2X,
            modelDir = "cache/models/waifu2x-cunet",
            scale = 2,
            noise = 3
        )

        assertEquals("cache/models/waifu2x-cunet/noise3_scale2.0x_model.param", files.paramPath)
        assertEquals("cache/models/waifu2x-cunet/noise3_scale2.0x_model.bin", files.binPath)
    }

    @Test
    fun selectsRealCuganNoDenoiseAndConservativeNames() {
        val noDenoise = NativeModelFileSelector.select(
            engine = SuperResEngine.REAL_CUGAN,
            modelDir = "cache/models/realcugan-standard",
            scale = 2,
            noise = 0
        )
        val conservative = NativeModelFileSelector.select(
            engine = SuperResEngine.REAL_CUGAN,
            modelDir = "cache/models/realcugan-standard",
            scale = 4,
            noise = -1
        )

        assertEquals("cache/models/realcugan-standard/up2x-no-denoise.param", noDenoise.paramPath)
        assertEquals("cache/models/realcugan-standard/up2x-no-denoise.bin", noDenoise.binPath)
        assertEquals("cache/models/realcugan-standard/up4x-conservative.param", conservative.paramPath)
        assertEquals("cache/models/realcugan-standard/up4x-conservative.bin", conservative.binPath)
    }

    @Test
    fun selectsRealCuganDenoiseName() {
        val files = NativeModelFileSelector.select(
            engine = SuperResEngine.REAL_CUGAN,
            modelDir = "cache/models/realcugan-pro",
            scale = 3,
            noise = 3
        )

        assertEquals("cache/models/realcugan-pro/up3x-denoise3x.param", files.paramPath)
        assertEquals("cache/models/realcugan-pro/up3x-denoise3x.bin", files.binPath)
    }
}
