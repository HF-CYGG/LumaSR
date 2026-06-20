/**
 * 本文件定义了 LumaViewModel，负责管理 UI 状态与业务逻辑。
 * 包括图片选择、参数配置、模型加载以及超分辨率任务的执行和取消。
 */
package com.lumasr.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumasr.data.GalleryRepository
import com.lumasr.data.ImageCacheRepository
import com.lumasr.data.ModelAssetRepository
import com.lumasr.data.ModelManifestRepository
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ModelManifest
import com.lumasr.domain.ModelPack
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleParamsFactory
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.processor.HybridSuperResProcessor
import com.lumasr.domain.SuperResProcessor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LumaViewModel(
    application: Application,
    private val repository: ModelManifestRepository = ModelManifestRepository(application),
    private val imageCacheRepository: ImageCacheRepository = ImageCacheRepository(application),
    private val modelAssetRepository: ModelAssetRepository = ModelAssetRepository.fromContext(application),
    private val galleryRepository: GalleryRepository = GalleryRepository(application),
    private val processor: SuperResProcessor = HybridSuperResProcessor(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LumaUiState())
    val uiState: StateFlow<LumaUiState> = _uiState.asStateFlow()

    private var currentJob: Job? = null
    private var currentTaskId: String? = null

    init {
        loadManifest()
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
                        savedOutputUris = emptyList(),
                        resultMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(resultMessage = "Cannot read selected image: ${error.message}") }
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
                        savedOutputUris = emptyList(),
                        resultMessage = null
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(resultMessage = "Cannot read selected images: ${error.message}") }
            }
        }
    }

    fun clearSelectedImage() {
        currentTaskId = null
        _uiState.update { it.clearImageSelection() }
    }

    fun selectModel(modelId: String) {
        val model = _uiState.value.models.firstOrNull { it.id == modelId } ?: return
        _uiState.update {
            it.copy(
                selectedModelId = modelId,
                scale = model.defaultScale,
                noise = model.defaultNoise,
                tta = false
            )
        }
    }

    fun setScale(scale: Int) {
        _uiState.update { it.copy(scale = scale) }
    }

    fun setNoise(noise: Int) {
        _uiState.update { it.copy(noise = noise) }
    }

    fun setAccelerationMode(mode: AccelerationMode) {
        _uiState.update { it.copy(accelerationMode = mode) }
    }

    fun setTileSize(tileSize: Int) {
        _uiState.update { it.copy(tileSize = sanitizeTileSize(tileSize)) }
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
        val images = state.selectedImages.ifEmpty { state.selectedImage?.let(::listOf).orEmpty() }
        if (images.isEmpty()) return
        val model = state.selectedModel ?: return
        currentJob?.cancel()

        // Batch state stays in the ViewModel so Compose can render the queue without owning task logic.
        currentJob = viewModelScope.launch {
            var lastParams: UpscaleParams? = null
            var lastResultMessage: String? = null
            var lastStage: UpscaleStage = UpscaleStage.CANCELLED
            var lastSuccess = false
            val completedResults = mutableListOf<RenderResultInfo>()
            val modelDir = runCatching {
                withContext(ioDispatcher) {
                    modelAssetRepository.prepareModel(model).absolutePath
                }
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        screen = LumaScreen.EDITING,
                        progress = null,
                        resultMessage = "Cannot prepare ${model.displayName}: ${error.message}"
                    )
                }
                currentTaskId = null
                return@launch
            }

            for ((index, image) in images.withIndex()) {
                if (!isActive) break

                val taskId = UUID.randomUUID().toString()
                currentTaskId = taskId
                _uiState.update {
                    it.copy(
                        selectedImage = image,
                        screen = LumaScreen.PROCESSING,
                        selectedTab = LumaTab.PROCESS,
                        progress = preparingProgress(taskId),
                        activeBatchIndex = index + 1,
                        activeBatchSize = images.size,
                        completedResults = emptyList(),
                        savedOutputUris = emptyList(),
                        resultMessage = null
                    )
                }

                val params = runCatching {
                    withContext(ioDispatcher) {
                        val paths = imageCacheRepository.copyToTaskCache(Uri.parse(image.sourceUri), taskId, image.mimeType)
                        UpscaleParamsFactory.create(
                            taskId = taskId,
                            inputPath = paths.inputFile.absolutePath,
                            outputPath = paths.outputFile.absolutePath,
                            model = model,
                            resolvedModelDir = modelDir,
                            scale = state.scale,
                            noise = state.noise,
                            tileSize = state.tileSize,
                            gpuHeadroomPercent = DEFAULT_GPU_HEADROOM_PERCENT,
                            accelerationMode = state.accelerationMode,
                            tta = state.tta,
                            outputFormat = OutputFormat.PNG
                        )
                    }
                }.getOrElse { error ->
                    currentTaskId = null
                    _uiState.update {
                        it.copy(
                            screen = LumaScreen.EDITING,
                            progress = null,
                            resultMessage = "Cannot prepare ${image.displayName}: ${error.message}"
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        activeParams = params,
                        recentTasks = it.recentTasks.upsert(
                            RenderTaskSummary.fromParams(
                                params = params,
                                fileName = image.displayName,
                                status = RenderTaskStatus.RUNNING,
                                progress = 0f
                            )
                        )
                    )
                }

                val result = processor.process(params) { progress ->
                    _uiState.update {
                        it.copy(
                            progress = progress,
                            recentTasks = it.recentTasks.upsert(
                                RenderTaskSummary.fromParams(
                                    params = params,
                                    fileName = image.displayName,
                                    status = progress.toTaskStatus(),
                                    progress = progress.progress
                                )
                            )
                        )
                    }
                }

                lastParams = params
                lastResultMessage = result.message
                lastStage = result.stage
                lastSuccess = result.success

                if (result.success) {
                    completedResults += RenderResultInfo(
                        taskId = params.taskId,
                        fileName = image.displayName,
                        inputPath = params.inputPath,
                        outputPath = result.outputPath ?: params.outputPath,
                        modelName = params.modelName,
                        scale = params.scale,
                        noise = params.noise,
                        outputFormat = params.outputFormat
                    )
                }

                _uiState.update {
                    it.copy(
                        recentTasks = it.recentTasks.upsert(
                            RenderTaskSummary.fromParams(
                                params = params,
                                fileName = image.displayName,
                                status = when {
                                    result.success -> RenderTaskStatus.DONE
                                    result.stage == UpscaleStage.CANCELLED -> RenderTaskStatus.CANCELLED
                                    else -> RenderTaskStatus.FAILED
                                },
                                progress = if (result.success) 1f else it.progress?.progress ?: 0f
                            )
                        )
                    )
                }

                if (!result.success) break
            }

            val finalTaskId = lastParams?.taskId ?: currentTaskId
            _uiState.update {
                it.copy(
                    screen = if (lastSuccess) LumaScreen.COMPARE else LumaScreen.EDITING,
                    resultMessage = lastResultMessage,
                    progress = it.progress ?: finalTaskId?.let(::doneProgress),
                    activeBatchIndex = 0,
                    activeBatchSize = images.size,
                    completedResults = completedResults.toList()
                )
            }
            if (lastStage != UpscaleStage.PROCESSING_TILE) {
                currentTaskId = null
            }
        }
    }

    fun cancelProcessing() {
        currentTaskId?.let(processor::cancel)
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
                        outputFormat = it.outputFormat
                    )
                )
            }.orEmpty()
        }
        if (results.isEmpty()) return
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
                _uiState.update {
                    it.copy(
                        savedOutputUri = uris.lastOrNull()?.toString(),
                        savedOutputUris = uris.map { uri -> uri.toString() },
                        resultMessage = if (uris.size > 1) {
                            "Saved ${uris.size} images to Pictures/LocalSR"
                        } else {
                            "Saved to Pictures/LocalSR"
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(resultMessage = "Save failed: ${error.message}") }
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
                _uiState.update { it.withLoadedManifest(manifest) }
            }.onFailure { error ->
                _uiState.update { it.copy(resultMessage = "Model manifest failed: ${error.message}") }
            }
        }
    }

    private fun preparingProgress(taskId: String) = UpscaleProgress(
        taskId = taskId,
        stage = UpscaleStage.PREPARING,
        progress = 0f,
        currentTile = 0,
        totalTiles = 1,
        completedTileIndexes = emptySet(),
        message = "Preparing processing files",
        estimatedRemainingMs = null
    )

    private fun doneProgress(taskId: String) = UpscaleProgress(
        taskId = taskId,
        stage = UpscaleStage.DONE,
        progress = 1f,
        currentTile = 16,
        totalTiles = 16,
        completedTileIndexes = (1..16).toSet(),
        message = "Mock upscale complete",
        estimatedRemainingMs = null
    )

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
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val accelerationMode: AccelerationMode = AccelerationMode.AUTO,
    val tta: Boolean = false,
    val screen: LumaScreen = LumaScreen.EDITING,
    val selectedTab: LumaTab = LumaTab.PROCESS,
    val progress: UpscaleProgress? = null,
    val activeParams: UpscaleParams? = null,
    val activeBatchIndex: Int = 0,
    val activeBatchSize: Int = 0,
    val recentTasks: List<RenderTaskSummary> = emptyList(),
    val completedResults: List<RenderResultInfo> = emptyList(),
    val savedOutputUri: String? = null,
    val savedOutputUris: List<String> = emptyList(),
    val resultMessage: String? = null
) {
    val selectedModel: ModelPack?
        get() = models.firstOrNull { it.id == selectedModelId }

    val selectedImageCount: Int
        get() = selectedImages.ifEmpty { selectedImage?.let(::listOf).orEmpty() }.size

    val canStartProcessing: Boolean
        get() = selectedImageCount > 0 && selectedModelId != null

    val startButtonLabel: String
        get() = if (selectedImageCount > 1) {
            "开始批量处理 $selectedImageCount 张"
        } else {
            "开始处理"
        }
}

