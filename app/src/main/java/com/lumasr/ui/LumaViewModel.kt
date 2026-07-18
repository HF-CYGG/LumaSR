/**
 * 本文件定义了 LumaViewModel，负责管理 UI 状态与业务逻辑。
 * 包括图片选择、参数配置、模型加载以及超分辨率任务的执行和取消。
 */
package com.lumasr.ui

import android.app.Application
import android.app.ActivityManager
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumasr.data.GalleryRepository
import com.lumasr.data.FileCalibrationReportRepository
import com.lumasr.data.ImageCacheRepository
import com.lumasr.data.ModelAssetRepository
import com.lumasr.data.ModelManifestRepository
import com.lumasr.data.ProcessingTaskSubmission
import com.lumasr.data.UserPreferencesRepository
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.AutoTileSizePolicy
import com.lumasr.domain.ExportMode
import com.lumasr.domain.CalibrationFailureReason
import com.lumasr.domain.CalibrationMode
import com.lumasr.domain.CalibrationProgressEvent
import com.lumasr.domain.CalibrationProgressReporter
import com.lumasr.domain.CalibrationReport
import com.lumasr.domain.CalibrationReportEvent
import com.lumasr.domain.CalibrationReportOutcome
import com.lumasr.domain.CalibrationRunResult
import com.lumasr.domain.DeviceCalibrationCoordinator
import com.lumasr.domain.ExtremeExportEstimate
import com.lumasr.domain.ModelManifest
import com.lumasr.domain.ModelPack
import com.lumasr.domain.ModelRuntimePolicy
import com.lumasr.domain.MediaOutputNamer
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.ProcessingResourceProfile
import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ResourceBudgetPolicy
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.TileSizeMode
import com.lumasr.domain.TileSizePreferences
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleParamsFactory
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.domain.availableDenoiseForScale
import com.lumasr.domain.realEsrganDenoisePreprocessor
import com.lumasr.domain.sanitizeTargetScale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs

