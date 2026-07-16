package com.lumasr.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTimeoutPolicyTest {
    @Test
    fun deadlineStopsTheServiceWhileThePauseThreadIsBlockedNonCooperatively() = runBlocking {
        val stopped = AtomicBoolean(false)
        val operationStarted = CompletableDeferred<Unit>()
        val stopObserved = CompletableDeferred<Unit>()
        val releaseOperation = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val startedAt = TimeSource.Monotonic.markNow()
        try {
            val operation = scope.launchWithForegroundDeadline(
                timeoutMs = 50L,
                operation = {
                    operationStarted.complete(Unit)
                    releaseOperation.await(5L, TimeUnit.SECONDS)
                },
                stopService = {
                    stopped.set(true)
                    stopObserved.complete(Unit)
                }
            )
            withTimeout(1_000L) {
                operationStarted.await()
                stopObserved.await()
            }

            assertTrue(stopped.get())
            assertTrue(operation.isActive)
            assertTrue(startedAt.elapsedNow() < 500.milliseconds)
        } finally {
            releaseOperation.countDown()
            scope.cancel()
        }
    }

    @Test
    fun completedPauseStillStopsTheService() = runBlocking {
        val stopCount = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val operation = scope.launchWithForegroundDeadline(
                timeoutMs = 1_000L,
                operation = {},
                stopService = { stopCount.incrementAndGet() }
            )
            withTimeout(1_000L) { operation.join() }

            assertEquals(1, stopCount.get())
        } finally {
            scope.cancel()
        }
    }
}
