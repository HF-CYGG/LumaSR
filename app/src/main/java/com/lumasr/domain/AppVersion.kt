package com.lumasr.domain

class AppVersion private constructor(
    private val segments: List<Int>
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val length = maxOf(segments.size, other.segments.size)
        repeat(length) { index ->
            val comparison = segments.getOrElse(index) { 0 }
                .compareTo(other.segments.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun equals(other: Any?): Boolean =
        other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = segments.joinToString(separator = ".")

    companion object {
        private val VERSION_PATTERN = Regex("^[vV]?([0-9]+(?:\\.[0-9]+){1,3})$")

        fun parse(value: String): AppVersion? {
            val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
            val parsed = match.groupValues[1]
                .split('.')
                .map { it.toIntOrNull() ?: return null }
            val normalized = parsed.dropLastWhileKeepingTwo { it == 0 }
            return AppVersion(normalized)
        }
    }
}

private fun <T> List<T>.dropLastWhileKeepingTwo(predicate: (T) -> Boolean): List<T> {
    var endExclusive = size
    while (endExclusive > 2 && predicate(this[endExclusive - 1])) {
        endExclusive -= 1
    }
    return subList(0, endExclusive)
}