class LumaViewModel(
    application: Application,
    private val repository: ModelManifestRepository = ModelManifestRepository(application),
    private val imageCacheRepository: ImageCacheRepository = ImageCacheRepository.persistent(application),
    private val modelAssetRepository: ModelAssetRepository = ModelAssetRepository.fromContext(application),
    private val galleryRepository: GalleryRepository = GalleryRepository(application),
    private val userPreferencesRepository: UserPreferencesRepository = UserPreferencesRepository.fromContext(application),
    private val persistentController: PersistentProcessingController = createPersistentProcessingController(application),
    private val executionProfileResolver: ProcessingExecutionProfileResolver =
        createInferenceExecutionProfileResolver(application),
    private val calibrationCoordinatorFactory: () -> DeviceCalibrationCoordinator = {
        createDeviceCalibrationCoordinator(application)
    },
    private val calibrationReportRepository: FileCalibrationReportRepository =
        FileCalibrationReportRepository(
            File(application.noBackupFilesDir, CALIBRATION_REPORT_DIRECTORY_NAME)
        ),
    private val modelRuntimePolicy: ModelRuntimePolicy = ModelRuntimePolicy(Build.SUPPORTED_ABIS.toList()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val elapsedRealtimeProvider: () -> Long = SystemClock::elapsedRealtime,
    private val currentTimeMillisProvider: () -> Long = System::currentTimeMillis
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LumaUiState())
    val uiState: StateFlow<LumaUiState> = _uiState.asStateFlow()

    private var currentJob: Job? = null
    private var currentTaskId: String? = null
    private var calibrationJob: Job? = null
    private var calibrationTickerJob: Job? = null
    private var documentSaveJob: Job? = null
    private var documentSaveQueue: DocumentSaveQueue? = null
    private var nextDocumentSaveRequestId: Long = 1L
    private val calibrationCoordinator: DeviceCalibrationCoordinator by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
        calibrationCoordinatorFactory
    )

    init {
        _uiState.update {
            it.withTileSizePreferences(userPreferencesRepository.loadTileSizePreferences()).copy(
                showCalibrationPrompt = false,
                calibrationStatus = if (userPreferencesRepository.wasCalibrationSkipped()) {
                    CalibrationUiStatus.SKIPPED
                } else {
                    CalibrationUiStatus.NOT_RUN
                }
            )
        }
        persistentController.startObserving(viewModelScope)
        viewModelScope.launch {
            persistentController.state.collect { processingState ->
                val trackedBatchId = processingState.trackedBatchId
                currentTaskId = processingState.records
                    .firstOrNull {
                        it.task.batchId == trackedBatchId && !it.task.state.isTerminal
                    }
                    ?.task
                    ?.id
                _uiState.update { state ->
                    PersistentProcessingUiMapper.apply(state, processingState)
                }
            }
        }
        loadManifest()
        refreshCalibrationReports()
    }

    fun onImageSelected(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    imageCacheRepository.readImageInfo(uri).toSelectedImageInfo()
                }
            }.onSuccess { imageInfo ->
                _uiState.update {
                    it.copy(
                        selectedImage = imageInfo,
                        selectedImages = listOf(imageInfo),
                        screen = LumaScreen.EDITING,
                        selectedTab = LumaTab.PROCESS,
                        progress = null,
                        completedResults = emptyList(),
                        savedOutputUri = null,
                        savedOutputUris = emptyList(),
                        resultMessage = null
                    ).withAutoTileSizeForCurrentSelection()
                }
                persistTileSizePreferences(_uiState.value)
            }.onFailure { error ->
                _uiState.update { it.withResultMessage("Cannot read selected image: ${error.message}") }
            }
        }
    }

    fun onImagesSelected(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    uris.map { uri -> imageCacheRepository.readImageInfo(uri).toSelectedImageInfo() }
                }
            }.onSuccess { images ->
                _uiState.update {
                    it.copy(
                        selectedImage = images.firstOrNull(),
                        selectedImages = images,
                        screen = LumaScreen.EDITING,
                        selectedTab = LumaTab.PROCESS,
                        progress = null,
                        activeBatchIndex = 0,
                        activeBatchSize = images.size,
                        completedResults = emptyList(),
                        savedOutputUri = null,
                        savedOutputUris = emptyList(),
                        resultMessage = null
                    ).withAutoTileSizeForCurrentSelection()
                }
                persistTileSizePreferences(_uiState.value)
            }.onFailure { error ->
                _uiState.update { it.withResultMessage("Cannot read selected images: ${error.message}") }
            }
        }
    }

    fun clearSelectedImage() {
        _uiState.update { it.clearImageSelection() }
    }

    fun selectModel(modelId: String) {
        if (!canChangeModel(_uiState.value)) return
        val model = _uiState.value.models.firstOrNull { it.id == modelId } ?: return
        _uiState.update {
            val scale = model.defaultScale
            it.copy(
                selectedModelId = modelId,
                scale = scale,
                noise = model.sanitizeNoiseForUi(scale, model.defaultNoise, it.models),
                accelerationMode = modelRuntimePolicy.sanitizeAccelerationMode(model, it.accelerationMode),
                tta = false
            ).withAutoTileSizeForCurrentSelection(model = model, scale = scale)
        }
        persistTileSizePreferences(_uiState.value)
        refreshCalibrationStatus()
    }

    fun setScale(scale: Int) {
        _uiState.update { state ->
            val model = state.selectedModel
            val resolvedScale = model?.sanitizeTargetScale(scale) ?: scale
            val resolvedNoise = model?.sanitizeNoiseForUi(resolvedScale, state.noise, state.models) ?: state.noise
            state.copy(
                scale = resolvedScale,
                noise = resolvedNoise
            ).withAutoTileSizeForCurrentSelection(model = model, scale = resolvedScale)
        }
        persistTileSizePreferences(_uiState.value)
    }

    fun setNoise(noise: Int) {
        _uiState.update { state ->
            val model = state.selectedModel
            state.copy(
                noise = model?.sanitizeNoiseForUi(state.scale, noise, state.models) ?: noise
            )
        }
    }

    fun setAccelerationMode(mode: AccelerationMode) {
        _uiState.update { state ->
            val model = state.selectedModel
            state.copy(
                accelerationMode = if (model != null) {
                    modelRuntimePolicy.sanitizeAccelerationMode(model, mode)
                } else {
                    mode
                }
            )
        }
    }

    fun setTileSize(tileSize: Int) {
        val sanitized = sanitizeTileSize(tileSize)
        _uiState.update {
            it.copy(
                tileSizeMode = TileSizeMode.MANUAL,
                manualTileSize = sanitized,
                tileSize = sanitized
            )
        }
        persistTileSizePreferences(_uiState.value)
    }

    fun setTileSizeAuto() {
        _uiState.update {
            it.copy(tileSizeMode = TileSizeMode.AUTO)
                .withAutoTileSizeForCurrentSelection()
        }
        persistTileSizePreferences(_uiState.value)
    }

    fun setTta(enabled: Boolean) {
        _uiState.update { state ->
            val model = state.selectedModel
            state.copy(tta = enabled && model?.supportsTta == true)
        }
    }

    fun selectTab(tab: LumaTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun startProcessing() {
        val state = _uiState.value
        if (state.calibrationStatus == CalibrationUiStatus.RUNNING) {
            _uiState.update {
                it.withResultMessage("请等待本机性能校准完成后再开始处理。")
            }
            return
        }
        val images = state.selectedImages.ifEmpty { state.selectedImage?.let(::listOf).orEmpty() }
        if (images.isEmpty()) return
        val model = state.selectedModel ?: return
        val denoiseModel = state.models.realEsrganDenoisePreprocessor()
            .takeIf { model.engine == SuperResEngine.REAL_ESRGAN && state.noise > 0 }
        val rejectedImage = images.firstNotNullOfOrNull { image ->
            val profile = processingResourceProfile(image)
            val requestedTileSize = state.tileSizeFor(image, model, state.scale, profile)
            val decision = ResourceBudgetPolicy.evaluate(
                imageWidth = image.width,
                imageHeight = image.height,
                model = model,
                scale = state.scale,
                tileSize = requestedTileSize,
                gpuHeadroomPercent = DEFAULT_GPU_HEADROOM_PERCENT,
                accelerationMode = state.accelerationMode,
                tta = state.tta,
                exportMode = ExportMode.AUTO,
                resourceProfile = profile
            )
            if (decision.allowed) null else image.displayName to decision.message
        }
        if (rejectedImage != null) {
            _uiState.update {
                it.copy(
                    screen = LumaScreen.EDITING,
                    selectedTab = LumaTab.PROCESS,
                    progress = null
                ).withResultMessage("${rejectedImage.first}: ${rejectedImage.second.orEmpty()}")
            }
            return
        }
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val preparedTaskDirectories = mutableListOf<File>()
            var published = false
            val batchId = UUID.randomUUID().toString()
            val taskIds = List(images.size) { UUID.randomUUID().toString() }
            currentTaskId = taskIds.first()
            _uiState.update {
                it.copy(
                    selectedImage = images.first(),
                    screen = LumaScreen.PROCESSING,
                    selectedTab = LumaTab.PROCESS,
                    progress = preparingProgress(taskIds.first()),
                    activeBatchIndex = 1,
                    activeBatchSize = images.size,
                    completedResults = emptyList(),
                    savedOutputUri = null,
                    savedOutputUris = emptyList()
                )
            }
            try {
                val preparedModelDirs = withContext(ioDispatcher) {
                    buildMap {
                        put(model.id, modelAssetRepository.prepareModel(model).absolutePath)
                        denoiseModel?.let { denoiser ->
                            put(denoiser.id, modelAssetRepository.prepareModel(denoiser).absolutePath)
                        }
                    }
                }
                val modelDir = preparedModelDirs.getValue(model.id)
                val denoiseModelDir = denoiseModel?.let { preparedModelDirs[it.id] }
                val baseCreatedAt = System.currentTimeMillis()
                val submissions = images.mapIndexed { index, image ->
                    if (!isActive) throw CancellationException("Processing preparation cancelled")
                    val profile = processingResourceProfile(image)
                    val requestedTileSize = state.tileSizeFor(image, model, state.scale, profile)
                    val budgetDecision = ResourceBudgetPolicy.evaluate(
                        imageWidth = image.width,
                        imageHeight = image.height,
                        model = model,
                        scale = state.scale,
                        tileSize = requestedTileSize,
                        gpuHeadroomPercent = DEFAULT_GPU_HEADROOM_PERCENT,
                        accelerationMode = state.accelerationMode,
                        tta = state.tta,
                        exportMode = ExportMode.AUTO,
                        resourceProfile = profile
                    )
                    if (index == 0) {
                        _uiState.update {
                            it.copy(
                                scale = budgetDecision.scale,
                                tileSize = budgetDecision.tileSize,
                                accelerationMode = budgetDecision.accelerationMode,
                                tta = budgetDecision.tta
                            ).withResultMessage(budgetDecision.message)
                        }
                    }
                    if (state.tileSizeMode == TileSizeMode.AUTO) {
                        persistTileSizePreferences(
                            state.copy(
                                tileSize = budgetDecision.tileSize,
                                lastAutoTileSize = budgetDecision.tileSize
                            )
                        )
                    }
                    val taskId = taskIds[index]
                    val paths = withContext(ioDispatcher) {
                        imageCacheRepository.copyToTaskCache(
                            uri = Uri.parse(image.sourceUri),
                            taskId = taskId,
                            mimeType = image.mimeType
                        )
                    }
                    preparedTaskDirectories += paths.taskDir
                    val unresolvedParams = UpscaleParamsFactory.createPipeline(
                        taskId = taskId,
                        inputPath = paths.inputFile.absolutePath,
                        outputPath = paths.outputFile.absolutePath,
                        model = model,
                        resolvedModelDir = modelDir,
                        denoiseModel = denoiseModel,
                        resolvedDenoiseModelDir = denoiseModelDir,
                        scale = budgetDecision.scale,
                        noise = state.noise,
                        tileSize = budgetDecision.tileSize,
                        gpuHeadroomPercent = budgetDecision.gpuHeadroomPercent,
                        accelerationMode = budgetDecision.accelerationMode,
                        allowRealEsrganVulkan = modelRuntimePolicy.allowsRealEsrganVulkan(model),
                        tta = budgetDecision.tta,
                        outputFormat = OutputFormat.PNG,
                        exportMode = budgetDecision.exportMode
                    ).processorParams
                    val params = executionProfileResolver.resolve(
                        modelId = model.id,
                        params = unresolvedParams,
                        nowEpochMs = System.currentTimeMillis(),
                        outputPixels = profile.outputPixelsFor(budgetDecision.scale)
                    )
                    val createdAt = baseCreatedAt + index
                    val task = ProcessingTask(
                        id = taskId,
                        copiedInputPath = params.inputPath,
                        outputPath = params.outputPath,
                        state = ProcessingTaskState.QUEUED,
                        imageIndex = index,
                        imageCount = images.size,
                        completedExtremeTileIndexes = emptySet(),
                        createdAtEpochMs = createdAt,
                        updatedAtEpochMs = createdAt,
                        terminalAtEpochMs = null,
                        retryableErrorCode = null,
                        executionProfileKey = params.executionProfileKey(),
                        batchId = batchId,
                        sourceDisplayName = image.displayName.toStableDisplayName(index)
                    )
                    ProcessingTaskSubmission(
                        task = task,
                        workSpec = ProcessingWorkSpec.fromParams(
                            modelId = model.id,
                            params = params,
                            outputPixels = profile.outputPixelsFor(budgetDecision.scale)
                        )
                    )
                }
                persistentController.submitBatch(submissions)
                published = true
            } catch (cancelled: CancellationException) {
                _uiState.update {
                    it.copy(screen = LumaScreen.EDITING, progress = null)
                        .withResultMessage("Processing cancelled.")
                }
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(screen = LumaScreen.EDITING, progress = null)
                        .withResultMessage("Cannot prepare processing batch: ${error.message}")
                }
            } finally {
                if (!published) {
                    withContext(NonCancellable + ioDispatcher) {
                        preparedTaskDirectories.forEach(::deleteUnpublishedTaskDirectory)
                    }
                }
                currentJob = null
                if (!published) currentTaskId = null
            }
        }
    }

    fun skipCalibrationPrompt() {
        val key = calibrationCoordinator.acceleratorCalibrationKey
        if (key != null) {
            userPreferencesRepository.markCalibrationPromptHandled(key, skipped = true)
        } else {
            userPreferencesRepository.markCalibrationPromptHandled(skipped = true)
        }
        _uiState.update {
            it.copy(
                showCalibrationPrompt = false,
                calibrationStatus = CalibrationUiStatus.SKIPPED
            )
        }
    }

    fun runCalibration(
        mode: CalibrationMode = if (calibrationCoordinator.supportsAdrenoCalibration) {
            CalibrationMode.QUICK
        } else {
            CalibrationMode.LEGACY
        }
    ) {
        if (calibrationJob?.isActive == true) return
        val state = _uiState.value
        if (!canStartCalibration(state)) {
            _uiState.update {
                it.copy(showCalibrationPrompt = false)
                    .withResultMessage("请等待当前图片处理完成后再校准。")
            }
            return
        }
        val model = state.selectedModel ?: return
        val acceleratorKey = calibrationCoordinator.acceleratorCalibrationKey
        if (acceleratorKey != null) {
            userPreferencesRepository.markCalibrationPromptHandled(acceleratorKey, skipped = false)
        } else {
            userPreferencesRepository.markCalibrationPromptHandled(skipped = false)
        }
        _uiState.update {
            it.copy(
                showCalibrationPrompt = false,
                calibrationStatus = CalibrationUiStatus.RUNNING
            ).startCalibrationDialog(elapsedRealtimeProvider(), mode)
        }
        startCalibrationTicker()
        calibrationJob = viewModelScope.launch {
            val reportStartedAtEpochMs = currentTimeMillisProvider().coerceAtLeast(0L)
            val reportEvents = mutableListOf<CalibrationReportEvent>()
            val result = try {
                withContext(ioDispatcher) {
                    if (!calibrationCoordinator.isSupported) {
                        CalibrationRunResult.Failed(CalibrationFailureReason.UNSUPPORTED_DEVICE)
                    } else {
                        val modelDir = modelAssetRepository.prepareModel(model).absolutePath
                        calibrationCoordinator.run(
                            model = model,
                            resolvedModelDir = modelDir,
                            mode = mode,
                            progressReporter = CalibrationProgressReporter { event ->
                                reportEvents += event.toCalibrationReportEvent()
                                _uiState.update { it.applyCalibrationProgress(event) }
                            }
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                CalibrationRunResult.Failed(CalibrationFailureReason.PROBE_FAILED)
            }
            calibrationTickerJob?.cancel()
            calibrationTickerJob = null
            var terminalSummary = ""
            var terminalElapsedMs = 0L
            _uiState.update { state ->
                if (!isCurrentCalibrationModel(model.id, state.selectedModelId)) {
                    return@update state.copy(calibrationStatus = CalibrationUiStatus.NOT_RUN)
                }
                val calibrationStatus = when (result) {
                    is CalibrationRunResult.Completed -> if (result.benchmark.hasEnabledOptimizations) {
                        CalibrationUiStatus.READY
                    } else {
                        CalibrationUiStatus.READY_SAFE
                    }
                    is CalibrationRunResult.Failed -> {
                        if (result.reason == CalibrationFailureReason.UNSUPPORTED_DEVICE) {
                            CalibrationUiStatus.UNSUPPORTED
                        } else {
                            CalibrationUiStatus.FAILED
                        }
                    }
                    CalibrationRunResult.TimedOut -> CalibrationUiStatus.FAILED
                }
                val dialogStatus = when (result) {
                    is CalibrationRunResult.Completed -> CalibrationDialogStatus.SUCCEEDED
                    is CalibrationRunResult.Failed -> {
                        if (result.reason == CalibrationFailureReason.UNSUPPORTED_DEVICE) {
                            CalibrationDialogStatus.UNSUPPORTED
                        } else {
                            CalibrationDialogStatus.FAILED
                        }
                    }
                    CalibrationRunResult.TimedOut -> CalibrationDialogStatus.TIMED_OUT
                }
                val summary = when (result) {
                    is CalibrationRunResult.Completed -> if (result.benchmark.hasEnabledOptimizations) {
                        "✓ 本机校准完成，高性能模式已安全启用"
                    } else {
                        "✓ 本机校准完成，当前设备继续使用兼容模式"
                    }
                    is CalibrationRunResult.Failed -> "✗ 校准未通过 · ${result.reason.name}"
                    CalibrationRunResult.TimedOut -> "✗ 校准超时，将继续使用兼容模式"
                }
                val elapsedMs = state.calibrationDialog?.let { dialog ->
                    (elapsedRealtimeProvider() - dialog.startedAtElapsedMs)
                        .coerceAtLeast(dialog.elapsedMs)
                } ?: 0L
                terminalSummary = summary
                terminalElapsedMs = elapsedMs
                state.copy(calibrationStatus = calibrationStatus)
                    .finishCalibrationDialog(dialogStatus, elapsedMs, summary)
                    .withResultMessage(
                        if (result is CalibrationRunResult.Completed) {
                            if (result.benchmark.hasEnabledOptimizations) {
                                "本机校准完成，高性能模式已安全启用。"
                            } else {
                                "本机校准完成，当前设备继续使用兼容模式。"
                            }
                        } else {
                            "校准未通过，将继续使用兼容模式。"
                        }
                    )
            }
            persistCalibrationReport(
                modelId = model.id,
                mode = mode,
                result = result,
                startedAtEpochMs = reportStartedAtEpochMs,
                elapsedMs = terminalElapsedMs,
                summary = terminalSummary,
                events = reportEvents + result.toCalibrationResultEvent(
                    elapsedMs = terminalElapsedMs,
                    previous = reportEvents.lastOrNull()
                )
            )
            calibrationJob = null
            refreshCalibrationStatus()
        }
    }

    fun dismissCalibrationDialog() {
        _uiState.update(LumaUiState::dismissCalibrationDialog)
    }

    fun openCalibrationReport(reportId: String) {
        _uiState.update { state ->
            if (state.calibrationReports.any { it.id == reportId }) {
                state.copy(selectedCalibrationReportId = reportId)
            } else {
                state
            }
        }
    }

    fun dismissCalibrationReport() {
        _uiState.update { it.copy(selectedCalibrationReportId = null) }
    }

    private fun startCalibrationTicker() {
        calibrationTickerJob?.cancel()
        calibrationTickerJob = viewModelScope.launch {
            while (isActive) {
                delay(CALIBRATION_TICK_INTERVAL_MS)
                _uiState.update { state ->
                    val dialog = state.calibrationDialog ?: return@update state
                    state.copy(
                        calibrationDialog = CalibrationProgressUiReducer.tick(
                            dialog,
                            elapsedRealtimeProvider()
                        )
                    )
                }
            }
        }
    }

    private fun refreshCalibrationStatus() {
        val model = _uiState.value.selectedModel ?: return
        if (calibrationJob?.isActive == true) return
        viewModelScope.launch {
            val (supported, valid, enabled) = try {
                withContext(ioDispatcher) {
                    Triple(
                        calibrationCoordinator.isSupported,
                        calibrationCoordinator.hasValidCalibration(model),
                        calibrationCoordinator.hasEnabledCalibration(model)
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Triple(false, false, false)
            }
            _uiState.update { state ->
                if (state.calibrationStatus == CalibrationUiStatus.RUNNING) return@update state
                if (!isCurrentCalibrationModel(model.id, state.selectedModelId)) return@update state
                val acceleratorKey = calibrationCoordinator.acceleratorCalibrationKey
                val adrenoAvailable = calibrationCoordinator.supportsAdrenoCalibration
                state.copy(
                    showCalibrationPrompt = shouldShowCalibrationPrompt(
                        preferencePending = adrenoAvailable &&
                            userPreferencesRepository.shouldShowCalibrationPrompt(acceleratorKey),
                        hasSelectedModel = state.selectedModelId != null,
                        deviceSupported = supported
                    ),
                    calibrationStatus = when {
                        !supported -> CalibrationUiStatus.UNSUPPORTED
                        valid && enabled -> CalibrationUiStatus.READY
                        valid -> CalibrationUiStatus.READY_SAFE
                        adrenoAvailable &&
                            userPreferencesRepository.wasCalibrationSkipped(acceleratorKey) ->
                            CalibrationUiStatus.SKIPPED
                        !adrenoAvailable && userPreferencesRepository.wasCalibrationSkipped() ->
                            CalibrationUiStatus.SKIPPED
                        else -> CalibrationUiStatus.NOT_RUN
                    },
                    adrenoCalibrationAvailable = adrenoAvailable
                )
            }
        }
    }

    private suspend fun persistCalibrationReport(
        modelId: String,
        mode: CalibrationMode,
        result: CalibrationRunResult,
        startedAtEpochMs: Long,
        elapsedMs: Long,
        summary: String,
        events: List<CalibrationReportEvent>
    ) {
        val finishedAtEpochMs = currentTimeMillisProvider().coerceAtLeast(startedAtEpochMs)
        val report = CalibrationReport(
            id = UUID.randomUUID().toString(),
            mode = mode,
            outcome = when (result) {
                is CalibrationRunResult.Completed -> CalibrationReportOutcome.SUCCEEDED
                is CalibrationRunResult.Failed -> {
                    if (result.reason == CalibrationFailureReason.UNSUPPORTED_DEVICE) {
                        CalibrationReportOutcome.UNSUPPORTED
                    } else {
                        CalibrationReportOutcome.FAILED
                    }
                }
                CalibrationRunResult.TimedOut -> CalibrationReportOutcome.TIMED_OUT
            },
            startedAtEpochMs = startedAtEpochMs,
            finishedAtEpochMs = finishedAtEpochMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            acceleratorCalibrationKey = calibrationCoordinator.acceleratorCalibrationKey,
            modelId = modelId,
            summary = summary,
            events = events
        )
        val reports = try {
            withContext(ioDispatcher) {
                calibrationReportRepository.save(report)
                calibrationReportRepository.list()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        reports?.let { updated ->
            _uiState.update { it.copy(calibrationReports = updated) }
        }
    }

    private fun refreshCalibrationReports() {
        viewModelScope.launch {
            val reports = try {
                withContext(ioDispatcher) { calibrationReportRepository.list() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            _uiState.update { it.copy(calibrationReports = reports) }
        }
    }

    private fun persistTileSizePreferences(state: LumaUiState) {
        userPreferencesRepository.saveTileSizePreferences(state.toTileSizePreferences())
    }

    fun cancelProcessing() {
        currentJob?.cancel()
        val taskId = currentTaskId ?: return
        viewModelScope.launch {
            persistentController.cancel(taskId)
        }
    }

    fun resumeProcessing() {
        viewModelScope.launch {
            persistentController.restoreAndResume()
        }
    }

    fun onHostStarted() {
        viewModelScope.launch {
            persistentController.onHostStarted()
        }
    }

    fun onHostStopped() {
        viewModelScope.launch {
            persistentController.onHostStopped()
        }
    }

    fun backHome() {
        _uiState.update { it.copy(screen = LumaScreen.EDITING, selectedTab = LumaTab.PROCESS) }
    }

    fun saveResultToGallery() {
        val state = _uiState.value
        val results = state.completedResults.ifEmpty {
            state.activeParams?.let {
                listOf(
                    RenderResultInfo(
                        taskId = it.taskId,
                        fileName = File(it.inputPath).name,
                        inputPath = it.inputPath,
                        outputPath = it.outputPath,
                        modelName = it.modelName,
                        scale = it.scale,
                        noise = it.noise,
                        pipelineLabel = it.pipelineLabel,
                        outputFormat = it.outputFormat,
                        isExtremeExport = it.exportMode == ExportMode.EXTREME_SINGLE_PNG
                    )
                )
            }.orEmpty()
        }
        if (results.isEmpty()) return
        if (resultSaveDestinationForSdk(sdkIntProvider()) == ResultSaveDestination.SAF_DOCUMENT) {
            startDocumentSaveQueue(results)
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val timestamp = System.currentTimeMillis()
                    results.mapIndexed { index, result ->
                        galleryRepository.saveImage(
                            sourceFile = File(result.outputPath),
                            modelName = result.modelName,
                            scale = result.scale,
                            format = result.outputFormat,
                            timestampMillis = timestamp + index * 1000L
                        )
                    }
                }
            }.onSuccess { uris ->
                val message = if (uris.size > 1) {
                    "Saved ${uris.size} images to Pictures/LocalSR"
                } else {
                    "Saved to Pictures/LocalSR"
                }
                _uiState.update {
                    it.copy(
                        savedOutputUri = uris.lastOrNull()?.toString(),
                        savedOutputUris = uris.map { uri -> uri.toString() }
                    ).withResultMessage(message)
                }
            }.onFailure { error ->
                _uiState.update { it.withResultMessage("Save failed: ${error.message}") }
            }
        }
    }

    private fun loadManifest() {
        viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    repository.loadManifest()
                }
            }.onSuccess { manifest ->
                val runtimeManifest = manifest.copy(models = modelRuntimePolicy.visibleModels(manifest.models))
                _uiState.update { it.withLoadedManifest(runtimeManifest) }
                refreshCalibrationStatus()
            }.onFailure { error ->
                _uiState.update { it.withResultMessage("Model manifest failed: ${error.message}") }
            }
        }
    }

    private fun preparingProgress(taskId: String) = UpscaleProgress(
        taskId = taskId,
        stage = UpscaleStage.PREPARING,
        progress = 0f,
        currentTile = 0,
        totalTiles = 1,
        completedTiles = 0,
        message = "Preparing processing files",
        estimatedRemainingMs = null
    )

    private fun deleteUnpublishedTaskDirectory(taskDirectory: File) {
        runCatching {
            val stableRoot = File(
                getApplication<Application>().noBackupFilesDir,
                ImageCacheRepository.PERSISTENT_TASK_DIRECTORY
            ).canonicalFile
            val target = taskDirectory.canonicalFile
            if (target.parentFile == stableRoot) target.deleteRecursively()
        }
    }

    fun markDocumentSaveLaunched(requestId: Long) {
        val current = documentSaveQueue ?: return
        val updated = current.markLaunched(requestId)
        if (updated === current) return
        documentSaveQueue = updated
        _uiState.update { it.copy(documentSavePrompt = updated.prompt) }
    }

    fun completeDocumentSave(requestId: Long, destinationUri: Uri) {
        val current = documentSaveQueue ?: return
        val prompt = current.prompt ?: return
        val item = current.currentItem ?: return
        if (prompt.requestId != requestId || !prompt.launched || documentSaveJob?.isActive == true) return
        documentSaveJob = viewModelScope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    galleryRepository.copyImageToUri(File(item.sourcePath), destinationUri)
                }
            }.onSuccess { savedUri ->
                val latest = documentSaveQueue ?: return@onSuccess
                val updated = latest.complete(requestId, savedUri.toString())
                if (updated === latest) return@onSuccess
                documentSaveQueue = updated.takeUnless(DocumentSaveQueue::isFinished)
                _uiState.update { state ->
                    val next = state.copy(
                        documentSavePrompt = updated.prompt,
                        savedOutputUri = updated.savedUris.lastOrNull(),
                        savedOutputUris = updated.savedUris
                    )
                    if (updated.isFinished) {
                        next.withResultMessage(savedDocumentMessage(updated.savedCount))
                    } else {
                        next
                    }
                }
            }.onFailure { error ->
                val latest = documentSaveQueue ?: return@onFailure
                val updated = latest.fail(requestId)
                if (updated === latest) return@onFailure
                documentSaveQueue = null
                _uiState.update { state ->
                    state.copy(
                        documentSavePrompt = null,
                        savedOutputUri = updated.savedUris.lastOrNull(),
                        savedOutputUris = updated.savedUris
                    ).withResultMessage(
                        "Save failed after ${updated.savedCount} image(s); " +
                            "${updated.stoppedCount} not saved: ${error.message}"
                    )
                }
            }
            documentSaveJob = null
        }
    }

    fun cancelDocumentSave(requestId: Long) {
        val current = documentSaveQueue ?: return
        val updated = current.cancel(requestId)
        if (updated === current) return
        documentSaveQueue = null
        _uiState.update { state ->
            state.copy(
                documentSavePrompt = null,
                savedOutputUri = updated.savedUris.lastOrNull(),
                savedOutputUris = updated.savedUris
            ).withResultMessage(
                "Save cancelled after ${updated.savedCount} image(s); " +
                    "${updated.stoppedCount} not saved."
            )
        }
    }

    private fun startDocumentSaveQueue(results: List<RenderResultInfo>) {
        if (documentSaveQueue?.isFinished == false) return
        val timestamp = System.currentTimeMillis()
        val items = results.mapIndexed { index, result ->
            DocumentSaveItem(
                sourcePath = result.outputPath,
                displayName = MediaOutputNamer.create(
                    timestampMillis = timestamp + index * 1_000L,
                    modelName = result.modelName,
                    scale = result.scale,
                    format = result.outputFormat
                ),
                mimeType = "image/png"
            )
        }
        val firstRequestId = nextDocumentSaveRequestId
        nextDocumentSaveRequestId += items.size.toLong()
        val queue = DocumentSaveQueue.start(items, firstRequestId)
        documentSaveQueue = queue
        _uiState.update {
            it.copy(
                documentSavePrompt = queue.prompt,
                savedOutputUri = null,
                savedOutputUris = emptyList()
            )
        }
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LumaViewModel(application) as T
        }
    }
}

