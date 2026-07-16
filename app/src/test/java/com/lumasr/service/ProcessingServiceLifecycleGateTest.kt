package com.lumasr.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingServiceLifecycleGateTest {
    @Test
    fun doesNotStopBeforeFirstStartOrWhileResumeRefreshIsRunning() {
        val gate = ProcessingServiceLifecycleGate()

        assertNull(gate.idleStopCandidate(isIdle = true))

        val resume = gate.beginOperation(startId = 10)
        assertNull(gate.idleStopCandidate(isIdle = true))

        gate.completeOperation(resume)
        assertNull(gate.idleStopCandidate(isIdle = false))
        assertEquals(10, gate.idleStopCandidate(isIdle = true)?.startId)
    }

    @Test
    fun newerResumeInvalidatesIdleStopCapturedForOlderStartId() {
        val gate = ProcessingServiceLifecycleGate()
        val oldResume = gate.beginOperation(startId = 20)
        gate.completeOperation(oldResume)
        val oldStop = requireNotNull(gate.idleStopCandidate(isIdle = true))
        assertTrue(gate.isStopStillValid(oldStop))

        val newResume = gate.beginOperation(startId = 21)

        assertFalse(gate.isStopStillValid(oldStop))
        assertNull(gate.idleStopCandidate(isIdle = true))
        gate.completeOperation(newResume)
        val newStop = requireNotNull(gate.idleStopCandidate(isIdle = true))
        assertEquals(21, newStop.startId)
        assertTrue(gate.isStopStillValid(newStop))
    }
}
