package com.lumasr.ui

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ModelPack
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import com.lumasr.domain.availableNativePassScales
import com.lumasr.domain.availableDenoiseForScale
import com.lumasr.domain.availableTargetScales
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ProcessVisualMode {
    Editing,
    Rendering
}

internal const val ProcessSheetBottomSpacerDp = 128
internal const val ResultSaveFlashDurationMillis = 2500L

internal enum class ResultSaveCardPhase {
    Ready,
    SavedFlash,
    SavedSettled
}

internal fun initialResultSaveCardPhase(savedCount: Int): ResultSaveCardPhase =
    if (savedCount > 0) ResultSaveCardPhase.SavedSettled else ResultSaveCardPhase.Ready

internal fun resultSaveCardPhaseAfterStateChange(
    currentPhase: ResultSaveCardPhase,
    previousResultsKey: String,
    resultsKey: String,
    previousSavedCount: Int,
    savedCount: Int
): ResultSaveCardPhase {
    if (previousResultsKey != resultsKey) return ResultSaveCardPhase.Ready
    if (previousSavedCount == 0 && savedCount > 0) return ResultSaveCardPhase.SavedFlash
    return currentPhase
}

internal fun settleResultSaveCardPhase(phase: ResultSaveCardPhase): ResultSaveCardPhase =
    if (phase == ResultSaveCardPhase.SavedFlash) ResultSaveCardPhase.SavedSettled else phase

internal fun resultSaveCardButtonLabels(phase: ResultSaveCardPhase): List<String> =
    if (phase == ResultSaveCardPhase.Ready) listOf("保存", "返回") else listOf("返回")

