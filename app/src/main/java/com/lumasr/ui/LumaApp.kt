package com.lumasr.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
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
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.UpscaleProgress
import com.lumasr.domain.UpscaleStage
import java.io.File
import java.util.Locale
import kotlin.math.abs

@Composable
fun LumaApp(viewModel: LumaViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF82B1FF),
            secondary = Color(0xFF73D7B2),
            tertiary = Color(0xFFFFC46B),
            background = Color(0xFF101318),
            surface = Color(0xFF171B22),
            surfaceVariant = Color(0xFF242A33),
            primaryContainer = Color(0xFF163A68),
            secondaryContainer = Color(0xFF123D33)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0F6BFF),
            secondary = Color(0xFF087F5B),
            tertiary = Color(0xFFE68619),
            background = Color(0xFFF6F8FC),
            surface = Color.White,
            surfaceVariant = Color(0xFFEAF0F8),
            primaryContainer = Color(0xFFDDEBFF),
            secondaryContainer = Color(0xFFDDF7EC)
        )
    }
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
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
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
            StatusBanner(text = it)
        }
        Button(
            onClick = onStart,
            enabled = state.selectedImage != null && state.selectedModel != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("开始超分处理", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("SR", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "LumaSR",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "本地 AI 图像超分",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            InfoPill("离线")
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "本地超分，一步完成。",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "内置 Waifu2x 与 RealCUGAN 模型，支持 ncnn、Vulkan/CPU 和实时分块进度。",
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricChip("5", "模型", Modifier.weight(1f))
                    MetricChip("0", "上传", Modifier.weight(1f))
                    MetricChip("Tile", "预览", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MetricChip(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusBanner(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (text.contains("fail", ignoreCase = true) || text.contains("失败")) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    ) {
        Text(
            text.toChineseProgress(),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmptyImageVisual() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(148.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("IMG", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text("选择一张图片开始", fontWeight = FontWeight.SemiBold)
            Text("支持系统相册图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImageInputCard(
    image: SelectedImageInfo?,
    onPick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(title = "源图片", subtitle = "使用系统照片选择器，权限范围更小")
            if (image == null) {
                EmptyImageVisual()
            } else {
                ImagePreview(
                    location = image.sourceUri,
                    contentDescription = "已选择的源图片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(image.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = listOfNotNull(
                            image.dimensionsText(),
                            formatBytes(image.sizeBytes),
                            image.mimeType ?: "image"
                        ).joinToString("  ·  "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            OutlinedButton(
                onClick = onPick,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (image == null) "选择图片" else "更换图片")
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "模型引擎", subtitle = "按图片类型选择合适的增强模型")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            models.forEach { model ->
                ModelCard(
                    model = model,
                    selected = model.id == selectedModelId,
                    onClick = { onModelSelected(model.id) }
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelPack,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = if (model.engine == SuperResEngine.REAL_CUGAN) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Card(
        modifier = Modifier
            .width(154.dp)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    model.engine.shortLabel(),
                    color = accent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            Text(model.displayName.modelTitleCn(), fontWeight = FontWeight.Bold)
            Text(model.engine.labelCn(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${model.defaultScale}x · 降噪 ${model.defaultNoise}",
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                fontWeight = FontWeight.SemiBold
            )
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
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader(title = "处理参数", subtitle = model?.description?.toChineseDescription() ?: "选择模型后可调节细节")
            ParameterSlider("放大倍率", "${state.scale}x", state.scale, model?.scales ?: listOf(1, 2), onScaleChanged)
            ParameterSlider("降噪强度", state.noise.toString(), state.noise, model?.denoise ?: listOf(0, 1, 2), onNoiseChanged)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("加速模式", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccelerationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.accelerationMode == mode,
                            onClick = { onAccelerationChanged(mode) },
                            label = { Text(mode.labelCn()) }
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("TTA 高质量", fontWeight = FontWeight.SemiBold)
                    Text("画质更稳，耗时更长", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = state.tta,
                    enabled = model?.supportsTta == true,
                    onCheckedChange = onTtaChanged
                )
            }
        }
    }
}

@Composable
private fun ParameterSlider(
    title: String,
    valueText: String,
    value: Int,
    range: List<Int>,
    onChanged: (Int) -> Unit
) {
    val sorted = range.distinct().sorted()
    if (sorted.isEmpty()) return
    val min = sorted.first()
    val max = sorted.last()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(valueText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
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
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("正在处理", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "${(current.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(current.stage.labelCn(), fontWeight = FontWeight.SemiBold)
                Text(current.message.toChineseProgress(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { current.progress }, modifier = Modifier.fillMaxWidth())
            }
        }
        SectionHeader(title = "分块进度", subtitle = "${current.currentTile}/${current.totalTiles.coerceAtLeast(1)} 个 tile")
        TileGrid(progress = current, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("取消处理")
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tile.toString(),
                    color = if (done) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
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
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("画质对比", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        ComparePreview(
            inputPath = params?.inputPath,
            outputPath = params?.outputPath
        )
        StatusBanner(text = state.resultMessage ?: "处理完成")
        params?.let {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "运行摘要", subtitle = "本次处理参数")
                    Text("${it.modelName}  ·  ${it.scale}x  ·  降噪 ${it.noise}", fontWeight = FontWeight.SemiBold)
                    Text("${it.engine.labelCn()}  ·  ${it.accelerationMode.labelCn()}  ·  ${it.outputFormat}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSave,
                enabled = params != null,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = {
                    state.savedOutputUri?.let { rawUri ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(rawUri))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "分享 LumaSR 结果"))
                    }
                },
                enabled = state.savedOutputUri != null,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("分享")
            }
        }
        ElevatedButton(
            onClick = onBackHome,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("继续处理")
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
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.fillMaxSize()) {
                PreviewPane("原图", inputPath, MaterialTheme.colorScheme.surfaceVariant, Modifier.weight(split))
                PreviewPane("增强", outputPath, MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f - split))
            }
        }
        Slider(value = split, onValueChange = { split = it.coerceIn(0.1f, 0.9f) })
        Text("拖动滑杆查看原图和增强结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewPane(label: String, path: String?, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(260.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(color),
        contentAlignment = Alignment.TopStart
    ) {
        ImagePreview(path, label, Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.padding(10.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        ) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                fontWeight = FontWeight.SemiBold
            )
        }
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
            Text("暂无预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun SuperResEngine.labelCn(): String {
    return when (this) {
        SuperResEngine.WAIFU2X -> "Waifu2x"
        SuperResEngine.REAL_CUGAN -> "RealCUGAN"
    }
}

private fun SuperResEngine.shortLabel(): String {
    return when (this) {
        SuperResEngine.WAIFU2X -> "W2"
        SuperResEngine.REAL_CUGAN -> "RC"
    }
}

private fun AccelerationMode.labelCn(): String {
    return when (this) {
        AccelerationMode.AUTO -> "自动"
        AccelerationMode.VULKAN -> "Vulkan"
        AccelerationMode.CPU -> "CPU"
    }
}

private fun UpscaleStage.labelCn(): String {
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

private fun String.modelTitleCn(): String {
    return when (this.lowercase(Locale.US)) {
        "cunet" -> "CUnet 插画"
        "anime" -> "Anime 动漫"
        "photo" -> "Photo 照片"
        "standard" -> "Standard 均衡"
        "pro" -> "Pro 修复"
        else -> this
    }
}

private fun String.toChineseDescription(): String {
    return when {
        contains("Quality-first", ignoreCase = true) -> "适合线稿、插画和动漫图，优先保留细节。"
        contains("Balanced model", ignoreCase = true) -> "适合动漫截图和压缩插画，速度与质量更均衡。"
        contains("Softer", ignoreCase = true) -> "适合普通照片，增强更柔和。"
        contains("Default balanced RealCUGAN", ignoreCase = true) -> "适合干净插画和通用动漫图，默认推荐。"
        contains("Heavy artifact", ignoreCase = true) -> "适合压缩瑕疵修复，质量更高但耗时更长。"
        else -> this
    }
}

private fun String.toChineseProgress(): String {
    return when {
        contains("Native inference complete", ignoreCase = true) -> "本地推理完成"
        contains("Saved to Pictures", ignoreCase = true) -> "已保存到 Pictures/LocalSR"
        contains("Preparing", ignoreCase = true) -> "准备本地推理"
        contains("Decoding", ignoreCase = true) -> "正在读取图片"
        contains("Loading", ignoreCase = true) -> "正在加载模型"
        contains("Processing tile", ignoreCase = true) -> replace("Processing tile", "正在处理分块")
        contains("Stitching", ignoreCase = true) -> "正在拼接结果"
        contains("Saving", ignoreCase = true) -> "正在保存输出"
        contains("GPU failed", ignoreCase = true) -> "GPU 不可用，正在切换 CPU"
        contains("not supported", ignoreCase = true) -> "当前图片或模型组合暂不支持"
        contains("failed", ignoreCase = true) -> replace("failed", "失败")
        else -> this
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
