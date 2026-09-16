package dev.droidpilot.observer

import android.graphics.Bitmap
import android.graphics.BitmapFactory

// Whether a capture actually shows the screen.
//
// A game that runs a security solution sets FLAG_SECURE, and the platform then
// hands back a capture that succeeded by every API measure and contains one
// flat colour. Passing that to the planner is worse than passing nothing: it
// answers, in the format the grammar demands, with coordinates it invented.
// There is no way to tell from the view tree, because a game has no view tree
object ScreenshotProbe {

    // A 16 by 16 lattice. Small enough to cost nothing, dense enough that a
    // single dialog or button anywhere on screen lands on at least one sample
    private const val SAMPLES_PER_AXIS = 16

    // Compression and gradients move the low bits around. A real screen varies
    // by far more than this between its lightest and darkest sample
    private const val FLAT_TOLERANCE = 8

    fun isBlank(bytes: ByteArray?): Boolean {
        val bitmap = decode(bytes) ?: return true
        return try {
            isBlank(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun isBlank(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true

        var minLuma = Int.MAX_VALUE
        var maxLuma = Int.MIN_VALUE

        for (row in 0 until SAMPLES_PER_AXIS) {
            for (column in 0 until SAMPLES_PER_AXIS) {
                val x = bitmap.width * column / SAMPLES_PER_AXIS
                val y = bitmap.height * row / SAMPLES_PER_AXIS
                val luma = luma(bitmap.getPixel(x, y))

                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma

                // Enough contrast to be a picture of something, so the rest of
                // the lattice cannot change the answer
                if (maxLuma - minLuma > FLAT_TOLERANCE) return false
            }
        }
        return true
    }

    // Rounded ITU-R BT.601, integer only. A secured capture is usually black,
    // but a white or single-colour frame is just as useless and this catches
    // every one of them with the same test
    private fun luma(pixel: Int): Int {
        val red = pixel shr 16 and 0xFF
        val green = pixel shr 8 and 0xFF
        val blue = pixel and 0xFF
        return (red * 77 + green * 151 + blue * 28) shr 8
    }
}
