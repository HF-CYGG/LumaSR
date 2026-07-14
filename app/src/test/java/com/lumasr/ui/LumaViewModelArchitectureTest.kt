package com.lumasr.ui

import com.lumasr.processor.HybridSuperResProcessor
import com.lumasr.processor.NativeCacheOwner
import com.lumasr.domain.SuperResProcessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LumaViewModelArchitectureTest {
    @Test
    fun viewModelOwnsPersistentControllerInsteadOfProcessorOrNativeCache() {
        val fields = LumaViewModel::class.java.declaredFields.map { it.type }
        val constructorTypes = LumaViewModel::class.java.declaredConstructors
            .flatMap { constructor -> constructor.parameterTypes.toList() }
        val exposedTypes = fields + constructorTypes

        assertTrue(fields.any { it == PersistentProcessingController::class.java })
        assertFalse(exposedTypes.any { SuperResProcessor::class.java.isAssignableFrom(it) })
        assertFalse(exposedTypes.any { HybridSuperResProcessor::class.java.isAssignableFrom(it) })
        assertFalse(exposedTypes.any { NativeCacheOwner::class.java.isAssignableFrom(it) })
    }

    @Test
    fun resolvesCalibratedExecutionFieldsBeforePersistingEveryWorkSpec() {
        val source = File("src/main/java/com/lumasr/ui/LumaViewModel.kt").readText()
        val resolveIndex = source.indexOf("executionProfileResolver.resolve(")
        val persistIndex = source.indexOf("ProcessingWorkSpec.fromParams(model.id, params)")

        assertTrue(resolveIndex >= 0)
        assertTrue(persistIndex > resolveIndex)
    }

    @Test
    fun calibrationIsOptionalAndRunsThroughBackgroundCoordinator() {
        val source = File("src/main/java/com/lumasr/ui/LumaViewModel.kt").readText()

        assertTrue(source.contains("fun skipCalibrationPrompt()"))
        assertTrue(source.contains("fun runCalibration()"))
        assertTrue(source.contains("withContext(ioDispatcher)"))
        assertTrue(source.contains("calibrationCoordinator.run("))
    }

    @Test
    fun modelSelectionIsGuardedWhileCalibrationRuns() {
        val source = File("src/main/java/com/lumasr/ui/LumaViewModel.kt").readText()

        assertTrue(source.contains("if (!canChangeModel(_uiState.value)) return"))
    }
}
