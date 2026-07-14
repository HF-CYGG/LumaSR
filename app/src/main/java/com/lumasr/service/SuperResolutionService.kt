package com.lumasr.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import com.lumasr.R
import com.lumasr.data.FileProcessingTaskRepository
import com.lumasr.data.FileProcessingWorkSpecRepository
import com.lumasr.data.AndroidThermalSignalSource
import com.lumasr.data.ThermalSampler
import com.lumasr.domain.ApplicationProcessingSnapshotBridge
import com.lumasr.domain.ProcessingCoordinator
import com.lumasr.domain.ProcessingSnapshotLease
import com.lumasr.domain.ProcessingSnapshotOwner
import com.lumasr.domain.ProcessingTaskSnapshot
import com.lumasr.domain.ProcessingTaskState
import com.lumasr.ui.createInferenceExecutionProfileResolver
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal enum class IncomingProcessingServiceCommand {
    RESUME,
    CANCEL,
    RECOVER_ONLY
}

internal fun resolveIncomingProcessingServiceCommand(action: String?): IncomingProcessingServiceCommand = when (action) {
    SuperResolutionService.ACTION_RESUME -> IncomingProcessingServiceCommand.RESUME
    SuperResolutionService.ACTION_CANCEL -> IncomingProcessingServiceCommand.CANCEL
    else -> IncomingProcessingServiceCommand.RECOVER_ONLY
}

class SuperResolutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleGate = ProcessingServiceLifecycleGate()
    private val failureBoundary = ProcessingServiceFailureBoundary { code ->
        Log.e(LOG_TAG, code.stableCode)
    }
    private lateinit var notificationManager: NotificationManager
    private lateinit var coordinator: ProcessingCoordinator
    private lateinit var snapshotLease: ProcessingSnapshotLease
    private lateinit var ownerSession: ProcessingServiceOwnerSession

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        startForegroundImmediately(buildNotification(active = null))
        val taskDirectory = File(noBackupFilesDir, TASK_DIRECTORY_NAME)
        coordinator = ProcessingCoordinator(
            taskRepository = FileProcessingTaskRepository(taskDirectory),
            workSpecRepository = FileProcessingWorkSpecRepository(taskDirectory),
            runner = SuperResProcessingTaskRunner(
                thermalLevelProvider = ThermalSampler(
                    AndroidThermalSignalSource(this)
                )::sampleLevel,
                executionProfileResolver = createInferenceExecutionProfileResolver(this)
            )
        )
        snapshotLease = checkNotNull(
            ApplicationProcessingSnapshotBridge.acquire(ProcessingSnapshotOwner.SERVICE)
        )
        ownerSession = ProcessingServiceOwnerSession(
            serviceLease = snapshotLease,
            cleanupScope = ApplicationProcessingServiceCleanupScope.scope,
            relinquishForOwnerHandoff = coordinator::relinquishForOwnerHandoff,
            closeCoordinator = coordinator::close
        )
        serviceScope.launch {
            coordinator.snapshots.collectLatest { snapshots ->
                snapshotLease.publish(snapshots)
                val active = snapshots.firstOrNull { it.task.state == ProcessingTaskState.RUNNING }
                    ?: snapshots.firstOrNull { it.task.state == ProcessingTaskState.QUEUED }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(active))
                stopIfIdle(snapshots)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val operation = lifecycleGate.beginOperation(startId)
        when (resolveIncomingProcessingServiceCommand(intent?.action)) {
            IncomingProcessingServiceCommand.RESUME -> launchOperation(operation) {
                ownerSession.runAfterPredecessorRelease(resolvesRecoveryFailure = true) {
                    coordinator.resumePersistedWork()
                }
            }
            IncomingProcessingServiceCommand.CANCEL -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    launchOperation(operation) {
                        ownerSession.runAfterPredecessorRelease(resolvesRecoveryFailure = true) {
                            coordinator.cancel(taskId)
                        }
                    }
                } else {
                    lifecycleGate.completeOperation(operation)
                    stopIfIdle(coordinator.snapshots.value)
                }
            }

            IncomingProcessingServiceCommand.RECOVER_ONLY -> launchOperation(operation) {
                ownerSession.runAfterPredecessorRelease(resolvesRecoveryFailure = true) {
                    coordinator.start()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        val pauseGeneration = coordinator.requestPause()
        serviceScope.launchWithForegroundDeadline(
            timeoutMs = FOREGROUND_TIMEOUT_BUDGET_MS,
            operation = {
                failureBoundary.run(ProcessingServiceFailureCode.TIMEOUT_FAILED) {
                    // Timeout shutdown must not queue behind normal service operations: the
                    // coordinator owns its lifecycle serialization and invalidates old resumes.
                    coordinator.pauseActive(pauseGeneration)
                }
            },
            // Android 15 requires the timed-out FGS to stop even if a newer startId exists.
            stopService = ::stopSelf
        )
    }

    override fun onDestroy() {
        if (::ownerSession.isInitialized) ownerSession.scheduleCleanup()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Image processing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Local super-resolution progress"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun launchOperation(
        operation: ProcessingServiceLifecycleGate.Operation,
        block: suspend () -> Unit
    ) {
        serviceScope.launch {
            failureBoundary.run(ProcessingServiceFailureCode.OPERATION_FAILED) {
                try {
                    block()
                } finally {
                    lifecycleGate.completeOperation(operation)
                    stopIfIdle(coordinator.snapshots.value)
                }
            }
        }
    }

    private fun stopIfIdle(snapshots: List<ProcessingTaskSnapshot>) {
        val isIdle = snapshots.none {
            it.task.state == ProcessingTaskState.RUNNING || it.task.state == ProcessingTaskState.QUEUED
        }
        val candidate = lifecycleGate.idleStopCandidate(isIdle) ?: return
        if (lifecycleGate.isStopStillValid(candidate)) {
            stopSelfResult(candidate.startId)
        }
    }

    private fun buildNotification(active: ProcessingTaskSnapshot?): Notification {
        val progress = active?.progress?.progress?.coerceIn(0f, 1f)
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(if (active == null) "LumaSR is ready" else "Enhancing image")
            .setContentText(active?.progress?.message ?: "Preparing local processing")
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(active != null)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)

        active?.let { snapshot ->
            val cancelIntent = Intent(this, SuperResolutionService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, snapshot.task.id)
            }
            val pendingCancel = PendingIntent.getService(
                this,
                snapshot.task.id.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher_monochrome),
                    "Cancel",
                    pendingCancel
                ).build()
            )
        }
        return builder.build()
    }

    private fun startForegroundImmediately(notification: Notification) {
        when {
            Build.VERSION.SDK_INT >= 35 -> startMediaProcessingForeground(notification)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startDataSyncForeground(notification)
            else -> startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(35)
    private fun startMediaProcessingForeground(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startDataSyncForeground(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        const val ACTION_RESUME = "com.lumasr.action.RESUME_PERSISTED_PROCESSING"
        const val ACTION_CANCEL = "com.lumasr.action.CANCEL_PROCESSING"
        const val EXTRA_TASK_ID = "com.lumasr.extra.TASK_ID"

        private const val TASK_DIRECTORY_NAME = "lumasr-tasks"
        private const val NOTIFICATION_CHANNEL_ID = "lumasr-processing"
        private const val NOTIFICATION_ID = 2001
        private const val FOREGROUND_TIMEOUT_BUDGET_MS = 4_500L
        private const val LOG_TAG = "LumaSRProcessing"
    }
}
