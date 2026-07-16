package com.lumasr.data

import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRepository
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.ProcessingWorkSpecRepository
import com.lumasr.service.ProcessingServiceClient
import com.lumasr.service.ProcessingServiceStartResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProcessingTaskSubmission(
    val task: ProcessingTask,
    val workSpec: ProcessingWorkSpec
)

class ProcessingTaskSubmissionStore(
    private val taskRepository: ProcessingTaskRepository,
    private val workSpecRepository: ProcessingWorkSpecRepository,
    private val serviceClient: ProcessingServiceClient
) {
    suspend fun submit(
        task: ProcessingTask,
        workSpec: ProcessingWorkSpec
    ): ProcessingServiceStartResult = submitBatch(
        listOf(ProcessingTaskSubmission(task = task, workSpec = workSpec))
    )

    suspend fun submitBatch(
        submissions: List<ProcessingTaskSubmission>
    ): ProcessingServiceStartResult = processSubmissionMutex.withLock {
        require(submissions.isNotEmpty()) { "At least one task must be submitted" }
        submissions.forEach { submission -> validate(submission.task, submission.workSpec) }
        val batchId = submissions.first().task.batchId
        submissions.forEachIndexed { imageIndex, submission ->
            val task = submission.task
            require(task.batchId == batchId) { "All submitted tasks must belong to the same batch" }
            require(task.imageCount == submissions.size) {
                "Task image count must match the submitted batch size"
            }
            require(task.imageIndex == imageIndex) {
                "Task image indexes must match submission order"
            }
        }
        submissions.zipWithNext().forEach { (previous, next) ->
            require(next.task.createdAtEpochMs > previous.task.createdAtEpochMs) {
                "Task creation timestamps must strictly increase in submission order"
            }
        }
        val taskIds = submissions.map { submission -> submission.task.id }
        require(taskIds.size == taskIds.toSet().size) { "Task ids must be unique within a batch" }
        submissions.forEach { submission ->
            val taskId = submission.task.id
            check(taskRepository.find(taskId) == null && workSpecRepository.find(taskId) == null) {
                "Task $taskId has already been submitted"
            }
        }

        val touchedTaskIds = ArrayList<String>(submissions.size)
        try {
            submissions.forEach { submission ->
                val taskId = submission.task.id
                touchedTaskIds += taskId
                workSpecRepository.save(submission.workSpec)
                taskRepository.save(submission.task)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                touchedTaskIds.asReversed().forEach { taskId ->
                    runCatching { taskRepository.delete(taskId) }
                    runCatching { workSpecRepository.delete(taskId) }
                }
            }
            throw failure
        }
        serviceClient.resumePersistedWork()
    }

    private fun validate(task: ProcessingTask, workSpec: ProcessingWorkSpec) {
        require(task.state == ProcessingTaskState.QUEUED) { "Only queued tasks can be submitted" }
        require(task.id == workSpec.taskId) { "Task and work spec ids must match" }
        require(task.copiedInputPath == workSpec.params.inputPath) {
            "Task and work spec input paths must match"
        }
        require(task.outputPath == workSpec.params.outputPath) {
            "Task and work spec output paths must match"
        }
    }

    private companion object {
        val processSubmissionMutex = Mutex()
    }
}
