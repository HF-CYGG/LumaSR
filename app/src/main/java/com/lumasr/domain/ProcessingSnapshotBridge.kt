package com.lumasr.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred

enum class ProcessingSnapshotOwner {
    FOREGROUND_FALLBACK,
    SERVICE
}

sealed interface ProcessingOwnerHandoffResult {
    data object Released : ProcessingOwnerHandoffResult

    data object Failed : ProcessingOwnerHandoffResult
}

class ProcessingSnapshotBridge {
    private val lock = Any()
    private var nextEpoch = 0L
    private var activeOwner: LeaseState? = null
    private var pendingPredecessorResult: ProcessingOwnerHandoffResult? = null
    private val _snapshots = MutableStateFlow<List<ProcessingTaskSnapshot>>(emptyList())

    val snapshots: StateFlow<List<ProcessingTaskSnapshot>> = _snapshots.asStateFlow()

    fun acquire(owner: ProcessingSnapshotOwner): ProcessingSnapshotLease? = synchronized(lock) {
        val predecessor = activeOwner
        if (owner == ProcessingSnapshotOwner.FOREGROUND_FALLBACK && predecessor != null) {
            return@synchronized null
        }
        predecessor?.revoked?.complete(Unit)
        val predecessorReleased = predecessor?.released ?: completedSignal(
            pendingPredecessorResult ?: ProcessingOwnerHandoffResult.Released
        )
        if (predecessor == null) pendingPredecessorResult = null
        val active = LeaseState(owner = owner, epoch = ++nextEpoch)
        activeOwner = active
        ProcessingSnapshotLease(
            bridge = this,
            state = active,
            predecessorReleased = predecessorReleased
        )
    }

    internal fun publish(
        owner: ProcessingSnapshotOwner,
        epoch: Long,
        snapshots: List<ProcessingTaskSnapshot>
    ): Boolean = synchronized(lock) {
        if (!isCurrent(owner, epoch)) return@synchronized false
        _snapshots.value = snapshots.toList()
        true
    }

    internal fun clear(owner: ProcessingSnapshotOwner, epoch: Long): Boolean =
        publish(owner, epoch, emptyList())

    internal fun release(
        state: LeaseState,
        result: ProcessingOwnerHandoffResult
    ): Boolean = synchronized(lock) {
        state.released.complete(result)
        if (activeOwner !== state) return@synchronized false
        activeOwner = null
        pendingPredecessorResult = result
        true
    }

    private fun isCurrent(owner: ProcessingSnapshotOwner, epoch: Long): Boolean {
        val active = activeOwner
        return active?.owner == owner && active.epoch == epoch
    }

    internal class LeaseState(
        val owner: ProcessingSnapshotOwner,
        val epoch: Long,
        val revoked: CompletableDeferred<Unit> = CompletableDeferred(),
        val released: CompletableDeferred<ProcessingOwnerHandoffResult> = CompletableDeferred()
    )

    private fun completedSignal(
        result: ProcessingOwnerHandoffResult
    ): CompletableDeferred<ProcessingOwnerHandoffResult> =
        CompletableDeferred<ProcessingOwnerHandoffResult>().apply {
            complete(result)
        }
}

class ProcessingSnapshotLease internal constructor(
    private val bridge: ProcessingSnapshotBridge,
    private val state: ProcessingSnapshotBridge.LeaseState,
    private val predecessorReleased: CompletableDeferred<ProcessingOwnerHandoffResult>
) {
    fun publish(snapshots: List<ProcessingTaskSnapshot>): Boolean {
        return bridge.publish(state.owner, state.epoch, snapshots)
    }

    fun clear(): Boolean = bridge.clear(state.owner, state.epoch)

    suspend fun awaitRevocation() {
        state.revoked.await()
    }

    suspend fun awaitPredecessorRelease(): ProcessingOwnerHandoffResult =
        predecessorReleased.await()

    fun release(): Boolean = bridge.release(state, ProcessingOwnerHandoffResult.Released)

    fun failRelease(): Boolean = bridge.release(state, ProcessingOwnerHandoffResult.Failed)
}

object ApplicationProcessingSnapshotBridge {
    private val delegate = ProcessingSnapshotBridge()

    val snapshots: StateFlow<List<ProcessingTaskSnapshot>>
        get() = delegate.snapshots

    fun acquire(owner: ProcessingSnapshotOwner): ProcessingSnapshotLease? {
        return delegate.acquire(owner)
    }
}
