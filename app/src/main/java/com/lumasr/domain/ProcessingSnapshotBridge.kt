package com.lumasr.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProcessingSnapshotOwner {
    FOREGROUND_FALLBACK,
    SERVICE
}

class ProcessingSnapshotBridge {
    private val lock = Any()
    private var nextEpoch = 0L
    private var activeOwner: ActiveOwner? = null
    private val _snapshots = MutableStateFlow<List<ProcessingTaskSnapshot>>(emptyList())

    val snapshots: StateFlow<List<ProcessingTaskSnapshot>> = _snapshots.asStateFlow()

    fun acquire(owner: ProcessingSnapshotOwner): ProcessingSnapshotLease? = synchronized(lock) {
        if (
            owner == ProcessingSnapshotOwner.FOREGROUND_FALLBACK &&
            activeOwner?.owner == ProcessingSnapshotOwner.SERVICE
        ) {
            return@synchronized null
        }
        val active = ActiveOwner(owner = owner, epoch = ++nextEpoch)
        activeOwner = active
        ProcessingSnapshotLease(this, active.owner, active.epoch)
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

    internal fun release(owner: ProcessingSnapshotOwner, epoch: Long): Boolean = synchronized(lock) {
        if (!isCurrent(owner, epoch)) return@synchronized false
        activeOwner = null
        true
    }

    private fun isCurrent(owner: ProcessingSnapshotOwner, epoch: Long): Boolean {
        return activeOwner == ActiveOwner(owner, epoch)
    }

    private data class ActiveOwner(
        val owner: ProcessingSnapshotOwner,
        val epoch: Long
    )
}

class ProcessingSnapshotLease internal constructor(
    private val bridge: ProcessingSnapshotBridge,
    private val owner: ProcessingSnapshotOwner,
    private val epoch: Long
) {
    fun publish(snapshots: List<ProcessingTaskSnapshot>): Boolean {
        return bridge.publish(owner, epoch, snapshots)
    }

    fun clear(): Boolean = bridge.clear(owner, epoch)

    fun release(): Boolean = bridge.release(owner, epoch)
}

object ApplicationProcessingSnapshotBridge {
    private val delegate = ProcessingSnapshotBridge()

    val snapshots: StateFlow<List<ProcessingTaskSnapshot>>
        get() = delegate.snapshots

    fun acquire(owner: ProcessingSnapshotOwner): ProcessingSnapshotLease? {
        return delegate.acquire(owner)
    }
}
