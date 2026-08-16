package com.tote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}
