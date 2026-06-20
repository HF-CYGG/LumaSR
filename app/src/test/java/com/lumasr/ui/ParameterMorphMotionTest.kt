package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterMorphMotionTest {
    @Test
    fun keepsOneCurrentControlWithoutTranslation() {
        assertEquals(1f, ParameterMorphMotion.settledScaleX, 0.0001f)
    }

    @Test
    fun usesBezierCompressionWithoutOvershoot() {
        assertTrue(ParameterMorphMotion.compressedScaleX in 0.9f..0.98f)
        assertEquals(ParameterMorphMotion.settledScaleX, ParameterMorphMotion.maxScaleX, 0.0001f)
    }
}
