package com.lumasr.data

import android.os.Build
import com.lumasr.BuildConfig
import com.lumasr.domain.QnnCapabilitySnapshot

data class NativeQnnCapabilityRecord(
    val providerBuildId: String,
    val htpArchitecture: String,
    val supportsFp16: Boolean,
    val supportsA16W8: Boolean
)

class AndroidQnnCapabilityProbe(
    private val enabledProvider: () -> Boolean = { BuildConfig.LUMASR_QNN_ENABLED },
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val isArm64Provider: () -> Boolean = {
        Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
    },
    private val recordReader: () -> NativeQnnCapabilityRecord? = QnnNativeCapabilitySource::read
) : QnnCapabilityProbe {
    override fun snapshot(): QnnCapabilitySnapshot {
        val sdkInt = sdkIntProvider().coerceAtLeast(0)
        val isArm64 = isArm64Provider()
        if (!enabledProvider()) return unavailable(sdkInt, isArm64, "QNN runtime not packaged")
        if (sdkInt < QnnCapabilitySnapshot.MIN_ANDROID_SDK) {
            return unavailable(sdkInt, isArm64, "QNN requires Android API 30 or newer")
        }
        if (!isArm64) return unavailable(sdkInt, false, "QNN requires arm64")

        return try {
            val record = recordReader()
                ?: return unavailable(sdkInt, true, "QNN native adapter unavailable")
            QnnCapabilitySnapshot(
                isAvailable = true,
                sdkInt = sdkInt,
                isArm64 = true,
                providerBuildId = record.providerBuildId,
                htpArchitecture = record.htpArchitecture,
                supportsFp16 = record.supportsFp16,
                supportsA16W8 = record.supportsA16W8
            )
        } catch (_: LinkageError) {
            unavailable(sdkInt, true, "QNN native adapter unavailable")
        } catch (_: SecurityException) {
            unavailable(sdkInt, true, "QNN capability probe failed")
        } catch (_: RuntimeException) {
            unavailable(sdkInt, true, "QNN capability probe failed")
        }
    }

    private fun unavailable(
        sdkInt: Int,
        isArm64: Boolean,
        reason: String
    ): QnnCapabilitySnapshot = QnnCapabilitySnapshot(
        isAvailable = false,
        sdkInt = sdkInt,
        isArm64 = isArm64,
        providerBuildId = null,
        htpArchitecture = null,
        supportsFp16 = false,
        supportsA16W8 = false,
        unavailableReason = reason
    )
}

private object QnnNativeCapabilitySource {
    private val libraryLoaded by lazy {
        runCatching { System.loadLibrary("lumasr_qnn_adapter") }.isSuccess
    }

    fun read(): NativeQnnCapabilityRecord? = if (libraryLoaded) {
        QnnCapabilityJni.readSnapshot()
    } else {
        null
    }
}

private object QnnCapabilityJni {
    external fun readSnapshot(): NativeQnnCapabilityRecord?
}
