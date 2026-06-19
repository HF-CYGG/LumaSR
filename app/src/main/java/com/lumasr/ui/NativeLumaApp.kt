package com.lumasr.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
fun NativeLumaApp(viewModel: LumaViewModel) {
    val state by viewModel.uiState.collectAsState()
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF8CB8FF),
            secondary = Color(0xFF74D7A8),
            background = Color(0xFF101318),
            surface = Color(0xFF171B20),
            surfaceVariant = Color(0xFF20262F),
            primaryContainer = Color(0xFF173C78),
            secondaryContainer = Color(0xFF123D2E)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF2563EB),
            secondary = Color(0xFF0F9F6E),
            background = Color(0xFFF6F8FC),
            surface = Color.White,
            surfaceVariant = Color(0xFFEAF0F7),
            primaryContainer = Color(0xFFE6EEFF),
            secondaryContainer = Color(0xFFE1F7ED)
        )
    }

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            bottomBar = {
                if (state.screen != LumaScreen.COMPARE) {
                    LumaBottomBar(
                        selected = state.selectedTab,
                        onSelected = viewModel::selectTab
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding)
            ) {
                if (state.screen == LumaScreen.COMPARE) {
                    CompareScreenV3(
                        state = state,
                        onSave = viewModel::saveResultToGallery,
                        onBackHome = viewModel::backHome
                    )
                } else {
                    AnimatedContent(targetState = state.selectedTab, label = "tabContent") { tab ->
                        when (tab) {
                            LumaTab.HOME -> HomeDashboard(
                                state = state,
                                onPickImage = viewModel::onImageSelected,
                                onOpenProcess = { viewModel.selectTab(LumaTab.PROCESS) }
                            )

                            LumaTab.PROCESS -> ProcessTab(
                                state = state,
                                onPickImage = viewModel::onImageSelected,
                                onModelSelected = viewModel::selectModel,
                                onScaleChanged = viewModel::setScale,
                                onNoiseChanged = viewModel::setNoise,
                                onAccelerationChanged = viewModel::setAccelerationMode,
                                onTtaChanged = viewModel::setTta,
                                onStart = viewModel::startProcessing,
                                onCancel = viewModel::cancelProcessing,
                                onRunInBackground = { viewModel.selectTab(LumaTab.HOME) }
                            )

                            LumaTab.HISTORY -> QueueScreen(state = state)
                            LumaTab.SETTINGS -> SettingsScreen(
                                state = state,
                                onAccelerationChanged = viewModel::setAccelerationMode
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LumaBottomBar(
    selected: LumaTab,
    onSelected: (LumaTab) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
        BottomItem(LumaTab.HOME, selected, Icons.Rounded.Home, "首页", onSelected)
        BottomItem(LumaTab.PROCESS, selected, Icons.AutoMirrored.Rounded.FormatListBulleted, "处理", onSelected)
        BottomItem(LumaTab.HISTORY, selected, Icons.Rounded.History, "历史", onSelected)
        BottomItem(LumaTab.SETTINGS, selected, Icons.Rounded.Settings, "设置", onSelected)
        }
    }
}

@Composable
private fun RowScope.BottomItem(
    tab: LumaTab,
    selected: LumaTab,
    icon: ImageVector,
    label: String,
    onSelected: (LumaTab) -> Unit
) {
    val active = tab == selected
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(22.dp))
            .clickable { onSelected(tab) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun HomeDashboard(
    state: LumaUiState,
    onPickImage: (Uri) -> Unit,
    onOpenProcess: () -> Unit
) {
    val photoPicker = rememberImagePicker(onPickImage)
    PageColumn {
        PageTitle(
            title = "LumaSR",
            subtitle = "原生 Android · 本地 AI 超分",
            actionIcon = Icons.Rounded.Settings,
            actionDescription = "外观设置",
            onAction = onOpenProcess
        )
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconTile(icon = Icons.Rounded.AutoFixHigh, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("开始增强", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("选择照片，使用设备本地模型处理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(
                onClick = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选择素材", fontWeight = FontWeight.SemiBold)
            }
        }
        SectionTitle("常用功能")
        FlowActions(
            items = listOf(
                ActionChipData(Icons.Rounded.PhotoLibrary, "照片超分"),
                ActionChipData(Icons.Rounded.AutoFixHigh, "动漫线稿"),
                ActionChipData(Icons.AutoMirrored.Rounded.FormatListBulleted, "批量队列")
            )
        )
        SectionTitle("最近处理")
        if (state.recentTasks.isEmpty()) {
            RecentTaskPlaceholder()
        } else {
            state.recentTasks.take(2).forEach { RecentTaskCard(it) }
        }
        StatusCard(
            icon = Icons.Rounded.Bolt,
            title = if (state.accelerationMode == AccelerationMode.CPU) "CPU 模式" else "GPU 可用",
            subtitle = "离线模式 · 模型已就绪",
            value = if (state.models.isEmpty()) "加载中" else "${state.models.size} 个模型",
            success = state.models.isNotEmpty()
        )
    }
}

@Composable
private fun ProcessTab(
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
    PageColumn {
        PageTitle(
            title = "增强设置",
            subtitle = "保持原生控件，参数不打扰",
            actionIcon = Icons.Rounded.Tune,
            actionDescription = "参数"
        )
        CompareSetupCard(state.selectedImage)
        SectionTitle("模型")
        ModelModeChips(
            models = state.models,
            selectedModelId = state.selectedModelId,
            onModelSelected = onModelSelected
        )
        SectionTitle("放大倍率")
        ScalePicker(
            values = state.selectedModel?.scales ?: listOf(2, 3, 4),
            selected = state.scale,
            onSelected = onScaleChanged
        )
        ParameterSlider("降噪", state.noise, state.selectedModel?.denoise ?: listOf(0, 1, 2), onNoiseChanged)
        ParameterSlider("锐化", if (state.tta) 2 else 1, listOf(0, 1, 2), { onTtaChanged(it > 1) })
        AccelerationChips(state.accelerationMode, onAccelerationChanged)
        Button(
            onClick = onStart,
            enabled = state.selectedImage != null && state.selectedModel != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(horizontal = 18.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("开始处理", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.selectedImage == null) "先选择素材" else "更换素材")
        }
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
    PageColumn {
        PageTitle(
            title = "实时处理",
            subtitle = "像渲染器一样显示每一块进度",
            actionIcon = Icons.Rounded.Info,
            actionDescription = "处理说明"
        )
        RenderMonitor(
            fileName = state.selectedImage?.displayName ?: state.activeParams?.inputPath?.let { File(it).name } ?: "IMG_2048.jpg",
            modelName = state.activeParams?.modelName ?: state.selectedModel?.displayName ?: "RealCUGAN",
            scale = state.activeParams?.scale ?: state.scale,
            tileSize = state.activeParams?.tileSize ?: 512,
            accelerationMode = state.activeParams?.accelerationMode ?: state.accelerationMode,
            progress = progress
        )
        ProgressCard(progress)
        CardBlock {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Icons.Rounded.Bolt, MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(progress.stage.labelCn(), fontWeight = FontWeight.Bold)
                    Text(progress.message.toChineseProgress(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("取消")
                }
                Button(
                    onClick = onRunInBackground,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("后台运行")
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(state: LumaUiState) {
    PageColumn {
        PageTitle(
            title = "处理",
            subtitle = "任务队列与状态",
            actionIcon = Icons.AutoMirrored.Rounded.FormatListBulleted,
            actionDescription = "队列"
        )
        state.recentTasks.firstOrNull { it.status == RenderTaskStatus.RUNNING }?.let {
            CardBlock {
                Text("当前任务", fontWeight = FontWeight.Bold)
                MiniRenderPreview(progress = it.progress)
                Text("${it.fileName} · ${(it.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        }
        SectionTitle("队列")
        if (state.recentTasks.isEmpty()) {
            EmptyState("还没有处理记录", "选择一张图片后会在这里显示任务。")
        } else {
            state.recentTasks.forEach { RecentTaskCard(it) }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: LumaUiState,
    onAccelerationChanged: (AccelerationMode) -> Unit
) {
    var notifications by remember { mutableStateOf(true) }
    var exif by remember { mutableStateOf(true) }
    val gpuEnabled = state.accelerationMode != AccelerationMode.CPU
    PageColumn {
        PageTitle(
            title = "设置",
            subtitle = "原生列表控件 + Material Icons",
            actionIcon = Icons.Rounded.Settings,
            actionDescription = "设置"
        )
        SettingsGroup {
            SettingsRow(
                icon = Icons.Rounded.Bolt,
                title = "GPU 加速",
                value = if (gpuEnabled) "开启" else "关闭",
                success = gpuEnabled,
                onClick = { onAccelerationChanged(if (gpuEnabled) AccelerationMode.CPU else AccelerationMode.AUTO) }
            )
            SettingsDivider()
            SettingsRow(Icons.Rounded.Tune, "分块大小", "${state.activeParams?.tileSize ?: 512} px")
            SettingsDivider()
            SettingsSwitchRow(Icons.Rounded.Info, "后台通知", notifications) { notifications = it }
        }
        SettingsGroup {
            state.models.take(3).forEachIndexed { index, model ->
                SettingsRow(
                    icon = if (model.engine == SuperResEngine.WAIFU2X) Icons.Rounded.AutoFixHigh else Icons.Rounded.PhotoLibrary,
                    title = model.displayName.modelTitleCn(),
                    value = if (model.isBuiltIn) "已安装" else "未安装",
                    success = model.isBuiltIn
                )
                if (index != state.models.take(3).lastIndex) SettingsDivider()
            }
        }
        SettingsGroup {
            SettingsRow(Icons.Rounded.CloudOff, "云端上传", "关闭")
            SettingsDivider()
            SettingsSwitchRow(Icons.Rounded.Save, "EXIF 保留", exif) { exif = it }
        }
    }
}

@Composable
private fun CompareScreenV3(
    state: LumaUiState,
    onSave: () -> Unit,
    onBackHome: () -> Unit
) {
    val context = LocalContext.current
    val params = state.activeParams
    PageColumn {
        PageTitle("画质对比", "拖动中线检查细节", Icons.Rounded.Compare, "对比")
        ComparePreviewV3(params?.inputPath, params?.outputPath)
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

@Composable
private fun RenderMonitor(
    fileName: String,
    modelName: String,
    scale: Int,
    tileSize: Int,
    accelerationMode: AccelerationMode,
    progress: UpscaleProgress
) {
    CardBlock {
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

@Composable
private fun RenderTileCanvas(progress: UpscaleProgress, modifier: Modifier = Modifier) {
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
    CardBlock {
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

@Composable
private fun CompareSetupCard(image: SelectedImageInfo?) {
    CardBlock {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.fillMaxSize()) {
                PreviewPaneV3("原图", image?.sourceUri, Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                )
                PreviewPaneV3("预览", image?.sourceUri, Modifier.weight(1f))
            }
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Icon(
                    Icons.Rounded.Compare,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text("拖动中线对比细节", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ComparePreviewV3(inputPath: String?, outputPath: String?) {
    var split by remember { mutableFloatStateOf(0.5f) }
    CardBlock {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.fillMaxSize()) {
                PreviewPaneV3("原图", inputPath, Modifier.weight(split))
                PreviewPaneV3("增强", outputPath, Modifier.weight(1f - split))
            }
        }
        Slider(value = split, onValueChange = { split = it.coerceIn(0.1f, 0.9f) })
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
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content
    )
}

@Composable
private fun PageTitle(
    title: String,
    subtitle: String,
    actionIcon: ImageVector,
    actionDescription: String,
    onAction: (() -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            modifier = Modifier
                .size(48.dp)
                .then(if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier),
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(actionIcon, contentDescription = actionDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tint.copy(alpha = 0.12f)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.padding(14.dp)
        )
    }
}

private data class ActionChipData(val icon: ImageVector, val text: String)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FlowActions(items: List<ActionChipData>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surface,
                border = CardDefaults.outlinedCardBorder()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(it.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(it.text)
                }
            }
        }
    }
}

@Composable
private fun RecentTaskPlaceholder() {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Rounded.History, MaterialTheme.colorScheme.primary)
            Column {
                Text("暂无最近处理", fontWeight = FontWeight.Bold)
                Text("完成一次超分后会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecentTaskCard(task: RenderTaskSummary) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(
                icon = when (task.status) {
                    RenderTaskStatus.DONE -> Icons.Rounded.CheckCircle
                    RenderTaskStatus.FAILED -> Icons.Rounded.ErrorOutline
                    RenderTaskStatus.CANCELLED -> Icons.Rounded.Close
                    RenderTaskStatus.RUNNING -> Icons.Rounded.Bolt
                },
                tint = if (task.status == RenderTaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(task.fileName, fontWeight = FontWeight.Bold)
                Text("${task.modelName} · ${task.scale}x", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(task.status.labelCn(), color = task.status.statusColor(), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusCard(icon: ImageVector, title: String, subtitle: String, value: String, success: Boolean) {
    CardBlock {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
@OptIn(ExperimentalLayoutApi::class)
private fun ModelModeChips(models: List<ModelPack>, selectedModelId: String?, onModelSelected: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        models.forEach { model ->
            FilterChip(
                selected = model.id == selectedModelId,
                onClick = { onModelSelected(model.id) },
                label = { Text(model.displayName.modelTitleCn()) }
            )
        }
    }
}

@Composable
private fun ScalePicker(values: List<Int>, selected: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        values.distinct().sorted().forEach { scale ->
            val active = scale == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clickable { onSelected(scale) },
                shape = RoundedCornerShape(999.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${scale}x", color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ParameterSlider(title: String, value: Int, values: List<Int>, onChanged: (Int) -> Unit) {
    val sorted = values.distinct().sorted()
    if (sorted.isEmpty()) return
    val min = sorted.first()
    val max = sorted.last()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.width(48.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        Slider(
            value = value.toFloat(),
            onValueChange = { raw -> onChanged(sorted.minBy { kotlin.math.abs(it - raw.toInt()) }) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AccelerationChips(selected: AccelerationMode, onSelected: (AccelerationMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccelerationMode.entries.forEach { mode ->
            FilterChip(selected = selected == mode, onClick = { onSelected(mode) }, label = { Text(mode.labelCn()) })
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), content = content)
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, value: String, success: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(value, color = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SettingsSwitchRow(icon: ImageVector, title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 42.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
}

@Composable
private fun MiniRenderPreview(progress: Float) {
    val fakeProgress = UpscaleProgress(
        taskId = "preview",
        stage = UpscaleStage.PROCESSING_TILE,
        progress = progress,
        currentTile = max(1, (progress * 32).toInt()),
        totalTiles = 32,
        completedTileIndexes = (1..(progress * 32).toInt()).toSet(),
        message = "Preview",
        estimatedRemainingMs = null
    )
    RenderTileCanvas(
        progress = fakeProgress,
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .clip(RoundedCornerShape(18.dp))
    )
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    CardBlock {
        Text(title, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun rememberImagePicker(onPickImage: (Uri) -> Unit) = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
    onResult = { uri -> uri?.let(onPickImage) }
)

private fun SuperResEngine.labelCn(): String {
    return when (this) {
        SuperResEngine.WAIFU2X -> "Waifu2x"
        SuperResEngine.REAL_CUGAN -> "RealCUGAN"
    }
}

private fun AccelerationMode.labelCn(): String {
    return when (this) {
        AccelerationMode.AUTO -> "GPU"
        AccelerationMode.VULKAN -> "Vulkan"
        AccelerationMode.CPU -> "CPU"
    }
}

private fun UpscaleStage.labelCn(): String {
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

private fun RenderTaskStatus.labelCn(): String {
    return when (this) {
        RenderTaskStatus.RUNNING -> "处理中"
        RenderTaskStatus.DONE -> "已完成"
        RenderTaskStatus.FAILED -> "失败"
        RenderTaskStatus.CANCELLED -> "已取消"
    }
}

@Composable
private fun RenderTaskStatus.statusColor(): Color {
    return when (this) {
        RenderTaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
        RenderTaskStatus.DONE -> MaterialTheme.colorScheme.secondary
        RenderTaskStatus.FAILED -> MaterialTheme.colorScheme.error
        RenderTaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun String.modelTitleCn(): String {
    return when (lowercase(Locale.US)) {
        "cunet" -> "CUnet 插画"
        "anime" -> "动漫"
        "photo" -> "旧图修复"
        "standard" -> "RealCUGAN 标准"
        "pro" -> "锐化修复"
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
        contains("cancelled", ignoreCase = true) -> "任务已取消"
        contains("failed", ignoreCase = true) -> replace("failed", "失败")
        else -> this
    }
}