data class LumaUiState(
    val models: List<ModelPack> = emptyList(),
    val selectedModelId: String? = null,
    val selectedImage: SelectedImageInfo? = null,
    val selectedImages: List<SelectedImageInfo> = emptyList(),
    val scale: Int = 2,
    val noise: Int = 1,
    val tileSizeMode: TileSizeMode = TileSizeMode.AUTO,
    val manualTileSize: Int = DEFAULT_TILE_SIZE,
    val lastAutoTileSize: Int = DEFAULT_TILE_SIZE,
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val accelerationMode: AccelerationMode = AccelerationMode.AUTO,
    val exportMode: ExportMode = ExportMode.AUTO,
    val tta: Boolean = false,
    val screen: LumaScreen = LumaScreen.EDITING,
    val selectedTab: LumaTab = LumaTab.PROCESS,
    val progress: UpscaleProgress? = null,
    val processingPaused: Boolean = false,
    val activeParams: UpscaleParams? = null,
    val activeBatchIndex: Int = 0,
    val activeBatchSize: Int = 0,
    val recentTasks: List<RenderTaskSummary> = emptyList(),
    val completedResults: List<RenderResultInfo> = emptyList(),
    val savedOutputUri: String? = null,
    val savedOutputUris: List<String> = emptyList(),
    val documentSavePrompt: DocumentSavePrompt? = null,
    val resultMessage: String? = null,
    val resultMessageEventId: Long = 0L,
    val calibrationStatus: CalibrationUiStatus = CalibrationUiStatus.NOT_RUN,
    val showCalibrationPrompt: Boolean = false,
    val calibrationDialog: CalibrationProgressDialogState? = null,
    val adrenoCalibrationAvailable: Boolean = false,
    val calibrationReports: List<CalibrationReport> = emptyList(),
    val selectedCalibrationReportId: String? = null
) {
    val selectedModel: ModelPack?
        get() = models.firstOrNull { it.id == selectedModelId }

    val selectedImageCount: Int
        get() = selectedImages.ifEmpty { selectedImage?.let(::listOf).orEmpty() }.size

    val selectedCalibrationReport: CalibrationReport?
        get() = calibrationReports.firstOrNull { it.id == selectedCalibrationReportId }

    val canStartProcessing: Boolean
        get() = selectedImageCount > 0 &&
            selectedModelId != null &&
            calibrationStatus != CalibrationUiStatus.RUNNING

    val startButtonLabel: String
        get() = if (selectedImageCount > 1) {
            "开始批量处理 $selectedImageCount 张"
        } else {
            "开始处理"
    }
}

