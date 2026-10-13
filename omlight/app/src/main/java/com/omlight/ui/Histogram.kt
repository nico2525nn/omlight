package com.omlight.ui

import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-channel RGB + luminance histogram from JPEG bytes.
 *
 * Downsamples to <= 256px on the long edge before counting: a 1920px
 * preview decodes in ~30ms, a 256px one in ~3ms, and the histogram
 * shape is visually identical. 128 bins for smooth curves.
 */
object Histogram {

    const val BINS = 128

    data class Rgb(
        val r: IntArray,
        val g: IntArray,
        val b: IntArray,
        /** Rec.709 luminance, same bins. */
        val y: IntArray,
        val max: Int,
        /** Share of pixels clipped pure black / white (0..1). */
        val blackClip: Float,
        val whiteClip: Float,
    ) {
        val isEmpty: Boolean get() = max <= 0
    }

    suspend fun of(jpeg: ByteArray): Rgb = withContext(Dispatchers.Default) {
        try {
            // Bounds first, then subsampled decode.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@withContext empty()
            val sample = (longest / 256).coerceAtLeast(1)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                // 8888, not 565: 565 quantizes to 32/64 levels and leaves
                // every other bin at zero (comb teeth on the graph).
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
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
                val y = IntArray(BINS)
                var black = 0
                var white = 0
                for (p in px) {
                    val cr = (p shr 16) and 0xFF
                    val cg = (p shr 8) and 0xFF
                    val cb = p and 0xFF
                    r[cr * BINS / 256]++
                    g[cg * BINS / 256]++
                    b[cb * BINS / 256]++
                    val lum = (0.2126 * cr + 0.7152 * cg + 0.0722 * cb).toInt()
                        .coerceIn(0, 255)
                    y[lum * BINS / 256]++
                    if (cr == 0 && cg == 0 && cb == 0) black++
                    if (cr == 255 && cg == 255 && cb == 255) white++
                }
                // Box-blur the bins (radius 1): kills single-bin spikes
                // from JPEG ringing so the line reads as a curve.
                fun smooth(d: IntArray): IntArray {
                    val o = IntArray(BINS)
                    for (i in 0 until BINS) {
                        var s = d[i]
                        var n = 1
                        if (i > 0) {
                            s += d[i - 1]; n++
                        }
                        if (i + 1 < BINS) {
                            s += d[i + 1]; n++
                        }
                        o[i] = s / n
                    }
                    return o
                }
                val sr = smooth(r)
                val sg = smooth(g)
                val sb = smooth(b)
                val sy = smooth(y)
                val n = px.size.toFloat().coerceAtLeast(1f)
                Rgb(sr, sg, sb, sy, maxOf(sr.max(), sg.max(), sb.max(), sy.max()), black / n, white / n)
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        } catch (_: Exception) {
            empty()
        }
    }

    private fun empty() = Rgb(IntArray(BINS), IntArray(BINS), IntArray(BINS), IntArray(BINS), 0, 0f, 0f)
}
