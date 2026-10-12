package com.neurolens.eyecare.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class EyeSignalEngineTest {
    @Test fun sparseFramesCannotClaimZeroBlinks() {
        val engine = EyeSignalEngine()
        (1..12).forEach { engine.add(it * 500L, .2, .2, emptyMap()) }
        val signals = engine.snapshot(6_000L)
        assertEquals("insufficient_temporal_sampling", signals["F02.blinksPerMin"]?.reason)
        assertNotNull(signals["F06.openMean"]?.value)
    }

    @Test fun denseFramesCanObserveBlinkRate() {
        val engine = EyeSignalEngine()
        (1..30).forEach { engine.add(it * 100L, .2, .2, emptyMap()) }
        val signals = engine.snapshot(3_000L)
        assertEquals(0.0, signals["F02.blinksPerMin"]?.value!!, .001)
    }
}
