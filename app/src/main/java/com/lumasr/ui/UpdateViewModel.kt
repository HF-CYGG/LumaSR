package com.lumasr.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lumasr.BuildConfig
import com.lumasr.data.GitHubReleaseSource
import com.lumasr.data.NoPublishedReleaseException
import com.lumasr.data.UpdatePreferences
import com.lumasr.data.UpdatePreferencesRepository
import com.lumasr.domain.CheckForUpdateUseCase
import com.lumasr.domain.UpdateCheckResult
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class UpdateViewModel(
    private val checkForUpdate: CheckForUpdateUseCase,
    private val preferences: UpdatePreferences,
    private val installedVersion: String,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        UpdateUiState(
            automaticEnabled = preferences.isAutomaticCheckEnabled(),
            lastAttemptAtMillis = preferences.lastAttemptAtMillis()
        )
    )
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var checkJob: Job? = null

    fun onHostStarted() {
        val state = _uiState.value
        val now = currentTimeMillis()
        if (
            shouldAutomaticallyCheck(
                enabled = state.automaticEnabled,
                lastAttemptAtMillis = state.lastAttemptAtMillis,
                nowMillis = now
            )
        ) {
            startCheck(manual = false, nowMillis = now)
        }
    }

    fun checkNow() {
        startCheck(manual = true, nowMillis = currentTimeMillis())
    }

    fun setAutomaticEnabled(enabled: Boolean) {
        preferences.setAutomaticCheckEnabled(enabled)
        _uiState.update { it.copy(automaticEnabled = enabled) }
    }

    fun dismissUpdateDialog() {
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun reportLinkOpenFailure() {
        _uiState.update {
            it.copy(
                status = UpdateStatus.Failure("无法打开系统浏览器，请稍后重试。"),
                showUpdateDialog = false
            )
        }
    }

    private fun startCheck(manual: Boolean, nowMillis: Long) {
        if (checkJob?.isActive == true) return
        preferences.setLastAttemptAtMillis(nowMillis)
        _uiState.update {
            it.copy(
                lastAttemptAtMillis = nowMillis,
                status = UpdateStatus.Checking,
                showUpdateDialog = false
            )
        }
        checkJob = viewModelScope.launch {
            runCatching { checkForUpdate.check(installedVersion) }
                .onSuccess { result ->
                    _uiState.update { state ->
                        when (result) {
                            is UpdateCheckResult.Available -> state.copy(
                                status = UpdateStatus.Available(result.update),
                                showUpdateDialog = true
                            )
                            is UpdateCheckResult.UpToDate -> state.copy(
                                status = UpdateStatus.UpToDate(result.installedVersion),
                                showUpdateDialog = false
                            )
                        }
                    }
                }
                .onFailure { error ->
                    _uiState.update { state ->
                        if (manual) {
                            state.copy(
                                status = UpdateStatus.Failure(error.toSafeUpdateMessage()),
                                showUpdateDialog = false
                            )
                        } else {
                            state.copy(status = UpdateStatus.Idle, showUpdateDialog = false)
                        }
                    }
                }
            checkJob = null
        }
    }

    class Factory(
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val source = GitHubReleaseSource(
                userAgent = "LumaSR-Android/${BuildConfig.VERSION_NAME}"
            )
            return UpdateViewModel(
                checkForUpdate = CheckForUpdateUseCase(source),
                preferences = UpdatePreferencesRepository.fromContext(application),
                installedVersion = BuildConfig.VERSION_NAME
            ) as T
        }
    }
}

private fun Throwable.toSafeUpdateMessage(): String = when (this) {
    is NoPublishedReleaseException -> "GitHub 暂无可用的正式版本。"
    is IllegalArgumentException -> "最新 GitHub Release 配置不完整。"
    is IOException -> "网络连接失败，请稍后重试。"
    else -> "检查更新失败，请稍后重试。"
}
