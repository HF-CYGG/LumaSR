package com.lumasr.service

import com.lumasr.domain.ProcessingSnapshotLease
import com.lumasr.domain.ProcessingOwnerHandoffResult

class ProcessingOwnerHandoffException : IllegalStateException(
    "Previous processing owner did not relinquish safely"
)

class ProcessingServiceOwnerHandoff(
    private val serviceLease: ProcessingSnapshotLease
) {
    suspend fun <T> runAfterPredecessorRelease(block: suspend () -> T): T {
        if (serviceLease.awaitPredecessorRelease() != ProcessingOwnerHandoffResult.Released) {
            throw ProcessingOwnerHandoffException()
        }
        return block()
    }
}
