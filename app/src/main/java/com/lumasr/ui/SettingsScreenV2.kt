package com.lumasr.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumasr.domain.AccelerationMode
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.TileSizeMode
import com.lumasr.domain.availableTargetScales

internal const val SettingsFooterBottomSpacerDp = 128

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenV2(
    state: LumaUiState,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTileSizeChanged: (Int) -> Unit,
    onTileSizeAutoSelected: () -> Unit,
    appVersionLabel: String? = null
) {
    var notifications by remember { mutableStateOf(true) }
    var exif by remember { mutableStateOf(true) }
    val gpuEnabled = state.accelerationMode != AccelerationMode.CPU
    val context = LocalContext.current
    val resolvedAppVersionLabel = appVersionLabel ?: remember(context) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        formatSettingsVersion(versionName, versionCode)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置", fontWeight = FontWeight.Bold) })
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsGroupTitleV2("处理与性能")
            SettingsClickableRowV2(
                icon = Icons.Rounded.Bolt,
                title = "GPU 加速",
                subtitle = if (gpuEnabled) "优先使用 Vulkan，失败时回退 CPU" else "仅使用 CPU 推理",
                onClick = {
                    onAccelerationChanged(if (gpuEnabled) AccelerationMode.CPU else AccelerationMode.AUTO)
                }
            )
            SettingsInfoRowV2(
                icon = Icons.Rounded.Tune,
                title = "分块大小",
                subtitle = if (state.tileSizeMode == TileSizeMode.AUTO) {
                    "自动 · 当前 ${state.tileSize} px"
                } else {
                    "${state.tileSize} px"
                }
            )
            SettingsTileSizeSelector(
                selectedMode = state.tileSizeMode,
                selectedTileSize = state.tileSize,
                onTileSizeChanged = onTileSizeChanged,
                onTileSizeAutoSelected = onTileSizeAutoSelected
            )
            SettingsSwitchRowV2(
                icon = Icons.Rounded.Memory,
                title = "后台通知",
                subtitle = "处理完成时发送系统通知",
                checked = notifications,
                onCheckedChange = { notifications = it }
            )

            SectionDivider()
            SettingsGroupTitleV2("内置模型")
            state.models.forEach { model ->
                SettingsInfoRowV2(
                    icon = if (model.engine == SuperResEngine.WAIFU2X) Icons.Rounded.PhotoLibrary else Icons.Rounded.Security,
                    title = model.settingsModelTitle(),
                    subtitle = if (model.isBuiltIn) "已内置 · ${model.availableTargetScales().joinToString("x / ", postfix = "x")}" else "未安装"
                )
            }

            SectionDivider()
            SettingsGroupTitleV2("隐私与输出")
            SettingsInfoRowV2(
                icon = Icons.Rounded.CloudOff,
                title = "云端上传",
                subtitle = "关闭。所有图片仅在本机处理"
            )
            SettingsSwitchRowV2(
                icon = Icons.Rounded.Save,
                title = "保留 EXIF",
                subtitle = "保存结果时尽量保留原始图片元数据",
                checked = exif,
                onCheckedChange = { exif = it }
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = resolvedAppVersionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            )
            Spacer(modifier = Modifier.height(SettingsFooterBottomSpacerDp.dp))
        }
    }
}

internal fun formatSettingsVersion(versionName: String, versionCode: Long): String {
    return "\u7248\u672c $versionName ($versionCode)"
}

@Composable
private fun SettingsTileSizeSelector(
    selectedMode: TileSizeMode,
    selectedTileSize: Int,
    onTileSizeChanged: (Int) -> Unit,
    onTileSizeAutoSelected: () -> Unit
) {
    val selected = sanitizeTileSize(selectedTileSize)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TileSizeChoices.forEach { choice ->
            val active = if (choice.value == null) {
                selectedMode == TileSizeMode.AUTO
            } else {
                selectedMode == TileSizeMode.MANUAL && choice.value == selected
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clickable { choice.value?.let(onTileSizeChanged) ?: onTileSizeAutoSelected() },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupTitleV2(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    )
}

@Composable
private fun SettingsClickableRowV2(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    SettingsRowFrame(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SettingsInfoRowV2(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    SettingsRowFrame(icon = icon, title = title, subtitle = subtitle)
}

@Composable
private fun SettingsSwitchRowV2(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRowFrame(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
}

@Composable
private fun SettingsRowFrame(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

private fun String.cleanModelTitle(): String {
    return when (lowercase()) {
        "cunet" -> "CUnet 插画"
        "anime" -> "动漫"
        "photo" -> "旧图像修复"
        "standard" -> "RealCUGAN 标准"
        "pro" -> "锐化修复"
        else -> this
    }
}
