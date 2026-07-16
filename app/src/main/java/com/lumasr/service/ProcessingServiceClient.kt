package com.lumasr.service

import android.content.Context
import android.content.Intent

sealed interface ProcessingServiceCommand {
    data object ResumePersistedWork : ProcessingServiceCommand

    data class Cancel(val taskId: String) : ProcessingServiceCommand
}

fun interface ProcessingServiceStarter {
    fun start(command: ProcessingServiceCommand)
}

enum class ForegroundFallbackReason {
    START_NOT_ALLOWED,
    SECURITY_RESTRICTION,
    START_FAILED
}

sealed interface ProcessingServiceStartResult {
    data object Started : ProcessingServiceStartResult

    data class ForegroundOnlyFallback(
        val reason: ForegroundFallbackReason
    ) : ProcessingServiceStartResult
}

class ProcessingServiceClient(
    private val starter: ProcessingServiceStarter
) {
    constructor(context: Context) : this(AndroidProcessingServiceStarter(context.applicationContext))

    fun resumePersistedWork(): ProcessingServiceStartResult {
        return start(ProcessingServiceCommand.ResumePersistedWork)
    }

    fun cancel(taskId: String): ProcessingServiceStartResult {
        require(taskId.isNotBlank()) { "Task id must not be blank" }
        return start(ProcessingServiceCommand.Cancel(taskId))
    }

    private fun start(command: ProcessingServiceCommand): ProcessingServiceStartResult {
        return try {
            starter.start(command)
            ProcessingServiceStartResult.Started
        } catch (_: SecurityException) {
            ProcessingServiceStartResult.ForegroundOnlyFallback(
                ForegroundFallbackReason.SECURITY_RESTRICTION
            )
        } catch (_: IllegalStateException) {
            ProcessingServiceStartResult.ForegroundOnlyFallback(
                ForegroundFallbackReason.START_NOT_ALLOWED
            )
        } catch (_: RuntimeException) {
            ProcessingServiceStartResult.ForegroundOnlyFallback(
                ForegroundFallbackReason.START_FAILED
            )
        } catch (_: Exception) {
            ProcessingServiceStartResult.ForegroundOnlyFallback(
                ForegroundFallbackReason.START_FAILED
            )
        }
    }
}

private class AndroidProcessingServiceStarter(
    private val context: Context
) : ProcessingServiceStarter {
    override fun start(command: ProcessingServiceCommand) {
        val intent = Intent(context, SuperResolutionService::class.java).apply {
            when (command) {
                ProcessingServiceCommand.ResumePersistedWork -> action = SuperResolutionService.ACTION_RESUME
                is ProcessingServiceCommand.Cancel -> {
                    action = SuperResolutionService.ACTION_CANCEL
                    putExtra(SuperResolutionService.EXTRA_TASK_ID, command.taskId)
                }
            }
        }
        context.startForegroundService(intent)
    }
}
