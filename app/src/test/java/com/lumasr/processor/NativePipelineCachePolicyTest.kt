package com.lumasr.processor

import com.lumasr.domain.DeviceCapabilityProfile
import com.lumasr.domain.VulkanDeviceCapabilities
import com.lumasr.domain.VulkanDriverQuirks
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePipelineCachePolicyTest {
    @Test
    fun enablesOnlySafeQualcommDeviceInsideNoBackupRoot() {
        val root = Files.createTempDirectory("pipeline-policy").toFile()
        val safe = NativePipelineCacheConfigurationFactory.create(root, device(corrupted = false))
        val corrupted = NativePipelineCacheConfigurationFactory.create(root, device(corrupted = true))
        val nonQualcomm = NativePipelineCacheConfigurationFactory.create(
            root,
            device(corrupted = false, vendorId = 0x13B5L)
        )

        assertTrue(safe.enabled)
        assertTrue(safe.directory.toPath().startsWith(root.canonicalFile.toPath()))
        assertFalse(corrupted.enabled)
        assertFalse(nonQualcomm.enabled)
        root.deleteRecursively()
    }

    private fun device(
        corrupted: Boolean,
        vendorId: Long = VulkanDeviceCapabilities.QUALCOMM_VENDOR_ID
    ) = DeviceCapabilityProfile(
        schemaKey = "device-capability-v1",
        profileKey = "device-capability-v1:test",
        sdkInt = 35,
        isLowRamDevice = false,
        memoryBucketMb = 8192,
        physicalCoreCount = 8,
        bigCoreCount = 2,
        littleCoreCount = 6,
        gpuVendor = "vendor",
        gpuDevice = "gpu",
        gpuDriverVersion = "driver",
        gpuRoughScore = 100,
        supportsVulkan = true,
        supportsFp16Storage = true,
        supportsFp16Arithmetic = true,
        vulkanCapabilities = VulkanDeviceCapabilities(
            vendorId = vendorId,
            deviceId = 1,
            apiVersion = 1,
            driverId = 1,
            driverVersion = 1,
            pipelineCacheUuid = "0".repeat(32),
            subgroupSize = 64,
            subgroupMinSize = 32,
            subgroupMaxSize = 128,
            computeQueueCount = 2,
            maxComputeSharedMemoryBytes = 32_768,
            supportsSubgroupOps = true,
            supportsSubgroupSizeControl = true,
            supportsComputeFullSubgroups = true,
            driverQuirks = VulkanDriverQuirks(
                corruptedOnlinePipelineCache = corrupted,
                storageBufferNoL1 = false,
                bufferImageLoadZero = false,
                implicitFp16Arithmetic = false
            )
        ),
        acceleratorCalibrationKey = if (vendorId == VulkanDeviceCapabilities.QUALCOMM_VENDOR_ID) {
            "adreno-capability-v1:key"
        } else {
            null
        }
    )
}
