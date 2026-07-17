package com.lumasr.service

import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ProcessingTaskRunner
import com.lumasr.domain.RuntimePipelineResourceProvider
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.ThermalLevel
import com.lumasr.processor.ExtremeResourceProfileProvider
import com.lumasr.processor.HybridSuperResProcessor
import com.lumasr.processor.RuntimePipelineExtremeResourceProfileProvider

internal fun createRuntimeAwareTaskRunner(
    thermalLevelProvider: () -> ThermalLevel?,
    runtimeResourceProvider: RuntimePipelineResourceProvider,
    executionProfileResolver: ProcessingExecutionProfileResolver?,
    processorFactory: (ExtremeResourceProfileProvider) -> SuperResProcessor =
        DEFAULT_RUNTIME_AWARE_PROCESSOR_FACTORY,
    runnerFactory: (
        SuperResProcessor,
        () -> ThermalLevel?,
        RuntimePipelineResourceProvider,
        ProcessingExecutionProfileResolver?
    ) -> ProcessingTaskRunner = ::createDefaultRuntimeAwareTaskRunner
): ProcessingTaskRunner {
    val extremeResourceProfileProvider =
        RuntimePipelineExtremeResourceProfileProvider(runtimeResourceProvider)
    return runnerFactory(
        processorFactory(extremeResourceProfileProvider),
        thermalLevelProvider,
        runtimeResourceProvider,
        executionProfileResolver
    )
}

private val DEFAULT_RUNTIME_AWARE_PROCESSOR_FACTORY:
    (ExtremeResourceProfileProvider) -> SuperResProcessor = { resourceProfileProvider ->
    HybridSuperResProcessor(
        extremeResourceProfileProvider = resourceProfileProvider,
        rgbTransportBudgetProvider =
            resourceProfileProvider as? com.lumasr.processor.RgbTransportBudgetProvider
                ?: com.lumasr.processor.RgbTransportBudgetProvider {
                    com.lumasr.domain.RuntimeRgbTransportBudgetPolicy().decide(null)
                }
    )
}

private fun createDefaultRuntimeAwareTaskRunner(
    processor: SuperResProcessor,
    thermalLevelProvider: () -> ThermalLevel?,
    runtimeResourceProvider: RuntimePipelineResourceProvider,
    executionProfileResolver: ProcessingExecutionProfileResolver?
): ProcessingTaskRunner {
    return executionProfileResolver?.let { resolver ->
        SuperResProcessingTaskRunner(
            processor = processor,
            thermalLevelProvider = thermalLevelProvider,
            runtimeResourceProvider = runtimeResourceProvider,
            executionProfileResolver = resolver
        )
    } ?: SuperResProcessingTaskRunner(
        processor = processor,
        thermalLevelProvider = thermalLevelProvider,
        runtimeResourceProvider = runtimeResourceProvider
    )
}
