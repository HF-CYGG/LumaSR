package com.lumasr.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumasr.domain.CalibrationPerformanceGrade
import com.lumasr.domain.CalibrationTierOutcome
import com.lumasr.domain.ResolutionTier
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun CalibrationProgressDialog(
    state: CalibrationProgressDialogState,
    onDismiss: () -> Unit
) {
    val logScrollState = rememberScrollState()
    LaunchedEffect(logScrollState) {
        snapshotFlow { logScrollState.maxValue }.collectLatest { maxValue ->
            logScrollState.scrollTo(maxValue)
        }
    }
    Dialog(
        onDismissRequest = { if (state.canDismiss) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = state.canDismiss,
            dismissOnClickOutside = state.canDismiss,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .widthIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${calibrationModeLabel(state.mode)}${calibrationDialogTitle(state.status)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = calibrationStepText(state),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${(state.progressFraction * 100f).toInt().coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = state.currentStep,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                LinearProgressIndicator(
                    progress = { state.progressFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已用时间：${formatCalibrationDuration(state.elapsedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = calibrationRemainingText(state.estimatedRemainingMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.tierOutcomes.isNotEmpty()) {
                    Text(
                        text = "分辨率档位" + state.overallGrade?.let {
                            " · 总体${calibrationGradeLabel(it)}"
                        }.orEmpty(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.tierOutcomes.sortedBy { it.resolutionTier.ordinal }.forEach { outcome ->
                            CalibrationTierSummary(
                                outcome = outcome,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "真实校准日志",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 190.dp, max = 390.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = CalibrationConsoleBackground
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(logScrollState)
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (state.logLines.isEmpty()) {
                                ConsoleLine("> 等待第一个真实校准事件…")
                            } else {
                                state.logLines.forEach { line ->
                                    ConsoleLine(
                                        "[+${formatCalibrationDuration(line.elapsedMs)}] ${line.text}"
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                }
                if (state.canDismiss) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationTierSummary(
    outcome: CalibrationTierOutcome,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = outcome.resolutionTier.calibrationTierLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = calibrationGradeLabel(outcome.grade),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = outcome.speedupPercent?.let {
                    String.format(Locale.US, "%+.1f%%", it)
                } ?: outcome.fallbackReason?.name.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun calibrationGradeLabel(grade: CalibrationPerformanceGrade): String = when (grade) {
    CalibrationPerformanceGrade.ELITE -> "极致"
    CalibrationPerformanceGrade.HIGH_PERFORMANCE -> "高性能"
    CalibrationPerformanceGrade.STABLE -> "稳定"
    CalibrationPerformanceGrade.COMPATIBLE -> "兼容"
}

private val ResolutionTier.calibrationTierLabel: String
    get() = when (this) {
        ResolutionTier.SMALL -> "SMALL"
        ResolutionTier.STANDARD -> "STANDARD"
        ResolutionTier.LARGE -> "LARGE"
    }

@Composable
private fun ConsoleLine(text: String) {
    Text(
        text = text,
        color = CalibrationConsoleText,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )
}

internal fun formatCalibrationDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun calibrationRemainingText(remainingMs: Long?): String = if (remainingMs == null) {
    "预计剩余：正在估算"
} else {
    "预计剩余：${formatCalibrationDuration(remainingMs)}"
}

private fun calibrationStepText(state: CalibrationProgressDialogState): String {
    if (state.totalSteps <= 0) return "正在准备"
    val current = state.stepIndex.coerceIn(0, state.totalSteps)
    return "步骤 $current/${state.totalSteps}"
}

private fun calibrationDialogTitle(status: CalibrationDialogStatus): String = when (status) {
    CalibrationDialogStatus.RUNNING -> "正在进行性能校准"
    CalibrationDialogStatus.SUCCEEDED -> "性能校准完成"
    CalibrationDialogStatus.COMPLETED_WITH_FALLBACK -> "性能校准已带回退完成"
    CalibrationDialogStatus.DEFERRED -> "性能校准暂缓"
    CalibrationDialogStatus.FAILED -> "性能校准未通过"
    CalibrationDialogStatus.TIMED_OUT -> "性能校准已超时"
    CalibrationDialogStatus.UNSUPPORTED -> "当前设备不支持校准"
}

private fun calibrationModeLabel(mode: com.lumasr.domain.CalibrationMode): String = when (mode) {
    com.lumasr.domain.CalibrationMode.LEGACY -> ""
    com.lumasr.domain.CalibrationMode.QUICK -> "快速 · "
    com.lumasr.domain.CalibrationMode.DEEP -> "深度 · "
}

private val CalibrationConsoleBackground = Color(0xFF0B1118)
private val CalibrationConsoleText = Color(0xFFD7E4F2)
