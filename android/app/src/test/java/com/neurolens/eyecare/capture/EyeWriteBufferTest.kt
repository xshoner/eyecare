package com.neurolens.eyecare.capture

import org.junit.Assert.*
import org.junit.Test

class EyeWriteBufferTest {
    private fun frame(at: Long) = EyeObservation(at, at, at * 1_000_000,
        "synthetic", null, null)

    @Test fun flushesInOrderAtFourFramesOrTimeAndDrainsAtStop() {
        val b = EyeWriteBuffer()
        assertNull(b.offer(frame(1000)))
        assertNull(b.offer(frame(1033)))
        assertNull(b.offer(frame(1066)))
        assertEquals(listOf(1000L, 1033L, 1066L, 1099L),
            b.offer(frame(1099))!!.map { it.capturedAtMs })
        assertNull(b.offer(frame(2000)))
        assertNull(b.due(2249))
        assertEquals(2000L, b.due(2250)!!.single().capturedAtMs)
        b.offer(frame(3000))
        assertEquals(3000L, b.drain().single().capturedAtMs)
        assertTrue(b.drain().isEmpty())
    }
}
