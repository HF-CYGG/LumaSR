package com.lumasr.ui

import com.lumasr.processor.HybridSuperResProcessor
import com.lumasr.processor.NativeCacheOwner
import com.lumasr.domain.SuperResProcessor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
