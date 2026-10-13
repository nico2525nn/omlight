package com.omlight.ui

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-channel RGB histogram from JPEG bytes.
 *
 * Downsamples to <= 256px on the long edge before counting: a 1920px
 * preview decodes in ~30ms, a 256px one in ~3ms, and the histogram
 * shape is visually identical. 64 bins keep the canvas cheap.
 */
object Histogram {

    const val BINS = 64

    data class Rgb(
        val r: IntArray,
        val g: IntArray,
        val b: IntArray,
        val max: Int,
    ) {
        val isEmpty: Boolean get() = max <= 0
    }

    suspend fun of(jpeg: ByteArray): Rgb = withContext(Dispatchers.Default) {
        try {
            // Bounds first, then subsampled decode.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            val sample = (longest / 256).coerceAtLeast(1)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                ?: return@withContext empty()
            try {
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                val r = IntArray(BINS)
                val g = IntArray(BINS)
                val b = IntArray(BINS)
                for (p in px) {
                    r[((p shr 16) and 0xFF) * BINS / 256]++
                    g[((p shr 8) and 0xFF) * BINS / 256]++
                    b[(p and 0xFF) * BINS / 256]++
                }
                val max = maxOf(r.max(), g.max(), b.max())
                Rgb(r, g, b, max)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (_: Exception) {
            empty()
        }
    }

    private fun empty() = Rgb(IntArray(BINS), IntArray(BINS), IntArray(BINS), 0)
}
