package com.lumasr.ui

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentProcessingUiMapperTest {
    @Test
    fun completedTwoImageBatchProducesTwoOrderedResults() {
        val state = PersistentProcessingState(
            records = listOf(
                record("second", 1, ProcessingTaskState.COMPLETED),
                record("first", 0, ProcessingTaskState.COMPLETED)
            ),
            trackedBatchId = BATCH_ID
        )

        val mapped = PersistentProcessingUiMapper.apply(LumaUiState(), state)

        assertEquals(LumaScreen.COMPARE, mapped.screen)
        assertEquals(listOf("first", "second"), mapped.completedResults.map { it.taskId })
        assertEquals(listOf("first.png", "second.png"), mapped.completedResults.map { it.fileName })
        assertEquals(2, mapped.recentTasks.size)
    }

    @Test
    fun activeBatchRestoresProgressParamsAndDisplayPosition() {
        val progress = UpscaleProgress(
            taskId = "second",
            stage = UpscaleStage.PROCESSING_TILE,
            progress = 0.4f,
            currentTile = 4,
            totalTiles = 10,
            completedTiles = 4,
            message = "Processing tile",
            estimatedRemainingMs = 500L
        )
        val state = PersistentProcessingState(
            records = listOf(
                record("first", 0, ProcessingTaskState.COMPLETED),
                record("second", 1, ProcessingTaskState.RUNNING, progress)
            ),
            trackedBatchId = BATCH_ID
        )

        val mapped = PersistentProcessingUiMapper.apply(LumaUiState(), state)

        assertEquals(LumaScreen.PROCESSING, mapped.screen)
        assertFalse(mapped.processingPaused)
        assertEquals("second", mapped.activeParams?.taskId)
        assertEquals(progress, mapped.progress)
        assertEquals(2, mapped.activeBatchIndex)
        assertEquals(2, mapped.activeBatchSize)
        assertEquals("second.png", mapped.selectedImage?.displayName)
    }

    @Test
    fun failedAndCancelledBatchesReturnToEditingWithFriendlyMessages() {
        val failed = PersistentProcessingUiMapper.apply(
            LumaUiState(screen = LumaScreen.PROCESSING),
            PersistentProcessingState(
                records = listOf(
                    record(
                        "failed",
                        0,
                        ProcessingTaskState.FAILED,
                        retryableErrorCode = "WORK_SPEC_MISSING"
                    )
                ),
                trackedBatchId = BATCH_ID
            )
        )
        val cancelled = PersistentProcessingUiMapper.apply(
            LumaUiState(screen = LumaScreen.PROCESSING),
            PersistentProcessingState(
                records = listOf(record("cancelled", 0, ProcessingTaskState.CANCELLED)),
                trackedBatchId = BATCH_ID
            )
        )

        assertEquals(LumaScreen.EDITING, failed.screen)
        assertTrue(failed.resultMessage.orEmpty().contains("work", ignoreCase = true))
        assertEquals(LumaScreen.EDITING, cancelled.screen)
        assertTrue(cancelled.resultMessage.orEmpty().contains("cancel", ignoreCase = true))
    }

    @Test
    fun criticalThermalPauseStaysNonTerminalAndExplainsHowToResume() {
        val mapped = PersistentProcessingUiMapper.apply(
            LumaUiState(screen = LumaScreen.PROCESSING),
            PersistentProcessingState(
                records = listOf(
                    record(
                        "thermal",
                        0,
                        ProcessingTaskState.PAUSED,
                        retryableErrorCode = "THERMAL_CRITICAL"
                    )
                ),
                trackedBatchId = BATCH_ID
            )
        )

        assertEquals(LumaScreen.PROCESSING, mapped.screen)
        assertTrue(mapped.processingPaused)
        assertTrue(mapped.resultMessage.orEmpty().contains("cool", ignoreCase = true))
        assertTrue(mapped.resultMessage.orEmpty().contains("resume", ignoreCase = true))
    }

    @Test
    fun historicalTerminalRecordsStayDiscoverableWithoutOpeningCompare() {
        val previous = LumaUiState(screen = LumaScreen.EDITING)

        val mapped = PersistentProcessingUiMapper.apply(
            previous,
            PersistentProcessingState(
                records = listOf(record("historical", 0, ProcessingTaskState.COMPLETED)),
                trackedBatchId = null
            )
        )

        assertEquals(LumaScreen.EDITING, mapped.screen)
        assertTrue(mapped.completedResults.isEmpty())
        assertEquals(listOf("historical"), mapped.recentTasks.map { it.taskId })
    }

    @Test
    fun completedTrackedBatchDoesNotHideALaterActiveBatch() {
        val mapped = PersistentProcessingUiMapper.apply(
            LumaUiState(),
            PersistentProcessingState(
                records = listOf(
                    record(
                        id = "old",
                        imageIndex = 0,
                        state = ProcessingTaskState.COMPLETED,
                        batchId = "old-batch",
                        imageCount = 1
                    ),
                    record(
                        id = "next",
                        imageIndex = 0,
                        state = ProcessingTaskState.RUNNING,
                        batchId = "next-batch",
                        imageCount = 1
                    )
                ),
                trackedBatchId = "old-batch"
            )
        )

        assertEquals(LumaScreen.PROCESSING, mapped.screen)
        assertEquals("next", mapped.activeParams?.taskId)
        assertTrue(mapped.completedResults.isEmpty())
    }

    @Test
    fun failedBatchDoesNotRemainProcessingBecauseALaterItemIsStillQueued() {
        val mapped = PersistentProcessingUiMapper.apply(
            LumaUiState(screen = LumaScreen.PROCESSING),
            PersistentProcessingState(
                records = listOf(
                    record("failed", 0, ProcessingTaskState.FAILED),
                    record("queued", 1, ProcessingTaskState.QUEUED)
                ),
                trackedBatchId = BATCH_ID
            )
        )

        assertEquals(LumaScreen.EDITING, mapped.screen)
        assertEquals(RenderTaskStatus.FAILED, mapped.recentTasks.single { it.taskId == "failed" }.status)
        assertTrue(mapped.completedResults.isEmpty())
    }

    private companion object {
        const val BATCH_ID = "batch"

        fun record(
            id: String,
            imageIndex: Int,
            state: ProcessingTaskState,
            progress: UpscaleProgress? = null,
            retryableErrorCode: String? = null,
            batchId: String = BATCH_ID,
            imageCount: Int = 2
        ): PersistentTaskRecord {
            val createdAt = 1_000L + imageIndex
            val updatedAt = if (state.isTerminal) createdAt + 1_000L else createdAt
            val directory = File("build/ui-mapper/$id").absoluteFile
            val params = UpscaleParams(
                taskId = id,
                inputPath = directory.resolve("input.png").path,
                outputPath = directory.resolve("output.png").path,
                engine = SuperResEngine.WAIFU2X,
                modelDir = File("build/ui-mapper/models").absolutePath,
                modelName = "model",
                scale = 2,
                noise = 0,
                tileSize = 128,
                accelerationMode = AccelerationMode.CPU,
                tta = false,
                outputFormat = OutputFormat.PNG
            )
            return PersistentTaskRecord(
                task = ProcessingTask(
                    id = id,
                    copiedInputPath = params.inputPath,
                    outputPath = params.outputPath,
                    state = state,
                    imageIndex = imageIndex,
                    imageCount = imageCount,
                    completedExtremeTileIndexes = emptySet(),
                    createdAtEpochMs = createdAt,
                    updatedAtEpochMs = updatedAt,
                    terminalAtEpochMs = updatedAt.takeIf { state.isTerminal },
                    retryableErrorCode = retryableErrorCode,
                    executionProfileKey = "profile",
                    batchId = batchId,
                    sourceDisplayName = "$id.png"
                ),
                workSpec = ProcessingWorkSpec.fromParams("model", params),
                progress = progress
            )
        }
    }
}
