/**
 * 本文件提供了一些在 UI 中公用的基础可组合函数 (Composable) 和占位符视图。
 * 包含通用卡片、小部件以及数据状态预览的组件封装。
 */
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

// NativeLumaApp and LumaBottomBar moved to MainScreen.kt

// HomeDashboard removed, moved to HomeScreen.kt










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
fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
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
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
fun IconTile(icon: ImageVector, tint: Color) {
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

// ActionChipData and FlowActions removed

@Composable
fun RecentTaskPlaceholder() {
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
fun RecentTaskCard(task: RenderTaskSummary) {
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


