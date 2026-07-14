package com.lumasr.domain

class RgbImage(
    val width: Int,
    val height: Int,
    bytes: ByteArray
) {
    val byteCount: Int = checkedByteCount(width, height)
    private val channels = bytes.also { source ->
        require(source.size == byteCount) {
            "RGB byte count ${bytes.size} does not match ${width}x${height}x$CHANNEL_COUNT"
        }
    }.copyOf()

    fun copyBytes(): ByteArray = channels.copyOf()

    internal fun channelAt(index: Int): Int = channels[index].toInt() and 0xff

    internal fun hasSameChannels(other: RgbImage): Boolean = channels.contentEquals(other.channels)

    internal companion object {
        const val CHANNEL_COUNT = 3

        fun checkedByteCount(width: Int, height: Int): Int {
            require(width > 0) { "RGB width must be positive" }
            require(height > 0) { "RGB height must be positive" }
            require(width <= Int.MAX_VALUE / height) { "RGB dimensions are too large" }
            val pixelCount = width * height
            require(pixelCount <= Int.MAX_VALUE / CHANNEL_COUNT) { "RGB dimensions are too large" }
            return pixelCount * CHANNEL_COUNT
        }
    }
}
