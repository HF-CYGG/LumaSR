/**
 * 本文件负责处理图片增强的核心流程（参数设置、渲染进度、结果对比）。
 * 包含沉浸式图片对比视图、底部参数控制面板，并采用 Material 3 控件。
 */
package com.lumasr.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlin.math.ceil
import kotlin.math.max

@Composable
fun ProcessTab(
    state: LumaUiState,
    onPickImage: (Uri) -> Unit,
    onModelSelected: (String) -> Unit,
    onScaleChanged: (Int) -> Unit,
    onNoiseChanged: (Int) -> Unit,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTtaChanged: (Boolean) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onRunInBackground: () -> Unit
) {
    if (state.screen == LumaScreen.PROCESSING) {
        RenderScreen(
            state = state,
            onCancel = onCancel,
            onRunInBackground = onRunInBackground
        )
    } else {
        EditorScreen(
            state = state,
            onPickImage = onPickImage,
            onModelSelected = onModelSelected,
            onScaleChanged = onScaleChanged,
            onNoiseChanged = onNoiseChanged,
            onAccelerationChanged = onAccelerationChanged,
            onTtaChanged = onTtaChanged,
            onStart = onStart
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    state: LumaUiState,
    onPickImage: (Uri) -> Unit,
    onModelSelected: (String) -> Unit,
    onScaleChanged: (Int) -> Unit,
    onNoiseChanged: (Int) -> Unit,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTtaChanged: (Boolean) -> Unit,
    onStart: () -> Unit
) {
    val photoPicker = rememberImagePicker(onPickImage)
    
    Box(modifier = Modifier.fillMaxSize()) {
        // 沉浸式对比视图区
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 440.dp) // 为底部面板留出空间
        ) {
            ImmersiveComparePreview(
                inputPath = state.selectedImage?.sourceUri,
                outputPath = state.selectedImage?.sourceUri, // 处理前，预览即原图
                modifier = Modifier.fillMaxSize()
            )
            // 提示用户选择图片
            if (state.selectedImage == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = "选择图片",
                        modifier = Modifier
                            .size(64.dp)
                            .clickable {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("点击选择图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // 更换图片按钮
                SmallFloatingActionButton(
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = "更换素材")
                }
            }
        }
        
        // 底部参数控制面板
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(460.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("处理参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                
                // 模型选择 SegmentedButton
                if (state.models.isNotEmpty()) {
                    Text("模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        state.models.forEachIndexed { index, model ->
                            SegmentedButton(
                                selected = model.id == state.selectedModelId,
                                onClick = { onModelSelected(model.id) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = state.models.size)
                            ) {
                                Text(model.displayName.modelTitleCn())
                            }
                        }
                    }
                }
                
                // 放大倍率选择 SegmentedButton
                val scales = state.selectedModel?.scales ?: listOf(2, 3, 4)
                if (scales.isNotEmpty()) {
                    Text("放大倍率", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        scales.forEachIndexed { index, scale ->
                            SegmentedButton(
                                selected = scale == state.scale,
                                onClick = { onScaleChanged(scale) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = scales.size)
                            ) {
                                Text("${scale}x")
                            }
                        }
                    }
                }
                
                // 降噪调节 Slider
                val noises = state.selectedModel?.denoise ?: listOf(0, 1, 2)
                if (noises.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("降噪等级", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = state.noise.toFloat(),
                            onValueChange = { raw -> onNoiseChanged(noises.minBy { kotlin.math.abs(it - raw.toInt()) }) },
                            valueRange = noises.first().toFloat()..noises.last().toFloat(),
                            steps = (noises.last() - noises.first() - 1).coerceAtLeast(0),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = state.noise.toString(),
                            modifier = Modifier
                                .width(32.dp)
                                .padding(start = 8.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // 加速模式选择
                Text("加速模式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AccelerationMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = state.accelerationMode == mode,
                            onClick = { onAccelerationChanged(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = AccelerationMode.entries.size)
                        ) {
                            Text(mode.labelCn())
                        }
                    }
                }

                // TTA 选项
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TTA 高质量", style = MaterialTheme.typography.bodyMedium)
                        Text("画质更稳，耗时更长", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = state.tta,
                        enabled = state.selectedModel?.supportsTta == true,
                        onCheckedChange = onTtaChanged
                    )
                }
                
                // 占位，避免 FAB 遮挡
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
        
        // 开始处理 ExtendedFloatingActionButton
        ExtendedFloatingActionButton(
            onClick = onStart,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = "开始处理") },
            text = { Text("开始处理") },
            expanded = state.selectedImage != null && state.selectedModel != null
        )
    }
}

@Composable
fun CompareScreen(
    state: LumaUiState,
    onSave: () -> Unit,
    onBackHome: () -> Unit
) {
    val context = LocalContext.current
    val params = state.activeParams
    
    Box(modifier = Modifier.fillMaxSize()) {
        // 沉浸式对比视图区
        ImmersiveComparePreview(
            inputPath = params?.inputPath,
            outputPath = params?.outputPath,
            modifier = Modifier.fillMaxSize()
        )
        
        // 底部操作栏
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusCard(
                    icon = Icons.Rounded.CheckCircle,
                    title = state.resultMessage?.toChineseProgress() ?: "处理完成",
                    subtitle = params?.let { "${it.modelName} · ${it.scale}x · 降噪 ${it.noise}" } ?: "等待结果",
                    value = params?.accelerationMode?.labelCn().orEmpty(),
                    success = true
                )
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
                                    setType("image/*")
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
    }
}

@Composable
private fun ImmersiveComparePreview(inputPath: String?, outputPath: String?, modifier: Modifier = Modifier) {
    var split by remember { mutableFloatStateOf(0.5f) }
    Box(modifier = modifier) {
        Row(Modifier.fillMaxSize()) {
            PreviewPaneV3("原图", inputPath, Modifier.weight(split))
            PreviewPaneV3("预览/结果", outputPath, Modifier.weight(1f - split))
        }
        Slider(
            value = split,
            onValueChange = { split = it.coerceIn(0.1f, 0.9f) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 32.dp, end = 32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun PreviewPaneV3(label: String, path: String?, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        ImagePreviewV3(path, label, Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.padding(10.dp),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        ) {
            Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ImagePreviewV3(path: String?, contentDescription: String, modifier: Modifier) {
    val bitmap = remember(path) {
        path?.let { File(it).takeIf { file -> file.exists() } }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }
    if (bitmap == null) {
        PlaceholderMountains(modifier)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun PlaceholderMountains(modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)
    Canvas(modifier = modifier.background(bg)) {
        val p1 = Path().apply {
            moveTo(size.width * 0.08f, size.height * 0.82f)
            lineTo(size.width * 0.42f, size.height * 0.38f)
            lineTo(size.width * 0.72f, size.height * 0.82f)
            close()
        }
        val p2 = Path().apply {
            moveTo(size.width * 0.40f, size.height * 0.84f)
            lineTo(size.width * 0.76f, size.height * 0.44f)
            lineTo(size.width * 1.02f, size.height * 0.84f)
            close()
        }
        drawPath(p1, fg)
        drawPath(p2, fg.copy(alpha = 0.36f))
        drawCircle(Color.White.copy(alpha = 0.78f), radius = 18.dp.toPx(), center = Offset(size.width * 0.78f, size.height * 0.28f))
    }
}

@Composable
private fun RenderScreen(
    state: LumaUiState,
    onCancel: () -> Unit,
    onRunInBackground: () -> Unit
) {
    val progress = state.progress ?: UpscaleProgress(
        taskId = state.activeParams?.taskId.orEmpty(),
        stage = UpscaleStage.PREPARING,
        progress = 0f,
        currentTile = 0,
        totalTiles = 80,
        completedTileIndexes = emptySet(),
        message = "Preparing native inference",
        estimatedRemainingMs = null
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("实时处理", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("像渲染器一样显示每一块进度", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Info, contentDescription = "处理说明", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        
        RenderMonitor(
            fileName = state.selectedImage?.displayName ?: state.activeParams?.inputPath?.let { File(it).name } ?: "IMG_2048.jpg",
            modelName = state.activeParams?.modelName ?: state.selectedModel?.displayName ?: "RealCUGAN",
            scale = state.activeParams?.scale ?: state.scale,
            tileSize = state.activeParams?.tileSize ?: 512,
            accelerationMode = state.activeParams?.accelerationMode ?: state.accelerationMode,
            progress = progress
        )
        ProgressCard(progress)
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(14.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(progress.stage.labelCn(), fontWeight = FontWeight.Bold)
                        Text(progress.message.toChineseProgress(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Rounded.Pause, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("取消")
                    }
                    Button(
                        onClick = onRunInBackground,
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("后台运行")
                    }
                }
            }
        }
    }
}

@Composable
private fun RenderMonitor(
    fileName: String,
    modelName: String,
    scale: Int,
    tileSize: Int,
    accelerationMode: AccelerationMode,
    progress: UpscaleProgress
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$modelName · ${scale}x · $tileSize px tiles", color = MaterialTheme.colorScheme.onSurfaceVariant)
            RenderTileCanvas(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.18f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(progress.progress * 100).toInt()}% · Tile ${progress.currentTile}/${progress.totalTiles.coerceAtLeast(1)}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    accelerationMode.labelCn(),
                    color = if (accelerationMode == AccelerationMode.CPU) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun RenderTileCanvas(progress: UpscaleProgress, modifier: Modifier = Modifier) {
    val totalTiles = progress.totalTiles.coerceAtLeast(1)
    val columns = when {
        totalTiles >= 40 -> 10
        totalTiles >= 16 -> 8
        else -> 4
    }
    val rows = ceil(totalTiles / columns.toFloat()).toInt().coerceAtLeast(1)
    val animatedProgress by animateFloatAsState(progress.progress.coerceIn(0f, 1f), label = "renderProgress")
    val pulse by rememberInfiniteTransition(label = "tilePulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(680), RepeatMode.Reverse),
        label = "currentTilePulse"
    )
    val surface = MaterialTheme.colorScheme.surface
    val pending = MaterialTheme.colorScheme.surfaceVariant
    val done = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    val running = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f + pulse * 0.18f)
    val primary = MaterialTheme.colorScheme.primary
    val mountain = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.26f)
    val mountainDark = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)

    Canvas(modifier = modifier) {
        val tileWidth = size.width / columns
        val tileHeight = size.height / rows
        drawRoundRect(surface, size = size, cornerRadius = CornerRadius(18.dp.toPx()))

        for (index in 1..(rows * columns)) {
            val row = (index - 1) / columns
            val column = (index - 1) % columns
            val left = column * tileWidth + 2.dp.toPx()
            val top = row * tileHeight + 2.dp.toPx()
            val isVisibleTile = index <= totalTiles
            val isDone = progress.completedTileIndexes.contains(index) || index <= (animatedProgress * totalTiles).toInt()
            val isRunning = index == progress.currentTile && progress.stage == UpscaleStage.PROCESSING_TILE
            val color = when {
                !isVisibleTile -> Color.Transparent
                isRunning -> running
                isDone -> done
                else -> pending.copy(alpha = 0.72f)
            }
            if (isVisibleTile) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(tileWidth - 4.dp.toPx(), tileHeight - 4.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx())
                )
            }
        }

        val firstMountain = Path().apply {
            moveTo(size.width * 0.05f, size.height * 0.82f)
            lineTo(size.width * 0.40f, size.height * 0.42f)
            lineTo(size.width * 0.68f, size.height * 0.82f)
            close()
        }
        val secondMountain = Path().apply {
            moveTo(size.width * 0.38f, size.height * 0.84f)
            lineTo(size.width * 0.72f, size.height * 0.34f)
            lineTo(size.width * 0.98f, size.height * 0.84f)
            close()
        }
        drawPath(firstMountain, mountain)
        drawPath(secondMountain, mountainDark)

        if (progress.stage == UpscaleStage.PROCESSING_TILE && progress.currentTile in 1..totalTiles) {
            val currentIndex = progress.currentTile
            val row = (currentIndex - 1) / columns
            val column = (currentIndex - 1) % columns
            drawRoundRect(
                color = primary,
                topLeft = Offset(column * tileWidth + 2.dp.toPx(), row * tileHeight + 2.dp.toPx()),
                size = Size(tileWidth - 4.dp.toPx(), tileHeight - 4.dp.toPx()),
                cornerRadius = CornerRadius(5.dp.toPx()),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun ProgressCard(progress: UpscaleProgress) {
    val percent = (progress.progress * 100).toInt()
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("处理进度", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("$percent%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            LinearProgressIndicator(progress = { progress.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(
                "当前块 ${progress.currentTile}/${progress.totalTiles.coerceAtLeast(1)} · 预计剩余 ${progress.estimatedRemainingMs?.let { "${it / 1000}s" } ?: "计算中"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusCard(icon: ImageVector, title: String, subtitle: String, value: String, success: Boolean) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, color = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun rememberImagePicker(onPickImage: (Uri) -> Unit) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
    onResult = { uri -> uri?.let(onPickImage) }
)

fun AccelerationMode.labelCn(): String {
    return when (this) {
        AccelerationMode.AUTO -> "GPU"
        AccelerationMode.VULKAN -> "Vulkan"
        AccelerationMode.CPU -> "CPU"
    }
}

fun UpscaleStage.labelCn(): String {
    return when (this) {
        UpscaleStage.PREPARING -> "准备中"
        UpscaleStage.ANALYZING -> "分析图片"
        UpscaleStage.LOADING_MODEL -> "加载模型"
        UpscaleStage.PROCESSING_TILE -> "正在推理"
        UpscaleStage.STITCHING -> "拼接结果"
        UpscaleStage.SAVING -> "保存输出"
        UpscaleStage.DONE -> "处理完成"
        UpscaleStage.FAILED -> "处理失败"
        UpscaleStage.CANCELLED -> "已取消"
    }
}

fun String.modelTitleCn(): String {
    return when (lowercase(Locale.US)) {
        "cunet" -> "CUnet 插画"
        "anime" -> "动漫"
        "photo" -> "旧图修复"
        "standard" -> "RealCUGAN 标准"
        "pro" -> "锐化修复"
        else -> this
    }
}

fun String.toChineseProgress(): String {
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
        contains("cancelled", ignoreCase = true) -> "任务已取消"
        contains("failed", ignoreCase = true) -> replace("failed", "失败")
        else -> this
    }
}

fun SuperResEngine.labelCn(): String {
    return when (this) {
        SuperResEngine.WAIFU2X -> "Waifu2x"
        SuperResEngine.REAL_CUGAN -> "RealCUGAN"
        SuperResEngine.REAL_ESRGAN -> "Real-ESRGAN"
    }
}

fun RenderTaskStatus.labelCn(): String {
    return when (this) {
        RenderTaskStatus.RUNNING -> "处理中"
        RenderTaskStatus.DONE -> "已完成"
        RenderTaskStatus.FAILED -> "失败"
        RenderTaskStatus.CANCELLED -> "已取消"
    }
}

@Composable
fun RenderTaskStatus.statusColor(): Color {
    return when (this) {
        RenderTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        RenderTaskStatus.DONE -> MaterialTheme.colorScheme.secondary
        RenderTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        RenderTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
