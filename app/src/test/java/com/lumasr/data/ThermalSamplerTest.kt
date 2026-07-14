package com.lumasr.data

import android.os.PowerManager
import com.lumasr.domain.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThermalSamplerTest {
    @Test
    fun neverReadsHeadroomMoreOftenThanEveryTenSeconds() {
        val clock = FakeMonotonicClock()
        val source = FakeThermalSignalSource(headrooms = listOf(0.40f, 0.65f))
        val sampler = ThermalSampler(source = source, clock = clock)

        assertEquals(0.40f, sampler.sample().headroom)

        clock.nowMs = 9_999L
        assertEquals(0.40f, sampler.sample().headroom)
        assertEquals(1, source.headroomReadCount)

        clock.nowMs = 10_000L
        assertEquals(0.65f, sampler.sample().headroom)
        assertEquals(2, source.headroomReadCount)
    }

    @Test
    fun keepsLastValidHeadroomWhenNewReadingIsNanOrUnavailable() {
        val clock = FakeMonotonicClock()
        val source = FakeThermalSignalSource(
            headrooms = listOf(0.45f, Float.NaN, null)
        )
        val sampler = ThermalSampler(source = source, clock = clock)

        assertEquals(0.45f, sampler.sample().headroom)

        clock.nowMs = 10_000L
        assertEquals(0.45f, sampler.sample().headroom)

        clock.nowMs = 20_000L
        assertEquals(0.45f, sampler.sample().headroom)
        assertEquals(3, source.headroomReadCount)

        val unknown = ThermalSampler(
            source = FakeThermalSignalSource(headrooms = listOf(Float.NaN)),
            clock = FakeMonotonicClock()
        ).sample()
        assertNull(unknown.headroom)
    }

    @Test
    fun mapsEveryPublicAndroidThermalStatus() {
        val expected = mapOf(
            PowerManager.THERMAL_STATUS_NONE to ThermalLevel.NORMAL,
            PowerManager.THERMAL_STATUS_LIGHT to ThermalLevel.LIGHT,
            PowerManager.THERMAL_STATUS_MODERATE to ThermalLevel.MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE to ThermalLevel.SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL to ThermalLevel.CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY to ThermalLevel.CRITICAL,
            PowerManager.THERMAL_STATUS_SHUTDOWN to ThermalLevel.CRITICAL
        )

        expected.forEach { (status, level) ->
            assertEquals(level, AndroidThermalStatusMapper.map(status))
        }
        assertNull(AndroidThermalStatusMapper.map(null))
        assertNull(AndroidThermalStatusMapper.map(Int.MAX_VALUE))
    }

    @Test
    fun gatesStatusReadsToOnePerSecondWhileHeadroomRemainsMoreConservative() {
        val clock = FakeMonotonicClock()
        val source = FakeThermalSignalSource(
            statuses = listOf(
                PowerManager.THERMAL_STATUS_LIGHT,
                PowerManager.THERMAL_STATUS_SEVERE
            ),
            headrooms = listOf(0.50f)
        )
        val sampler = ThermalSampler(source = source, clock = clock)

        assertEquals(ThermalLevel.LIGHT, sampler.sample().level)
        clock.nowMs = 999L
        assertEquals(ThermalLevel.LIGHT, sampler.sample().level)
        assertEquals(1, source.statusReadCount)

        clock.nowMs = 1_000L
        assertEquals(ThermalLevel.SEVERE, sampler.sample().level)
        assertEquals(2, source.statusReadCount)
        assertEquals(1, source.headroomReadCount)
    }

    @Test
    fun androidSourceSdkGuardsKeepApi26Through28Safe() {
        assertEquals(false, AndroidThermalSignalSource.canReadStatus(sdkInt = 28))
        assertEquals(true, AndroidThermalSignalSource.canReadStatus(sdkInt = 29))
        assertEquals(false, AndroidThermalSignalSource.canReadHeadroom(sdkInt = 29))
        assertEquals(true, AndroidThermalSignalSource.canReadHeadroom(sdkInt = 30))
    }

    private class FakeMonotonicClock(
        var nowMs: Long = 0L
    ) : MonotonicClock {
        override fun nowMs(): Long = nowMs
    }

    private class FakeThermalSignalSource(
        private val statuses: List<Int?> = listOf(PowerManager.THERMAL_STATUS_NONE),
        private val headrooms: List<Float?>
    ) : ThermalSignalSource {
        var statusReadCount = 0
            private set
        var headroomReadCount = 0
            private set

        override fun readStatus(): Int? {
            val index = statusReadCount.coerceAtMost(statuses.lastIndex)
            statusReadCount += 1
            return statuses.getOrNull(index)
        }

        override fun readHeadroom(): Float? {
            val index = headroomReadCount.coerceAtMost(headrooms.lastIndex)
            headroomReadCount += 1
            return headrooms.getOrNull(index)
        }
    }
}