internal fun LumaUiState.startCalibrationDialog(
    startedAtElapsedMs: Long,
    mode: CalibrationMode = CalibrationMode.LEGACY
): LumaUiState = copy(
    calibrationDialog = CalibrationProgressUiReducer.start(startedAtElapsedMs, mode)
)

internal fun LumaUiState.applyCalibrationProgress(event: CalibrationProgressEvent): LumaUiState {
    val dialog = calibrationDialog ?: return this
    return copy(calibrationDialog = CalibrationProgressUiReducer.apply(dialog, event))
}

internal fun LumaUiState.finishCalibrationDialog(
    status: CalibrationDialogStatus,
    elapsedMs: Long,
    summary: String
): LumaUiState {
    val dialog = calibrationDialog ?: return this
    return copy(
        calibrationDialog = CalibrationProgressUiReducer.finish(
            dialog,
            status,
            elapsedMs,
            summary
        )
    )
}

internal fun LumaUiState.dismissCalibrationDialog(): LumaUiState {
    val dialog = calibrationDialog ?: return this
    return copy(calibrationDialog = CalibrationProgressUiReducer.dismiss(dialog))
}

fun LumaUiState.withResultMessage(message: String?): LumaUiState {
    return if (message.isNullOrBlank()) {
        copy(resultMessage = null)
    } else {
        copy(
            resultMessage = message,
            resultMessageEventId = resultMessageEventId + 1
        )
    }
}

