package com.lumasr.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeviceCapabilityRepositoryTest {
    @Test
    fun assemblesLowRamStateAndStableMemoryBucket() {
        val profile = repository(
            androidSnapshot = androidSnapshot(
                isLowRamDevice = true,
                totalMemoryBytes = 6_500L * MEBIBYTE
            )
        ).loadProfile()

        assertTrue(profile.isLowRamDevice)
        assertEquals(6_144, profile.memoryBucketMb)
    }

    @Test
    fun prefersNativeCpuTopologyAndCopiesGpuCapabilities() {
        val profile = repository(
            androidSnapshot = androidSnapshot(runtimeCoreCount = 12),
            nativeSnapshot = NativeCapabilitySnapshot(
                physicalCoreCount = 8,
                bigCoreCount = 3,
                littleCoreCount = 5,
                gpuVendor = "ARM",
                gpuDevice = "Mali-G715",
                gpuDriverVersion = "r42p0",
                gpuRoughScore = 850,
                supportsVulkan = true,
                supportsFp16Storage = true,
                supportsFp16Arithmetic = true
            )
        ).loadProfile()

        assertEquals(8, profile.physicalCoreCount)
        assertEquals(3, profile.bigCoreCount)
        assertEquals(5, profile.littleCoreCount)
        assertEquals("ARM", profile.gpuVendor)
        assertEquals("Mali-G715", profile.gpuDevice)
        assertEquals("r42p0", profile.gpuDriverVersion)
        assertEquals(850, profile.gpuRoughScore)
        assertTrue(profile.supportsVulkan)
        assertTrue(profile.supportsFp16Storage)
        assertTrue(profile.supportsFp16Arithmetic)
    }

    @Test
    fun publishesStructuredAdrenoCapabilitiesAndDedicatedCalibrationIdentity() {
        val profile = repository(
            nativeSnapshot = NativeCapabilitySnapshot(
                physicalCoreCount = 8,
                gpuVendor = "vendor=0x00005143",
                gpuDevice = "Adreno 840",
                gpuDriverVersion = "id=0x00000003 version=0x08010000",
                gpuVendorId = 0x5143L,
                gpuDeviceId = 0x0840L,
                gpuApiVersion = 0x00403000L,
                gpuDriverId = 3L,
                gpuDriverVersionRaw = 0x08010000L,
                pipelineCacheUuid = "00112233445566778899aabbccddeeff",
                subgroupSize = 64,
                subgroupMinSize = 32,
                subgroupMaxSize = 128,
                computeQueueCount = 2,
                maxComputeSharedMemoryBytes = 32_768L,
                supportsSubgroupOps = true,
                supportsSubgroupSizeControl = true,
                supportsComputeFullSubgroups = true,
                corruptedOnlinePipelineCache = false,
                storageBufferNoL1 = true,
                bufferImageLoadZero = false,
                implicitFp16Arithmetic = false,
                supportsVulkan = true,
                supportsFp16Storage = true,
                supportsFp16Arithmetic = true
            )
        ).loadProfile()

        assertTrue(profile.isQualcommAdreno)
        assertEquals(0x5143L, profile.vulkanCapabilities?.vendorId)
        assertEquals(64, profile.vulkanCapabilities?.subgroupSize)
        assertEquals(2, profile.vulkanCapabilities?.computeQueueCount)
        assertTrue(profile.vulkanCapabilities?.driverQuirks?.storageBufferNoL1 == true)
        assertTrue(profile.acceleratorCalibrationKey.orEmpty().startsWith("adreno-capability-v1:"))
        assertFalse(profile.acceleratorCalibrationKey.orEmpty().contains("Adreno"))
    }

    @Test
    fun structuredDriverChangesOnlyAdrenoIdentityAndNonQualcommStaysCompatible() {
        fun snapshot(vendorId: Long, driverVersion: Long) = NativeCapabilitySnapshot(
            physicalCoreCount = 8,
            gpuVendor = if (vendorId == 0x5143L) "Qualcomm" else "ARM",
            gpuDevice = if (vendorId == 0x5143L) "Adreno" else "Mali",
            gpuDriverVersion = "stable-display-string",
            gpuVendorId = vendorId,
            gpuDeviceId = 7L,
            gpuDriverId = 3L,
            gpuDriverVersionRaw = driverVersion,
            supportsVulkan = true,
            supportsFp16Storage = true,
            supportsFp16Arithmetic = true
        )

        val adrenoV1 = repository(nativeSnapshot = snapshot(0x5143L, 1L)).loadProfile()
        val adrenoV2 = repository(nativeSnapshot = snapshot(0x5143L, 2L)).loadProfile()
        val maliV1 = repository(nativeSnapshot = snapshot(0x13B5L, 1L)).loadProfile()
        val maliV2 = repository(nativeSnapshot = snapshot(0x13B5L, 2L)).loadProfile()

        assertEquals(adrenoV1.profileKey, adrenoV2.profileKey)
        assertNotEquals(adrenoV1.acceleratorCalibrationKey, adrenoV2.acceleratorCalibrationKey)
        assertEquals(maliV1.profileKey, maliV2.profileKey)
        assertNull(maliV1.acceleratorCalibrationKey)
        assertNull(maliV2.acceleratorCalibrationKey)
    }

    @Test
    fun fallsBackToRuntimeTopologyWhenNativeTopologyIsUnavailable() {
        val profile = repository(
            androidSnapshot = androidSnapshot(runtimeCoreCount = 6),
            nativeSnapshot = NativeCapabilitySnapshot(
                physicalCoreCount = 0,
                bigCoreCount = 4,
                littleCoreCount = 4
            )
        ).loadProfile()

        assertEquals(6, profile.physicalCoreCount)
        assertNull(profile.bigCoreCount)
        assertNull(profile.littleCoreCount)
    }

    @Test
    fun createsStableOpaqueProfileIdentityFromCapabilities() {
        val first = repository().loadProfile()
        val repeated = repository().loadProfile()
        val changedFingerprint = repository(
            androidSnapshot = androidSnapshot(fingerprint = "release/fingerprint-v2")
        ).loadProfile()

        assertEquals(first.profileKey, repeated.profileKey)
        assertNotEquals(first.profileKey, changedFingerprint.profileKey)
        assertTrue(first.profileKey.startsWith("${DeviceCapabilityRepository.SCHEMA_KEY}:"))
        assertFalse(first.profileKey.contains("release/fingerprint-v1"))
        assertFalse(first.profileKey.contains("Mali", ignoreCase = true))
    }

    @Test
    fun capabilityCollectionDoesNotBranchOnManufacturerOrBrand() {
        val source = File("src/main/java/com/lumasr/data/DeviceCapabilityRepository.kt").readText()

        assertFalse(source.contains("Build.MANUFACTURER"))
        assertFalse(source.contains("Build.BRAND"))
        assertFalse(source.contains("manufacturer", ignoreCase = true))
    }

    @Test
    fun releaseRulesKeepCapabilityJniAndReflectedSnapshotConstructor() {
        val rules = File("proguard-rules.pro").readText()

        assertTrue(rules.contains("-keep class com.lumasr.data.NativeCapabilityJni"))
        assertTrue(rules.contains("native <methods>;"))
        assertTrue(rules.contains("-keep class com.lumasr.data.NativeCapabilitySnapshot"))
        assertTrue(
            rules.contains(
                "public <init>(java.lang.Integer,java.lang.Integer,java.lang.Integer," +
                    "java.lang.String,java.lang.String,java.lang.String,java.lang.Integer," +
                    "long,long,long,long,long,java.lang.String,int,int,int,int,long," +
                    "boolean,boolean,boolean,boolean,boolean,boolean,boolean,boolean,boolean,boolean);"
            )
        )
    }

    @Test
    fun androidNativeProbeFailsClosedWhenJniIsUnavailable() {
        val probe = AndroidNativeCapabilityProbe {
            throw UnsatisfiedLinkError("JNI is not installed yet")
        }

        assertEquals(NativeCapabilitySnapshot.UNKNOWN, probe.snapshot())
    }

    @Test
    fun libraryLoadFailureSkipsNativeCapabilityCall() {
        var nativeCalls = 0
        val reader = NativeCapabilitySnapshotReader(
            libraryLoader = { false },
            nativeReader = {
                nativeCalls += 1
                NativeCapabilitySnapshot(physicalCoreCount = 8)
            }
        )

        assertNull(reader.readSnapshot())
        assertEquals(0, nativeCalls)
    }

    @Test
    fun sanitizesPartialNativeSnapshotBeforePublishingOrHashing() {
        val unboundedVendor = "\u0000  " + "V".repeat(256) + "  \u0007"
        val profile = repository(
            nativeSnapshot = NativeCapabilitySnapshot(
                physicalCoreCount = 6,
                bigCoreCount = null,
                littleCoreCount = 4,
                gpuVendor = unboundedVendor,
                gpuDevice = "  Partial GPU  ",
                gpuDriverVersion = "\u0000\t\r\n",
                gpuRoughScore = -1,
                supportsVulkan = false
            )
        ).loadProfile()

        assertEquals(6, profile.physicalCoreCount)
        assertNull(profile.bigCoreCount)
        assertEquals(4, profile.littleCoreCount)
        assertEquals(DeviceCapabilityRepository.MAX_CAPABILITY_STRING_LENGTH, profile.gpuVendor?.length)
        assertTrue(profile.gpuVendor.orEmpty().all { !it.isISOControl() })
        assertEquals("Partial GPU", profile.gpuDevice)
        assertNull(profile.gpuDriverVersion)
        assertNull(profile.gpuRoughScore)
    }

    private fun repository(
        androidSnapshot: AndroidCapabilitySnapshot = androidSnapshot(),
        nativeSnapshot: NativeCapabilitySnapshot = NativeCapabilitySnapshot(
            physicalCoreCount = 8,
            bigCoreCount = 3,
            littleCoreCount = 5,
            gpuVendor = "ARM",
            gpuDevice = "Mali-G715",
            gpuDriverVersion = "r42p0",
            gpuRoughScore = 850,
            supportsVulkan = true,
            supportsFp16Storage = true,
            supportsFp16Arithmetic = true
        )
    ) = DeviceCapabilityRepository(
        androidSource = AndroidCapabilitySource { androidSnapshot },
        nativeProbe = NativeCapabilityProbe { nativeSnapshot }
    )

    private fun androidSnapshot(
        sdkInt: Int = 35,
        fingerprint: String = "release/fingerprint-v1",
        isLowRamDevice: Boolean = false,
        totalMemoryBytes: Long = 8_500L * MEBIBYTE,
        runtimeCoreCount: Int = 12
    ) = AndroidCapabilitySnapshot(
        sdkInt = sdkInt,
        fingerprint = fingerprint,
        isLowRamDevice = isLowRamDevice,
        totalMemoryBytes = totalMemoryBytes,
        runtimeCoreCount = runtimeCoreCount
    )

    private companion object {
        const val MEBIBYTE = 1_024L * 1_024L
    }
}
