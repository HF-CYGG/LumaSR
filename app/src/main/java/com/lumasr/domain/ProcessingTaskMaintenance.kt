package com.lumasr.domain

data class ProcessingTaskMaintenanceResult(
    val removedTaskIds: Set<String>
)

fun interface ProcessingTaskMaintenance {
    suspend fun maintain(nowEpochMs: Long): ProcessingTaskMaintenanceResult

    companion object {
        val NONE = ProcessingTaskMaintenance {
            ProcessingTaskMaintenanceResult(emptySet())
        }
    }
}