private fun CalibrationProgressEvent.toCalibrationReportEvent() = CalibrationReportEvent(
    elapsedMs = elapsedMs,
    type = type,
    title = title,
    message = logMessage,
    stepIndex = stepIndex,
    totalSteps = totalSteps,
    completedWorkUnits = completedWorkUnits,
    totalWorkUnits = totalWorkUnits
)

private fun CalibrationRunResult.toCalibrationResultEvent(
    elapsedMs: Long,
    previous: CalibrationReportEvent?
): CalibrationReportEvent {
    val resultLog = when (this) {
        is CalibrationRunResult.Completed -> buildString {
            append("result mode=")
            append(benchmark.calibrationMode.name)
            append(" vulkan=")
            append(
                benchmark.vulkanTiers.joinToString(",") { tier ->
                    val profile = tier.profile
                    if (profile == null) {
                        "${tier.resolutionTier.name}:${tier.decision.name}"
                    } else {
                        "${tier.resolutionTier.name}:${tier.decision.name}" +
                            "(tile=${profile.tileSize},batch=${profile.commandBatchSize}," +
                            "depth=${profile.maxPipelineDepth},tuning=${profile.tuning.toNativeFlags()})"
                    }
                }
            )
            append(" cpu=")
            append(
                benchmark.cpuProfiles.joinToString(",") { profile ->
                    "${profile.resolutionTier.name}(tile=${profile.tileSize},threads=${profile.cpuThreads}," +
                        "blocktime=${profile.openmpBlocktimeMs},post=${profile.postprocessThreads})"
                }
            )
            append(" tta=")
            append(benchmark.ttaProfiles.size)
        }
        is CalibrationRunResult.Failed -> "result failed=${reason.name}"
        CalibrationRunResult.TimedOut -> "result timed_out=1"
    }
    return CalibrationReportEvent(
        elapsedMs = elapsedMs.coerceAtLeast(0L),
        type = com.lumasr.domain.CalibrationProgressEventType.NOTE,
        title = "校准结果",
        message = resultLog,
        stepIndex = previous?.stepIndex ?: 0,
        totalSteps = previous?.totalSteps ?: 0,
        completedWorkUnits = previous?.completedWorkUnits ?: 0L,
        totalWorkUnits = previous?.totalWorkUnits ?: 0L
    )
}

