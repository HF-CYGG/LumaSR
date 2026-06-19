package com.lumasr.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.ModelPack
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import java.io.File
import java.util.Locale
import kotlin.math.abs

@Composable
fun LumaApp(viewModel: LumaViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Scaffold { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding)
            ) {
                when (state.screen) {
                    LumaScreen.HOME -> HomeScreen(
                        state = state,
                        onPickImage = viewModel::onImageSelected,
                        onModelSelected = viewModel::selectModel,
                        onScaleChanged = viewModel::setScale,
                        onNoiseChanged = viewModel::setNoise,
                        onAccelerationChanged = viewModel::setAccelerationMode,
                        onTtaChanged = viewModel::setTta,
                        onStart = viewModel::startProcessing
                    )

                    LumaScreen.PROCESSING -> ProcessingScreen(
                        progress = state.progress,
                        onCancel = viewModel::cancelProcessing
                    )

                    LumaScreen.COMPARE -> CompareScreen(
                        state = state,
                        onSave = viewModel::saveResultToGallery,
                        onBackHome = viewModel::backHome
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: LumaUiState,
    onPickImage: (Uri) -> Unit,
    onModelSelected: (String) -> Unit,
    onScaleChanged: (Int) -> Unit,
    onNoiseChanged: (Int) -> Unit,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTtaChanged: (Boolean) -> Unit,
    onStart: () -> Unit
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(onPickImage) }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Header()
        ImageInputCard(
            image = state.selectedImage,
            onPick = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        )
        ModelSection(
            models = state.models,
            selectedModelId = state.selectedModelId,
            onModelSelected = onModelSelected
        )
        ParameterSection(
            state = state,
            onScaleChanged = onScaleChanged,
            onNoiseChanged = onNoiseChanged,
            onAccelerationChanged = onAccelerationChanged,
            onTtaChanged = onTtaChanged
        )
        state.resultMessage?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = onStart,
            enabled = state.selectedImage != null && state.selectedModel != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start enhancement")
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "LumaSR",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Local AI super-resolution for Android, prepared for ncnn and Vulkan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImageInputCard(
    image: SelectedImageInfo?,
    onPick: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Source image", style = MaterialTheme.typography.titleMedium)
            if (image == null) {
                Text(
                    "No image selected. Use Android Photo Picker to keep storage access scoped.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                ImagePreview(
                    location = image.sourceUri,
                    contentDescription = "Selected source image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Text(image.displayName, fontWeight = FontWeight.Medium)
                Text(
                    text = listOfNotNull(
                        image.dimensionsText(),
                        formatBytes(image.sizeBytes),
                        image.mimeType ?: "image"
                    ).joinToString(" | "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = onPick) {
                Text(if (image == null) "Choose image" else "Change image")
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelSection(
    models: List<ModelPack>,
    selectedModelId: String?,
    onModelSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Enhancement engine", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { model ->
                FilterChip(
                    selected = model.id == selectedModelId,
                    onClick = { onModelSelected(model.id) },
                    label = { Text("${model.engine.name.lowercase().replace('_', ' ')} | ${model.displayName}") }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ParameterSection(
    state: LumaUiState,
    onScaleChanged: (Int) -> Unit,
    onNoiseChanged: (Int) -> Unit,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTtaChanged: (Boolean) -> Unit
) {
    val model = state.selectedModel
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Parameters", style = MaterialTheme.typography.titleMedium)
        model?.description?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ParameterSlider("Scale", state.scale, model?.scales ?: listOf(1, 2), onScaleChanged)
        ParameterSlider("Denoise", state.noise, model?.denoise ?: listOf(0, 1, 2), onNoiseChanged)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccelerationMode.entries.forEach { mode ->
                FilterChip(
                    selected = state.accelerationMode == mode,
                    onClick = { onAccelerationChanged(mode) },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.titlecase(Locale.ROOT) }) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TTA high quality", modifier = Modifier.weight(1f))
            Switch(
                checked = state.tta,
                enabled = model?.supportsTta == true,
                onCheckedChange = onTtaChanged
            )
        }
    }
}

@Composable
private fun ParameterSlider(
    title: String,
    value: Int,
    range: List<Int>,
    onChanged: (Int) -> Unit
) {
    val sorted = range.distinct().sorted()
    if (sorted.isEmpty()) return
    val min = sorted.first()
    val max = sorted.last()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(title, modifier = Modifier.weight(1f))
            Text(value.toString(), fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val nearest = sorted.minBy { abs(it - raw.toInt()) }
                onChanged(nearest)
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0)
        )
    }
}

@Composable
private fun ProcessingScreen(
    progress: UpscaleProgress?,
    onCancel: () -> Unit
) {
    val current = progress ?: UpscaleProgress(
        taskId = "",
        stage = UpscaleStage.PREPARING,
        progress = 0f,
        currentTile = 0,
        totalTiles = 16,
        completedTileIndexes = emptySet(),
        message = "Preparing",
        estimatedRemainingMs = null
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Processing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(current.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
        Text("${(current.progress * 100).toInt()}% | ${current.stage.name}")
        TileGrid(progress = current, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

@Composable
private fun TileGrid(progress: UpscaleProgress, modifier: Modifier = Modifier) {
    val tiles = (1..progress.totalTiles).toList()
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tiles) { tile ->
            val done = tile in progress.completedTileIndexes
            Box(
                modifier = Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tile.toString(),
                    color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CompareScreen(
    state: LumaUiState,
    onSave: () -> Unit,
    onBackHome: () -> Unit
) {
    val context = LocalContext.current
    val params = state.activeParams
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Before / After", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        ComparePreview(
            inputPath = params?.inputPath,
            outputPath = params?.outputPath
        )
        Text(
            text = state.resultMessage ?: "Mock comparison ready",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        params?.let {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Run summary", style = MaterialTheme.typography.titleMedium)
                    Text("${it.modelName} | ${it.scale}x | denoise ${it.noise}")
                    Text("${it.engine} | ${it.accelerationMode} | ${it.outputFormat}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSave, enabled = params != null, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            OutlinedButton(
                onClick = {
                    state.savedOutputUri?.let { rawUri ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(rawUri))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share LumaSR result"))
                    }
                },
                enabled = state.savedOutputUri != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Share")
            }
        }
        ElevatedButton(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
            Text("Back to Home")
        }
    }
}

@Composable
private fun ComparePreview(
    inputPath: String?,
    outputPath: String?
) {
    var split by remember { mutableFloatStateOf(0.5f) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.fillMaxSize()) {
                PreviewPane("Before", inputPath, MaterialTheme.colorScheme.surfaceVariant, Modifier.weight(split))
                PreviewPane("After", outputPath, MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f - split))
            }
        }
        Slider(value = split, onValueChange = { split = it.coerceIn(0.1f, 0.9f) })
        Text("Drag the slider to compare cached input and output.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewPane(label: String, path: String?, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        ImagePreview(path, label, Modifier.fillMaxSize())
        Text(label, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ImagePreview(
    location: String?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(location) {
        location?.let { raw ->
            runCatching {
                val file = File(raw)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    context.contentResolver.openInputStream(Uri.parse(raw))?.use(BitmapFactory::decodeStream)
                }
            }.getOrNull()?.asImageBitmap()
        }
    }
    if (bitmap == null) {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("Preview unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

private fun SelectedImageInfo.dimensionsText(): String? {
    val w = width ?: return null
    val h = height ?: return null
    return "${w}x$h"
}

private fun formatBytes(sizeBytes: Long?): String? {
    if (sizeBytes == null || sizeBytes < 0) return null
    val kb = sizeBytes / 1024f
    return if (kb < 1024f) {
        String.format(Locale.US, "%.1f KB", kb)
    } else {
        String.format(Locale.US, "%.1f MB", kb / 1024f)
    }
}
