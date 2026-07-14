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

class ProcessingTaskSubmissionStore(
    private val taskRepository: ProcessingTaskRepository,
    private val workSpecRepository: ProcessingWorkSpecRepository,
    private val serviceClient: ProcessingServiceClient
) {
    private val submissionMutex = Mutex()

    suspend fun submit(
        task: ProcessingTask,
        workSpec: ProcessingWorkSpec
    ): ProcessingServiceStartResult = submissionMutex.withLock {
        validate(task, workSpec)
        check(taskRepository.find(task.id) == null && workSpecRepository.find(task.id) == null) {
            "Task ${task.id} has already been submitted"
        }

        try {
            workSpecRepository.save(workSpec)
            taskRepository.save(task)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching { taskRepository.delete(task.id) }
                runCatching { workSpecRepository.delete(task.id) }
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
}
