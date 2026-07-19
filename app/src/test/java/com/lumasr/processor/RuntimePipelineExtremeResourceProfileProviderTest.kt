package com.lumasr.processor

import com.lumasr.domain.ExtremeExportPlanner
import com.lumasr.domain.RuntimePipelineResourceProvider
import com.lumasr.domain.RuntimePipelineResources
import com.lumasr.domain.SuperResEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimePipelineExtremeResourceProfileProviderTest {
    @Test
    fun mapsLowUnknownAndHighRuntimeMemoryIntoPlannerBudgets() {
        val low = plannerBudget(
            RuntimePipelineResourceProvider {
                RuntimePipelineResources(
                    availableRamBytes = 2L * MIB,
                    gpuHeapBudgetBytes = 512L * MIB,
                    isLowRamDevice = true
                )
            }
        )
        val unknown = plannerBudget(RuntimePipelineResourceProvider { null })
        val high = plannerBudget(
            RuntimePipelineResourceProvider {
                RuntimePipelineResources(
                    availableRamBytes = 1536L * MIB,
                    gpuHeapBudgetBytes = null,
                    isLowRamDevice = false
                )
            }
        )

        assertEquals(16L * MIB, low)
        assertEquals(32L * MIB, unknown)
        assertEquals(64L * MIB, high)
    }

    @Test
    fun mapsPartialAndThrowingRuntimeResourcesToSafeProfiles() {
        val partial = plannerBudget(
            RuntimePipelineResourceProvider {
                RuntimePipelineResources(
                    availableRamBytes = null,
                    gpuHeapBudgetBytes = Long.MAX_VALUE,
                    isLowRamDevice = false
                )
            }
        )
        val throwing = plannerBudget(RuntimePipelineResourceProvider {
            error("runtime resource source unavailable")
        })

        assertEquals(32L * MIB, partial)
        assertEquals(32L * MIB, throwing)
    }

    private fun plannerBudget(provider: RuntimePipelineResourceProvider): Long {
        val profile = RuntimePipelineExtremeResourceProfileProvider(provider).profile(1024, 1024)
        return ExtremeExportPlanner.plan(
            imageWidth = 1024,
            imageHeight = 1024,
            scale = 4,
            engine = SuperResEngine.REAL_ESRGAN,
            resourceProfile = profile
        ).rowBandBudgetBytes
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
