package com.lumasr.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtremeAccelerationPolicyTest {
    @Test
    fun x86RuntimeForcesCpuEvenWhenGpuWasRequested() {
        val decision = ExtremeAccelerationPolicy.decide(
            params = params().copy(accelerationMode = AccelerationMode.VULKAN),
            supportedAbis = listOf("x86_64")
        )

        assertEquals(AccelerationMode.CPU, decision.mode)
        assertFalse(decision.allowGpuRetry)
        assertTrue(decision.reason.contains("x86"))
    }

    @Test
    fun healthyArmRuntimeUsesVulkanForAutoExtremeExport() {
        val decision = ExtremeAccelerationPolicy.decide(
            params = params().copy(accelerationMode = AccelerationMode.AUTO),
            supportedAbis = listOf("arm64-v8a"),
            gpuHealthProbe = NativeGpuHealthProbe { true }
        )

        assertEquals(AccelerationMode.VULKAN, decision.mode)
        assertTrue(decision.allowGpuRetry)
        assertFalse(decision.disableGpuForSession)
    }

    @Test
    fun disabledSessionGpuKeyUsesCpuWithoutRetry() {
        val params = params().copy(accelerationMode = AccelerationMode.VULKAN)
        val decision = ExtremeAccelerationPolicy.decide(
            params = params,
            supportedAbis = listOf("arm64-v8a"),
            disabledGpuKeys = setOf(params.extremeGpuKey())
        )

        assertEquals(AccelerationMode.CPU, decision.mode)
        assertFalse(decision.allowGpuRetry)
        assertTrue(decision.reason.contains("disabled"))
    }

    @Test
    fun unhealthyGpuProbeDisablesGpuForSession() {
        val decision = ExtremeAccelerationPolicy.decide(
            params = params().copy(accelerationMode = AccelerationMode.AUTO),
            supportedAbis = listOf("arm64-v8a"),
            gpuHealthProbe = NativeGpuHealthProbe { false }
        )

        assertEquals(AccelerationMode.CPU, decision.mode)
        assertTrue(decision.disableGpuForSession)
    }

    private fun params() = UpscaleParams(
        taskId = "task-1",
        inputPath = "cache/input.png",
        outputPath = "cache/output.png",
        engine = SuperResEngine.REAL_ESRGAN,
        modelDir = "cache/models/realesrgan",
        modelName = "Standard",
        modelFileBase = "realesrgan-x4plus",
        scale = 4,
        noise = 0,
        tileSize = 128,
        gpuHeadroomPercent = 10,
        accelerationMode = AccelerationMode.AUTO,
        tta = false,
        outputFormat = OutputFormat.PNG,
        exportMode = ExportMode.EXTREME_SINGLE_PNG
    )
}