val TileSizeOptions: List<Int> = UpscaleParamsFactory.supportedTileSizes.sorted()

fun sanitizeTileSize(tileSize: Int): Int = UpscaleParamsFactory.sanitizeTileSize(tileSize)

private const val DEFAULT_TILE_SIZE = UpscaleParamsFactory.DEFAULT_TILE_SIZE
private const val DEFAULT_GPU_HEADROOM_PERCENT = UpscaleParamsFactory.DEFAULT_GPU_HEADROOM_PERCENT

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
    val outputFormat: OutputFormat
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
            modelName = params.modelName,
            scale = params.scale,
            tileSize = params.tileSize,
            status = status,
            progress = progress.coerceIn(0f, 1f)
        )
    }
}

private fun List<RenderTaskSummary>.upsert(item: RenderTaskSummary): List<RenderTaskSummary> {
    return (listOf(item) + filterNot { it.taskId == item.taskId }).take(8)
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
    return copy(
        models = manifest.models,
        selectedModelId = defaultModel?.id,
        scale = defaultModel?.defaultScale ?: scale,
        noise = defaultModel?.defaultNoise ?: noise
    )
}

private fun UpscaleProgress.toTaskStatus(): RenderTaskStatus {
    return when (stage) {
        UpscaleStage.DONE -> RenderTaskStatus.DONE
        UpscaleStage.FAILED -> RenderTaskStatus.FAILED
        UpscaleStage.CANCELLED -> RenderTaskStatus.CANCELLED
        else -> RenderTaskStatus.RUNNING
    }
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
