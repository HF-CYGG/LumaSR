package com.lumasr.service

import com.lumasr.domain.ProcessingOwnerHandoffResult
import com.lumasr.domain.ProcessingSnapshotLease
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProcessingServiceOwnerSession(
    private val serviceLease: ProcessingSnapshotLease,
    private val cleanupScope: CoroutineScope,
    private val relinquishForOwnerHandoff: suspend () -> Unit,
    private val closeCoordinator: () -> Unit
) {
    private val cleanupLock = Any()
    private val operationMutex = Mutex()
    private val cleanupRequested = AtomicBoolean(false)
    private val predecessorFailureResolved = AtomicBoolean(false)
    private var cleanupJob: Deferred<ProcessingOwnerHandoffResult>? = null
    private var revocationWatcher: Job? = null

    init {
        revocationWatcher = cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            serviceLease.awaitRevocation()
            scheduleCleanup()
        }
    }

    suspend fun <T> runAfterPredecessorRelease(
        resolvesRecoveryFailure: Boolean = false,
        block: suspend () -> T
    ): T {
        val predecessorResult = serviceLease.awaitPredecessorRelease()
        return operationMutex.withLock {
            if (cleanupRequested.get()) {
                throw CancellationException("Service processing owner cleanup was requested")
            }
            val value = block()
            if (
                resolvesRecoveryFailure &&
                predecessorResult == ProcessingOwnerHandoffResult.Failed
            ) {
                predecessorFailureResolved.set(true)
            }
            value
        }
    }

    fun scheduleCleanup(): Deferred<ProcessingOwnerHandoffResult> = synchronized(cleanupLock) {
        cleanupRequested.set(true)
        cleanupJob ?: cleanupScope.async(start = CoroutineStart.LAZY) {
            cleanupOwnership()
        }.also { created ->
            cleanupJob = created
            created.start()
        }
    }

    private suspend fun cleanupOwnership(): ProcessingOwnerHandoffResult =
        withContext(NonCancellable) {
            val predecessorResult = serviceLease.awaitPredecessorRelease()
            operationMutex.withLock {
                var result = if (
                    predecessorResult == ProcessingOwnerHandoffResult.Failed &&
                    !predecessorFailureResolved.get()
                ) {
                    ProcessingOwnerHandoffResult.Failed
                } else {
                    ProcessingOwnerHandoffResult.Released
                }
                try {
                    relinquishForOwnerHandoff()
                } catch (_: Exception) {
                    result = ProcessingOwnerHandoffResult.Failed
                }
                try {
                    closeCoordinator()
                } catch (_: Exception) {
                    result = ProcessingOwnerHandoffResult.Failed
                }
                when (result) {
                    ProcessingOwnerHandoffResult.Released -> serviceLease.release()
                    ProcessingOwnerHandoffResult.Failed -> serviceLease.failRelease()
                }
                revocationWatcher?.cancel()
                result
            }
        }
}

internal object ApplicationProcessingServiceCleanupScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
