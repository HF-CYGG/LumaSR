package com.lumasr.processor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NativeProguardRulesTest {
    @Test
    fun keepsAllNativeProgressSinkMembersForJniCallbackSignatures() {
        val rules = File("proguard-rules.pro").readText()

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
}
