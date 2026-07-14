package com.lumasr.service

class ProcessingServiceLifecycleGate {
    private val lock = Any()
    private val activeOperationIds = mutableSetOf<Long>()
    private var latestStartId: Int? = null
    private var generation: Long = 0L
    private var nextOperationId: Long = 0L

    fun beginOperation(startId: Int): Operation = synchronized(lock) {
        require(startId > 0) { "Service start id must be positive" }
        generation += 1L
        latestStartId = startId
        Operation(id = ++nextOperationId).also { activeOperationIds += it.id }
    }

    fun completeOperation(operation: Operation) {
        synchronized(lock) {
            activeOperationIds.remove(operation.id)
        }
    }

    fun idleStopCandidate(isIdle: Boolean): IdleStopToken? = synchronized(lock) {
        val startId = latestStartId
        if (!isIdle || startId == null || activeOperationIds.isNotEmpty()) {
            null
        } else {
            IdleStopToken(startId = startId, generation = generation)
        }
    }

    fun isStopStillValid(token: IdleStopToken): Boolean = synchronized(lock) {
        latestStartId == token.startId &&
            generation == token.generation &&
            activeOperationIds.isEmpty()
    }

    data class Operation internal constructor(internal val id: Long)

    data class IdleStopToken internal constructor(
        val startId: Int,
        internal val generation: Long
    )
}
