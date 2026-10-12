package com.neurolens.eyecare.capture

/** Pixel index transform for right-angle camera sensor rotation, independent of Android Bitmap. */
object FrameRotation {
    fun dimensions(width: Int, height: Int, rotation: Int): Pair<Int, Int> =
        if (rotation == 90 || rotation == 270) height to width else width to height

    inline fun destination(row: Int, col: Int, width: Int, height: Int,
        rotation: Int, outWidth: Int): Int {
        return when (rotation) {
            90 -> col * outWidth + (height - 1 - row)
            180 -> (height - 1 - row) * outWidth + (width - 1 - col)
            270 -> (width - 1 - col) * outWidth + row
            else -> row * outWidth + col
        }
    }
}
