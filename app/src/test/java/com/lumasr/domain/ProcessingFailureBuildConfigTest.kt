package com.lumasr.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcessingFailureBuildConfigTest {
    @Test
    fun supportsAndroid10AndNewer() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("minSdk = 29"))
    }

    @Test
    fun disablesDiagnosticProcessingCrashesInReleaseBuilds() {
        val buildScript = File("build.gradle.kts").readText().replace("\r\n", "\n")

        assertTrue(
            "Release builds must report processing failures in the UI instead of crashing the app.",
            buildScript.contains(
                "buildConfigField(\"boolean\", \"LUMASR_CRASH_ON_PROCESSING_FAILURE\", \"false\")"
            )
        )
    }
}