val TileSizeOptions: List<Int> = UpscaleParamsFactory.supportedTileSizes.sorted()

fun sanitizeTileSize(tileSize: Int): Int = UpscaleParamsFactory.sanitizeTileSize(tileSize)

private const val DEFAULT_TILE_SIZE = UpscaleParamsFactory.DEFAULT_TILE_SIZE
private const val DEFAULT_GPU_HEADROOM_PERCENT = UpscaleParamsFactory.DEFAULT_GPU_HEADROOM_PERCENT
private const val CALIBRATION_REPORT_DIRECTORY_NAME = "calibration-reports"
private const val CALIBRATION_TICK_INTERVAL_MS = 1_000L

data class SelectedImageInfo(
    val sourceUri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val mimeType: String?
)

data class RenderResultInfo(
    val taskId: String,
    val fileName: String,
    val inputPath: String,
    val outputPath: String,
    val modelName: String,
    val scale: Int,
    val noise: Int,
    val pipelineLabel: String? = null,
    val outputFormat: OutputFormat,
    val isExtremeExport: Boolean = false
)

enum class LumaScreen {
    EDITING,
    PROCESSING,
    COMPARE
}

enum class LumaTab {
    PROCESS,
    SETTINGS
}

enum class RenderTaskStatus {
    RUNNING,
    DONE,
    FAILED,
    CANCELLED
}

