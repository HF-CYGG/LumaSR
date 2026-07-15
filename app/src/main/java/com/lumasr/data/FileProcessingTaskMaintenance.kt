package com.lumasr.data

import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskMaintenance
import com.lumasr.domain.ProcessingTaskMaintenanceResult
import com.lumasr.domain.ProcessingTaskRepository
import com.lumasr.domain.ProcessingWorkSpecRepository
import com.lumasr.domain.TaskCleanupPolicy
import java.io.File

class FileProcessingTaskMaintenance(
    taskRoot: File,
    private val taskRepository: ProcessingTaskRepository,
    private val workSpecRepository: ProcessingWorkSpecRepository,
    private val deletePath: (File) -> Boolean = { path -> path.deleteRecursively() }
) : ProcessingTaskMaintenance {
    private val canonicalRoot = taskRoot.canonicalFile

    override suspend fun maintain(nowEpochMs: Long): ProcessingTaskMaintenanceResult {
        require(nowEpochMs >= 0L) { "Maintenance timestamp must be non-negative" }
        if (!canonicalRoot.exists()) {
            return ProcessingTaskMaintenanceResult(emptySet())
        }

        val persisted = taskRepository.list()
        val terminal = persisted
            .asSequence()
            .filter { it.state.isTerminal }
            .sortedWith(TERMINAL_ORDER)
            .toList()
        val overLimitIds = terminal
            .take((terminal.size - MAX_RETAINED_TERMINAL_TASKS).coerceAtLeast(0))
            .mapTo(linkedSetOf(), ProcessingTask::id)
        val candidates = terminal.filter { task ->
            task.id in overLimitIds || isExpired(task, nowEpochMs)
        }

        val removedIds = linkedSetOf<String>()
        candidates.forEach { candidate ->
            val current = runCatching { taskRepository.find(candidate.id) }.getOrNull()
            if (current == null || current != candidate || !current.state.isTerminal) return@forEach
            if (removeTask(current)) removedIds += current.id
        }

        cleanupStaleEntries(
            nowEpochMs = nowEpochMs,
            retainedTaskIds = persisted.mapTo(hashSetOf(), ProcessingTask::id) - removedIds
        )
        return ProcessingTaskMaintenanceResult(removedIds)
    }

    private suspend fun removeTask(task: ProcessingTask): Boolean {
        val artifactDirectory = File(canonicalRoot, task.id).canonicalFile
        if (!isDirectChild(artifactDirectory)) return false
        if (artifactDirectory.exists() && !deletePath(artifactDirectory)) return false
        if (artifactDirectory.exists()) return false
        if (!runCatching { workSpecRepository.delete(task.id) }.getOrDefault(false)) return false
        return runCatching { taskRepository.delete(task.id) }.getOrDefault(false)
    }

    private fun cleanupStaleEntries(
        nowEpochMs: Long,
        retainedTaskIds: Set<String>
    ) {
        canonicalRoot.listFiles().orEmpty().forEach { entry ->
            val canonicalEntry = runCatching { entry.canonicalFile }.getOrNull() ?: return@forEach
            if (!isDirectChild(canonicalEntry)) return@forEach
            val retention = when {
                canonicalEntry.isDirectory && canonicalEntry.name !in retainedTaskIds -> ORPHAN_GRACE_MS
                canonicalEntry.isFile && canonicalEntry.name.endsWith(".tmp") -> ORPHAN_GRACE_MS
                canonicalEntry.isFile && canonicalEntry.name.endsWith(".corrupt") -> TERMINAL_RETENTION_MS
                else -> return@forEach
            }
            if (hasReachedAge(canonicalEntry.lastModified(), nowEpochMs, retention)) {
                runCatching { deletePath(canonicalEntry) }
            }
        }
    }

    private fun isDirectChild(file: File): Boolean = file.parentFile == canonicalRoot

    private fun isExpired(task: ProcessingTask, nowEpochMs: Long): Boolean {
        val terminalAt = task.terminalAtEpochMs ?: return false
        return hasReachedAge(terminalAt, nowEpochMs, TERMINAL_RETENTION_MS)
    }

    private fun hasReachedAge(
        timestampEpochMs: Long,
        nowEpochMs: Long,
        minimumAgeMs: Long
    ): Boolean {
        return nowEpochMs >= timestampEpochMs && nowEpochMs - timestampEpochMs >= minimumAgeMs
    }

    companion object {
        const val TERMINAL_RETENTION_MS: Long = TaskCleanupPolicy.COMPLETED_RETENTION_MS
        const val ORPHAN_GRACE_MS: Long = 24L * 60L * 60L * 1_000L
        const val MAX_RETAINED_TERMINAL_TASKS: Int = 100

        private val TERMINAL_ORDER = compareBy<ProcessingTask>(
            { it.terminalAtEpochMs },
            { it.createdAtEpochMs },
            ProcessingTask::id
        )
    }
}
