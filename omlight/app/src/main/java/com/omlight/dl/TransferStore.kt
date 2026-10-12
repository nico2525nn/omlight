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
        // Identity = camera path + size + photo time. savedAt is only
        // used for expiry, never for identity.
        return load().any { it.path == path && it.size == size && it.takenAt == takenAt }
    }

    /** Hash-verified check: same identity AND same SHA-256. */
    @Synchronized
    fun isDoneVerified(path: String, size: Long, takenAt: Long, sha256: String): Boolean {
        if (sha256.isEmpty()) return isDone(path, size, takenAt)
        prune()
        return loadRaw().any {
            it.path == path && it.size == size && it.takenAt == takenAt && it.sha256 == sha256
        }
    }

    @Synchronized
    fun markDone(path: String, size: Long, takenAt: Long, sha256: String = "") {
        val cur = loadRaw().toMutableList()
        cur.removeAll { it.path == path }
        cur.add(DoneRaw(path, size, takenAt, System.currentTimeMillis(), sha256))
        pruneInPlace(cur)
        saveRaw(cur)
    }

    /**
     * Drop entries saved longer ago than KEEP_DAYS and cap the count.
     * Expiry uses the phone wall-clock at save time — NOT the photo's
     * taken date (an old photo downloaded today must stay skipped).
     */
    private fun prune() {
        val cur = loadRaw().toMutableList()
        if (pruneInPlace(cur)) saveRaw(cur)
    }

    private fun pruneInPlace(cur: MutableList<DoneRaw>): Boolean {
        val before = cur.size
        var changed = false
        while (cur.size > MAX_HISTORY) {
            cur.removeAt(0)
            changed = true
        }
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 3600 * 1000
        val kept = cur.filter { it.savedAt >= cutoff }
        if (kept.size != cur.size) {
            cur.clear()
            cur.addAll(kept)
            changed = true
        }
        return changed || cur.size != before
    }

    /** Storage row: identity + phone save time + content hash. */
    private data class DoneRaw(
        val path: String,
        val size: Long,
        val takenAt: Long,
        val savedAt: Long,
        val sha256: String = "",
    )

    @Synchronized
    fun history(): List<Done> = load()

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_DONE).apply()
    }

    // -- pending queue journal ------------------------------------------------------
    // Two slots: queued[] (not started) and active (mid-download).
    // active is cleared only after MediaStore publish, so a crash
    // mid-file resumes it via Range instead of losing it.

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
        // Active first (resume it), then the rest in order.
        val out = ArrayList<DlItem>()
        loadActive()?.let { out.add(it) }
        val raw = prefs.getString(KEY_QUEUE, null) ?: return out
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(DlItem(o.getString("p"), o.getLong("s"), o.optLong("t")))
            }
        } catch (_: Exception) { /* corrupted journal: active still resumes */ }
        return out
    }

    @Synchronized
    fun setActive(item: DlItem?) {
        val ed = prefs.edit()
        if (item == null) {
            ed.remove(KEY_ACTIVE)
        } else {
            ed.putString(
                KEY_ACTIVE,
                JSONObject().put("p", item.path).put("s", item.sizeBytes)
                    .put("t", item.takenAt).toString(),
            )
        }
        // Commit, not apply: the download starts right after; the journal
        // must be on disk before any byte is trusted.
        ed.commit()
    }

    private fun loadActive(): DlItem? {
        val raw = prefs.getString(KEY_ACTIVE, null) ?: return null
        return try {
            val o = JSONObject(raw)
            DlItem(o.getString("p"), o.getLong("s"), o.optLong("t"))
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearQueue() {
        prefs.edit().remove(KEY_QUEUE).remove(KEY_ACTIVE).apply()
    }

    /**
     * Pending work summary for the "resume later" banner.
     * Returns (item count, total bytes or -1 when unknown).
     */
    @Synchronized
    fun pendingSummary(): Pair<Int, Long> {
        val items = loadQueue().filterNot {
            isDoneNoPrune(it.path, it.sizeBytes, it.takenAt)
        }
        if (items.isEmpty()) return 0 to -1
        val bytes = items.sumOf { it.sizeBytes }
        return items.size to if (bytes > 0) bytes else -1
    }

    private fun isDoneNoPrune(path: String, size: Long, takenAt: Long): Boolean {
        if (size <= 0) return false
        return load().any { it.path == path && it.size == size && it.takenAt == takenAt }
    }

    // -- internals ---------------------------------------------------------------------

    private fun load(): List<Done> =
        loadRaw().map { Done(it.path, it.size, it.takenAt) }

    private fun loadRaw(): List<DoneRaw> {
        val raw = prefs.getString(KEY_DONE, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                DoneRaw(
                    o.getString("p"), o.getLong("s"), o.optLong("t"),
                    // v1 rows lack savedAt: treat as now (don't nuke
                    // history on upgrade; they'll age out naturally).
                    o.optLong("saved", System.currentTimeMillis()),
                    o.optString("sha", ""),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveRaw(list: List<DoneRaw>) {
        val a = JSONArray()
        for (d in list) {
            a.put(
                JSONObject().put("p", d.path).put("s", d.size)
                    .put("t", d.takenAt).put("saved", d.savedAt)
                    .put("sha", d.sha256)
            )
        }
        prefs.edit().putString(KEY_DONE, a.toString()).apply()
    }

    companion object {
        private const val KEY_DONE = "done_v1"
        private const val KEY_QUEUE = "queue_v1"
        private const val KEY_ACTIVE = "active_v1"
        private const val MAX_HISTORY = 5000
        private const val KEEP_DAYS = 90L
    }
}
