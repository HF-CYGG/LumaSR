package com.lumasr.data

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.lumasr.domain.ThermalLevel

fun interface MonotonicClock {
    fun nowMs(): Long
}

object AndroidMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

interface ThermalSignalSource {
    fun readStatus(): Int?

    fun readHeadroom(): Float?
}

class AndroidThermalSignalSource(
    private val powerManager: PowerManager?,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : ThermalSignalSource {
    constructor(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ) : this(
        powerManager = context.applicationContext.getSystemService(PowerManager::class.java),
        sdkInt = sdkInt
    )

    override fun readStatus(): Int? {
        if (!canReadStatus(sdkInt)) return null
        val manager = powerManager ?: return null
        return readStatusApi29(manager)
    }

    override fun readHeadroom(): Float? {
        if (!canReadHeadroom(sdkInt)) return null
        val manager = powerManager ?: return null
        return readHeadroomApi30(manager)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun readStatusApi29(manager: PowerManager): Int? =
        runCatching { manager.currentThermalStatus }.getOrNull()

    @TargetApi(Build.VERSION_CODES.R)
    private fun readHeadroomApi30(manager: PowerManager): Float? =
        runCatching { manager.getThermalHeadroom(0) }.getOrNull()

    companion object {
        internal fun canReadStatus(sdkInt: Int): Boolean =
            sdkInt >= Build.VERSION_CODES.Q

        internal fun canReadHeadroom(sdkInt: Int): Boolean =
            sdkInt >= Build.VERSION_CODES.R
    }
}

internal object AndroidThermalStatusMapper {
    fun map(status: Int?): ThermalLevel? = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalLevel.NORMAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.CRITICAL

        else -> null
    }
}

data class ThermalSample(
    val level: ThermalLevel?,
    val headroom: Float?
)

class ThermalSampler(
    private val source: ThermalSignalSource,
    private val clock: MonotonicClock = AndroidMonotonicClock
) {
    private var lastHeadroomReadAtMs: Long? = null
    private var lastValidHeadroom: Float? = null

    @Synchronized
    fun sample(): ThermalSample {
        val status = source.readStatus()
        val nowMs = clock.nowMs()
        if (shouldReadHeadroom(nowMs)) {
            lastHeadroomReadAtMs = nowMs
            source.readHeadroom()
                ?.takeIf { it.isFinite() }
                ?.let { lastValidHeadroom = it }
        }
        return ThermalSample(
            level = AndroidThermalStatusMapper.map(status),
            headroom = lastValidHeadroom
        )
    }

    private fun shouldReadHeadroom(nowMs: Long): Boolean {
        val lastReadAtMs = lastHeadroomReadAtMs ?: return true
        if (nowMs < lastReadAtMs) return false
        val elapsedMs = nowMs - lastReadAtMs
        return elapsedMs < 0L || elapsedMs >= MIN_HEADROOM_INTERVAL_MS
    }

    companion object {
        const val MIN_HEADROOM_INTERVAL_MS = 10_000L
    }
}
