package com.lumasr.service

import java.io.File
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

    private fun source(relativePath: String): String = File(
        "src/main/java/com/lumasr/$relativePath"
    ).readText()
}
