package com.lumasr.service

import com.lumasr.data.FileProcessingTaskMaintenance
import com.lumasr.data.FileProcessingTaskRepository
import com.lumasr.data.FileProcessingWorkSpecRepository
import com.lumasr.domain.ApplicationProcessingSnapshotBridge
import com.lumasr.domain.ProcessingCoordinator
import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ProcessingOwnerHandoffResult
import com.lumasr.domain.ProcessingSnapshotLease
import com.lumasr.domain.ProcessingSnapshotOwner
import com.lumasr.domain.ThermalLevel
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
        val target = sessionMutex.withLock {
            check(!closed.get()) { "Foreground processing runtime is closed" }
            session?.let { return@withLock ResumeTarget(it, newlyCreated = false) }
            val lease = leaseAcquirer(ProcessingSnapshotOwner.FOREGROUND_FALLBACK)
                ?: return false
            val coordinator = try {
                coordinatorFactory()
            } catch (failure: Exception) {
                lease.release()
                throw failure
            }
            val created = Session(
                id = nextSessionId.incrementAndGet(),
                lease = lease,
                coordinator = coordinator
            )
            val job = scope.launch(start = CoroutineStart.LAZY) { runSession(created) }
            created.job = job
            session = created
            job.start()
            ResumeTarget(created, newlyCreated = true)
        }
        if (!target.newlyCreated) {
            target.session.operationMutex.withLock {
                target.session.coordinator.resumePersistedWork()
            }
        }
        return target.session.job.isActive || !target.session.job.isCompleted
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
        var sessionFailed = false
        try {
            coroutineScope {
                val publisher = launch {
                    activeSession.coordinator.snapshots.collect { snapshots ->
                        activeSession.lease.publish(snapshots)
                    }
                }
                try {
                    activeSession.operationMutex.withLock {
                        activeSession.coordinator.resumePersistedWork()
                    }
                    // Foreground exit and Service takeover share the same fail-closed shutdown path.
                    awaitExitRequest(activeSession)
                } finally {
                    publisher.cancelAndJoin()
                }
            }
        } catch (_: Exception) {
            sessionFailed = true
        } finally {
            withContext(NonCancellable) {
                var handoffResult: ProcessingOwnerHandoffResult = ProcessingOwnerHandoffResult.Released
                activeSession.operationMutex.withLock {
                    try {
                        activeSession.coordinator.relinquishForOwnerHandoff()
                    } catch (_: Exception) {
                        sessionFailed = true
                        handoffResult = ProcessingOwnerHandoffResult.Failed
                    }
                    try {
                        activeSession.coordinator.close()
                    } catch (_: Exception) {
                        sessionFailed = true
                        handoffResult = ProcessingOwnerHandoffResult.Failed
                    }
                }
                when (handoffResult) {
                    ProcessingOwnerHandoffResult.Released -> activeSession.lease.release()
                    // A failed state recovery is published only after native execution has stopped.
                    ProcessingOwnerHandoffResult.Failed -> activeSession.lease.failRelease()
                }
                val result = if (sessionFailed) {
                    ForegroundRuntimeStopResult.Failed
                } else {
                    ForegroundRuntimeStopResult.Stopped
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
        val operationMutex = Mutex()
        lateinit var job: Job
    }

    private data class ResumeTarget(
        val session: Session,
        val newlyCreated: Boolean
    )
}

object ApplicationForegroundProcessingRuntime {
    private val lock = Any()
    private var taskDirectoryPath: String? = null

    @Volatile
    private var instance: ForegroundProcessingRuntime? = null

    fun getOrCreate(
        taskDirectory: File,
        thermalLevelProvider: () -> ThermalLevel? = { null },
        executionProfileResolver: ProcessingExecutionProfileResolver? = null
    ): ForegroundProcessingRuntime {
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
                    val taskRepository = FileProcessingTaskRepository(normalizedDirectory)
                    val workSpecRepository = FileProcessingWorkSpecRepository(normalizedDirectory)
                    ProcessingCoordinator(
                        taskRepository = taskRepository,
                        workSpecRepository = workSpecRepository,
                        runner = executionProfileResolver?.let { resolver ->
                            SuperResProcessingTaskRunner(
                                thermalLevelProvider = thermalLevelProvider,
                                executionProfileResolver = resolver
                            )
                        } ?: SuperResProcessingTaskRunner(
                            thermalLevelProvider = thermalLevelProvider
                        ),
                        maintenance = FileProcessingTaskMaintenance(
                            taskRoot = normalizedDirectory,
                            taskRepository = taskRepository,
                            workSpecRepository = workSpecRepository
                        )
                    )
                }
            ).also { instance = it }
        }
    }
}
