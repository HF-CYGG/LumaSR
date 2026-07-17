package com.lumasr.ui

import android.content.Context
import com.lumasr.data.AndroidRuntimeResourceSource
import com.lumasr.data.AndroidThermalSignalSource
import com.lumasr.data.FileProcessingTaskRepository
import com.lumasr.data.FileProcessingWorkSpecRepository
import com.lumasr.data.ImageCacheRepository
import com.lumasr.data.ProcessingTaskSubmissionStore
import com.lumasr.data.ThermalSampler
import com.lumasr.domain.ApplicationProcessingSnapshotBridge
import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.RuntimePipelineResourceProvider
import com.lumasr.domain.ThermalLevel
import com.lumasr.service.ApplicationForegroundProcessingRuntime
import com.lumasr.service.ForegroundProcessingRuntime
import com.lumasr.service.ProcessingServiceClient
import com.lumasr.service.ProcessingServiceStartResult
import java.io.File

internal fun createPersistentProcessingController(context: Context): PersistentProcessingController {
    val appContext = context.applicationContext
    val taskDirectory = File(
        appContext.noBackupFilesDir,
        ImageCacheRepository.PERSISTENT_TASK_DIRECTORY
    )
    val taskRepository = FileProcessingTaskRepository(taskDirectory)
    val workSpecRepository = FileProcessingWorkSpecRepository(taskDirectory)
    val serviceClient = ProcessingServiceClient(appContext)
    val submissionStore = ProcessingTaskSubmissionStore(
        taskRepository = taskRepository,
        workSpecRepository = workSpecRepository,
        serviceClient = serviceClient
    )
    val thermalSampler = ThermalSampler(AndroidThermalSignalSource(appContext))
    val runtimeResourceSource = AndroidRuntimeResourceSource(appContext)
    val executionProfileResolver = createInferenceExecutionProfileResolver(appContext)
    val foregroundRuntime = createPersistentForegroundRuntime(
        taskDirectory = taskDirectory,
        thermalLevelProvider = thermalSampler::sampleLevel,
        runtimeResourceProvider = runtimeResourceSource,
        executionProfileResolver = executionProfileResolver,
        runtimeFactory = ApplicationForegroundProcessingRuntime::getOrCreate
    )
    return PersistentProcessingController(
        taskRepository = taskRepository,
        workSpecRepository = workSpecRepository,
        batchSubmitter = PersistentBatchSubmitter(submissionStore::submitBatch),
        serviceGateway = AndroidPersistentProcessingServiceGateway(serviceClient),
        foregroundRuntime = AndroidPersistentForegroundRuntime(foregroundRuntime),
        snapshots = ApplicationProcessingSnapshotBridge.snapshots
    )
}

internal fun <T> createPersistentForegroundRuntime(
    taskDirectory: File,
    thermalLevelProvider: () -> ThermalLevel?,
    runtimeResourceProvider: RuntimePipelineResourceProvider,
    executionProfileResolver: ProcessingExecutionProfileResolver?,
    runtimeFactory: (
        File,
        () -> ThermalLevel?,
        RuntimePipelineResourceProvider,
        ProcessingExecutionProfileResolver?
    ) -> T
): T = runtimeFactory(
    taskDirectory,
    thermalLevelProvider,
    runtimeResourceProvider,
    executionProfileResolver
)

private class AndroidPersistentProcessingServiceGateway(
    private val client: ProcessingServiceClient
) : PersistentProcessingServiceGateway {
    override fun resumePersistedWork(): ProcessingServiceStartResult = client.resumePersistedWork()

    override fun cancel(taskId: String): ProcessingServiceStartResult = client.cancel(taskId)
}

private class AndroidPersistentForegroundRuntime(
    private val runtime: ForegroundProcessingRuntime
) : PersistentForegroundRuntime {
    override suspend fun resumePersistedWork(): Boolean = runtime.resumePersistedWork()

    override suspend fun cancel(taskId: String): Boolean = runtime.cancel(taskId)

    override suspend fun stopForForegroundExit() {
        runtime.stopForForegroundExit()
    }
}
