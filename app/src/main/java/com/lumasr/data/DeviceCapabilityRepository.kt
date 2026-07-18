package com.lumasr.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.lumasr.domain.DeviceCapabilityProfile
import com.lumasr.domain.VulkanDeviceCapabilities
import com.lumasr.domain.VulkanDriverQuirks
import java.security.MessageDigest

data class NativeCapabilitySnapshot(
    val physicalCoreCount: Int? = null,
    val bigCoreCount: Int? = null,
    val littleCoreCount: Int? = null,
    val gpuVendor: String? = null,
    val gpuDevice: String? = null,
    val gpuDriverVersion: String? = null,
    val gpuRoughScore: Int? = null,
    val gpuVendorId: Long = -1L,
    val gpuDeviceId: Long = -1L,
    val gpuApiVersion: Long = -1L,
    val gpuDriverId: Long = -1L,
    val gpuDriverVersionRaw: Long = -1L,
    val pipelineCacheUuid: String? = null,
    val subgroupSize: Int = -1,
    val subgroupMinSize: Int = -1,
    val subgroupMaxSize: Int = -1,
    val computeQueueCount: Int = -1,
    val maxComputeSharedMemoryBytes: Long = -1L,
    val supportsSubgroupOps: Boolean = false,
    val supportsSubgroupSizeControl: Boolean = false,
    val supportsComputeFullSubgroups: Boolean = false,
    val corruptedOnlinePipelineCache: Boolean = false,
    val storageBufferNoL1: Boolean = false,
    val bufferImageLoadZero: Boolean = false,
    val implicitFp16Arithmetic: Boolean = false,
    val supportsVulkan: Boolean = false,
    val supportsFp16Storage: Boolean = false,
    val supportsFp16Arithmetic: Boolean = false
) {
    companion object {
        val UNKNOWN = NativeCapabilitySnapshot()
    }
}

fun interface NativeCapabilityProbe {
    fun snapshot(): NativeCapabilitySnapshot
}

class AndroidNativeCapabilityProbe(
    private val snapshotReader: () -> NativeCapabilitySnapshot? = NativeCapabilitySource::readSnapshot
) : NativeCapabilityProbe {
    override fun snapshot(): NativeCapabilitySnapshot {
        return try {
            snapshotReader() ?: NativeCapabilitySnapshot.UNKNOWN
        } catch (_: LinkageError) {
            NativeCapabilitySnapshot.UNKNOWN
        } catch (_: SecurityException) {
            NativeCapabilitySnapshot.UNKNOWN
        } catch (_: RuntimeException) {
            NativeCapabilitySnapshot.UNKNOWN
        }
    }
}

internal class NativeCapabilitySnapshotReader(
    private val libraryLoader: () -> Boolean,
    private val nativeReader: () -> NativeCapabilitySnapshot?
) {
    fun readSnapshot(): NativeCapabilitySnapshot? {
        return if (libraryLoader()) nativeReader() else null
    }
}

private object NativeCapabilitySource {
    private val reader = NativeCapabilitySnapshotReader(
        libraryLoader = LocalSrNativeLibraryLoader::ensureLoaded,
        nativeReader = NativeCapabilityJni::readSnapshot
    )

    fun readSnapshot(): NativeCapabilitySnapshot? = reader.readSnapshot()
}

private object LocalSrNativeLibraryLoader {
    private val loaded by lazy {
        runCatching { System.loadLibrary("localsr") }.isSuccess
    }

    fun ensureLoaded(): Boolean = loaded
}

private object NativeCapabilityJni {
    external fun readSnapshot(): NativeCapabilitySnapshot?
}

data class AndroidCapabilitySnapshot(
    val sdkInt: Int,
    val fingerprint: String,
    val isLowRamDevice: Boolean,
    val totalMemoryBytes: Long,
    val runtimeCoreCount: Int
)

fun interface AndroidCapabilitySource {
    fun snapshot(): AndroidCapabilitySnapshot
}

class AndroidDeviceCapabilitySource(context: Context) : AndroidCapabilitySource {
    private val activityManager = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

    override fun snapshot(): AndroidCapabilitySnapshot {
        val memoryInfo = ActivityManager.MemoryInfo()
        val totalMemoryBytes = runCatching {
            activityManager?.getMemoryInfo(memoryInfo)
            if (activityManager == null) 0L else memoryInfo.totalMem
        }.getOrDefault(0L)
        val isLowRamDevice = runCatching {
            activityManager?.isLowRamDevice ?: false
        }.getOrDefault(false)
        val runtimeCoreCount = runCatching {
            Runtime.getRuntime().availableProcessors()
        }.getOrDefault(1)

        return AndroidCapabilitySnapshot(
            sdkInt = Build.VERSION.SDK_INT,
            fingerprint = Build.FINGERPRINT.orEmpty(),
            isLowRamDevice = isLowRamDevice,
            totalMemoryBytes = totalMemoryBytes,
            runtimeCoreCount = runtimeCoreCount.coerceAtLeast(1)
        )
    }
}

