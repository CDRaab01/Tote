package com.tote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Downscale a photo before upload.
 *
 * A requirement, not an optimisation. The server caps an upload at 8 MB and a modern phone's
 * camera clears that on a single frame — a queued bin's worth of raw captures would be rejected
 * one 413 at a time, after the photos had already been taken and the bin closed. Both the camera
 * and the gallery path route through here for that reason.
 *
 * 1600px is the size the vision model reads an item photo at just as well as a 4000px one, and it
 * is also what makes the upload survive the garage's Wi-Fi. The suite-shared idiom, ported from
 * Crate's `util/ImageBytes.kt` (itself from Cookbook).
 *
 * **This is also where orientation is decided, and it used to be where orientation died.** A
 * phone camera writes its pixels in SENSOR orientation and records how far to turn them in an
 * EXIF Orientation tag. `BitmapFactory` ignores that tag and `Bitmap.compress` writes no EXIF at
 * all — so re-encoding here shipped sideways pixels with nothing left in the file to say which
 * way up they belonged, and every photograph taken before this was fixed is unrecoverable from
 * its own bytes. The tag is now read BEFORE the decode and baked into the pixels, which makes
 * the uploaded bytes canonical: upright is upright, with no metadata anyone downstream has to
 * remember to honour.
 */
object ImageBytes {

    const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 85

    /**
     * Re-encode as JPEG with the long edge capped at [MAX_DIMENSION] px.
     *
     * Returns the original bytes when the image cannot be decoded. That is deliberate: this
     * function's job is to shrink a photo, not to adjudicate whether it is a photo. Something
     * undecodable is far more likely to be a bug worth seeing the server's 422 for than a file
     * worth silently dropping on the floor — and dropping it would lose the only copy.
     */
    fun downscaleToJpeg(bytes: ByteArray): ByteArray {
        // Read the tag first, off the ORIGINAL bytes — this is the last moment it exists. A file
        // that carries no EXIF (a gallery pick that has been through another app, a synthetic
        // test image) reads as NORMAL, which is the honest default: no claim was made.
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return bytes

        // Power-of-two subsampling gets within 2x of the target without ever allocating the
        // full-resolution bitmap — which on an 8-photo batch is the difference between working
        // and an OutOfMemoryError.
        var sampleSize = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sampleSize * 2) >= MAX_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return bytes

        // ...then an exact scale lands on the cap when still over it.
        val scale = MAX_DIMENSION.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }

        val upright = applyOrientation(bitmap, orientation)

        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        upright.recycle()
        return out.toByteArray()
    }

    /**
     * Bake an EXIF orientation into the pixels.
     *
     * All eight states are handled, not just the three rotations: the mirrored ones are rare but
     * real (some front cameras and a few gallery editors write them), and a half-handled tag is
     * worse than an unread one — it puts SOME photographs right and leaves others wrong with no
     * pattern anyone can spot.
     *
     * Returns the input untouched for [ExifInterface.ORIENTATION_NORMAL] and
     * [ExifInterface.ORIENTATION_UNDEFINED], so the overwhelmingly common case allocates nothing.
     */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned != bitmap) bitmap.recycle()
        return turned
    }
}
