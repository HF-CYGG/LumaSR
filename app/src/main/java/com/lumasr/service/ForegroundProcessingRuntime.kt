package com.lumasr.service

import com.lumasr.data.FileProcessingTaskRepository
import com.lumasr.data.FileProcessingWorkSpecRepository
import com.lumasr.domain.ApplicationProcessingSnapshotBridge
import com.lumasr.domain.ProcessingCoordinator
import com.lumasr.domain.ProcessingSnapshotLease
import com.lumasr.domain.ProcessingSnapshotOwner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ForegroundRuntimeStopResult {
    data object Stopped : ForegroundRuntimeStopResult

    data object Failed : ForegroundRuntimeStopResult
}

class ForegroundProcessingRuntime(
    private val leaseAcquirer: (ProcessingSnapshotOwner) -> ProcessingSnapshotLease?,
    private val coordinatorFactory: () -> ProcessingCoordinator,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val runtimeJob = SupervisorJob()
    private val scope = CoroutineScope(runtimeJob + dispatcher)
    private val sessionMutex = Mutex()
    private val closeMutex = Mutex()
    private val nextSessionId = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private var session: Session? = null

    val isClosed: Boolean
        get() = closed.get()

    suspend fun resumePersistedWork(): Boolean {
        val newSession = sessionMutex.withLock {
            check(!closed.get()) { "Foreground processing runtime is closed" }
            if (session != null) return true
            val lease = leaseAcquirer(ProcessingSnapshotOwner.FOREGROUND_FALLBACK)
                ?: return false
            Session(
                id = nextSessionId.incrementAndGet(),
                lease = lease,
                coordinator = coordinatorFactory()
            ).also { created ->
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    runSession(created)
                }
                created.job = job
                session = created
                job.start()
            }
        }
        return newSession.job.isActive || !newSession.job.isCompleted
    }

    suspend fun cancel(taskId: String): Boolean {
        require(taskId.isNotBlank()) { "Task id must not be blank" }
        val activeCoordinator = sessionMutex.withLock { session?.coordinator } ?: return false
        activeCoordinator.cancel(taskId)
        return true
    }

    suspend fun stopForForegroundExit(): ForegroundRuntimeStopResult {
        val activeSession = sessionMutex.withLock { session }
            ?: return ForegroundRuntimeStopResult.Stopped
        activeSession.stopRequested.complete(Unit)
        return activeSession.completion.await()
    }

    suspend fun closeAndJoin(): ForegroundRuntimeStopResult = closeMutex.withLock {
        if (closed.get()) return@withLock ForegroundRuntimeStopResult.Stopped
        val activeSession = sessionMutex.withLock {
            closed.set(true)
            session
        }
        val result = if (activeSession == null) {
            ForegroundRuntimeStopResult.Stopped
        } else {
            activeSession.stopRequested.complete(Unit)
            activeSession.completion.await()
        }
        runtimeJob.cancelAndJoin()
        result
    }

    private suspend fun runSession(activeSession: Session) {
        var result: ForegroundRuntimeStopResult = ForegroundRuntimeStopResult.Failed
        try {
            coroutineScope {
                val publisher = launch {
                    activeSession.coordinator.snapshots.collect { snapshots ->
                        activeSession.lease.publish(snapshots)
                    }
                }
                try {
                    activeSession.coordinator.resumePersistedWork()
                    // Foreground exit and Service takeover share the same fail-closed shutdown path.
                    awaitExitRequest(activeSession)
                    withContext(NonCancellable) {
                        activeSession.coordinator.relinquishForOwnerHandoff()
                    }
                    result = ForegroundRuntimeStopResult.Stopped
                } finally {
                    publisher.cancelAndJoin()
                }
            }
        } catch (_: Exception) {
            result = ForegroundRuntimeStopResult.Failed
        } finally {
            withContext(NonCancellable) {
                activeSession.coordinator.close()
                when (result) {
                    ForegroundRuntimeStopResult.Stopped -> activeSession.lease.release()
                    // Complete the predecessor barrier with failure so Service can stop instead of hanging.
                    ForegroundRuntimeStopResult.Failed -> activeSession.lease.failRelease()
                }
                sessionMutex.withLock {
                    if (session?.id == activeSession.id) session = null
                }
                activeSession.completion.complete(result)
            }
        }
    }

    private suspend fun awaitExitRequest(activeSession: Session) = coroutineScope {
        val exitRequested = CompletableDeferred<Unit>()
        val revocationWatcher = launch {
            activeSession.lease.awaitRevocation()
            exitRequested.complete(Unit)
        }
        val foregroundExitWatcher = launch {
            activeSession.stopRequested.await()
            exitRequested.complete(Unit)
        }
        try {
            exitRequested.await()
        } finally {
            revocationWatcher.cancel()
            foregroundExitWatcher.cancel()
            joinAll(revocationWatcher, foregroundExitWatcher)
        }
    }

    private class Session(
        val id: Long,
        val lease: ProcessingSnapshotLease,
        val coordinator: ProcessingCoordinator,
        val stopRequested: CompletableDeferred<Unit> = CompletableDeferred(),
        val completion: CompletableDeferred<ForegroundRuntimeStopResult> = CompletableDeferred()
    ) {
        lateinit var job: Job
    }
}

object ApplicationForegroundProcessingRuntime {
    private val lock = Any()
    private var taskDirectoryPath: String? = null

    @Volatile
    private var instance: ForegroundProcessingRuntime? = null

    fun getOrCreate(taskDirectory: File): ForegroundProcessingRuntime {
        val normalizedDirectory = taskDirectory.absoluteFile.normalize()
        val normalizedPath = normalizedDirectory.path
        return synchronized(lock) {
            instance?.takeUnless(ForegroundProcessingRuntime::isClosed)?.let { current ->
                check(taskDirectoryPath == normalizedPath) {
                    "Foreground processing runtime already uses another task directory"
                }
                return@synchronized current
            }
            taskDirectoryPath = normalizedPath
            ForegroundProcessingRuntime(
                leaseAcquirer = ApplicationProcessingSnapshotBridge::acquire,
                coordinatorFactory = {
                    ProcessingCoordinator(
                        taskRepository = FileProcessingTaskRepository(normalizedDirectory),
                        workSpecRepository = FileProcessingWorkSpecRepository(normalizedDirectory),
                        runner = SuperResProcessingTaskRunner()
                    )
                }
            ).also { instance = it }
        }
    }
}
