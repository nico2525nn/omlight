package com.omlight.ui

import android.content.Context
import com.omlight.cam.Thumb
import java.io.File
import java.security.MessageDigest

/**
 * On-disk thumbnail cache. Camera thumbnails are tiny (160x120) but the
 * camera HTTP stack is slow; caching them makes grid revisit instant and
 * survives process death. Keyed by path+size so a replaced file re-fetches.
 *
 * Rotation rides in a sidecar (`<hash>.rot`, plain degrees text): the
 * JPEG bytes alone can't carry it, and re-reading EXIF per bind is
 * wasteful. Missing sidecar = upright (legacy entries).
 *
 * Bounded: 2000 files / 64MB, LRU by last-modified. Eviction runs on write
 * (amortized cheap: only scans when over budget).
 */
class ThumbStore(ctx: Context) {

    private val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    fun get(path: String, size: Long): Thumb? {
        return try {
            val f = fileFor(path, size)
            if (f.exists() && f.length() > 0) {
                f.setLastModified(System.currentTimeMillis())
                val deg = sidecarFor(path, size)
                    .takeIf { it.exists() }
                    ?.readText()?.trim()?.toIntOrNull()
                    ?.takeIf { it == 90 || it == 180 || it == 270 } ?: 0
                Thumb(f.readBytes(), deg)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun put(path: String, size: Long, thumb: Thumb) {
        try {
            if (thumb.jpeg.isEmpty()) return
            fileFor(path, size).writeBytes(thumb.jpeg)
            val sc = sidecarFor(path, size)
            if (thumb.degrees != 0) sc.writeText(thumb.degrees.toString())
            else sc.delete()
            evictIfNeeded()
        } catch (_: Exception) { /* cache is best-effort */ }
    }

    fun clear() {
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun fileFor(path: String, size: Long): File =
        File(dir, hashOf(path, size) + ".jpg")

    private fun sidecarFor(path: String, size: Long): File =
        File(dir, hashOf(path, size) + ".rot")

    private fun hashOf(path: String, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest("$path|$size".toByteArray()).take(16)
            .joinToString("") { "%02x".format(it) }
    }

    private fun evictIfNeeded() {
        val files = try {
            dir.listFiles { f -> f.extension == "jpg" }.orEmpty()
        } catch (_: Exception) {
            return
        }
        val total = files.sumOf { it.length() }
        if (files.size <= MAX_FILES && total <= MAX_BYTES) return
        // Over budget (count OR bytes): delete oldest until both fit,
        // keeping at least a quarter of the file budget. Sidecars go
        // with their JPEG so orphans never accumulate.
        val target = (MAX_FILES * 3 / 4).coerceAtLeast(100)
        var freed = 0L
        var kept = files.size
        for (f in files.sortedBy { it.lastModified() }) {
            if (kept <= target && total - freed <= MAX_BYTES) break
            freed += f.length()
            kept--
            runCatching { f.delete() }
            runCatching { File(dir, f.nameWithoutExtension + ".rot").delete() }
        }
    }

    companion object {
        private const val MAX_FILES = 2000
        private const val MAX_BYTES = 64L * 1024 * 1024
    }
}