data class RenderTaskSummary(
    val taskId: String,
    val fileName: String,
    val modelName: String,
    val scale: Int,
    val tileSize: Int,
    val status: RenderTaskStatus,
    val progress: Float
) {
    companion object {
        fun fromParams(
            params: UpscaleParams,
            fileName: String,
            status: RenderTaskStatus,
            progress: Float
        ) = RenderTaskSummary(
            taskId = params.taskId,
            fileName = fileName,
            modelName = params.pipelineLabel ?: params.modelName,
            scale = params.scale,
            tileSize = params.tileSize,
            status = status,
            progress = progress.coerceIn(0f, 1f)
        )
    }
}

fun LumaUiState.clearImageSelection(): LumaUiState {
    return copy(
        selectedImage = null,
        selectedImages = emptyList(),
        screen = LumaScreen.EDITING,
        selectedTab = LumaTab.PROCESS,
        progress = null,
        activeParams = null,
        activeBatchIndex = 0,
        activeBatchSize = 0,
        completedResults = emptyList(),
        savedOutputUri = null,
        savedOutputUris = emptyList(),
        resultMessage = null
    )
}

fun LumaUiState.withLoadedManifest(manifest: ModelManifest): LumaUiState {
    val defaultModel = manifest.models.firstOrNull { it.id == DEFAULT_MODEL_ID }
        ?: manifest.models.firstOrNull()
    val resolvedScale = defaultModel?.defaultScale ?: scale
    return copy(
        models = manifest.models,
        selectedModelId = defaultModel?.id,
        scale = resolvedScale,
        noise = defaultModel?.sanitizeNoiseForUi(resolvedScale, defaultModel.defaultNoise, manifest.models) ?: noise
    ).withAutoTileSizeForCurrentSelection(model = defaultModel, scale = resolvedScale)
}

