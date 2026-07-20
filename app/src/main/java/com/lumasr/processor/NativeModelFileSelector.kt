package com.lumasr.processor

import com.lumasr.domain.SuperResEngine
import com.lumasr.domain.nativeModelBaseName

data class NativeModelFiles(
    val paramPath: String,
    val binPath: String
)

object NativeModelFileSelector {
    fun select(
        engine: SuperResEngine,
        modelDir: String,
        scale: Int,
        noise: Int,
        modelFileBase: String? = null
    ): NativeModelFiles {
        val baseName = nativeModelBaseName(engine, scale, noise, modelFileBase)
        val cleanDir = modelDir.trimEnd('/', '\\')
        return NativeModelFiles(
            paramPath = "$cleanDir/$baseName.param",
            binPath = "$cleanDir/$baseName.bin"
        )
    }
}