@Composable
fun ProcessTabV2(
    state: LumaUiState,
    onPickImages: (List<Uri>) -> Unit,
    onModelSelected: (String) -> Unit,
    onScaleChanged: (Int) -> Unit,
    onNoiseChanged: (Int) -> Unit,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTileSizeChanged: (Int) -> Unit,
    onTtaChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onClearImage: () -> Unit,
    onCancel: () -> Unit
) {
    val batchPhotoPicker = rememberImageBatchPicker(onPickImages)
    val mode = if (state.screen == LumaScreen.PROCESSING) ProcessVisualMode.Rendering else ProcessVisualMode.Editing
    val progress = state.progress ?: state.activeParams?.let {
        UpscaleProgress(
            taskId = it.taskId,
            stage = UpscaleStage.PREPARING,
            progress = 0f,
            currentTile = 0,
            totalTiles = 64,
            completedTileIndexes = emptySet(),
            message = "Preparing native inference",
            estimatedRemainingMs = null
        )
    }
    val previewPath = if (mode == ProcessVisualMode.Rendering) {
        state.activeParams?.inputPath ?: state.selectedImage?.sourceUri
    } else {
        state.selectedImage?.sourceUri
    }
    val selectedPreviewImages = state.selectedImages.ifEmpty {
        state.selectedImage?.let(::listOf).orEmpty()
    }
    var previewIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectedPreviewImages.firstOrNull()?.sourceUri, selectedPreviewImages.size) {
        previewIndex = 0
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val parameterSheetHeight = maxHeight * 0.64f
        val bottomInset by animateDpAsState(
            targetValue = if (mode == ProcessVisualMode.Rendering) 92.dp else parameterSheetHeight,
            animationSpec = spring(stiffness = 420f, dampingRatio = 0.86f),
            label = "previewBottomInset"
        )
        val previewHeight = (maxHeight - bottomInset - 24.dp).coerceAtLeast(180.dp)

        if (mode == ProcessVisualMode.Editing) {
            PreviewImageCarousel(
                images = selectedPreviewImages,
                currentIndex = previewIndex,
                onIndexChanged = { previewIndex = it },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(previewHeight)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(30.dp)),
                onClick = {
                    batchPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            )
        } else {
            PreviewImageSurface(
                path = previewPath,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(previewHeight)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(30.dp))
            ) { previewImage ->
                if (progress != null && previewImage != null) {
                    RenderTileOverlay(
                        progress = progress,
                        previewImage = previewImage,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = mode == ProcessVisualMode.Editing,
            enter = slideInVertically(animationSpec = spring(stiffness = 420f, dampingRatio = 0.88f)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(animationSpec = tween(260)) { it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ParameterSheet(
                state = state,
                onPickImages = { batchPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onModelSelected = onModelSelected,
                onScaleChanged = onScaleChanged,
                onNoiseChanged = onNoiseChanged,
                onAccelerationChanged = onAccelerationChanged,
                onTileSizeChanged = onTileSizeChanged,
                onTtaChanged = onTtaChanged,
                onClearImage = onClearImage,
                onStart = onStart,
                sheetHeight = parameterSheetHeight,
                modifier = Modifier
            )
        }

        AnimatedVisibility(
            visible = mode == ProcessVisualMode.Rendering && progress != null,
            enter = slideInVertically(animationSpec = spring(stiffness = 420f, dampingRatio = 0.9f)) { it / 2 } + fadeIn(tween(180)),
            exit = slideOutVertically(animationSpec = tween(180)) { it / 2 } + fadeOut(tween(120)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        ) {
            progress?.let {
                RenderStatusStrip(
                    progress = it,
                    batchIndex = state.activeBatchIndex,
                    batchSize = state.activeBatchSize,
                    onCancel = onCancel
                )
            }
        }
    }
}

@Composable
private fun ParameterSheet(
    state: LumaUiState,
    onPickImages: () -> Unit,
    onModelSelected: (String) -> Unit,
    onScaleChanged: (Int) -> Unit,
    onNoiseChanged: (Int) -> Unit,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTileSizeChanged: (Int) -> Unit,
    onTtaChanged: (Boolean) -> Unit,
    onClearImage: () -> Unit,
    onStart: () -> Unit,
    sheetHeight: Dp,
    modifier: Modifier = Modifier
) {
    val sheetScrollState = remember { ScrollState(0) }
    val darkTheme = isSystemInDarkTheme()
    val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .border(
                width = 0.6.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = if (darkTheme) 0.18f else 0.08f),
                shape = sheetShape
            ),
        shape = sheetShape,
        color = parameterSheetColor(),
        tonalElevation = if (darkTheme) 0.dp else 6.dp,
        shadowElevation = if (darkTheme) 0.dp else 10.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(sheetScrollState)
                .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = ProcessSheetBottomSpacerDp.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("处理参数", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        state.parameterSubtitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = state.selectedImage != null,
                        enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 3 },
                        exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { it / 3 }
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable(onClick = onClearImage),
                            shape = CircleShape,
                            color = iconButtonContainerColor(),
                            tonalElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(onClick = onPickImages),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        tonalElevation = 3.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = "批量选择图片", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = !state.resultMessage.isNullOrBlank(),
                enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { it / 3 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { -it / 3 }
            ) {
                state.resultMessage?.let { message ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                    ) {
                        Text(
                            text = message.toReadableProgress(),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (state.models.isNotEmpty()) {
                SectionLabel("模型")
                ModelCapsules(
                    models = state.models,
                    selectedModelId = state.selectedModelId,
                    selectedScale = state.scale,
                    onModelSelected = onModelSelected
                )
                AnimatedContent(
                    targetState = state.selectedModel?.suitabilityText(),
                    transitionSpec = {
                        (slideInVertically(tween(180)) { it / 3 } + fadeIn(tween(160))) togetherWith
                            (slideOutVertically(tween(140)) { -it / 4 } + fadeOut(tween(100)))
                    },
                    label = "modelSuitability"
                ) { suitability ->
                    if (!suitability.isNullOrBlank()) {
                        Text(
                            text = suitability,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            val selectedModelGroup = remember(state.models, state.selectedModelId) {
                state.models.modelChoiceGroups().firstOrNull { it.containsModel(state.selectedModelId) }
            }
            val scales = selectedModelGroup?.scales ?: state.selectedModel?.scales.orEmpty().ifEmpty { listOf(2) }
            SectionLabel("放大倍率")
            ParameterMorphContainer(morphKey = scales) {
                AnimatedSegmentedSelector(
                    items = scales,
                    selected = state.scale.takeIf { it in scales } ?: scales.first(),
                    itemLabel = { "${it}x" },
                    onSelected = { scale ->
                        val scaleModel = selectedModelGroup?.modelForScale(scale)
                        if (scaleModel != null && scaleModel.id != state.selectedModelId) {
                            onModelSelected(scaleModel.id)
                        }
                        onScaleChanged(scale)
                    }
                )
            }

            val noises = state.selectedModel
                ?.availableDenoiseForScale(state.scale)
                .orEmpty()
                .ifEmpty { listOf(0) }
            LaunchedEffect(noises, state.noise) {
                if (state.noise !in noises) {
                    onNoiseChanged(nearestNoiseLevel(noises, state.noise.toFloat()))
                }
            }
            if (noises.distinct().size > 1) {
                ParameterMorphContainer(morphKey = noises) {
                    NoiseSliderRow(
                        value = state.noise,
                        options = noises,
                        onNoiseChanged = onNoiseChanged
                    )
                }
            } else {
                DenoiseUnavailableRow()
            }

            SectionLabel("加速模式")
            AnimatedSegmentedSelector(
                items = AccelerationMode.entries,
                selected = state.accelerationMode,
                itemLabel = { it.accelerationLabel() },
                onSelected = onAccelerationChanged
            )

            SectionLabel("分块大小")
            AnimatedSegmentedSelector(
                items = TileSizeOptions,
                selected = sanitizeTileSize(state.tileSize),
                itemLabel = { it.toString() },
                onSelected = onTileSizeChanged
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("TTA 高质量", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("提升稳定性，会增加处理时间", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.tta,
                    enabled = state.selectedModel?.supportsTta == true,
                    onCheckedChange = onTtaChanged
                )
            }

            StartProcessingButton(
                label = state.startButtonLabel,
                enabled = state.canStartProcessing,
                onStart = onStart,
                modifier = Modifier.fillMaxWidth()
            )
        }
        }
    }
}

@Composable
private fun StartProcessingButton(
    label: String,
    enabled: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onStart,
        enabled = enabled,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun LumaUiState.parameterSubtitle(): String {
    return when {
        selectedImageCount > 1 -> "已选择 $selectedImageCount 张图片，将按当前参数批量处理"
        selectedImage != null -> selectedImage.displayName
        else -> "选择一张或多张图片后开始本地超分"
    }
}

internal object ParameterMorphMotion {
    const val compressedScaleX: Float = 0.94f
    const val settledScaleX: Float = 1f
    const val maxScaleX: Float = settledScaleX
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Composable
private fun ParameterMorphContainer(
    morphKey: Any?,
    content: @Composable () -> Unit
) {
    val scaleX = remember { Animatable(ParameterMorphMotion.settledScaleX) }
    LaunchedEffect(morphKey) {
        scaleX.snapTo(ParameterMorphMotion.compressedScaleX)
        scaleX.animateTo(
            targetValue = ParameterMorphMotion.settledScaleX,
            animationSpec = tween(durationMillis = 260, easing = ParameterMorphMotion.easing)
        )
    }

    Box(
        modifier = Modifier.graphicsLayer {
            this.scaleX = scaleX.value
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        }
    ) {
        content()
    }
}

internal fun nearestNoiseLevel(options: List<Int>, raw: Float): Int =
    options.sorted().ifEmpty { listOf(0) }.minBy { abs(it - raw) }

@Composable
private fun NoiseSliderRow(
    value: Int,
    options: List<Int>,
    onNoiseChanged: (Int) -> Unit
) {
    val safeOptions = options.sorted().ifEmpty { listOf(0) }
    val safeValue = nearestNoiseLevel(safeOptions, value.toFloat())
    val rangeStart = safeOptions.first().toFloat()
    val rangeEnd = if (safeOptions.first() == safeOptions.last()) {
        safeOptions.first() + 1f
    } else {
        safeOptions.last().toFloat()
    }
    var visualTarget by remember(safeOptions) { mutableFloatStateOf(safeValue.toFloat()) }
    LaunchedEffect(safeValue, safeOptions) {
        visualTarget = safeValue.toFloat()
    }
    val animatedValue by animateFloatAsState(
        targetValue = visualTarget.coerceIn(rangeStart, rangeEnd),
        animationSpec = spring(stiffness = 760f, dampingRatio = 0.84f),
        label = "noiseSliderValue"
    )

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("降噪等级", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Slider(
            value = animatedValue.coerceIn(rangeStart, rangeEnd),
            onValueChange = { raw ->
                val clamped = raw.coerceIn(rangeStart, rangeEnd)
                visualTarget = clamped
                onNoiseChanged(nearestNoiseLevel(safeOptions, clamped))
            },
            valueRange = rangeStart..rangeEnd,
            steps = if (safeOptions.first() == safeOptions.last()) 0 else (safeOptions.last() - safeOptions.first() - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f)
        )
        AnimatedContent(
            targetState = safeValue,
            transitionSpec = {
                (slideInVertically(tween(180)) { it } + fadeIn(tween(140))) togetherWith
                    (slideOutVertically(tween(140)) { -it } + fadeOut(tween(100)))
            },
            label = "noiseNumber"
        ) { number ->
            Text(number.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DenoiseUnavailableRow() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("降噪等级", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = "该模型没有独立降噪档位",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("0", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ModelCapsules(
    models: List<ModelPack>,
    selectedModelId: String?,
    selectedScale: Int,
    onModelSelected: (String) -> Unit
) {
    val groups = remember(models) { models.modelChoiceGroups() }
    val selectedIndex = groups.indexOfFirst { it.containsModel(selectedModelId) }.coerceAtLeast(0)
    val cardHeight = 66.dp
    val cardGap = 8.dp
    val indicatorEasing = CubicBezierEasing(0.22f, 0.86f, 0.28f, 1f)
    val darkTheme = isSystemInDarkTheme()

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - cardGap) / 2f
        val targetX = if (selectedIndex % 2 == 0) 0.dp else cardWidth + cardGap
        val targetY = (cardHeight + cardGap) * (selectedIndex / 2)
        val indicatorX by animateDpAsState(
            targetValue = targetX,
            animationSpec = tween(durationMillis = 260, easing = indicatorEasing),
            label = "modelIndicatorX"
        )
        val indicatorY by animateDpAsState(
            targetValue = targetY,
            animationSpec = tween(durationMillis = 260, easing = indicatorEasing),
            label = "modelIndicatorY"
        )
        val indicatorColor = if (darkTheme) {
            Color.White.copy(alpha = 0.74f)
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        Column(verticalArrangement = Arrangement.spacedBy(cardGap)) {
            groups.chunked(2).forEach { rowModels ->
                Row(horizontalArrangement = Arrangement.spacedBy(cardGap), modifier = Modifier.fillMaxWidth()) {
                    rowModels.forEach { group ->
                        ModelChoiceCard(
                            group = group,
                            selected = group.containsModel(selectedModelId),
                            onClick = {
                                val targetModel = group.modelForScale(selectedScale) ?: group.representative
                                onModelSelected(targetModel.id)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(cardHeight)
                        )
                    }
                    if (rowModels.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        if (groups.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .offset(x = indicatorX, y = indicatorY)
                    .width(cardWidth)
                    .height(cardHeight)
            ) {
                drawRoundRect(
                    color = indicatorColor,
                    size = size,
                    cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun ModelChoiceCard(
    group: ModelChoiceGroup,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val darkTheme = isSystemInDarkTheme()
    val container = modelCardContainerColor(selected = selected, darkTheme = darkTheme)
    val content = MaterialTheme.colorScheme.onSurface
    val supporting = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (darkTheme) 0.86f else 1f)

    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = container,
        tonalElevation = if (!darkTheme && selected) 2.dp else 0.dp,
        shadowElevation = if (!darkTheme && selected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${group.engineLabel} · ${group.scaleSummary()}",
                style = MaterialTheme.typography.labelMedium,
                color = supporting,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class ModelChoiceGroup(
    val key: String,
    val title: String,
    val engineLabel: String,
    val models: List<ModelPack>
) {
    val representative: ModelPack = models.first()
    val scales: List<Int> = models.flatMap { it.availableTargetScales() }.distinct().sorted()

    fun containsModel(modelId: String?): Boolean = models.any { it.id == modelId }

    fun modelForScale(scale: Int): ModelPack? {
        models.firstOrNull { scale in it.availableNativePassScales() }?.let { return it }
        models.firstOrNull { scale in it.availableTargetScales() }?.let { return it }
        return when {
            scale == 16 -> models.firstOrNull { 4 in it.scales }
            scale == 9 -> models.firstOrNull { 3 in it.scales }
            scale == 8 || scale == 4 -> models.firstOrNull { 2 in it.scales }
            else -> null
        }
    }

    fun scaleSummary(): String =
        if (scales.size >= 4) {
            scales.joinToString("/") + "x"
        } else {
            scales.joinToString(" / ") { "${it}x" }
        }
}

private fun List<ModelPack>.modelChoiceGroups(): List<ModelChoiceGroup> =
    groupBy { it.modelChoiceKey() }
        .values
        .map { groupedModels ->
            val sortedModels = groupedModels.sortedBy { it.defaultScale }
            val representative = sortedModels.first()
            ModelChoiceGroup(
                key = representative.modelChoiceKey(),
                title = representative.groupTitle(),
                engineLabel = representative.engineLabel(),
                models = sortedModels
            )
        }

private fun ModelPack.modelChoiceKey(): String =
    if (displayName.startsWith("AnimeVideo v3", ignoreCase = true)) {
        "realesrgan-animevideo-v3"
    } else {
        id
    }

private fun ModelPack.groupTitle(): String =
    if (displayName.startsWith("AnimeVideo v3", ignoreCase = true)) {
        "视频"
    } else {
        shortModelTitle()
    }

private fun ModelPack.engineLabel(): String =
    when (engine.name) {
        "WAIFU2X" -> "Waifu2x"
        "REAL_CUGAN" -> "RealCUGAN"
        "REAL_ESRGAN" -> "Real-ESRGAN"
        else -> engine.name
    }

internal fun ModelPack.suitabilityText(): String? {
    val sceneText = scenes
        .mapNotNull { it.sceneLabel() }
        .distinct()
        .joinToString("、")
    val content = sceneText.ifBlank { description.trim() }
    return content.takeIf { it.isNotBlank() }?.let { "适用于：$it" }
}

private fun String.sceneLabel(): String? {
    val normalized = trim().lowercase()
    if (normalized.isBlank()) return null
    return when (normalized) {
        "illustration" -> "插画"
        "anime" -> "动漫"
        "screenshot" -> "截图"
        "photo" -> "照片"
        "artifact_repair" -> "压缩修复"
        "video_frame" -> "视频帧"
        else -> normalized.replace('_', ' ')
    }
}

@Composable
private fun <T> AnimatedSegmentedSelector(
    items: List<T>,
    selected: T,
    itemLabel: (T) -> String,
    onSelected: (T) -> Unit,
    height: Dp = 52.dp
) {
    if (items.isEmpty()) return

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val itemWidth = maxWidth / items.size.toFloat()
        val selectedIndex = items.indexOf(selected).coerceAtLeast(0)
        val indicatorOffset by animateDpAsState(
            targetValue = itemWidth * selectedIndex,
            animationSpec = spring(stiffness = 520f, dampingRatio = 0.84f),
            label = "segmentedIndicator"
        )

        Surface(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(itemWidth)
                .fillMaxHeight()
                .padding(4.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {}

        Row(modifier = Modifier.fillMaxSize()) {
            items.forEach { item ->
                val active = item == selected
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelected(item) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = itemLabel(item),
                        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewImageCarousel(
    images: List<SelectedImageInfo>,
    currentIndex: Int,
    onIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val safeIndex = currentIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))
    val slideOffset = remember { Animatable(0f) }
    val animationEasing = CubicBezierEasing(0.22f, 0.86f, 0.28f, 1f)
    val scope = rememberCoroutineScope()

    LaunchedEffect(images.size, safeIndex) {
        slideOffset.snapTo(0f)
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        val pageWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val dragModifier = if (images.size > 1) {
            Modifier.pointerInput(images.size, safeIndex, constraints.maxWidth) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        scope.launch { slideOffset.stop() }
                    },
                    onDragEnd = {
                        val threshold = pageWidth * 0.18f
                        val targetIndex = when {
                            slideOffset.value < -threshold && safeIndex < images.lastIndex -> safeIndex + 1
                            slideOffset.value > threshold && safeIndex > 0 -> safeIndex - 1
                            else -> safeIndex
                        }
                        scope.launch {
                            if (targetIndex == safeIndex) {
                                slideOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(durationMillis = 220, easing = animationEasing)
                                )
                            } else {
                                val exitOffset = if (targetIndex > safeIndex) -pageWidth else pageWidth
                                slideOffset.animateTo(
                                    targetValue = exitOffset,
                                    animationSpec = tween(durationMillis = 240, easing = animationEasing)
                                )
                                onIndexChanged(targetIndex)
                                slideOffset.snapTo(0f)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch {
                            slideOffset.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 180, easing = animationEasing)
                            )
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val rawOffset = slideOffset.value + dragAmount
                        val edgeDampedOffset = when {
                            safeIndex == 0 && rawOffset > 0f -> rawOffset * 0.38f
                            safeIndex == images.lastIndex && rawOffset < 0f -> rawOffset * 0.38f
                            else -> rawOffset
                        }
                        scope.launch {
                            slideOffset.snapTo(edgeDampedOffset.coerceIn(-pageWidth, pageWidth))
                        }
                    }
                )
            }
        } else {
            Modifier
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(dragModifier),
            color = previewSurfaceColor(),
            tonalElevation = if (isSystemInDarkTheme()) 0.dp else 1.dp
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (images.isEmpty()) {
                    CleanImagePlaceholder()
                } else {
                    ((safeIndex - 1)..(safeIndex + 1))
                        .filter { it in images.indices }
                        .forEach { pageIndex ->
                            PreviewImagePage(
                                path = images[pageIndex].sourceUri,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset {
                                        IntOffset(
                                            x = (slideOffset.value + (pageIndex - safeIndex) * pageWidth).roundToInt(),
                                            y = 0
                                        )
                                    }
                            )
                        }
                }

                if (images.size > 1) {
                    PhotoIndexBadge(
                        currentIndex = safeIndex,
                        totalCount = images.size,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewImagePage(
    path: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            decodePreviewImage(context, path)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (image == null) {
            CleanImagePlaceholder()
        } else {
            Image(
                bitmap = image!!,
                contentDescription = "待处理图片",
                modifier = Modifier
                    .fillMaxSize()
                    .background(previewImageBackgroundColor()),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun PhotoIndexBadge(
    currentIndex: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = floatingChipColor(),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Text(
            text = "${currentIndex + 1} / $totalCount",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PreviewImageSurface(
    path: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.(ImageBitmap?) -> Unit = {}
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            decodePreviewImage(context, path)
        }
    }
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Surface(
        modifier = modifier.then(clickModifier),
        color = previewSurfaceColor(),
        tonalElevation = if (isSystemInDarkTheme()) 0.dp else 1.dp
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (image == null) {
                CleanImagePlaceholder()
            } else {
                Image(
                    bitmap = image!!,
                    contentDescription = "待处理图片",
                    modifier = Modifier
                        .fillMaxSize()
                        .background(previewImageBackgroundColor()),
                    contentScale = ContentScale.Fit
                )
            }
            overlay(image)
        }
    }
}

@Composable
private fun CleanImagePlaceholder() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(999.dp), color = placeholderPillColor()) {
            Text(
                "选择图片",
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            "点击预览区域",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun parameterSheetColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else MaterialTheme.colorScheme.surface

@Composable
private fun iconButtonContainerColor(): Color =
    if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant

private fun modelCardContainerColor(selected: Boolean, darkTheme: Boolean): Color =
    if (darkTheme) {
        if (selected) Color(0xFF2A2A2C) else Color(0xFF242426)
    } else {
        if (selected) Color.White else Color(0xFFF5F5F5).copy(alpha = 0.72f)
    }

@Composable
private fun previewSurfaceColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF1A1A1C) else MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun previewImageBackgroundColor(): Color =
    if (isSystemInDarkTheme()) Color.Black.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.04f)

@Composable
private fun floatingChipColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF2B2B2D).copy(alpha = 0.92f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)

@Composable
private fun placeholderPillColor(): Color =
    if (isSystemInDarkTheme()) Color.Black.copy(alpha = 0.34f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)

@Composable
private fun RenderTileOverlay(
    progress: UpscaleProgress,
    previewImage: ImageBitmap,
    modifier: Modifier = Modifier
) {
    val visualState = remember(progress.stage, progress.currentTile, progress.totalTiles, progress.completedTileIndexes) {
        calculateRenderTileVisualState(progress)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = visualState.completedRatio,
        animationSpec = tween(220),
        label = "tileProgress"
    )
    val pulse by rememberInfiniteTransition(label = "tilePulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "activeTilePulse"
    )
    val pendingOverlay = MaterialTheme.colorScheme.surface.copy(alpha = 0.30f)
    val gridLine = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)
    val strokeColor = MaterialTheme.colorScheme.primary
    val scanLineColor = Color.White.copy(alpha = 0.22f)

    Canvas(modifier = modifier) {
        val totalTiles = progress.totalTiles.coerceAtLeast(1)
        val viewport = calculateRenderImageViewport(
            canvasWidth = size.width,
            canvasHeight = size.height,
            imageWidth = previewImage.width,
            imageHeight = previewImage.height
        )
        val tileRects = buildRenderTileRects(viewport = viewport, totalTiles = totalTiles)
        val grid = calculateRenderTileGrid(totalTiles, viewport.width, viewport.height)
        val dstOffset = IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt())
        val dstSize = IntSize(viewport.width.roundToInt(), viewport.height.roundToInt())
        val completedCutoff = (animatedProgress * totalTiles).roundToInt().coerceIn(0, totalTiles)

        // Pending tiles use one soft layer over the same image; completed tiles reveal the original preview.
        drawRect(
            color = pendingOverlay,
            topLeft = Offset(viewport.left, viewport.top),
            size = Size(viewport.width, viewport.height)
        )

        tileRects
            .filter { it.index <= completedCutoff || progress.completedTileIndexes.contains(it.index) }
            .forEach { tile ->
                clipRect(left = tile.left, top = tile.top, right = tile.right, bottom = tile.bottom) {
                    drawImage(
                        image = previewImage,
                        dstOffset = dstOffset,
                        dstSize = dstSize
                    )
                }
            }

        for (column in 1 until grid.columns) {
            val x = viewport.left + viewport.width * column / grid.columns
            drawLine(
                color = gridLine,
                start = Offset(x, viewport.top),
                end = Offset(x, viewport.bottom),
                strokeWidth = 0.38.dp.toPx()
            )
        }
        for (row in 1 until grid.rows) {
            val y = viewport.top + viewport.height * row / grid.rows
            drawLine(
                color = gridLine,
                start = Offset(viewport.left, y),
                end = Offset(viewport.right, y),
                strokeWidth = 0.38.dp.toPx()
            )
        }

        val activeIndex = if (progress.stage == UpscaleStage.PROCESSING_TILE && completedCutoff < totalTiles) {
            completedCutoff + 1
        } else {
            visualState.activeIndex
        }
        if (activeIndex != null) {
            tileRects.firstOrNull { it.index == activeIndex }?.let { active ->
                drawRoundRect(
                    color = strokeColor.copy(alpha = 0.24f + pulse * 0.36f),
                    topLeft = Offset(active.left + 1.5.dp.toPx(), active.top + 1.5.dp.toPx()),
                    size = Size(active.width - 3.dp.toPx(), active.height - 3.dp.toPx()),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.1.dp.toPx())
                )
                drawLine(
                    color = scanLineColor,
                    start = Offset(active.left + 4.dp.toPx(), active.top + active.height * 0.52f),
                    end = Offset(active.right - 4.dp.toPx(), active.top + active.height * 0.52f),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}

@Composable
private fun RenderStatusStrip(
    progress: UpscaleProgress,
    batchIndex: Int,
    batchSize: Int,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${(progress.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (batchSize > 1 && batchIndex > 0) {
                            "${progress.stage.stageLabel()} · $batchIndex/$batchSize"
                        } else {
                            progress.stage.stageLabel()
                        },
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        progress.message.toReadableProgress(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("取消")
                }
            }
            LinearProgressIndicator(
                progress = { progress.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            )
        }
    }
}

@Composable
fun CompareScreenV2(
    state: LumaUiState,
    onSave: () -> Unit,
    onBackHome: () -> Unit
) {
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
    var previewIndex by remember(results) { mutableIntStateOf(0) }
    val safePreviewIndex = previewIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    LaunchedEffect(results.size) {
        previewIndex = previewIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    }
    val currentResult = results.getOrNull(safePreviewIndex)
    val savedCount = state.savedOutputUris.size.coerceAtLeast(if (state.savedOutputUri != null) 1 else 0)
    val animationKey = remember(results) { results.joinToString(separator = "|") { it.taskId } }
    var contentVisible by remember(animationKey) { mutableStateOf(false) }
    var saveCardPhase by remember(animationKey) { mutableStateOf(initialResultSaveCardPhase(savedCount)) }
    var observedResultsKey by remember { mutableStateOf(animationKey) }
    var observedSavedCount by remember { mutableIntStateOf(savedCount) }

    LaunchedEffect(animationKey) {
        contentVisible = false
        delay(60)
        contentVisible = true
    }
    LaunchedEffect(animationKey, savedCount) {
        val nextPhase = resultSaveCardPhaseAfterStateChange(
            currentPhase = saveCardPhase,
            previousResultsKey = observedResultsKey,
            resultsKey = animationKey,
            previousSavedCount = observedSavedCount,
            savedCount = savedCount
        )
        saveCardPhase = nextPhase
        observedResultsKey = animationKey
        observedSavedCount = savedCount
        if (nextPhase == ResultSaveCardPhase.SavedFlash) {
            delay(ResultSaveFlashDurationMillis)
            saveCardPhase = settleResultSaveCardPhase(saveCardPhase)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(260)) +
                scaleIn(initialScale = 0.985f, animationSpec = tween(280, easing = ParameterMorphMotion.easing)) +
                slideInVertically(tween(280, easing = ParameterMorphMotion.easing)) { it / 20 },
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, top = 14.dp, end = 14.dp, bottom = 150.dp)
                .clip(RoundedCornerShape(30.dp))
        ) {
            ResultPreviewCarousel(
                results = results,
                currentIndex = safePreviewIndex,
                onIndexChanged = { previewIndex = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = contentVisible,
            enter = slideInVertically(tween(320, easing = ParameterMorphMotion.easing)) { it / 2 } +
                fadeIn(tween(220)) +
                scaleIn(initialScale = 0.965f, animationSpec = tween(320, easing = ParameterMorphMotion.easing)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 8.dp,
                shadowElevation = 10.dp
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnimatedContent(
                        targetState = resultSaveCardText(
                            phase = saveCardPhase,
                            result = currentResult,
                            resultCount = results.size,
                            resultIndex = safePreviewIndex,
                            savedCount = savedCount
                        ),
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 4 }) togetherWith
                                (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 4 })
                        },
                        label = "resultSaveCardText"
                    ) { text ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = text.first,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = text.second,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = saveCardPhase,
                        transitionSpec = {
                            (fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 5 }) togetherWith
                                (fadeOut(tween(120)) + slideOutVertically(tween(120)) { -it / 5 })
                        },
                        label = "resultSaveCardActions"
                    ) { phase ->
                        val labels = resultSaveCardButtonLabels(phase)
                        if (labels.size == 2) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = onSave,
                                    enabled = results.isNotEmpty(),
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(labels[0])
                                }
                                OutlinedButton(
                                    onClick = onBackHome,
                                    modifier = Modifier.weight(1f).height(50.dp),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(labels[1])
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = onBackHome,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(labels.single())
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resultSaveCardText(
    phase: ResultSaveCardPhase,
    result: RenderResultInfo?,
    resultCount: Int,
    resultIndex: Int,
    savedCount: Int
): Pair<String, String> {
    return if (phase == ResultSaveCardPhase.SavedFlash) {
        "图片已保存" to savedResultSubtitle(savedCount)
    } else {
        "本地处理完成" to resultSummarySubtitle(result, resultCount, resultIndex, savedCount)
    }
}

private fun savedResultSubtitle(savedCount: Int): String {
    return if (savedCount > 1) {
        "已保存 $savedCount 张到 Pictures/LocalSR"
    } else {
        "已保存到 Pictures/LocalSR"
    }
}

private fun resultSummarySubtitle(
    result: RenderResultInfo?,
    resultCount: Int,
    resultIndex: Int,
    savedCount: Int
): String {
    return result?.let {
        buildString {
            append("${it.modelName} · ${it.scale}x · 降噪 ${it.noise}")
            if (resultCount > 1) append(" · ${resultIndex + 1}/$resultCount")
            if (savedCount > 0) append(" · 已保存 $savedCount 张")
        }
    } ?: "没有可预览的输出"
}

@Composable
private fun ResultPreviewCarousel(
    results: List<RenderResultInfo>,
    currentIndex: Int,
    onIndexChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeIndex = currentIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    val slideOffset = remember { Animatable(0f) }
    val animationEasing = CubicBezierEasing(0.22f, 0.86f, 0.28f, 1f)
    val scope = rememberCoroutineScope()

    LaunchedEffect(results.size, safeIndex) {
        slideOffset.snapTo(0f)
    }

    BoxWithConstraints(
        modifier = modifier.clipToBounds()
    ) {
        val pageWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val dragModifier = if (results.size > 1) {
            Modifier.pointerInput(results.size, safeIndex, constraints.maxWidth) {
                detectHorizontalDragGestures(
                    onDragStart = { scope.launch { slideOffset.stop() } },
                    onDragEnd = {
                        val threshold = pageWidth * 0.18f
                        val targetIndex = when {
                            slideOffset.value < -threshold && safeIndex < results.lastIndex -> safeIndex + 1
                            slideOffset.value > threshold && safeIndex > 0 -> safeIndex - 1
                            else -> safeIndex
                        }
                        scope.launch {
                            if (targetIndex == safeIndex) {
                                slideOffset.animateTo(0f, tween(220, easing = animationEasing))
                            } else {
                                val exitOffset = if (targetIndex > safeIndex) -pageWidth else pageWidth
                                slideOffset.animateTo(exitOffset, tween(240, easing = animationEasing))
                                onIndexChanged(targetIndex)
                                slideOffset.snapTo(0f)
                            }
                        }
                    },
                    onDragCancel = {
                        scope.launch { slideOffset.animateTo(0f, tween(180, easing = animationEasing)) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val rawOffset = slideOffset.value + dragAmount
                        val edgeDampedOffset = when {
                            safeIndex == 0 && rawOffset > 0f -> rawOffset * 0.38f
                            safeIndex == results.lastIndex && rawOffset < 0f -> rawOffset * 0.38f
                            else -> rawOffset
                        }
                        scope.launch {
                            slideOffset.snapTo(edgeDampedOffset.coerceIn(-pageWidth, pageWidth))
                        }
                    }
                )
            }
        } else {
            Modifier
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .then(dragModifier),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (results.isEmpty()) {
                    CleanImagePlaceholder()
                } else {
                    ((safeIndex - 1)..(safeIndex + 1))
                        .filter { it in results.indices }
                        .forEach { pageIndex ->
                            ResultPreviewPage(
                                result = results[pageIndex],
                                modifier = Modifier
                                    .fillMaxSize()
                                    .offset {
                                        IntOffset(
                                            x = (slideOffset.value + (pageIndex - safeIndex) * pageWidth).roundToInt(),
                                            y = 0
                                        )
                                    }
                            )
                        }
                }

                if (results.size > 1) {
                    PhotoIndexBadge(
                        currentIndex = safeIndex,
                        totalCount = results.size,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPreviewPage(
    result: RenderResultInfo,
    modifier: Modifier = Modifier
) {
    val path = remember(result.inputPath) { result.inputPath }
    PreviewImagePage(
        path = path,
        modifier = modifier
    )
}

@Composable
private fun CompareImageSurface(inputPath: String?, outputPath: String?, modifier: Modifier) {
    val context = LocalContext.current
    val inputImage by produceState<ImageBitmap?>(initialValue = null, inputPath) {
        value = withContext(Dispatchers.IO) {
            decodePreviewImage(context, inputPath)
        }
    }
    val outputImage by produceState<ImageBitmap?>(initialValue = null, outputPath) {
        value = withContext(Dispatchers.IO) {
            decodePreviewImage(context, outputPath)
        }
    }
    val split by animateFloatAsState(targetValue = 0.5f, animationSpec = tween(220), label = "compareSplit")
    val inputBitmap = inputImage
    val outputBitmap = outputImage
    val baseImage = outputBitmap ?: inputBitmap
    val canCompare = inputBitmap != null && outputBitmap != null

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (baseImage == null) {
                CleanImagePlaceholder()
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val referenceImage = inputBitmap ?: baseImage
                    val viewport = calculateRenderImageViewport(
                        canvasWidth = size.width,
                        canvasHeight = size.height,
                        imageWidth = referenceImage.width,
                        imageHeight = referenceImage.height
                    )
                    val dstOffset = IntOffset(viewport.left.roundToInt(), viewport.top.roundToInt())
                    val dstSize = IntSize(viewport.width.roundToInt(), viewport.height.roundToInt())
                    val resultImage = outputBitmap ?: baseImage

                    drawImage(
                        image = resultImage,
                        dstOffset = dstOffset,
                        dstSize = dstSize
                    )

                    if (canCompare && inputBitmap != null) {
                        val splitX = viewport.left + viewport.width * split.coerceIn(0f, 1f)
                        clipRect(
                            left = viewport.left,
                            top = viewport.top,
                            right = splitX,
                            bottom = viewport.bottom
                        ) {
                            drawImage(
                                image = inputBitmap,
                                dstOffset = dstOffset,
                                dstSize = dstSize
                            )
                        }
                        drawLine(
                            color = Color.White.copy(alpha = 0.92f),
                            start = Offset(splitX, viewport.top),
                            end = Offset(splitX, viewport.bottom),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
            }

            if (outputPath != null && outputBitmap == null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    tonalElevation = 2.dp
                ) {
                    Text(
                        text = "结果预览不可用",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (canCompare) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                        .width(maxWidth * 0.74f)
                        .height(4.dp),
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                ) {}
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .size(26.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 5.dp
                ) {}
            }
        }
    }
}

private fun decodePreviewImage(context: Context, rawPath: String?, maxDimension: Int = 1800): ImageBitmap? {
    if (rawPath.isNullOrBlank()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openPreviewInputStream(context, rawPath)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sampleSize = PreviewImageSampler.calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = openPreviewInputStream(context, rawPath)?.use { BitmapFactory.decodeStream(it, null, options) }
    return bitmap?.asImageBitmap()
}

private fun openPreviewInputStream(context: Context, rawPath: String): InputStream? {
    val uri = runCatching { Uri.parse(rawPath) }.getOrNull()
    return runCatching {
        when (uri?.scheme) {
            ContentResolver.SCHEME_CONTENT,
            ContentResolver.SCHEME_ANDROID_RESOURCE -> context.contentResolver.openInputStream(uri)
            ContentResolver.SCHEME_FILE -> File(uri.path.orEmpty()).inputStream()
            null, "" -> File(rawPath).takeIf { it.exists() }?.inputStream()
            else -> File(rawPath).takeIf { it.exists() }?.inputStream()
                ?: context.contentResolver.openInputStream(uri)
        }
    }.getOrNull()
}

@Composable
private fun rememberImageBatchPicker(onPickImages: (List<Uri>) -> Unit) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50),
    onResult = { uris ->
        if (uris.isNotEmpty()) {
            onPickImages(uris)
        }
    }
)

private fun String.shortModelTitle(): String {
    return when (lowercase()) {
        "cunet" -> "CUnet"
        "anime" -> "动漫"
        "photo" -> "旧图"
        "standard" -> "标准"
        "pro" -> "锐化"
        else -> this
    }
}

private fun AccelerationMode.accelerationLabel(): String {
    return when (this) {
        AccelerationMode.AUTO -> "GPU"
        AccelerationMode.VULKAN -> "Vulkan"
        AccelerationMode.CPU -> "CPU"
    }
}

private fun UpscaleStage.stageLabel(): String {
    return when (this) {
        UpscaleStage.PREPARING -> "准备中"
        UpscaleStage.ANALYZING -> "分析图片"
        UpscaleStage.LOADING_MODEL -> "加载模型"
        UpscaleStage.PROCESSING_TILE -> "分块推理"
        UpscaleStage.STITCHING -> "拼接结果"
        UpscaleStage.SAVING -> "保存输出"
        UpscaleStage.DONE -> "处理完成"
        UpscaleStage.FAILED -> "处理失败"
        UpscaleStage.CANCELLED -> "已取消"
    }
}

private fun String.toReadableProgress(): String {
    return when {
        contains("Native inference complete", ignoreCase = true) -> "本地推理完成"
        contains("Saved to Pictures", ignoreCase = true) -> "已保存到 Pictures/LocalSR"
        contains("Preparing", ignoreCase = true) -> "准备本地推理"
        contains("Decoding", ignoreCase = true) -> "正在读取图片"
        contains("Loading", ignoreCase = true) -> "正在加载模型"
        contains("Processing tile", ignoreCase = true) -> replace("Processing tile", "正在处理分块")
        contains("Stitching", ignoreCase = true) -> "正在拼接结果"
        contains("Saving", ignoreCase = true) -> "正在保存输出"
        contains("cancelled", ignoreCase = true) -> "任务已取消"
        contains("failed", ignoreCase = true) -> replace("failed", "失败")
        else -> this
    }
}
