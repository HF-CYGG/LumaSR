package com.lumasr.domain

object SyntheticCalibrationPattern {
    private val blockOffsets = arrayOf(
        intArrayOf(44, -18, -18),
        intArrayOf(-20, 42, -14),
        intArrayOf(-18, -12, 46),
        intArrayOf(34, 20, -34),
        intArrayOf(-28, 30, 32),
        intArrayOf(30, -28, 26)
    )

    fun generate(width: Int, height: Int, seed: Long): RgbImage {
        val byteCount = RgbImage.checkedByteCount(width, height)
        val bytes = ByteArray(byteCount)
        val random = XorShift64(seed xor (width.toLong() shl 32) xor height.toLong())
        val xDenominator = (width - 1).coerceAtLeast(1).toLong()
        val yDenominator = (height - 1).coerceAtLeast(1).toLong()
        val diagonalDenominator = (width.toLong() + height.toLong() - 2L).coerceAtLeast(1L)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val blockX = (x.toLong() * 3L / width).toInt().coerceAtMost(2)
                val blockY = (y.toLong() * 2L / height).toInt().coerceAtMost(1)
                val block = blockOffsets[blockY * 3 + blockX]
                val checker = if (((x / 2) + (y / 2)) and 1 == 0) 30 else -30
                val base = intArrayOf(
                    (x.toLong() * 255L / xDenominator).toInt(),
                    (y.toLong() * 255L / yDenominator).toInt(),
                    ((x.toLong() + y.toLong()) * 255L / diagonalDenominator).toInt()
                )
                val offset = (y * width + x) * 3
                for (channel in 0 until 3) {
                    val noise = random.nextByte().mod(15) - 7
                    bytes[offset + channel] = (base[channel] + block[channel] + checker + noise)
                        .coerceIn(0, 255)
                        .toByte()
                }
            }
        }
        return RgbImage(width, height, bytes)
    }

    private class XorShift64(initialState: Long) {
        private var state = if (initialState == 0L) NON_ZERO_STATE else initialState

        fun nextByte(): Int {
            var value = state
            value = value xor (value shl 13)
            value = value xor (value ushr 7)
            value = value xor (value shl 17)
            state = value
            return (value ushr 56).toInt() and 0xff
        }
    }

    private const val NON_ZERO_STATE = -0x61c8864680b583ebL
}
