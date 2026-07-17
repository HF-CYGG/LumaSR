package com.lumasr.data

import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ExportMode
import com.lumasr.domain.InferencePrecision
import com.lumasr.domain.NativeBackend
import com.lumasr.domain.NativeOutputMode
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProcessingWorkSpecRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripsEveryUpscaleAndExecutionProfileField() = runBlocking {
        val root = temporaryFolder.newFolder("round-trip")
        val repository = FileProcessingWorkSpecRepository(root)
        val expected = workSpec()

        repository.save(expected)

        val restored = repository.find(expected.taskId)
        assertEquals(expected, restored)
        assertEquals(expected.toUpscaleParams(), restored?.toUpscaleParams())
        assertEquals(96_000_000L, restored?.outputPixels)
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun readsLegacyWorkSpecWithoutOutputPixels() = runBlocking {
        val root = temporaryFolder.newFolder("legacy-without-output-pixels")
        val repository = FileProcessingWorkSpecRepository(root)
        val expected = workSpec()
        repository.save(expected)
        val sidecar = root.listFiles().orEmpty().single { it.extension == "work" }
        sidecar.writeText(
            sidecar.readText().replace("\"outputPixels\":96000000,", "")
        )

        val restored = repository.find(expected.taskId)

        assertEquals(expected.modelId, restored?.modelId)
        assertEquals(expected.params, restored?.params)
        assertNull(restored?.outputPixels)
        assertTrue(sidecar.exists())
        assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".corrupt") })
    }

    @Test
    fun corruptSidecarIsQuarantinedWithoutAffectingTaskRecords() = runBlocking {
        val root = temporaryFolder.newFolder("corrupt")
        val taskRepository = FileProcessingTaskRepository(root)
        val workRepository = FileProcessingWorkSpecRepository(root)
        val task = task()
        val spec = workSpec()
        taskRepository.save(task)
        workRepository.save(spec)
        val sidecar = root.listFiles().orEmpty().single { it.extension == "work" }
        sidecar.writeText("{not-json")

        assertNull(workRepository.find(spec.taskId))
        assertEquals(listOf(task), taskRepository.list())
        assertTrue(root.listFiles().orEmpty().any { it.name.startsWith(sidecar.name) && it.name.endsWith(".corrupt") })
    }

    @Test
    fun rejectsUnstableInputAndOutputPaths() {
        val base = workSpec().toUpscaleParams()

        assertThrows(IllegalArgumentException::class.java) {
            ProcessingWorkSpec.fromParams("model-id", base.copy(inputPath = "content://images/1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessingWorkSpec.fromParams("model-id", base.copy(outputPath = "relative/output.png"))
        }
    }

    private fun workSpec(): ProcessingWorkSpec {
        val taskDirectory = temporaryFolder.root.resolve("tasks/task-1")
        val input = taskDirectory.resolve("input.png").absolutePath
        val intermediate = taskDirectory.resolve("intermediate.png").absolutePath
        val output = taskDirectory.resolve("output.webp").absolutePath
        val pass = UpscaleParams(
            taskId = "task-1",
            inputPath = input,
            outputPath = intermediate,
            engine = SuperResEngine.WAIFU2X,
            modelDir = "/models/waifu2x",
            modelName = "Waifu2x",
            modelFileBase = "noise2_scale2.0x_model",
            modelScales = listOf(1, 2),
            scale = 2,
            noise = 2,
            tileSize = 128,
            gpuHeadroomPercent = 9,
            accelerationMode = AccelerationMode.CPU,
            tta = false,
            outputFormat = OutputFormat.PNG,
            exportMode = ExportMode.SAFE_IMAGE,
            outputMode = NativeOutputMode.PNG_IMAGE,
            retryCount = 1,
            regionIndex = 4,
            pipelineLabel = "denoise",
            nativeBackend = NativeBackend.LEGACY_SAFE,
            inferencePrecision = InferencePrecision.FP32,
            cpuThreads = 3,
            openmpBlocktimeMs = 31,
            commandBatchSize = 2,
            modelCacheCapacity = 2
        )
        val params = UpscaleParams(
            taskId = "task-1",
            inputPath = input,
            outputPath = output,
            engine = SuperResEngine.REAL_ESRGAN,
            modelDir = "/models/realesrgan",
            modelName = "Real-ESRGAN",
            modelFileBase = null,
            modelScales = listOf(2, 3, 4),
            scale = 4,
            noise = 3,
            tileSize = 768,
            gpuHeadroomPercent = 10,
            accelerationMode = AccelerationMode.VULKAN,
            tta = true,
            outputFormat = OutputFormat.WEBP,
            exportMode = ExportMode.EXTREME_SINGLE_PNG,
            outputMode = NativeOutputMode.RAW_CROPPED_RGB_TILE,
            outputCropLeft = 11,
            outputCropTop = 12,
            outputCropWidth = 401,
            outputCropHeight = 402,
            retryCount = 2,
            regionIndex = 7,
            pipelinePasses = listOf(pass),
            pipelineLabel = "two-pass",
            nativeBackend = NativeBackend.OPTIMIZED,
            inferencePrecision = InferencePrecision.FP16,
            cpuThreads = 6,
            openmpBlocktimeMs = 45,
            commandBatchSize = 4,
            modelCacheCapacity = 3
        )
        return ProcessingWorkSpec.fromParams(
            modelId = "realesrgan-x4plus",
            params = params,
            outputPixels = 96_000_000L
        )
    }

    private fun task(): ProcessingTask {
        val taskDirectory = temporaryFolder.root.resolve("tasks/task-1")
        return ProcessingTask(
            id = "task-1",
            copiedInputPath = taskDirectory.resolve("input.png").absolutePath,
            outputPath = taskDirectory.resolve("output.webp").absolutePath,
            state = ProcessingTaskState.QUEUED,
            imageIndex = 0,
            imageCount = 1,
            completedExtremeTileIndexes = emptySet(),
            createdAtEpochMs = 1_000L,
            updatedAtEpochMs = 1_000L,
            terminalAtEpochMs = null,
            retryableErrorCode = null,
            executionProfileKey = "optimized-vulkan-fp16"
        )
    }
}
