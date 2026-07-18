package com.lumasr.service

import com.lumasr.domain.ProcessingExecutionProfileResolver
import com.lumasr.domain.ProcessingTaskRunner
import com.lumasr.domain.RuntimePipelineResourceProvider
import com.lumasr.domain.SuperResProcessor
import com.lumasr.domain.ThermalLevel
import com.lumasr.processor.ExtremeResourceProfileProvider
import com.lumasr.processor.AdpfHintSessionFactory
import com.lumasr.processor.AdpfPerformanceHintController
import com.lumasr.processor.HybridSuperResProcessor
import com.lumasr.processor.QnnNativeSuperResProcessor
import com.lumasr.processor.RuntimePipelineExtremeResourceProfileProvider

internal fun createRuntimeAwareTaskRunner(
    thermalLevelProvider: () -> ThermalLevel?,
    runtimeResourceProvider: RuntimePipelineResourceProvider,
    executionProfileResolver: ProcessingExecutionProfileResolver?,
    adpfHintSessionFactory: AdpfHintSessionFactory? = null,
    processorFactory: ((ExtremeResourceProfileProvider) -> SuperResProcessor)? = null,
    runnerFactory: (
        SuperResProcessor,
        () -> ThermalLevel?,
        RuntimePipelineResourceProvider,
        ProcessingExecutionProfileResolver?
    ) -> ProcessingTaskRunner = ::createDefaultRuntimeAwareTaskRunner
): ProcessingTaskRunner {
    val extremeResourceProfileProvider =
        RuntimePipelineExtremeResourceProfileProvider(runtimeResourceProvider)
    val effectiveProcessorFactory = processorFactory ?: { resourceProfileProvider ->
        HybridSuperResProcessor(
            extremeResourceProfileProvider = resourceProfileProvider,
            rgbTransportBudgetProvider =
                resourceProfileProvider as? com.lumasr.processor.RgbTransportBudgetProvider
                    ?: com.lumasr.processor.RgbTransportBudgetProvider {
                        com.lumasr.domain.RuntimeRgbTransportBudgetPolicy().decide(null)
                    },
            qnnProcessor = QnnNativeSuperResProcessor(
                performanceHintController = adpfHintSessionFactory?.let {
                    AdpfPerformanceHintController(it)
                }
            )
        )
    }
    return runnerFactory(
        effectiveProcessorFactory(extremeResourceProfileProvider),
        thermalLevelProvider,
        runtimeResourceProvider,
        executionProfileResolver
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
