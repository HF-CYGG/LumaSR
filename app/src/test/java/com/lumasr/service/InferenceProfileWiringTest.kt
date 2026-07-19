package com.lumasr.service

import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ProcessingRunResult
import com.lumasr.domain.ProcessingTask
import com.lumasr.domain.ProcessingTaskRunner
import com.lumasr.domain.ProcessingWorkSpec
import com.lumasr.domain.RuntimePipelineResourceProvider
import com.lumasr.domain.RuntimePipelineResources
import com.lumasr.domain.ThermalLevel
import com.lumasr.domain.UpscaleProgress
import com.lumasr.processor.ExtremeResourceProfileProvider
import com.lumasr.processor.MockSuperResProcessor
import com.lumasr.ui.createPersistentForegroundRuntime
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceProfileWiringTest {
    @Test
    fun runtimeAssemblySharesOneProviderBetweenRunnerAndExtremeProfileForBothResolverBranches() {
        var resourceSamples = 0
        val provider = RuntimePipelineResourceProvider {
            resourceSamples += 1
            RuntimePipelineResources(
                availableRamBytes = 1536L * MIB,
                gpuHeapBudgetBytes = null,
                isLowRamDevice = false
            )
        }
        val resolver = ProcessingExecutionProfileResolver { _, params, _ -> params }
        val profiles = mutableListOf<ExtremeResourceProfileProvider>()
        val runnerProviders = mutableListOf<RuntimePipelineResourceProvider>()
        val runnerResolvers = mutableListOf<ProcessingExecutionProfileResolver?>()

        listOf(null, resolver).forEach { executionProfileResolver ->
            createRuntimeAwareTaskRunner(
                thermalLevelProvider = { ThermalLevel.NORMAL },
                runtimeResourceProvider = provider,
                executionProfileResolver = executionProfileResolver,
                processorFactory = { profileProvider ->
                    profiles += profileProvider
                    MockSuperResProcessor()
                },
                runnerFactory = { _, _, runnerProvider, capturedResolver ->
                    runnerProviders += runnerProvider
                    runnerResolvers += capturedResolver
                    NoOpTaskRunner
                }
            )
        }

        assertEquals(listOf(null, resolver), runnerResolvers)
        assertTrue(runnerProviders.all { it === provider })
        profiles.forEach { profileProvider ->
            assertEquals(1536L * MIB, profileProvider.profile(1024, 1024).availableMemoryBytes)
        }
        assertEquals(2, resourceSamples)
    }

    @Test
    fun persistentForegroundAssemblyForwardsTheInjectedProviderAndResolverByIdentity() {
        val provider = RuntimePipelineResourceProvider { null }
        val resolver = ProcessingExecutionProfileResolver { _, params, _ -> params }

        val result: String = createPersistentForegroundRuntime(
            taskDirectory = File("build/persistent-runtime-wiring"),
            thermalLevelProvider = { ThermalLevel.LIGHT },
            runtimeResourceProvider = provider,
            executionProfileResolver = resolver,
            runtimeFactory = { directory, thermalProvider, passedProvider, passedResolver ->
                assertEquals("persistent-runtime-wiring", directory.name)
                assertEquals(ThermalLevel.LIGHT, thermalProvider())
                assertSame(provider, passedProvider)
                assertSame(resolver, passedResolver)
                "foreground"
            }
        )

        assertEquals("foreground", result)
    }

    @Test
    fun calibrationFactoryInjectsLiveThermalLevelProvider() {
        val factory = source("ui/CalibrationAndroidFactory.kt")

        assertTrue(factory.contains("ThermalSampler(AndroidThermalSignalSource(appContext))"))
        assertTrue(factory.contains("thermalLevelProvider = thermalSampler::sampleLevel"))
    }

    @Test
    fun calibrationFactoryInjectsLiveJavaHeapProvider() {
        val factory = source("ui/CalibrationAndroidFactory.kt")

        assertTrue(factory.contains("availableHeapBytesProvider ="))
        assertTrue(factory.contains("InferenceCalibrationRunner.currentAvailableHeapBytes()"))
    }

    @Test
    fun calibrationFactoryUsesBoundedProductionCandidateBudget() {
        val factory = source("ui/CalibrationAndroidFactory.kt")

        assertTrue(factory.contains("VulkanCalibrationCandidates.productionBounded()"))
        assertTrue(factory.contains("CpuCalibrationCandidates.productionBounded()"))
    }

    private fun source(relativePath: String): String = File(
        "src/main/java/com/lumasr/$relativePath"
    ).readText()

    private object NoOpTaskRunner : ProcessingTaskRunner {
        override suspend fun run(
            task: ProcessingTask,
            workSpec: ProcessingWorkSpec,
            onProgress: (UpscaleProgress) -> Unit
        ): ProcessingRunResult = ProcessingRunResult.Completed

        override fun cancel(taskId: String) = Unit
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
