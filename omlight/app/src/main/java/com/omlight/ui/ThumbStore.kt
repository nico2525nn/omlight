package com.omlight.ui

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * On-disk thumbnail cache. Camera thumbnails are tiny (160x120) but the
 * camera HTTP stack is slow; caching them makes grid revisit instant and
 * survives process death. Keyed by path+size so a replaced file re-fetches.
 *
 * Bounded: 2000 files / 64MB, LRU by last-modified. Eviction runs on write
 * (amortized cheap: only scans when over budget).
 */
class ThumbStore(ctx: Context) {

    private val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    fun get(path: String, size: Long): ByteArray? {
        return try {
            val f = fileFor(path, size)
            if (f.exists() && f.length() > 0) {
                f.setLastModified(System.currentTimeMillis())
                f.readBytes()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun put(path: String, size: Long, jpeg: ByteArray) {
        try {
            if (jpeg.isEmpty()) return
            fileFor(path, size).writeBytes(jpeg)
            evictIfNeeded()
        } catch (_: Exception) { /* cache is best-effort */ }
    }

    fun clear() {
        try {
            dir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) { /* ignore */ }
    }

    private fun fileFor(path: String, size: Long): File {
        val md = MessageDigest.getInstance("SHA-256")
        val h = md.digest("$path|$size".toByteArray()).take(16)
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$h.jpg")
    }

    private fun evictIfNeeded() {
        val files = try { dir.listFiles().orEmpty() } catch (_: Exception) { return }
        if (files.size <= MAX_FILES) {
            val total = files.sumOf { it.length() }
            if (total <= MAX_BYTES) return
        }
        files.sortedBy { it.lastModified() }
            .dropLast(MAX_FILES / 2)
            .forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val MAX_FILES = 2000
        private const val MAX_BYTES = 64L * 1024 * 1024
    }
}
