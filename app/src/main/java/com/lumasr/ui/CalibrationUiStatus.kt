package com.lumasr.ui

enum class CalibrationUiStatus {
    NOT_RUN,
    RUNNING,
    READY,
    FAILED,
    UNSUPPORTED,
    SKIPPED
}

internal fun calibrationStatusText(status: CalibrationUiStatus): String = when (status) {
    CalibrationUiStatus.NOT_RUN -> "未校准，将使用兼容模式"
    CalibrationUiStatus.RUNNING -> "正在本机校准…"
    CalibrationUiStatus.READY -> "已校准，高性能模式可用"
    CalibrationUiStatus.FAILED -> "校准未通过，将继续使用兼容模式"
    CalibrationUiStatus.UNSUPPORTED -> "设备不支持安全加速校准"
    CalibrationUiStatus.SKIPPED -> "已跳过，将使用兼容模式"
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
