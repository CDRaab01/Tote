package com.tote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real bitmaps, decoded and re-decoded — not byte-array stand-ins.
 *
 * The suite has been burned by a photo pipeline whose every test monkeypatched the one function
 * that touched pixels: it was green for weeks while shipping a defect that blackened every dark
 * garment. So these tests build an actual bitmap, run it through the real
 * `BitmapFactory`/`Bitmap.compress` path under Robolectric's native graphics, and decode the
 * result back to check its dimensions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageBytesTest {

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Not a flat fill: a uniform image compresses to almost nothing, which would make a
        // "the output is smaller" assertion meaningless.
        for (x in 0 until width step 8) {
            for (y in 0 until height step 8) {
                bitmap.setPixel(x, y, 0xFFCC3322.toInt())
            }
        }
        return ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        }.toByteArray()
    }

    private fun dimensionsOf(bytes: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test
    fun `an oversized photo is capped on its long edge`() {
        val (w, h) = dimensionsOf(ImageBytes.downscaleToJpeg(jpeg(4000, 3000)))

        assertEquals(ImageBytes.MAX_DIMENSION, w)
        // Aspect ratio preserved: 4000x3000 is 4:3, so 1600 wide is 1200 tall. A squashed photo
        // is a worse photo for both the model and the person reviewing it.
        assertEquals(1200, h)
    }

    @Test
    fun `a portrait photo is capped on its long edge too`() {
        val (w, h) = dimensionsOf(ImageBytes.downscaleToJpeg(jpeg(3000, 4000)))

        assertEquals(1200, w)
        assertEquals(ImageBytes.MAX_DIMENSION, h)
    }

    @Test
    fun `an already-small photo is not enlarged`() {
        val (w, h) = dimensionsOf(ImageBytes.downscaleToJpeg(jpeg(800, 600)))

        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun `the result is materially smaller, which is the whole point`() {
        val original = jpeg(4000, 3000)
        val downscaled = ImageBytes.downscaleToJpeg(original)

        // The server rejects anything over 8 MB and a phone camera clears that on one frame.
        // This is not a nice-to-have: without it the queue would 413 one capture at a time,
        // after the bin was already closed.
        assertTrue(
            downscaled.size < original.size / 2,
            "expected a real reduction, got ${downscaled.size} from ${original.size}",
        )
    }

    @Test
    fun `something undecodable is passed through rather than dropped`() {
        val garbage = ByteArray(128) { 0x7F }

        // Deliberate: this function shrinks photos, it does not adjudicate what a photo is.
        // Dropping the bytes would destroy the only copy of something that cannot be retaken;
        // passing them on gets an honest 422 from the server instead.
        assertTrue(ImageBytes.downscaleToJpeg(garbage).contentEquals(garbage))
    }
}
