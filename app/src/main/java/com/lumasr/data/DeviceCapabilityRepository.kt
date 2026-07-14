package com.lumasr.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.lumasr.domain.DeviceCapabilityProfile
import java.security.MessageDigest

data class NativeCapabilitySnapshot(
    val physicalCoreCount: Int? = null,
    val bigCoreCount: Int? = null,
    val littleCoreCount: Int? = null,
    val gpuVendor: String? = null,
    val gpuDevice: String? = null,
    val gpuDriverVersion: String? = null,
    val gpuRoughScore: Int? = null,
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
    private val snapshotReader: () -> NativeCapabilitySnapshot? = NativeCapabilityJni::readSnapshot
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
            supportsFp16Arithmetic = native.supportsFp16Arithmetic
        )
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
            android.fingerprint,
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

    private fun String?.normalizedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val SCHEMA_KEY = "device-capability-v1"
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
