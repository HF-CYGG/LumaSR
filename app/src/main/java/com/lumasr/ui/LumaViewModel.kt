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
import com.lumasr.domain.ModelPack
import com.lumasr.domain.OutputFormat
import com.lumasr.domain.UpscaleParams
import com.lumasr.domain.UpscaleParamsFactory
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.processor.HybridSuperResProcessor
import com.lumasr.domain.SuperResProcessor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class LumaViewModel(
    application: Application,
    private val repository: ModelManifestRepository = ModelManifestRepository(application),
    private val imageCacheRepository: ImageCacheRepository = ImageCacheRepository(application),
    private val modelAssetRepository: ModelAssetRepository = ModelAssetRepository.fromContext(application),
    private val galleryRepository: GalleryRepository = GalleryRepository(application),
    private val processor: SuperResProcessor = HybridSuperResProcessor()
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LumaUiState())
    val uiState: StateFlow<LumaUiState> = _uiState.asStateFlow()

    private var currentJob: Job? = null
    private var currentTaskId: String? = null

    init {
        loadManifest()
    }

    fun onImageSelected(uri: Uri) {
        _uiState.update {
            it.copy(
                selectedImage = imageCacheRepository.readImageInfo(uri).toSelectedImageInfo(),
                screen = LumaScreen.HOME,
                progress = null,
                resultMessage = null
            )
        }
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

    fun setTta(enabled: Boolean) {
        _uiState.update { state ->
            val model = state.selectedModel
            state.copy(tta = enabled && model?.supportsTta == true)
        }
    }

    fun startProcessing() {
        val state = _uiState.value
        val image = state.selectedImage ?: return
        val model = state.selectedModel ?: return
        val taskId = UUID.randomUUID().toString()
        val paths = runCatching {
            imageCacheRepository.copyToTaskCache(Uri.parse(image.sourceUri), taskId, image.mimeType)
        }.getOrElse { error ->
            _uiState.update { it.copy(resultMessage = "Cannot cache selected image: ${error.message}") }
            return
        }
        val modelDir = runCatching {
            modelAssetRepository.prepareModel(model).absolutePath
        }.getOrElse { error ->
            _uiState.update { it.copy(resultMessage = "Model asset unavailable: ${error.message}") }
            return
        }
        val params = UpscaleParamsFactory.create(
            taskId = taskId,
            inputPath = paths.inputFile.absolutePath,
            outputPath = paths.outputFile.absolutePath,
            model = model,
            resolvedModelDir = modelDir,
            scale = state.scale,
            noise = state.noise,
            accelerationMode = state.accelerationMode,
            tta = state.tta,
            outputFormat = OutputFormat.PNG
        )
        currentTaskId = taskId
        currentJob?.cancel()

        // Processing state is centralized here so UI callbacks remain stateless and predictable.
        currentJob = viewModelScope.launch {
            _uiState.update { it.copy(screen = LumaScreen.PROCESSING, activeParams = params) }
            val result = processor.process(params) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            _uiState.update {
                it.copy(
                    screen = if (result.success) LumaScreen.COMPARE else LumaScreen.HOME,
                    resultMessage = result.message,
                    progress = it.progress ?: doneProgress(taskId)
                )
            }
        }
    }

    fun cancelProcessing() {
        currentTaskId?.let(processor::cancel)
    }

    fun backHome() {
        _uiState.update { it.copy(screen = LumaScreen.HOME) }
    }

    fun saveResultToGallery() {
        val state = _uiState.value
        val params = state.activeParams ?: return
        viewModelScope.launch {
            runCatching {
                galleryRepository.saveImage(
                    sourceFile = File(params.outputPath),
                    modelName = params.modelName,
                    scale = params.scale,
                    format = params.outputFormat
                )
            }.onSuccess { uri ->
                _uiState.update {
                    it.copy(
                        savedOutputUri = uri.toString(),
                        resultMessage = "Saved to Pictures/LocalSR"
                    )
                }
            }.onFailure { error ->
                _uiState.update { it.copy(resultMessage = "Save failed: ${error.message}") }
            }
        }
    }

    private fun loadManifest() {
        runCatching { repository.loadManifest() }
            .onSuccess { manifest ->
                val defaultModel = manifest.models.firstOrNull { it.id == "realcugan-standard" }
                    ?: manifest.models.firstOrNull()
                _uiState.update {
                    it.copy(
                        models = manifest.models,
                        selectedModelId = defaultModel?.id,
                        scale = defaultModel?.defaultScale ?: 2,
                        noise = defaultModel?.defaultNoise ?: 1
                    )
                }
            }
            .onFailure { error ->
                _uiState.update { it.copy(resultMessage = "Model manifest failed: ${error.message}") }
            }
    }

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
    val scale: Int = 2,
    val noise: Int = 1,
    val accelerationMode: AccelerationMode = AccelerationMode.AUTO,
    val tta: Boolean = false,
    val screen: LumaScreen = LumaScreen.HOME,
    val progress: UpscaleProgress? = null,
    val activeParams: UpscaleParams? = null,
    val savedOutputUri: String? = null,
    val resultMessage: String? = null
) {
    val selectedModel: ModelPack?
        get() = models.firstOrNull { it.id == selectedModelId }
}

data class SelectedImageInfo(
    val sourceUri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val mimeType: String?
)

enum class LumaScreen {
    HOME,
    PROCESSING,
    COMPARE
}

private fun com.lumasr.data.CachedImageInfo.toSelectedImageInfo() = SelectedImageInfo(
    sourceUri = sourceUri,
    displayName = displayName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    mimeType = mimeType
)