fun LumaUiState.withTileSizePreferences(preferences: TileSizePreferences): LumaUiState {
    val manual = sanitizeTileSize(preferences.manualTileSize)
    val lastAuto = sanitizeTileSize(preferences.lastAutoTileSize)
    return copy(
        tileSizeMode = preferences.mode,
        manualTileSize = manual,
        lastAutoTileSize = lastAuto,
        tileSize = if (preferences.mode == TileSizeMode.MANUAL) manual else lastAuto
    )
}

fun LumaUiState.toTileSizePreferences(): TileSizePreferences {
    return TileSizePreferences(
        mode = tileSizeMode,
        manualTileSize = sanitizeTileSize(manualTileSize),
        lastAutoTileSize = sanitizeTileSize(lastAutoTileSize)
    )
}

fun LumaUiState.withAutoTileSizeForCurrentSelection(
    model: ModelPack? = selectedModel,
    scale: Int = this.scale
): LumaUiState {
    if (tileSizeMode != TileSizeMode.AUTO) {
        return copy(tileSize = sanitizeTileSize(manualTileSize))
    }
    val autoTileSize = autoTileSizeForCurrentSelection(model = model, scale = scale)
    return copy(tileSize = autoTileSize, lastAutoTileSize = autoTileSize)
}

fun LumaUiState.autoTileSizeForCurrentSelection(
    model: ModelPack? = selectedModel,
    scale: Int = this.scale
): Int {
    val images = selectedImages.ifEmpty { selectedImage?.let(::listOf).orEmpty() }
    if (images.isEmpty()) {
        return sanitizeTileSize(tileSize)
    }
    return images
        .map { image -> AutoTileSizePolicy.resolve(image.width, image.height, model, scale) }
        .minOrNull()
        ?.let(::sanitizeTileSize)
        ?: DEFAULT_TILE_SIZE
}

fun LumaUiState.extremeExportEstimate(): ExtremeExportEstimate? {
    val image = selectedImage ?: selectedImages.firstOrNull() ?: return null
    val model = selectedModel ?: return null
    val width = image.width?.takeIf { it > 0 } ?: return null
    val height = image.height?.takeIf { it > 0 } ?: return null
    val profile = ProcessingResourceProfile(width, height)
    val outputPixels = profile.outputPixelsFor(scale) ?: return null
    val decision = ResourceBudgetPolicy.evaluate(
        imageWidth = width,
        imageHeight = height,
        model = model,
        scale = scale,
        tileSize = tileSize,
        gpuHeadroomPercent = DEFAULT_GPU_HEADROOM_PERCENT,
        accelerationMode = accelerationMode,
        tta = tta,
        exportMode = exportMode,
        resourceProfile = profile
    )
    val outputWidth = (width.toLong() * decision.scale)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val outputHeight = (height.toLong() * decision.scale)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    return ExtremeExportEstimate(
        outputWidth = outputWidth,
        outputHeight = outputHeight,
        outputPixels = outputPixels,
        mode = decision.exportMode,
        recommendedTileSize = decision.tileSize,
        message = decision.message
    )
}

private fun LumaUiState.tileSizeFor(
    image: SelectedImageInfo,
    model: ModelPack,
    scale: Int,
    resourceProfile: ProcessingResourceProfile = ProcessingResourceProfile(image.width, image.height)
): Int {
    return if (tileSizeMode == TileSizeMode.AUTO) {
        AutoTileSizePolicy.resolve(image.width, image.height, model, scale, resourceProfile)
    } else {
        manualTileSize
    }.let(::sanitizeTileSize)
}

private fun AndroidViewModel.processingResourceProfile(image: SelectedImageInfo): ProcessingResourceProfile {
    val activityManager = getApplication<Application>().getSystemService(ActivityManager::class.java)
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memoryInfo)
    return ProcessingResourceProfile(
        imageWidth = image.width,
        imageHeight = image.height,
        isLowRamDevice = activityManager?.isLowRamDevice == true,
        availableMemoryBytes = memoryInfo.availMem.takeIf { it > 0L }
    )
}

private fun ModelPack.sanitizeNoiseForUi(
    targetScale: Int,
    noise: Int,
    allModels: List<ModelPack>
): Int {
    return availableDenoiseForScale(targetScale, allModels)
        .ifEmpty { listOf(defaultNoise) }
        .minBy { abs(it - noise) }
}

private fun UpscaleParams.executionProfileKey(): String = listOf(
    engine.name,
    scale.toString(),
    noise.toString(),
    tileSize.toString(),
    accelerationMode.name,
    tta.toString(),
    exportMode.name,
    outputMode.name,
    nativeBackend.name,
    inferencePrecision.name,
    cpuThreads.toString(),
    openmpBlocktimeMs.toString(),
    commandBatchSize.toString(),
    modelCacheCapacity.toString()
).joinToString(separator = "_")

private fun String.toStableDisplayName(index: Int): String {
    val sanitized = map { character ->
        if (character.isISOControl()) '_' else character
    }.joinToString(separator = "").trim().take(MAX_SOURCE_DISPLAY_NAME_LENGTH)
    return sanitized.ifBlank { "image-${index + 1}.png" }
}

private fun com.lumasr.data.CachedImageInfo.toSelectedImageInfo() = SelectedImageInfo(
    sourceUri = sourceUri,
    displayName = displayName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    mimeType = mimeType
)

private const val DEFAULT_MODEL_ID = "realcugan-standard"
private const val MAX_SOURCE_DISPLAY_NAME_LENGTH = 512

private fun savedDocumentMessage(savedCount: Int): String {
    return if (savedCount == 1) "Saved 1 image." else "Saved $savedCount images."
}
