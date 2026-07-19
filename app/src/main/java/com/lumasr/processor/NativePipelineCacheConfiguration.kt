package com.lumasr.processor

import com.lumasr.domain.DeviceCapabilityProfile
import java.io.File

data class NativePipelineCacheConfiguration(
    val directory: File,
    val enabled: Boolean
)

internal class NativePipelineCacheConfigurator(
    private val configureNative: (NativePipelineCacheConfiguration) -> Boolean
) {
    private var appliedConfiguration: NativePipelineCacheConfiguration? = null

    @Synchronized
    fun configure(configuration: NativePipelineCacheConfiguration): Boolean {
        val normalized = runCatching {
            configuration.copy(directory = configuration.directory.canonicalFile)
        }.getOrNull() ?: return false
        if (appliedConfiguration == normalized) return true
        if (!configureNative(normalized)) return false
        appliedConfiguration = normalized
        return true
    }
}

object NativePipelineCacheConfigurationFactory {
    private const val DIRECTORY_NAME = "ncnn-pipeline-cache"

    fun create(
        noBackupFilesDir: File,
        device: DeviceCapabilityProfile
    ): NativePipelineCacheConfiguration {
        val root = noBackupFilesDir.canonicalFile
        val directory = File(root, DIRECTORY_NAME).canonicalFile
        check(directory.toPath().startsWith(root.toPath())) {
            "Native pipeline cache directory must stay inside no-backup storage"
        }
        val quirks = device.vulkanCapabilities?.driverQuirks
        return NativePipelineCacheConfiguration(
            directory = directory,
            enabled = device.isQualcommAdreno && quirks?.corruptedOnlinePipelineCache == false
        )
    }
}
