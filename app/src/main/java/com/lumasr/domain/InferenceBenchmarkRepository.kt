package com.lumasr.domain

class BenchmarkPublicationGate {
    private val lock = Any()
    private var state = State.OPEN

    fun cancel(): Boolean = synchronized(lock) {
        if (state != State.OPEN) return@synchronized false
        state = State.CANCELLED
        true
    }

    fun tryCommit(commit: () -> Unit): Boolean = synchronized(lock) {
        if (state != State.OPEN) return@synchronized false
        commit()
        state = State.COMMITTED
        true
    }

    val isCommitted: Boolean
        get() = synchronized(lock) { state == State.COMMITTED }

    private enum class State {
        OPEN,
        CANCELLED,
        COMMITTED
    }
}

interface InferenceBenchmarkRepository {
    suspend fun save(result: InferenceBenchmarkResult)

    suspend fun publish(
        result: InferenceBenchmarkResult,
        gate: BenchmarkPublicationGate
    ): Boolean

    suspend fun list(): List<InferenceBenchmarkResult>

    suspend fun findValid(identity: BenchmarkIdentity, nowEpochMs: Long): InferenceBenchmarkResult?
}
