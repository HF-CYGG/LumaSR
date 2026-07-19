package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun parsesVersionTagsAndNormalizesLeadingV() {
        assertEquals("0.3.2", AppVersion.parse("v0.3.2")?.toString())
        assertEquals("1.0.5.1", AppVersion.parse("1.0.5.1")?.toString())
    }

    @Test
    fun comparesNumericSegmentsInsteadOfStrings() {
        assertTrue(AppVersion.parse("0.3.10")!! > AppVersion.parse("0.3.9")!!)
        assertTrue(AppVersion.parse("v1.0.5.1")!! > AppVersion.parse("v1.0.5")!!)
        assertEquals(AppVersion.parse("1.2"), AppVersion.parse("1.2.0"))
    }

    @Test
    fun rejectsNonNumericOrOverlongVersions() {
        assertNull(AppVersion.parse("release"))
        assertNull(AppVersion.parse("v1.2.3-beta"))
        assertNull(AppVersion.parse("v1"))
        assertNull(AppVersion.parse("v1.2.3.4.5"))
    }
}
