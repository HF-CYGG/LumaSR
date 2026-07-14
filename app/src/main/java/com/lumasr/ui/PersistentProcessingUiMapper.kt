package com.lumasr.ui

import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage

object PersistentProcessingUiMapper {
    fun apply(
        previous: LumaUiState,
        processing: PersistentProcessingState
    ): LumaUiState {
        val orderedRecords = processing.records.sortedWith(
            compareBy({ it.task.createdAtEpochMs }, { it.task.id })
        )
        val recentTasks = orderedRecords
            .asReversed()
            .map(::toTaskSummary)
            .take(MAX_RECENT_TASKS)
        var mapped = previous.copy(recentTasks = recentTasks)
        val requestedBatchId = processing.trackedBatchId
        if (requestedBatchId == null) {
            return mapped.withNewMessage(processing.notice?.message)
        }

        val requestedBatchRecords = orderedRecords.filter { it.task.batchId == requestedBatchId }
        val nextActiveBatchId = if (requestedBatchRecords.none { !it.task.state.isTerminal }) {
            orderedRecords.firstOrNull { !it.task.state.isTerminal }?.task?.batchId
        } else {
            null
        }
        val effectiveBatchId = nextActiveBatchId ?: requestedBatchId
        val batchRecords = orderedRecords.filter { it.task.batchId == effectiveBatchId }
        if (batchRecords.isEmpty()) {
            return mapped.withNewMessage(processing.notice?.message)
        }
        val batchImages = batchRecords.map { record ->
            SelectedImageInfo(
                sourceUri = record.task.copiedInputPath,
                displayName = record.task.sourceDisplayName,
                sizeBytes = null,
                width = null,
                height = null,
                mimeType = null
            )
        }
        val terminalStop = batchRecords.firstOrNull {
            it.task.state == ProcessingTaskState.FAILED
        } ?: batchRecords.firstOrNull {
            it.task.state == ProcessingTaskState.CANCELLED
        }
        if (terminalStop != null) {
            val wasCancelled = terminalStop.task.state == ProcessingTaskState.CANCELLED
            val message = if (wasCancelled) {
                "Processing cancelled."
            } else {
                failureMessage(terminalStop.task.retryableErrorCode)
            }
            return mapped.copy(
                selectedImage = batchImages.getOrNull(terminalStop.task.imageIndex)
                    ?: batchImages.firstOrNull(),
                selectedImages = batchImages,
                screen = LumaScreen.EDITING,
                selectedTab = LumaTab.PROCESS,
                progress = terminalStop.progress,
                activeParams = terminalStop.workSpec?.toUpscaleParams(),
                activeBatchIndex = 0,
                activeBatchSize = terminalStop.task.imageCount,
                completedResults = emptyList()
            ).withNewMessage(message)
        }
        val active = batchRecords.firstOrNull { it.task.state == ProcessingTaskState.RUNNING }
            ?: batchRecords.firstOrNull {
                it.task.state == ProcessingTaskState.QUEUED ||
                    it.task.state == ProcessingTaskState.PAUSED
            }
        if (active != null) {
            val activeProgress = active.progress ?: queuedProgress(active)
            mapped = mapped.copy(
                selectedImage = batchImages.getOrNull(active.task.imageIndex) ?: batchImages.firstOrNull(),
                selectedImages = batchImages,
                screen = LumaScreen.PROCESSING,
                selectedTab = LumaTab.PROCESS,
                progress = activeProgress,
                activeParams = active.workSpec?.toUpscaleParams(),
                activeBatchIndex = active.task.imageIndex + 1,
                activeBatchSize = active.task.imageCount,
                completedResults = emptyList(),
                savedOutputUri = null,
                savedOutputUris = emptyList()
            )
            return mapped.withNewMessage(processing.notice?.message)
        }

        val expectedImageCount = batchRecords.maxOf { it.task.imageCount }
        val completed = batchRecords
            .filter { it.task.state == ProcessingTaskState.COMPLETED }
            .sortedBy { it.task.imageIndex }
        if (completed.size == expectedImageCount && completed.all { it.workSpec != null }) {
            val results = completed.map { record ->
                val params = requireNotNull(record.workSpec).toUpscaleParams()
                RenderResultInfo(
                    taskId = record.task.id,
                    fileName = record.task.sourceDisplayName,
                    inputPath = record.task.copiedInputPath,
                    outputPath = record.task.outputPath,
                    modelName = params.modelName,
                    scale = params.scale,
                    noise = params.noise,
                    pipelineLabel = params.pipelineLabel,
                    outputFormat = params.outputFormat,
                    isExtremeExport = params.exportMode == com.lumasr.domain.ExportMode.EXTREME_SINGLE_PNG
                )
            }
            val last = completed.last()
            mapped = mapped.copy(
                selectedImage = batchImages.firstOrNull(),
                selectedImages = batchImages,
                screen = LumaScreen.COMPARE,
                selectedTab = LumaTab.PROCESS,
                progress = last.progress ?: doneProgress(last.task.id),
                activeParams = last.workSpec?.toUpscaleParams(),
                activeBatchIndex = 0,
                activeBatchSize = expectedImageCount,
                completedResults = results
            )
        }
        return mapped.withNewMessage(processing.notice?.message)
    }

