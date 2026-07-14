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
import com.lumasr.domain.ProcessingCoordinator
import com.lumasr.domain.ProcessingTaskSnapshot
import com.lumasr.domain.ProcessingTaskState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SuperResolutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleGate = ProcessingServiceLifecycleGate()
    private val failureBoundary = ProcessingServiceFailureBoundary { code ->
        Log.e(LOG_TAG, code.stableCode)
    }
    private lateinit var notificationManager: NotificationManager
    private lateinit var coordinator: ProcessingCoordinator

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        startForegroundImmediately(buildNotification(active = null))

        val taskDirectory = File(noBackupFilesDir, TASK_DIRECTORY_NAME)
        coordinator = ProcessingCoordinator(
            taskRepository = FileProcessingTaskRepository(taskDirectory),
            workSpecRepository = FileProcessingWorkSpecRepository(taskDirectory),
            runner = SuperResProcessingTaskRunner()
        )
        serviceScope.launch {
            coordinator.snapshots.collectLatest { snapshots ->
                val active = snapshots.firstOrNull { it.task.state == ProcessingTaskState.RUNNING }
                    ?: snapshots.firstOrNull { it.task.state == ProcessingTaskState.QUEUED }
                notificationManager.notify(NOTIFICATION_ID, buildNotification(active))
                stopIfIdle(snapshots)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val operation = lifecycleGate.beginOperation(startId)
        when (intent?.action) {
            ACTION_RESUME -> launchOperation(operation) { coordinator.resumePersistedWork() }
            ACTION_CANCEL -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                if (!taskId.isNullOrBlank()) {
                    launchOperation(operation) { coordinator.cancel(taskId) }
                } else {
                    lifecycleGate.completeOperation(operation)
                    stopIfIdle(coordinator.snapshots.value)
                }
            }

            else -> launchOperation(operation) { coordinator.resumePersistedWork() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.launch {
            failureBoundary.run(ProcessingServiceFailureCode.TIMEOUT_FAILED) {
                try {
                    coordinator.pauseActive()
                } finally {
                    stopSelfResult(startId)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::coordinator.isInitialized) coordinator.close()
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
        private const val LOG_TAG = "LumaSRProcessing"
    }
}
