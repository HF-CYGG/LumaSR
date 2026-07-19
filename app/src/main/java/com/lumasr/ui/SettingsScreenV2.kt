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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.lumasr.domain.CalibrationReportOutcome
import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.TileSizeMode
import com.lumasr.domain.availableTargetScales

internal const val SettingsFooterBottomSpacerDp = 128
internal const val CalibrationReportsInitiallyExpanded = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreenV2(
    state: LumaUiState,
    onAccelerationChanged: (AccelerationMode) -> Unit,
    onTileSizeChanged: (Int) -> Unit,
    onTileSizeAutoSelected: () -> Unit,
    onRunCalibration: () -> Unit,
    onRunQuickCalibration: () -> Unit = onRunCalibration,
    onRunDeepCalibration: () -> Unit = onRunCalibration,
    onOpenCalibrationReport: (String) -> Unit = {},
    updateState: UpdateUiState = UpdateUiState(),
    onAutomaticUpdateCheckChanged: (Boolean) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    appVersionLabel: String? = null
) {
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
    var calibrationReportsExpanded by rememberSaveable {
        mutableStateOf(CalibrationReportsInitiallyExpanded)
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
            if (state.adrenoCalibrationAvailable) {
                SettingsClickableRowV2(
                    icon = Icons.Rounded.Memory,
                    title = "快速性能校准",
                    subtitle = "约 3 分钟 · STANDARD Vulkan · ${calibrationStatusText(state.calibrationStatus)}",
                    enabled = isCalibrationActionEnabled(state.calibrationStatus),
                    onClick = onRunQuickCalibration
                )
                SettingsClickableRowV2(
                    icon = Icons.Rounded.Tune,
                    title = "深度性能校准",
                    subtitle = "约 8 分钟 · 三档 Vulkan / CPU / TTA",
                    enabled = isCalibrationActionEnabled(state.calibrationStatus),
                    onClick = onRunDeepCalibration
                )
            } else {
                SettingsClickableRowV2(
                    icon = Icons.Rounded.Memory,
                    title = "本机性能校准",
                    subtitle = calibrationStatusText(state.calibrationStatus),
                    enabled = isCalibrationActionEnabled(state.calibrationStatus),
                    onClick = onRunCalibration
                )
            }
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
            if (state.calibrationReports.isNotEmpty()) {
                SectionDivider()
                SettingsCalibrationReportsHeaderV2(
                    reportCount = state.calibrationReports.size,
                    expanded = calibrationReportsExpanded,
                    onClick = {
                        calibrationReportsExpanded =
                            calibrationReportsExpandedAfterToggle(calibrationReportsExpanded)
                    }
                )
                if (calibrationReportsExpanded) {
                    state.calibrationReports.forEach { report ->
                        SettingsClickableRowV2(
                            icon = Icons.Rounded.Memory,
                            title = "${report.mode.name} · ${calibrationReportOutcomeText(report.outcome)}",
                            subtitle = "${formatCalibrationDuration(report.elapsedMs)} · ${report.summary}",
                            onClick = { onOpenCalibrationReport(report.id) }
                        )
                    }
                }
            }
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
            SettingsGroupTitleV2("软件更新")
            SettingsSwitchRowV2(
                icon = Icons.Rounded.SystemUpdate,
                title = "自动检查更新",
                subtitle = "前台启动时每天最多检查一次",
                checked = updateState.automaticEnabled,
                onCheckedChange = onAutomaticUpdateCheckChanged
            )
            SettingsClickableRowV2(
                icon = Icons.Rounded.SystemUpdate,
                title = "检查更新",
                subtitle = updateStatusSubtitle(updateState.status),
                enabled = updateState.status !is UpdateStatus.Checking,
                onClick = onCheckForUpdates
            )

            SectionDivider()
            SettingsGroupTitleV2("隐私与输出")
            SettingsInfoRowV2(
                icon = Icons.Rounded.CloudOff,
                title = "云端上传",
                subtitle = "关闭。所有图片仅在本机处理"
            )
            SettingsInfoRowV2(
                icon = Icons.Rounded.CloudOff,
                title = "更新网络访问",
                subtitle = "更新检查仅请求 GitHub 版本元数据；图片不会上传"
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

internal fun updateStatusSubtitle(status: UpdateStatus): String = when (status) {
    UpdateStatus.Idle -> "每天自动检查，也可立即检查"
    UpdateStatus.Checking -> "正在检查 GitHub Release…"
    is UpdateStatus.UpToDate -> "已是最新版本 ${status.installedVersion}"
    is UpdateStatus.Available -> "发现新版本 ${status.update.version}"
    is UpdateStatus.Failure -> status.message
}

internal fun calibrationReportOutcomeText(outcome: CalibrationReportOutcome): String = when (outcome) {
    CalibrationReportOutcome.SUCCEEDED -> "校准完成"
    CalibrationReportOutcome.COMPLETED_WITH_FALLBACK -> "带回退完成"
    CalibrationReportOutcome.DEFERRED -> "暂缓"
    CalibrationReportOutcome.FAILED -> "校准失败"
    CalibrationReportOutcome.TIMED_OUT -> "已超时"
    CalibrationReportOutcome.UNSUPPORTED -> "不支持"
}

internal fun calibrationReportsExpandedAfterToggle(current: Boolean): Boolean = !current

internal fun calibrationReportsDisclosureAction(expanded: Boolean): String =
    if (expanded) "收起最近校准报告" else "展开最近校准报告"

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
private fun SettingsCalibrationReportsHeaderV2(
    reportCount: Int,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "最近校准报告",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$reportCount 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = calibrationReportsDisclosureAction(expanded),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    SettingsRowFrame(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
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
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
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
