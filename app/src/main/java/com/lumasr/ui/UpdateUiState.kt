package com.lumasr.ui

import com.lumasr.domain.AppVersion
import com.lumasr.domain.AvailableUpdate

const val AutomaticUpdateCheckIntervalMillis: Long = 24L * 60L * 60L * 1_000L

data class UpdateUiState(
    val automaticEnabled: Boolean = true,
    val lastAttemptAtMillis: Long? = null,
    val status: UpdateStatus = UpdateStatus.Idle,
    val showUpdateDialog: Boolean = false
)

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val installedVersion: AppVersion) : UpdateStatus
    data class Available(val update: AvailableUpdate) : UpdateStatus
    data class Failure(val message: String) : UpdateStatus
}

fun shouldAutomaticallyCheck(
    enabled: Boolean,
    lastAttemptAtMillis: Long?,
    nowMillis: Long
): Boolean {
    if (!enabled) return false
    if (lastAttemptAtMillis == null || nowMillis < lastAttemptAtMillis) return true
    return nowMillis - lastAttemptAtMillis >= AutomaticUpdateCheckIntervalMillis
}
