package com.lumasr.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSaveQueueTest {
    @Test
    fun selectsSafOnlyBeforeAndroidQ() {
        assertEquals(ResultSaveDestination.SAF_DOCUMENT, resultSaveDestinationForSdk(26))
        assertEquals(ResultSaveDestination.SAF_DOCUMENT, resultSaveDestinationForSdk(28))
        assertEquals(ResultSaveDestination.MEDIA_STORE, resultSaveDestinationForSdk(29))
        assertEquals(ResultSaveDestination.MEDIA_STORE, resultSaveDestinationForSdk(35))
    }

    @Test
    fun startsWithFirstDocumentPromptWithoutExposingAnotherItem() {
        val queue = DocumentSaveQueue.start(items(), firstRequestId = 40L)

        assertEquals(firstItem, queue.currentItem)
        assertEquals(
            DocumentSavePrompt(
                requestId = 40L,
                displayName = "first.png",
                mimeType = "image/png",
                launched = false
            ),
            queue.prompt
        )
        assertEquals(0, queue.savedCount)
        assertEquals(2, queue.remainingCount)
        assertFalse(queue.isFinished)
    }

    @Test
    fun marksOnlyTheCurrentRequestAsLaunched() {
        val queue = DocumentSaveQueue.start(items(), firstRequestId = 7L)

        val stale = queue.markLaunched(requestId = 6L)
        val launched = queue.markLaunched(requestId = 7L)

        assertSame(queue, stale)
        assertTrue(requireNotNull(launched.prompt).launched)
        assertEquals(firstItem, launched.currentItem)
    }

    @Test
    fun successfulSaveAdvancesToNextPromptAndKeepsSavedUris() {
        val queue = DocumentSaveQueue.start(items(), firstRequestId = 12L)
            .markLaunched(12L)

        val advanced = queue.complete(12L, "content://saved/first")

        assertEquals(listOf("content://saved/first"), advanced.savedUris)
        assertEquals(secondItem, advanced.currentItem)
        assertEquals(13L, requireNotNull(advanced.prompt).requestId)
        assertFalse(requireNotNull(advanced.prompt).launched)
        assertEquals(1, advanced.savedCount)
        assertEquals(1, advanced.remainingCount)
    }

    @Test
    fun completingLastItemFinishesQueue() {
        val finished = DocumentSaveQueue.start(listOf(firstItem), firstRequestId = 3L)
            .complete(3L, "content://saved/first")

        assertNull(finished.prompt)
        assertNull(finished.currentItem)
        assertEquals(1, finished.savedCount)
        assertEquals(0, finished.remainingCount)
        assertTrue(finished.isFinished)
        assertFalse(finished.wasStopped)
    }

    @Test
    fun cancellationStopsRemainingItemsButPreservesCompletedUris() {
        val queue = DocumentSaveQueue.start(items(), firstRequestId = 20L)
            .complete(20L, "content://saved/first")

        val cancelled = queue.cancel(21L)

        assertNull(cancelled.prompt)
        assertEquals(listOf("content://saved/first"), cancelled.savedUris)
        assertEquals(1, cancelled.stoppedCount)
        assertTrue(cancelled.isFinished)
        assertTrue(cancelled.wasStopped)
    }

    @Test
    fun failureStopsRemainingItemsAndStaleCallbacksAreIgnored() {
        val queue = DocumentSaveQueue.start(items(), firstRequestId = 30L)

        assertSame(queue, queue.complete(29L, "content://stale"))
        assertSame(queue, queue.cancel(29L))
        assertSame(queue, queue.fail(29L))

        val failed = queue.fail(30L)

        assertTrue(failed.isFinished)
        assertTrue(failed.wasStopped)
        assertEquals(0, failed.savedCount)
        assertEquals(2, failed.stoppedCount)
    }

    private fun items() = listOf(firstItem, secondItem)

    private companion object {
        val firstItem = DocumentSaveItem(
            sourcePath = "C:/tasks/first/output.png",
            displayName = "first.png",
            mimeType = "image/png"
        )
        val secondItem = DocumentSaveItem(
            sourcePath = "C:/tasks/second/output.png",
            displayName = "second.png",
            mimeType = "image/png"
        )
    }
}
