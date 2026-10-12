package com.omlight.dl

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transfer history + persisted queue, backed by SharedPreferences JSON.
 *
 * Two jobs:
 * 1. Duplicate skip: `path|size|mtime` of every verified download. A file
 *    already saved is skipped on re-enqueue (user can force via UI later).
 * 2. Crash-safe queue: pending paths are journaled before download and
 *    cleared after verify, so a killed service resumes leftovers.
 *
 * Bounded: history keeps the newest 5000 entries; queue is naturally small.
 * All methods are synchronous and cheap (<1ms for typical sizes).
 */
class TransferStore(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("transfer", Context.MODE_PRIVATE)

    data class Done(val path: String, val size: Long, val takenAt: Long)

    @Synchronized
    fun isDone(path: String, size: Long, takenAt: Long): Boolean {
        if (size <= 0) return false
        prune()
        return load().any { it.path == path && it.size == size && it.takenAt == takenAt }
    }

    @Synchronized
    fun markDone(path: String, size: Long, takenAt: Long) {
        val cur = load().toMutableList()
        val d = Done(path, size, takenAt)
        cur.removeAll { it.path == path }
        cur.add(d)
        pruneInPlace(cur)
        save(cur)
    }

    /**
     * Drop entries older than KEEP_DAYS (by phone wall-clock at save
     * time, stored alongside) and cap the count. Pruning keeps the
     * JSON small and isDone() O(n)-cheap.
     */
    private fun prune() {
        val cur = load().toMutableList()
        if (pruneInPlace(cur)) save(cur)
    }

    private fun pruneInPlace(cur: MutableList<Done>): Boolean {
        val before = cur.size
        val cutoffPhoto = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        // takenAt is the photo's FAT time; entries with unknown or ancient
        // photo dates still prune by insertion order (list tail = newest).
        var changed = false
        while (cur.size > MAX_HISTORY) {
            cur.removeAt(0)
            changed = true
        }
        // Also drop entries whose photo predates the window — re-download
        // after 90 days is unsurprising and cheap to re-verify.
        val kept = cur.filter { it.takenAt <= 0 || it.takenAt >= cutoffPhoto }
        if (kept.size != cur.size) {
            cur.clear()
            cur.addAll(kept)
            changed = true
        }
        return changed || cur.size != before
    }

    @Synchronized
    fun history(): List<Done> = load()

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_DONE).apply()
    }

    // -- pending queue journal ------------------------------------------------------

    @Synchronized
    fun saveQueue(items: List<DlItem>) {
        val a = JSONArray()
        for (i in items) {
            a.put(
                JSONObject().put("p", i.path).put("s", i.sizeBytes).put("t", i.takenAt)
            )
        }
        prefs.edit().putString(KEY_QUEUE, a.toString()).apply()
    }

    @Synchronized
    fun loadQueue(): List<DlItem> {
        val raw = prefs.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                DlItem(o.getString("p"), o.getLong("s"), o.optLong("t"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clearQueue() {
        prefs.edit().remove(KEY_QUEUE).apply()
    }

    // -- internals ---------------------------------------------------------------------

    private fun load(): List<Done> {
        val raw = prefs.getString(KEY_DONE, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                Done(o.getString("p"), o.getLong("s"), o.optLong("t"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<Done>) {
        val a = JSONArray()
        for (d in list) {
            a.put(JSONObject().put("p", d.path).put("s", d.size).put("t", d.takenAt))
        }
        prefs.edit().putString(KEY_DONE, a.toString()).apply()
    }

    companion object {
        private const val KEY_DONE = "done_v1"
        private const val KEY_QUEUE = "queue_v1"
        private const val MAX_HISTORY = 5000
        private const val KEEP_DAYS = 90L
    }
}
