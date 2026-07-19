package com.lumasr.processor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeProguardRulesTest {
    @Test
    fun keepsAllNativeProgressSinkMembersForJniCallbackSignatures() {
        val rules = File("proguard-rules.pro").readText().replace("\r\n", "\n")

        assertTrue(
            "NativeProgressSink is called from JNI and all callback signatures must survive R8.",
            rules.contains(
                """
                -keep class com.lumasr.processor.NativeProgressSink {
                    *;
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun keepsQnnRuntimeControlCallbackAbiForExternalAdapter() {
        val rules = File("proguard-rules.pro").readText().replace("\r\n", "\n")

        listOf(
            "com.lumasr.domain.RuntimeInferenceControlProvider",
            "com.lumasr.domain.RuntimeInferenceControl",
            "com.lumasr.domain.QnnPerformanceMode"
        ).forEach { className ->
            assertTrue(
                "$className is read by the external QNN adapter and must survive R8.",
                rules.contains(
                    """
                    -keep class $className {
                        *;
                    }
                    """.trimIndent()
                )
            )
        }
    }
}
