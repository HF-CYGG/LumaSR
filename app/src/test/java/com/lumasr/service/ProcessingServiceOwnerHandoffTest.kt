package com.lumasr.service

import com.lumasr.domain.ProcessingSnapshotBridge
import com.lumasr.domain.ProcessingSnapshotOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceOwnerHandoffTest {
    @Test
    fun serviceOperationWaitsForFallbackReleaseAndRunsExactlyOnce() = runBlocking {
        val bridge = ProcessingSnapshotBridge()
        val fallback = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.FOREGROUND_FALLBACK))
        val serviceLease = requireNotNull(bridge.acquire(ProcessingSnapshotOwner.SERVICE))
        val gate = ProcessingServiceOwnerHandoff(serviceLease)
        val invoked = CompletableDeferred<Unit>()
        var invocationCount = 0

        val operation = async {
            gate.runAfterPredecessorRelease {
                invocationCount += 1
                invoked.complete(Unit)
            }
        }
        delay(50L)
        assertFalse(invoked.isCompleted)

        assertFalse(fallback.release())
        withTimeout(1_000L) {
            operation.await()
            invoked.await()
        }

        assertTrue(invoked.isCompleted)
        assertTrue(invocationCount == 1)
        serviceLease.release()
        Unit
    }
}