    private fun toTaskSummary(record: PersistentTaskRecord): RenderTaskSummary {
        val params = record.workSpec?.toUpscaleParams()
        return RenderTaskSummary(
            taskId = record.task.id,
            fileName = record.task.sourceDisplayName,
            modelName = params?.pipelineLabel ?: params?.modelName ?: record.workSpec?.modelId.orEmpty(),
            scale = params?.scale ?: 1,
            tileSize = params?.tileSize ?: 0,
            status = when (record.task.state) {
                ProcessingTaskState.QUEUED,
                ProcessingTaskState.RUNNING,
                ProcessingTaskState.PAUSED -> RenderTaskStatus.RUNNING
                ProcessingTaskState.COMPLETED -> RenderTaskStatus.DONE
                ProcessingTaskState.FAILED -> RenderTaskStatus.FAILED
                ProcessingTaskState.CANCELLED -> RenderTaskStatus.CANCELLED
            },
            progress = when (record.task.state) {
                ProcessingTaskState.COMPLETED -> 1f
                else -> record.progress?.progress ?: 0f
            }.coerceIn(0f, 1f)
        )
    }

    private fun queuedProgress(record: PersistentTaskRecord): UpscaleProgress {
        val paused = record.task.state == ProcessingTaskState.PAUSED
        return UpscaleProgress(
            taskId = record.task.id,
            stage = UpscaleStage.PREPARING,
            progress = 0f,
            currentTile = 0,
            totalTiles = 1,
            completedTiles = 0,
            message = if (paused) "Processing paused" else "Waiting to process",
            estimatedRemainingMs = null
        )
    }

    private fun doneProgress(taskId: String): UpscaleProgress = UpscaleProgress(
        taskId = taskId,
        stage = UpscaleStage.DONE,
        progress = 1f,
        currentTile = 1,
        totalTiles = 1,
        completedTiles = 1,
        message = "Processing complete",
        estimatedRemainingMs = 0L
    )

    private fun failureMessage(code: String?): String = when (code) {
        "WORK_SPEC_MISSING" -> "Processing work details are missing. Please submit the image again."
        "RUNNER_EXCEPTION" -> "Processing stopped unexpectedly. Try a smaller tile size or CPU mode."
        "PROCESSOR_FAILED" -> "Processing failed. Try a smaller tile size or CPU mode."
        else -> "Processing failed. Please try again."
    }

    private fun LumaUiState.withNewMessage(message: String?): LumaUiState {
        return if (message.isNullOrBlank() || message == resultMessage) {
            this
        } else {
            withResultMessage(message)
        }
    }

    private const val MAX_RECENT_TASKS = 8
}
