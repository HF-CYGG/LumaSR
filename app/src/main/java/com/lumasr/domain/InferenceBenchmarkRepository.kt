package com.lumasr.domain

import java.util.concurrent.atomic.AtomicReference

class BenchmarkPublicationGate {
    private val state = AtomicReference(State.OPEN)

    /** Records cancellation without waiting for filesystem work already in progress. */
    fun cancel(): Boolean {
        while (true) {
            val current = state.get()
            val cancelled = when (current) {
                State.OPEN -> State.CANCELLED
                State.COMMITTING -> State.ROLLBACK_REQUESTED
                else -> return false
            }
            if (state.compareAndSet(current, cancelled)) return true
        }
    }

    fun tryCommit(commit: () -> Unit): Boolean = tryCommit(commit, rollback = {})

    /**
     * Applies the commit and then rolls it back when cancellation won while commit was running.
     * Both callbacks execute on the publisher thread so repository locking remains intact.
     */
    fun tryCommit(
        commit: () -> Unit,
        rollback: () -> Unit
    ): Boolean {
        if (!state.compareAndSet(State.OPEN, State.COMMITTING)) return false
        try {
            commit()
        } catch (commitError: Throwable) {
            rollbackAfterFailedCommitIfRequested(rollback, commitError)
            throw commitError
        }

        while (true) {
            when (state.get()) {
                State.COMMITTING -> if (state.compareAndSet(State.COMMITTING, State.COMMITTED)) {
                    return true
                }
                State.ROLLBACK_REQUESTED -> {
                    rollback()
                    state.set(State.ROLLED_BACK)
                    return false
                }
                else -> error("Invalid benchmark publication state after commit")
            }
        }
    }

    val isCommitted: Boolean
        get() = state.get() == State.COMMITTED

    private fun rollbackAfterFailedCommitIfRequested(
        rollback: () -> Unit,
        commitError: Throwable
    ) {
        while (true) {
            when (state.get()) {
                State.COMMITTING -> if (state.compareAndSet(State.COMMITTING, State.FAILED)) {
                    return
                }
                State.ROLLBACK_REQUESTED -> {
                    try {
                        rollback()
                        state.set(State.ROLLED_BACK)
                    } catch (rollbackError: Throwable) {
                        commitError.addSuppressed(rollbackError)
                        state.set(State.FAILED)
                    }
                    return
                }
                else -> {
                    state.set(State.FAILED)
                    return
                }
            }
        }
    }

    private enum class State {
        OPEN,
        CANCELLED,
        COMMITTING,
        ROLLBACK_REQUESTED,
        COMMITTED,
        ROLLED_BACK,
        FAILED
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
