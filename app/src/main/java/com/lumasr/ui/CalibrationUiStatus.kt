package com.lumasr.ui

import com.lumasr.domain.CalibrationPerformanceGrade

enum class CalibrationUiStatus {
    NOT_RUN,
    RUNNING,
    READY_ELITE,
    READY_HIGH_PERFORMANCE,
    READY_STABLE,
    READY_COMPATIBLE,
    DEFERRED,
    FAILED,
    UNSUPPORTED,
    SKIPPED
}

internal fun calibrationStatusText(status: CalibrationUiStatus): String = when (status) {
    CalibrationUiStatus.NOT_RUN -> "未校准，将使用兼容模式"
    CalibrationUiStatus.RUNNING -> "正在本机校准…"
    CalibrationUiStatus.READY_ELITE -> "已校准：极致"
    CalibrationUiStatus.READY_HIGH_PERFORMANCE -> "已校准：高性能"
    CalibrationUiStatus.READY_STABLE -> "已校准：稳定"
    CalibrationUiStatus.READY_COMPATIBLE -> "已校准：兼容"
    CalibrationUiStatus.DEFERRED -> "校准暂缓，可稍后重试"
    CalibrationUiStatus.FAILED -> "校准未通过，将继续使用兼容模式"
    CalibrationUiStatus.UNSUPPORTED -> "设备不支持安全加速校准"
    CalibrationUiStatus.SKIPPED -> "已跳过，将使用兼容模式"
}

internal fun CalibrationPerformanceGrade.toCalibrationUiStatus(): CalibrationUiStatus = when (this) {
    CalibrationPerformanceGrade.ELITE -> CalibrationUiStatus.READY_ELITE
    CalibrationPerformanceGrade.HIGH_PERFORMANCE -> CalibrationUiStatus.READY_HIGH_PERFORMANCE
    CalibrationPerformanceGrade.STABLE -> CalibrationUiStatus.READY_STABLE
    CalibrationPerformanceGrade.COMPATIBLE -> CalibrationUiStatus.READY_COMPATIBLE
}

internal fun shouldShowCalibrationPrompt(
    preferencePending: Boolean,
    hasSelectedModel: Boolean,
    deviceSupported: Boolean
): Boolean = preferencePending && hasSelectedModel && deviceSupported

internal fun canStartCalibration(state: LumaUiState): Boolean =
    state.selectedModelId != null &&
        state.screen == LumaScreen.EDITING &&
        state.calibrationStatus != CalibrationUiStatus.RUNNING

internal fun canChangeModel(state: LumaUiState): Boolean =
    state.calibrationStatus != CalibrationUiStatus.RUNNING

internal fun isCalibrationActionEnabled(status: CalibrationUiStatus): Boolean =
    status != CalibrationUiStatus.RUNNING

internal fun isCurrentCalibrationModel(
    measuredModelId: String,
    selectedModelId: String?
): Boolean = measuredModelId == selectedModelId
