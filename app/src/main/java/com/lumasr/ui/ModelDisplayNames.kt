package com.lumasr.ui

import com.lumasr.domain.ModelPack
import com.lumasr.domain.SuperResEngine

internal fun ModelPack.settingsModelTitle(): String {
    val rawName = displayName.trim().ifBlank { id }
    return when (engine) {
        SuperResEngine.WAIFU2X -> "Waifu2x $rawName"
        SuperResEngine.REAL_CUGAN -> "RealCUGAN $rawName"
        SuperResEngine.REAL_ESRGAN -> "Real-ESRGAN $rawName"
    }
}

internal fun ModelPack.shortModelTitle(): String {
    return when (displayName.trim().lowercase()) {
        "cunet" -> "CUnet"
        "anime" -> "动漫"
        "photo" -> "旧图"
        "standard" -> "标准"
        "pro" -> "锐化"
        "x4plus" -> "通用4x"
        "x4plus anime" -> "动漫4x"
        "animevideo v3 x2" -> "视频2x"
        "animevideo v3 x3" -> "视频3x"
        "animevideo v3 x4" -> "视频4x"
        else -> displayName
    }
}
