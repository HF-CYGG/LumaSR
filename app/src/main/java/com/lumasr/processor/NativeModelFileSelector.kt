package com.lumasr.processor

import com.lumasr.domain.SuperResEngine

data class NativeModelFiles(
    val paramPath: String,
    val binPath: String
)

object NativeModelFileSelector {
    fun select(
        engine: SuperResEngine,
        modelDir: String,
        scale: Int,
        noise: Int
    ): NativeModelFiles {
        val baseName = when (engine) {
            SuperResEngine.WAIFU2X -> selectWaifu2xBaseName(scale, noise)
            SuperResEngine.REAL_CUGAN -> selectRealCuganBaseName(scale, noise)
        }
        val cleanDir = modelDir.trimEnd('/', '\\')
        return NativeModelFiles(
            paramPath = "$cleanDir/$baseName.param",
            binPath = "$cleanDir/$baseName.bin"
        )
    }

    private fun selectWaifu2xBaseName(scale: Int, noise: Int): String {
        return when {
            noise < 0 -> "scale${scale}.0x_model"
            scale <= 1 -> "noise${noise}_model"
            else -> "noise${noise}_scale${scale}.0x_model"
        }
    }

    private fun selectRealCuganBaseName(scale: Int, noise: Int): String {
        return when {
            noise < 0 -> "up${scale}x-conservative"
            noise == 0 -> "up${scale}x-no-denoise"
            else -> "up${scale}x-denoise${noise}x"
        }
    }
}
