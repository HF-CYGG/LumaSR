package com.lumasr.domain

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingSnapshotBridgeTest {
    @Test
    fun currentOwnerPublishesAndClearsStateFlowSnapshots() {
        val bridge = ProcessingSnapshotBridge()
        val lease = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))
        val expected = listOf(snapshot("fallback"))

        assertTrue(lease.publish(expected))
        assertEquals(expected, bridge.snapshots.value)
        assertTrue(lease.clear())
        assertTrue(bridge.snapshots.value.isEmpty())
    }

    @Test
    fun serviceTakeoverInvalidatesFallbackEpochAndRejectsLateSnapshots() {
        val bridge = ProcessingSnapshotBridge()
        val fallback = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))
        assertTrue(fallback.publish(listOf(snapshot("old-fallback"))))

        val service = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val serviceSnapshots = listOf(snapshot("service"))
        assertTrue(service.publish(serviceSnapshots))

        assertFalse(fallback.publish(listOf(snapshot("late-fallback"))))
        assertFalse(fallback.clear())
        assertFalse(fallback.release())
        assertEquals(serviceSnapshots, bridge.snapshots.value)
        assertTrue(service.release())
        assertFalse(service.publish(listOf(snapshot("destroyed-service"))))
    }

    @Test
    fun fallbackCannotTakeOwnershipFromActiveService() {
        val bridge = ProcessingSnapshotBridge()
        val service = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))

        assertNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))
        assertTrue(service.publish(listOf(snapshot("service"))))
    }

    @Test
    fun serviceRevokesFallbackAndWaitsForItsReleaseBarrier() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val fallback = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))

        val fallbackRevoked = async { fallback.awaitRevocation() }
        val service = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        withTimeout(1_000L) { fallbackRevoked.await() }

        val serviceReady = async { service.awaitPredecessorRelease() }
        delay(50L)
        assertFalse(serviceReady.isCompleted)

        assertFalse(fallback.release())
        withTimeout(1_000L) { serviceReady.await() }
        assertTrue(service.publish(listOf(snapshot("service-after-release"))))
    }

    @Test
    fun newerServiceRevokesOlderServiceAndChainsItsReleaseBarrier() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val firstService = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val firstRevoked = async { firstService.awaitRevocation() }

        val secondService = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        withTimeout(1_000L) { firstRevoked.await() }
        assertNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))

        val secondReady = async { secondService.awaitPredecessorRelease() }
        delay(50L)
        assertFalse(secondReady.isCompleted)
        assertFalse(firstService.release())
        assertEquals(
            ProcessingOwnerHandoffResult.Released,
            withTimeout(1_000L) { secondReady.await() }
        )

        assertFalse(firstService.publish(listOf(snapshot("stale-first-service"))))
        assertTrue(secondService.publish(listOf(snapshot("second-service"))))
        assertNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))
        assertTrue(secondService.release())
    }

    @Test
    fun failedCurrentGenerationDoesNotOrphanOwnershipAndIsVisibleToNextOwner() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val failedFallback = requireNotNull(
            bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK)
        )

        assertTrue(failedFallback.failRelease())
        val retryFallback = requireNotNull(
            bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK)
        )
        assertEquals(
            ProcessingOwnerHandoffResult.Failed,
            retryFallback.awaitPredecessorRelease()
        )
        assertTrue(retryFallback.release())
    }

    private fun snapshot(id: String): ProcessingTaskSnapshot {
        val taskDirectory = File("build/bridge/$id")
        return ProcessingTaskSnapshot(
            task = ProcessingTask(
                id = id,
                copiedInputPath = taskDirectory.resolve("input.png").absolutePath,
                outputPath = taskDirectory.resolve("output.png").absolutePath,
                state = ProcessingTaskState.QUEUED,
                imageIndex = 0,
                imageCount = 1,
                completedExtremeTileIndexes = emptySet(),
                createdAtEpochMs = 1_000L,
                updatedAtEpochMs = 1_000L,
                terminalAtEpochMs = null,
                retryableErrorCode = null,
                executionProfileKey = "profile"
            ),
            progress = null
        )
    }
}
