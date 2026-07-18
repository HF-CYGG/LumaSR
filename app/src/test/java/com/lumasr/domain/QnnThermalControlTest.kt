package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnThermalControlTest {
    @Test
    fun mapsQnnPerformanceModeAtTileBoundariesAndPausesOnlyAtCritical() {
        val config = QnnExecutionConfig(
            graphKey = "realcugan-fp16-v73",
            precision = QnnPrecision.FP16,
            tileSize = 256,
            maxPipelineDepth = 2,
            performanceMode = QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE
        )
        val profile = InferenceExecutionProfile(
            backend = NativeBackend.LEGACY_SAFE,
            accelerationMode = AccelerationMode.QNN_HTP,
            precision = InferencePrecision.FP32,
            cpuThreads = 4,
            tileSize = 256,
            commandBatchSize = 1,
            gpuHeadroomPercent = 8,
            modelCacheCapacity = 1,
            tta = false,
            maxPipelineDepth = 2,
            runtimeBackend = InferenceRuntimeBackend.QNN_HTP,
            qnnConfig = config
        )
        val initial = AdaptiveInferencePolicy.initialThermalState(profile)

        val normal = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(initial, ThermalLevel.NORMAL)
        val moderate = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(initial, ThermalLevel.MODERATE)
        val severe = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(initial, ThermalLevel.SEVERE)
        val critical = AdaptiveInferencePolicy.adjustForThermalAtTileBoundary(initial, ThermalLevel.CRITICAL)

        assertEquals(QnnPerformanceMode.SUSTAINED_HIGH_PERFORMANCE, normal.effectiveProfile.qnnConfig?.performanceMode)
        assertEquals(QnnPerformanceMode.BALANCED, moderate.effectiveProfile.qnnConfig?.performanceMode)
        assertEquals(QnnPerformanceMode.LOW_POWER, severe.effectiveProfile.qnnConfig?.performanceMode)
        assertEquals(1, moderate.effectiveProfile.maxPipelineDepth)
        assertEquals(1, severe.effectiveProfile.maxPipelineDepth)
        assertFalse(severe.shouldPause)
        assertTrue(critical.shouldPause)
    }
}
