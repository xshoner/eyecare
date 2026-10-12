package com.neurolens.eyecare.capture

import org.junit.Assert.*
import org.junit.Test

class FrameRotationTest {
    @Test fun rightAngleTransformsPreserveEveryPixelAndOrientation() {
        val expected = mapOf(0 to listOf(0, 1, 2, 3, 4, 5),
            90 to listOf(4, 2, 0, 5, 3, 1),
            180 to listOf(5, 4, 3, 2, 1, 0),
            270 to listOf(1, 3, 5, 0, 2, 4))
        for ((rotation, values) in expected) {
            val (w, h) = FrameRotation.dimensions(2, 3, rotation)
            val output = IntArray(w * h)
            for (row in 0 until 3) for (col in 0 until 2)
                output[FrameRotation.destination(row, col, 2, 3, rotation, w)] = row * 2 + col
            assertEquals(values, output.toList())
        }
    }
}
