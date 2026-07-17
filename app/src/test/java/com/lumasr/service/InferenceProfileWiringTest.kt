package com.lumasr.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceProfileWiringTest {
    @Test
    fun serviceAndForegroundFallbackInjectCurrentProfileResolver() {
        val service = source("service/SuperResolutionService.kt")
        val foreground = source("service/ForegroundProcessingRuntime.kt")
        val factory = source("ui/PersistentProcessingAndroidFactory.kt")

        assertTrue(service.contains("executionProfileResolver = createInferenceExecutionProfileResolver(this)"))
        assertTrue(foreground.contains("executionProfileResolver = resolver"))
        assertTrue(factory.contains("executionProfileResolver = executionProfileResolver"))
    }

    @Test
    fun serviceAndForegroundFactoryInjectRuntimeResourceSource() {
        val service = source("service/SuperResolutionService.kt")
        val foreground = source("service/ForegroundProcessingRuntime.kt")
        val factory = source("ui/PersistentProcessingAndroidFactory.kt")
        val normalizedForeground = foreground.replace(Regex("\\s+"), " ")

        assertTrue(service.contains("runtimeResourceProvider = AndroidRuntimeResourceSource(this)"))
        assertTrue(
            normalizedForeground.contains(
                "runtimeResourceProvider: RuntimePipelineResourceProvider = " +
                    "RuntimePipelineResourceProvider { null }"
            )
        )
        assertEquals(2, foreground.split("runtimeResourceProvider = runtimeResourceProvider").size - 1)
        assertTrue(factory.contains("val runtimeResourceSource = AndroidRuntimeResourceSource(appContext)"))
        assertTrue(factory.contains("runtimeResourceProvider = runtimeResourceSource"))
    }

    @Test
    fun calibrationFactoryInjectsLiveThermalLevelProvider() {
        val factory = source("ui/CalibrationAndroidFactory.kt")

        assertTrue(factory.contains("ThermalSampler(AndroidThermalSignalSource(appContext))"))
        assertTrue(factory.contains("thermalLevelProvider = thermalSampler::sampleLevel"))
    }

    @Test
    fun calibrationFactoryInjectsLiveJavaHeapProvider() {
        val factory = source("ui/CalibrationAndroidFactory.kt")

        assertTrue(factory.contains("availableHeapBytesProvider ="))
        assertTrue(factory.contains("InferenceCalibrationRunner.currentAvailableHeapBytes()"))
    }

    private fun source(relativePath: String): String = File(
        "src/main/java/com/lumasr/$relativePath"
    ).readText()
}
