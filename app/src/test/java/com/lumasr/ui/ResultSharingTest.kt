package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResultSharingTest {
    @Test
    fun createsSingleShareRequestForOneSavedResult() {
        val request = resultShareRequest(listOf("content://media/result-1"))

        assertEquals(ResultShareMode.SINGLE, request?.mode)
        assertEquals(listOf("content://media/result-1"), request?.uriStrings)
        assertEquals("image/png", request?.mimeType)
    }

    @Test
    fun createsMultipleShareRequestForSavedBatch() {
        val request = resultShareRequest(
            listOf(
                "content://media/result-1",
                "content://media/result-2",
                "content://media/result-1"
            )
        )

        assertEquals(ResultShareMode.MULTIPLE, request?.mode)
        assertEquals(
            listOf("content://media/result-1", "content://media/result-2"),
            request?.uriStrings
        )
    }

    @Test
    fun doesNotCreateShareRequestWithoutSavedResults() {
        assertNull(resultShareRequest(listOf("", "  ")))
    }
}
