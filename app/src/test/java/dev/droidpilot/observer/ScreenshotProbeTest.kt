package dev.droidpilot.observer

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

// A capture of a secured screen succeeds by every API measure and contains one
// flat colour, so nothing above this can tell it apart from a working one
@RunWith(AndroidJUnit4::class)
class ScreenshotProbeTest {

    private fun bitmap(width: Int = 400, height: Int = 800, fill: (Bitmap) -> Unit): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also(fill)

    private fun solid(color: Int) = bitmap { it.eraseColor(color) }

    private fun encode(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

    @Test
    fun `a black frame is what a secured screen returns`() {
        assertTrue(ScreenshotProbe.isBlank(solid(Color.BLACK)))
    }

    @Test
    fun `a white frame is just as useless as a black one`() {
        assertTrue(ScreenshotProbe.isBlank(solid(Color.WHITE)))
    }

    @Test
    fun `a screen with anything drawn on it is not blank`() {
        val shot = bitmap { target ->
            target.eraseColor(Color.BLACK)
            // One button's worth of light, somewhere in the middle
            for (x in 150 until 250) {
                for (y in 380 until 420) target.setPixel(x, y, Color.WHITE)
            }
        }

        assertFalse(ScreenshotProbe.isBlank(shot))
    }

    @Test
    fun `a missing capture counts as blank rather than as usable`() {
        assertTrue(ScreenshotProbe.isBlank(null))
        assertTrue(ScreenshotProbe.isBlank(ByteArray(0)))
    }

    @Test
    fun `bytes that decode to a picture survive the round trip`() {
        val shot = bitmap { target ->
            for (x in 0 until target.width) {
                for (y in 0 until target.height) {
                    target.setPixel(x, y, if ((x / 20 + y / 20) % 2 == 0) Color.BLACK else Color.WHITE)
                }
            }
        }

        assertFalse(ScreenshotProbe.isBlank(encode(shot)))
    }

    @Test
    fun `bytes that are not an image at all count as blank`() {
        assertTrue(ScreenshotProbe.isBlank("not a png".toByteArray()))
    }
}
