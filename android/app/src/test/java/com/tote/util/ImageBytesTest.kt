package com.tote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
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

    /** The same JPEG, with an EXIF Orientation tag actually written into it. */
    private fun jpegWithOrientation(width: Int, height: Int, orientation: Int): ByteArray {
        val file = File.createTempFile("exif", ".jpg").apply { deleteOnExit() }
        file.writeBytes(jpeg(width, height))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    @Test
    fun `a quarter-turn tag is baked into the pixels`() {
        // The defect this whole column exists for: the camera hands over landscape pixels and a
        // tag saying "turn this". Re-encoding without reading the tag shipped it sideways with
        // nothing left in the file to recover from.
        val out = ImageBytes.downscaleToJpeg(
            jpegWithOrientation(400, 200, ExifInterface.ORIENTATION_ROTATE_90)
        )

        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertTrue(
            decoded.height > decoded.width,
            "a 90° tag must swap the edges — got ${decoded.width}x${decoded.height}",
        )
    }

    /**
     * A photo with a bright block in ONE corner, so a turn is visible in the pixels.
     *
     * The symmetric grid [jpeg] draws cannot catch a 180° turn at all — the dimensions are
     * unchanged and every quadrant looks like every other. A test that cannot fail is worse than
     * no test, because it reads as coverage.
     */
    private fun cornerMarked(width: Int, height: Int, orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val topLeft = x < width / 2 && y < height / 2
                bitmap.setPixel(x, y, if (topLeft) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
            }
        }
        val file = File.createTempFile("corner", ".jpg").apply { deleteOnExit() }
        file.writeBytes(
            ByteArrayOutputStream().also {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }.toByteArray()
        )
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.readBytes()
    }

    /** Roughly how bright a small patch is, so JPEG ringing cannot flip the assertion. */
    private fun brightnessAt(bitmap: Bitmap, x: Int, y: Int): Int =
        (0 until 8).sumOf { dx ->
            (0 until 8).sumOf { dy -> bitmap.getPixel(x + dx, y + dy) and 0xFF }
        } / 64

    @Test
    fun `a half turn moves the pixels, not just the dimensions`() {
        val out = ImageBytes.downscaleToJpeg(
            cornerMarked(400, 200, ExifInterface.ORIENTATION_ROTATE_180)
        )

        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(400, decoded.width)
        assertEquals(200, decoded.height)
        // The bright corner started top-left; after a half turn it belongs bottom-right.
        assertTrue(
            brightnessAt(decoded, decoded.width - 12, decoded.height - 12) >
                brightnessAt(decoded, 4, 4),
            "the 180° tag was not applied — the marked corner never moved",
        )
    }

    @Test
    fun `an untagged photo is left exactly as it was`() {
        // The overwhelmingly common case, and the one a half-written orientation pass breaks:
        // rotating something that made no claim about which way up it is.
        val out = ImageBytes.downscaleToJpeg(jpeg(400, 200))

        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(400, decoded.width)
        assertEquals(200, decoded.height)
    }

    @Test
    fun `orientation is applied to a photo that also needed downscaling`() {
        // Both paths at once: the subsample-then-scale route AND the matrix. They are applied in
        // that order, so the cap is on the edge a viewer will actually see.
        val out = ImageBytes.downscaleToJpeg(
            jpegWithOrientation(3200, 1600, ExifInterface.ORIENTATION_ROTATE_90)
        )

        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertTrue(decoded.height > decoded.width)
        assertEquals(ImageBytes.MAX_DIMENSION, maxOf(decoded.width, decoded.height))
    }
}
