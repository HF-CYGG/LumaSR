package com.lumasr.domain

fun ModelPack.availableTargetScales(): List<Int> {
    val nativeScales = availableNativePassScales()
    if (nativeScales.isEmpty()) {
        return scales.filter { it > 0 }.distinct().sorted()
    }
    return nativeScales.expandedTargetScales()
}

fun ModelPack.availableNativePassScales(): List<Int> =
    nativeCapabilities()
        .map { it.scale }
        .distinct()
        .sorted()

fun ModelPack.nativeScalePlanFor(targetScale: Int): List<Int> =
    nativeScalePlanFor(targetScale, availableNativePassScales())

fun ModelPack.availableDenoiseForScale(targetScale: Int): List<Int> {
    val passScales = nativeScalePlanFor(targetScale)
    val nativeCapabilities = nativeCapabilities()
    val available = passScales
        .map { passScale ->
            nativeCapabilities
                .filter { it.scale == passScale }
                .map { it.noise }
                .toSet()
        }
        .reduceOrNull { acc, values -> acc.intersect(values) }
        .orEmpty()
        .sorted()

    return available.ifEmpty { denoise.sorted() }
}

fun ModelPack.sanitizeTargetScale(targetScale: Int): Int {
    val available = availableTargetScales()
    if (targetScale in available) {
        return targetScale
    }
    val preferred = defaultScale.takeIf { it in available }
    return preferred ?: available.firstOrNull() ?: defaultScale
}

fun nativeScalePlanFor(targetScale: Int, nativePassScales: List<Int>): List<Int> {
    val singlePassScales = nativePassScales
        .filter { it > 0 }
        .distinct()
        .sortedDescending()
    if (singlePassScales.isEmpty()) {
        return listOf(targetScale.coerceAtLeast(1))
    }
    if (targetScale <= 1) {
        return if (1 in singlePassScales) listOf(1) else listOf(singlePassScales.first())
    }
    if (targetScale in singlePassScales) {
        return listOf(targetScale)
    }

    var remaining = targetScale
    val plan = mutableListOf<Int>()
    while (remaining > 1) {
        val factor = singlePassScales.firstOrNull { it > 1 && remaining % it == 0 }
            ?: return listOf(singlePassScales.first())
        plan += factor
        remaining /= factor
    }
    return plan
}

private fun ModelPack.nativeCapabilities(): List<NativeModelCapability> {
    if (engine == SuperResEngine.REAL_ESRGAN) {
        val baseName = modelFileBase?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (!hasRequiredModelPair(baseName)) {
            return emptyList()
        }
        return scales
            .filter { it > 0 }
            .flatMap { scale -> denoise.ifEmpty { listOf(0) }.map { noise -> NativeModelCapability(scale, noise) } }
            .distinct()
    }

    val bases = requiredFiles
        .map { it.substringBeforeLast('.') }
        .distinct()

    return bases.mapNotNull { base ->
        when (engine) {
            SuperResEngine.WAIFU2X -> parseWaifu2xCapability(base)
            SuperResEngine.REAL_CUGAN -> parseRealCuganCapability(base)
            SuperResEngine.REAL_ESRGAN -> null
        }
    }.distinct()
}

private fun ModelPack.hasRequiredModelPair(baseName: String): Boolean {
    val files = requiredFiles.toSet()
    return "${baseName}.param" in files && "${baseName}.bin" in files
}

private fun parseWaifu2xCapability(baseName: String): NativeModelCapability? {
    if (baseName.startsWith("scale") && baseName.endsWith(".0x_model")) {
        val scale = baseName.removePrefix("scale").removeSuffix(".0x_model").toIntOrNull() ?: return null
        return NativeModelCapability(scale = scale, noise = -1)
    }
    if (baseName.startsWith("noise") && baseName.endsWith("_model")) {
        val noise = baseName.removePrefix("noise").removeSuffix("_model").toIntOrNull() ?: return null
        return NativeModelCapability(scale = 1, noise = noise)
    }
    if (baseName.startsWith("noise") && "_scale" in baseName && baseName.endsWith(".0x_model")) {
        val noise = baseName.removePrefix("noise").substringBefore("_scale").toIntOrNull() ?: return null
        val scale = baseName.substringAfter("_scale").removeSuffix(".0x_model").toIntOrNull() ?: return null
        return NativeModelCapability(scale = scale, noise = noise)
    }
    return null
}

private fun parseRealCuganCapability(baseName: String): NativeModelCapability? {
    if (!baseName.startsWith("up") || "x-" !in baseName) {
        return null
    }
    val scale = baseName.removePrefix("up").substringBefore("x-").toIntOrNull() ?: return null
    val suffix = baseName.substringAfter("x-")
    val noise = when {
        suffix == "conservative" -> -1
        suffix == "no-denoise" -> 0
        suffix.startsWith("denoise") && suffix.endsWith("x") ->
            suffix.removePrefix("denoise").removeSuffix("x").toIntOrNull() ?: return null
        else -> return null
    }
    return NativeModelCapability(scale = scale, noise = noise)
}

private fun List<Int>.expandedTargetScales(): List<Int> {
    val base = filter { it > 0 }.distinct().sorted()
    val extra = buildList {
        if (2 in base) {
            add(4)
            add(8)
        }
        if (3 in base) {
            add(9)
        }
        if (4 in base) {
            add(16)
        }
    }
    return (base + extra).distinct().sorted()
}

private data class NativeModelCapability(
    val scale: Int,
    val noise: Int
)
