package com.lumasr.processor

import com.lumasr.domain.DeviceCapabilityProfile
import java.io.File

data class NativePipelineCacheConfiguration(
    val directory: File,
    val enabled: Boolean
)

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
