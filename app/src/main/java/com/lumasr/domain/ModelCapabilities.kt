package com.lumasr.domain

fun ModelPack.availableDenoiseForScale(targetScale: Int): List<Int> {
    if (engine != SuperResEngine.REAL_CUGAN) {
        return denoise.sorted()
    }

    val passScale = nativePassScaleFor(targetScale)
    val prefix = "up${passScale}x-"
    val available = requiredFiles
        .asSequence()
        .mapNotNull { fileName ->
            when {
                fileName.startsWith("${prefix}conservative.") -> -1
                fileName.startsWith("${prefix}no-denoise.") -> 0
                fileName.startsWith("${prefix}denoise") -> fileName
                    .removePrefix("${prefix}denoise")
                    .substringBefore('x')
                    .toIntOrNull()
                else -> null
            }
        }
        .distinct()
        .sorted()
        .toList()

    return available.ifEmpty { denoise.sorted() }
}

private fun ModelPack.nativePassScaleFor(targetScale: Int): Int {
    if (targetScale in scales) return targetScale
    return scales
        .filter { it > 1 }
        .sortedDescending()
        .firstOrNull { targetScale % it == 0 }
        ?: targetScale
}
