package com.lumasr.service

import com.lumasr.domain.ProcessingOwnerHandoffResult
import com.lumasr.domain.ProcessingSnapshotBridge
import com.lumasr.domain.ProcessingSnapshotOwner
import com.lumasr.domain.ProcessingTaskState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceOwnerSessionTest {
    @Test
    fun timeoutAndDestroyWaitForBlockedFallbackCleanupWithoutEarlyRelease() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val fallback = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))
        val serviceLease = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val events = CopyOnWriteArrayList<String>()
        val session = ProcessingServiceOwnerSession(
            serviceLease = serviceLease,
            cleanupScope = cleanupScope,
            relinquishForOwnerHandoff = { events += "relinquish" },
            closeCoordinator = { events += "close" }
        )
        try {
            val timeout = async {
                session.runAfterPredecessorRelease {
                    events += "timeout"
                }
            }
            val destroyCleanup = session.scheduleCleanup()
            delay(50L)

            assertTrue(events.isEmpty())
            assertFalse(destroyCleanup.isCompleted)
            assertNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))

            timeout.cancelAndJoin()
            assertFalse(fallback.release())
            assertEquals(
                ProcessingOwnerHandoffResult.Released,
                withTimeout(1_000L) { destroyCleanup.await() }
            )
            assertEquals(listOf("relinquish", "close"), events)
            assertTrue(
                requireNotNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK)).release()
            )
        } finally {
            fallback.release()
            session.scheduleCleanup().await()
            cleanupScope.cancel()
        }
    }

    @Test
    fun destroyWaitsForActiveRunnerStopAndQueuedRecoveryBeforeReleasingService() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val serviceLease = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val nativeCancelCalled = CompletableDeferred<Unit>()
        val nativeStopped = CompletableDeferred<Unit>()
        var state = ProcessingTaskState.RUNNING
        var coordinatorClosed = false
        val session = ProcessingServiceOwnerSession(
            serviceLease = serviceLease,
            cleanupScope = cleanupScope,
            relinquishForOwnerHandoff = {
                nativeCancelCalled.complete(Unit)
                nativeStopped.await()
                state = ProcessingTaskState.QUEUED
            },
            closeCoordinator = { coordinatorClosed = true }
        )
        try {
            val cleanup = session.scheduleCleanup()
            withTimeout(1_000L) { nativeCancelCalled.await() }

            assertFalse(cleanup.isCompleted)
            assertEquals(ProcessingTaskState.RUNNING, state)
            assertNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))

            nativeStopped.complete(Unit)
            assertEquals(
                ProcessingOwnerHandoffResult.Released,
                withTimeout(1_000L) { cleanup.await() }
            )
            assertEquals(ProcessingTaskState.QUEUED, state)
            assertTrue(coordinatorClosed)
            assertTrue(
                requireNotNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK)).release()
            )
        } finally {
            nativeStopped.complete(Unit)
            session.scheduleCleanup().await()
            cleanupScope.cancel()
        }
    }

    @Test
    fun newerServiceOperationWaitsForRevokedServiceAutomaticCleanup() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstLease = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val firstCleanupStarted = CompletableDeferred<Unit>()
        val allowFirstCleanup = CompletableDeferred<Unit>()
        val firstSession = ProcessingServiceOwnerSession(
            serviceLease = firstLease,
            cleanupScope = cleanupScope,
            relinquishForOwnerHandoff = {
                firstCleanupStarted.complete(Unit)
                allowFirstCleanup.await()
            },
            closeCoordinator = {}
        )
        val secondLease = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val secondSession = ProcessingServiceOwnerSession(
            serviceLease = secondLease,
            cleanupScope = cleanupScope,
            relinquishForOwnerHandoff = {},
            closeCoordinator = {}
        )
        try {
            val operationRan = CompletableDeferred<Unit>()
            val secondOperation = async {
                secondSession.runAfterPredecessorRelease {
                    operationRan.complete(Unit)
                }
            }
            withTimeout(1_000L) { firstCleanupStarted.await() }
            delay(50L)
            assertFalse(operationRan.isCompleted)
            assertNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))

            allowFirstCleanup.complete(Unit)
            withTimeout(1_000L) { secondOperation.await() }
            assertTrue(operationRan.isCompleted)
        } finally {
            allowFirstCleanup.complete(Unit)
            firstSession.scheduleCleanup().await()
            secondSession.scheduleCleanup().await()
            cleanupScope.cancel()
        }
    }

    @Test
    fun cleanupWaitsForInFlightOwnerOperationBeforeRelinquishing() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serviceLease = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val operationStarted = CompletableDeferred<Unit>()
        val allowOperationToFinish = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<String>()
        val session = ProcessingServiceOwnerSession(
            serviceLease = serviceLease,
            cleanupScope = cleanupScope,
            relinquishForOwnerHandoff = { events += "relinquish" },
            closeCoordinator = { events += "close" }
        )
        try {
            val operation = async(Dispatchers.Default) {
                session.runAfterPredecessorRelease {
                    events += "operation-start"
                    operationStarted.complete(Unit)
                    allowOperationToFinish.await()
                    events += "operation-end"
                }
            }
            withTimeout(1_000L) { operationStarted.await() }

            val cleanup = session.scheduleCleanup()
            delay(50L)
            assertFalse(cleanup.isCompleted)
            assertEquals(listOf("operation-start"), events)

            allowOperationToFinish.complete(Unit)
            withTimeout(1_000L) {
                operation.await()
                cleanup.await()
            }
            assertEquals(
                listOf("operation-start", "operation-end", "relinquish", "close"),
                events
            )
        } finally {
            allowOperationToFinish.complete(Unit)
            session.scheduleCleanup().await()
            cleanupScope.cancel()
        }
    }
}