class DeviceCapabilityRepository(
    private val androidSource: AndroidCapabilitySource,
    private val nativeProbe: NativeCapabilityProbe = AndroidNativeCapabilityProbe()
) {
    constructor(
        context: Context,
        nativeProbe: NativeCapabilityProbe = AndroidNativeCapabilityProbe()
    ) : this(
        androidSource = AndroidDeviceCapabilitySource(context),
        nativeProbe = nativeProbe
    )

    fun loadProfile(): DeviceCapabilityProfile {
        val android = androidSource.snapshot()
        val native = nativeProbe.snapshot()
        val nativePhysicalCoreCount = native.physicalCoreCount?.takeIf { it > 0 }
        val physicalCoreCount = nativePhysicalCoreCount
            ?: android.runtimeCoreCount.coerceAtLeast(1)
        val bigCoreCount = native.bigCoreCount
            ?.takeIf { nativePhysicalCoreCount != null && it > 0 }
            ?.coerceAtMost(physicalCoreCount)
        val littleCoreCount = native.littleCoreCount
            ?.takeIf { nativePhysicalCoreCount != null && it > 0 }
            ?.coerceAtMost(physicalCoreCount)
        val memoryBucketMb = memoryBucketMb(android.totalMemoryBytes)
        val gpuVendor = native.gpuVendor.normalizedOrNull()
        val gpuDevice = native.gpuDevice.normalizedOrNull()
        val gpuDriverVersion = native.gpuDriverVersion.normalizedOrNull()
        val gpuRoughScore = native.gpuRoughScore?.takeIf { it >= 0 }
        val vulkanCapabilities = native.toVulkanCapabilities()
        val acceleratorCalibrationKey = vulkanCapabilities
            ?.takeIf(VulkanDeviceCapabilities::isQualcommAdreno)
            ?.let(::adrenoCalibrationKey)
        val profileKey = profileKey(
            android = android,
            memoryBucketMb = memoryBucketMb,
            physicalCoreCount = physicalCoreCount,
            bigCoreCount = bigCoreCount,
            littleCoreCount = littleCoreCount,
            gpuVendor = gpuVendor,
            gpuDevice = gpuDevice,
            gpuDriverVersion = gpuDriverVersion,
            gpuRoughScore = gpuRoughScore,
            supportsVulkan = native.supportsVulkan,
            supportsFp16Storage = native.supportsFp16Storage,
            supportsFp16Arithmetic = native.supportsFp16Arithmetic
        )

        return DeviceCapabilityProfile(
            schemaKey = SCHEMA_KEY,
            profileKey = profileKey,
            sdkInt = android.sdkInt,
            isLowRamDevice = android.isLowRamDevice,
            memoryBucketMb = memoryBucketMb,
            physicalCoreCount = physicalCoreCount,
            bigCoreCount = bigCoreCount,
            littleCoreCount = littleCoreCount,
            gpuVendor = gpuVendor,
            gpuDevice = gpuDevice,
            gpuDriverVersion = gpuDriverVersion,
            gpuRoughScore = gpuRoughScore,
            supportsVulkan = native.supportsVulkan,
            supportsFp16Storage = native.supportsFp16Storage,
            supportsFp16Arithmetic = native.supportsFp16Arithmetic,
            vulkanCapabilities = vulkanCapabilities,
            acceleratorCalibrationKey = acceleratorCalibrationKey
        )
    }

    private fun NativeCapabilitySnapshot.toVulkanCapabilities(): VulkanDeviceCapabilities? {
        if (!supportsVulkan) return null
        val vendorId = gpuVendorId.takeIf { it in 0..UINT32_MAX } ?: return null
        val normalizedUuid = pipelineCacheUuid
            ?.trim()
            ?.lowercase()
            ?.takeIf { PIPELINE_CACHE_UUID.matches(it) }
        return VulkanDeviceCapabilities(
            vendorId = vendorId,
            deviceId = gpuDeviceId.uint32OrNull(),
            apiVersion = gpuApiVersion.uint32OrNull(),
            driverId = gpuDriverId.uint32OrNull(),
            driverVersion = gpuDriverVersionRaw.uint32OrNull(),
            pipelineCacheUuid = normalizedUuid,
            subgroupSize = subgroupSize.positiveOrNull(),
            subgroupMinSize = subgroupMinSize.positiveOrNull(),
            subgroupMaxSize = subgroupMaxSize.positiveOrNull(),
            computeQueueCount = computeQueueCount.positiveOrNull(),
            maxComputeSharedMemoryBytes = maxComputeSharedMemoryBytes.takeIf { it > 0L },
            supportsSubgroupOps = supportsSubgroupOps,
            supportsSubgroupSizeControl = supportsSubgroupSizeControl,
            supportsComputeFullSubgroups = supportsComputeFullSubgroups,
            driverQuirks = VulkanDriverQuirks(
                corruptedOnlinePipelineCache = corruptedOnlinePipelineCache,
                storageBufferNoL1 = storageBufferNoL1,
                bufferImageLoadZero = bufferImageLoadZero,
                implicitFp16Arithmetic = implicitFp16Arithmetic
            )
        )
    }

    private fun adrenoCalibrationKey(capabilities: VulkanDeviceCapabilities): String {
        val values = listOf(
            ADRENO_SCHEMA_KEY,
            capabilities.vendorId,
            capabilities.deviceId,
            capabilities.apiVersion,
            capabilities.driverId,
            capabilities.driverVersion,
            capabilities.pipelineCacheUuid,
            capabilities.subgroupSize,
            capabilities.subgroupMinSize,
            capabilities.subgroupMaxSize,
            capabilities.computeQueueCount,
            capabilities.maxComputeSharedMemoryBytes,
            capabilities.supportsSubgroupOps,
            capabilities.supportsSubgroupSizeControl,
            capabilities.supportsComputeFullSubgroups,
            capabilities.driverQuirks.corruptedOnlinePipelineCache,
            capabilities.driverQuirks.storageBufferNoL1,
            capabilities.driverQuirks.bufferImageLoadZero,
            capabilities.driverQuirks.implicitFp16Arithmetic
        )
        val digest = sha256(values)
        return "$ADRENO_SCHEMA_KEY:$digest"
    }

    private fun profileKey(
        android: AndroidCapabilitySnapshot,
        memoryBucketMb: Int,
        physicalCoreCount: Int,
        bigCoreCount: Int?,
        littleCoreCount: Int?,
        gpuVendor: String?,
        gpuDevice: String?,
        gpuDriverVersion: String?,
        gpuRoughScore: Int?,
        supportsVulkan: Boolean,
        supportsFp16Storage: Boolean,
        supportsFp16Arithmetic: Boolean
    ): String {
        val values = listOf(
            SCHEMA_KEY,
            android.sdkInt.toString(),
            android.fingerprint.normalizedIdentity(MAX_IDENTITY_STRING_LENGTH),
            android.isLowRamDevice.toString(),
            memoryBucketMb.toString(),
            physicalCoreCount.toString(),
            bigCoreCount?.toString(),
            littleCoreCount?.toString(),
            gpuVendor,
            gpuDevice,
            gpuDriverVersion,
            gpuRoughScore?.toString(),
            supportsVulkan.toString(),
            supportsFp16Storage.toString(),
            supportsFp16Arithmetic.toString()
        )
        val encodedIdentity = values.joinToString(separator = "\n") { value ->
            if (value == null) "-1:" else "${value.length}:$value"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encodedIdentity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$SCHEMA_KEY:$digest"
    }

    private fun memoryBucketMb(totalMemoryBytes: Long): Int {
        if (totalMemoryBytes <= 0L) return 0

        val totalMemoryMb = totalMemoryBytes / MEBIBYTE
        return MEMORY_BUCKETS_MB.lastOrNull { totalMemoryMb >= it }
            ?: MEMORY_BUCKETS_MB.first()
    }

    private fun String?.normalizedOrNull(): String? {
        return this
            ?.filterNot(Char::isISOControl)
            ?.trim()
            ?.take(MAX_CAPABILITY_STRING_LENGTH)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun String.normalizedIdentity(maxLength: Int): String {
        return filterNot(Char::isISOControl).trim().take(maxLength)
    }

    private fun Long.uint32OrNull(): Long? = takeIf { it in 0..UINT32_MAX }

    private fun Int.positiveOrNull(): Int? = takeIf { it > 0 }

    private fun sha256(values: List<Any?>): String {
        val encodedIdentity = values.joinToString(separator = "\n") { value ->
            value?.toString()?.let { "${it.length}:$it" } ?: "-1:"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(encodedIdentity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        const val SCHEMA_KEY = "device-capability-v1"
        const val ADRENO_SCHEMA_KEY = "adreno-capability-v1"
        const val MAX_CAPABILITY_STRING_LENGTH = 128
        private const val UINT32_MAX = 0xffff_ffffL
        private val PIPELINE_CACHE_UUID = Regex("[0-9a-f]{32}")
        private const val MAX_IDENTITY_STRING_LENGTH = 512
        private const val MEBIBYTE = 1_024L * 1_024L
        private val MEMORY_BUCKETS_MB = listOf(
            512,
            1_024,
            2_048,
            3_072,
            4_096,
            6_144,
            8_192,
            12_288,
            16_384,
            24_576,
            32_768
        )
    }
}
