package com.omlight.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.omlight.MainActivity
import com.omlight.R
import com.omlight.cam.CamSession
import com.omlight.cam.OpcTransport
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One queued original-file download. */
data class DlItem(
    val path: String,
    val sizeBytes: Long,
    /** DCF taken-at millis for duplicate detection. 0 = unknown. */
    val takenAt: Long = 0L,
)

/** Observable queue state for the UI. */
data class DlState(
    val queued: Int = 0,
    val active: String? = null,
    val activeBytes: Long = 0L,
    val activeTotal: Long = 0L,
    val done: Int = 0,
    val failed: List<String> = emptyList(),
    val skipped: Int = 0,
    val paused: Boolean = false,
    val lastError: String? = null,
)

/**
 * Foreground download service.
 *
 * Why a foreground service (not WorkManager): the camera AP is an
 * internet-less network that exists only while the transfer runs.
 * WorkManager's network constraints (CONNECTED, let alone UNMETERED)
 * don't model "this specific Wi-Fi with no internet", and expedited
 * work is still subject to quotas. A user-visible FGS with type
 * dataSync is the honest primitive: the user asked for these files
 * now, a notification shows progress, and the transfer keeps running
 * when the app goes to the background.
 *
 * Files land in Pictures/OMLight via MediaStore (scoped storage,
 * no storage permission needed).
 */
class DownloadService : LifecycleService() {

    private val queue = Channel<DlItem>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var worker: Job? = null

    private var holding = false

    override fun onCreate() {
        super.onCreate()
        store = TransferStore(this)
        ensureChannel()
        startForegroundCompat(progressNotification("準備中…", 0, 0, true))
        // Drop staging temps older than a week (crashed runs that will
        // never resume: camera files rotate, Range offsets go stale).
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                cacheDir.listFiles { f ->
                    f.name.startsWith("dl_") && f.name.endsWith(".tmp")
                }?.forEach { f ->
                    if (f.lastModified() < cutoff) runCatching { f.delete() }
                }
            } catch (_: Exception) { /* best-effort */ }
        }
        worker = lifecycleScope.launch { drain() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val paths = intent.getStringArrayExtra(EXTRA_PATHS) ?: emptyArray()
                val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: longArrayOf()
                val taken = intent.getLongArrayExtra(EXTRA_TAKEN) ?: longArrayOf()
                lifecycleScope.launch {
                    val fresh = paths.mapIndexed { i, p ->
                        DlItem(p, sizes.getOrElse(i) { 0L }, taken.getOrElse(i) { 0L })
                    }
                    // Duplicate skip: already-verified files never re-download.
                    var skipped = 0
                    for (item in fresh) {
                        if (store.isDone(item.path, item.sizeBytes, item.takenAt)) {
                            skipped++
                        } else {
                            pending.add(item)
                            queue.send(item)
                        }
                    }
                    if (skipped > 0) {
                        _state.update { it.copy(skipped = it.skipped + skipped) }
                    }
                    _state.update { it.copy(queued = it.queued + fresh.size - skipped) }
                    persistQueue()
                    refresh()
                }
            }
            ACTION_CANCEL -> {
                lifecycleScope.launch {
                    mutex.withLock { cancelCurrent = true }
                }
            }
            ACTION_PAUSE -> {
                _state.update { it.copy(paused = true) }
                lifecycleScope.launch {
                    mutex.withLock { cancelCurrent = true }
                }
                refresh()
            }
            ACTION_RESUME -> {
                _state.update { it.copy(paused = false) }
                refresh()
            }
            ACTION_RETRY_FAILED -> {
                val paths = intent.getStringArrayExtra(EXTRA_PATHS) ?: emptyArray()
                val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: longArrayOf()
                val taken = intent.getLongArrayExtra(EXTRA_TAKEN) ?: longArrayOf()
                lifecycleScope.launch {
                    _state.update { it.copy(failed = emptyList(), lastError = null) }
                    var n = 0
                    paths.forEachIndexed { i, p ->
                        val item = DlItem(p, sizes.getOrElse(i) { 0L }, taken.getOrElse(i) { 0L })
                        pending.add(item)
                        queue.send(item)
                        n++
                    }
                    _state.update { it.copy(queued = it.queued + n) }
                    persistQueue()
                    refresh()
                }
            }
        }
        return START_NOT_STICKY
    }

    @Volatile private var cancelCurrent = false
    /** In-memory mirror of the channel for journaling. Guarded by mutex. */
    private val pending = ArrayDeque<DlItem>()
    private lateinit var store: TransferStore

    private fun persistQueue() {
        lifecycleScope.launch(Dispatchers.IO) {
            mutex.withLock { store.saveQueue(pending.toList()) }
        }
    }

    private suspend fun drain() {
        // Resume leftovers journaled by a previous (killed) run.
        for (item in store.loadQueue()) {
            if (store.isDone(item.path, item.sizeBytes, item.takenAt)) continue
            pending.add(item)
            queue.send(item)
            _state.update { it.copy(queued = it.queued + 1) }
        }
        for (item in queue) {
            // Pause gate: wait here (service stays foreground) until resume.
            while (_state.value.paused) {
                delay(500)
            }
            cancelCurrent = false
            _state.update { it.copy(queued = it.queued - 1) }
            mutex.withLock { pending.remove(item) }
            persistQueue()
            // Journal BEFORE downloading: a crash from here on resumes
            // this file via Range. Cleared only after publish.
            store.setActive(item)
            try {
                ensureClient()
                // Long queues outlive the default link timeout: refresh
                // every 10 files (cheap no-op when unsupported).
                if (_state.value.done % 10 == 0) {
                    try {
                        CamSession.peek()?.extendTimeout()
                    } catch (_: Exception) { /* best-effort */ }
                }
                val sha = downloadOne(item)
                store.markDone(item.path, item.sizeBytes, item.takenAt, sha)
                store.setActive(null)
                _state.update { it.copy(done = it.done + 1, active = null) }
            } catch (e: Paused) {
                // Requeue at head; the pause gate above will hold.
                // Keep the active journal: pause-then-kill still resumes.
                mutex.withLock { pending.addFirst(item) }
                queue.send(item)
                _state.update { it.copy(queued = it.queued + 1, active = null) }
                persistQueue()
            } catch (e: Exception) {
                // Transport died (AP lost?): drop the client so the
                // next item re-probes, then fail this item.
                dropClient()
                store.setActive(null)
                _state.update {
                    it.copy(
                        failed = it.failed + item.path,
                        active = null,
                        lastError = "${item.path.substringAfterLast('/')}: ${e.message}",
                    )
                }
            }
            refresh()
            if (_state.value.queued <= 0 && _state.value.active == null) {
                store.clearQueue()
                // Queue empty: stop the service, keep state for the UI.
                stopSelf()
            }
        }
    }

    private suspend fun ensureClient() {
        if (holding && CamSession.peek() != null) return
        val c = CamSession.acquire(this)
        holding = true
        CamSession.network()?.watchLoss {
            // AP dropped mid-transfer: abort the current file (temp stays
            // for Range resume) and fail loudly instead of spinning.
            cancelCurrent = true
        }
    }

    private suspend fun dropClient() {
        // Don't release the ref here: the session may be shared with the
        // UI. Just force the NEXT ensureClient to reconnect by closing
        // the shared triple only when we hold the last ref... simpler:
        // closeNow is too aggressive (kills UI too). Instead, poke the
        // shared client to reconnect on next use.
        //
        // Decision: ask the shared client to re-handshake. OpcClient is
        // stateless enough (mode re-sent) that connect() heals it.
        try {
            CamSession.peek()?.connect()
        } catch (_: Exception) {
            // Truly dead: release our ref so the next item re-acquires
            // from scratch (and the UI's own failure path fires too).
            if (holding) {
                holding = false
                CamSession.release()
            }
        }
    }

    private suspend fun downloadOne(item: DlItem): String {
        _state.update {
            it.copy(active = item.path, activeBytes = 0L, activeTotal = item.sizeBytes)
        }
        refresh()
        return withContext(Dispatchers.IO) {
            // Stage to a cache temp file first: allows Range resume and
            // post-download verification before anything hits MediaStore.
            // Name = hash of full identity (NOT the bare filename: two
            // DCF folders can hold same-named files with same size).
            val tmp = stagingFile(item)
            var offset = if (tmp.exists()) tmp.length() else 0L
            // Stale temp larger than expected (camera file replaced?): restart.
            if (item.sizeBytes > 0 && offset > item.sizeBytes) {
                tmp.delete()
                offset = 0L
            }
            // Skip when the temp already holds the whole verified file
            // (previous run died between verify and MediaStore publish).
            if (item.sizeBytes > 0 && offset == item.sizeBytes && verifyHead(tmp, item.path)) {
                val sha = sha256Of(tmp)
                publish(tmp, item)
                _state.update { it.copy(activeBytes = offset, activeTotal = item.sizeBytes) }
                return@withContext sha
            }
            var attempt = 0
            var done = offset
            while (true) {
                if (cancelCurrent) throw if (_state.value.paused) Paused() else Cancelled()
                // Re-acquire per attempt: a mid-file drop rebuilds the
                // client via ensureClientResume(); a stale handle would
                // retry on a dead transport.
                val c = CamSession.peek() ?: throw IOException("not connected")
                // Actual fetch (possibly resumed from `done`).
                c.originalFrom(item.path, done).use { r ->
                    if (r.code == 416) {
                        // Offset past EOF: restart from scratch.
                        tmp.delete()
                        done = 0L
                        return@use // exits use{}; loop retries fresh
                    }
                    if (!r.isSuccessful) throw OpcTransport.statusError(r, "download")
                    val resumed = r.code == 206 && done > 0
                    if (!resumed && done > 0) {
                        // Camera ignored Range: restart from scratch.
                        tmp.delete()
                        done = 0L
                    }
                    val total = contentTotal(r, item.sizeBytes, done, resumed)
                    _state.update { it.copy(activeTotal = total) }
                    val append = resumed
                    java.io.FileOutputStream(tmp, append).use { out ->
                        val src = r.body!!.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var lastPush = 0L
                        // Verify magic on the first bytes of a fresh file.
                        var headChecked = done > 0
                        val headBuf = java.io.ByteArrayOutputStream(64)
                        while (true) {
                            if (cancelCurrent) {
                                throw if (_state.value.paused) Paused() else Cancelled()
                            }
                            val n = src.read(buf)
                            if (n < 0) break
                            if (!headChecked) {
                                val need = 64 - headBuf.size()
                                if (need > 0) headBuf.write(buf, 0, minOf(n, need))
                                if (headBuf.size() >= 12 || done + n >= total && total > 0) {
                                    val head = headBuf.toByteArray()
                                    if (FileSniff.looksLikeError(head) ||
                                        !FileSniff.matches(item.path, head)
                                    ) {
                                        tmp.delete()
                                        throw IOException(
                                            "camera returned an error page, not ${item.path.substringAfterLast('/')}"
                                        )
                                    }
                                    headChecked = true
                                }
                            }
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastPush > 250) {
                                lastPush = now
                                _state.update { it.copy(activeBytes = done) }
                                refresh()
                            }
                        }
                    }
                    // Size check: received must equal expected.
                    val expect = if (total > 0) total else item.sizeBytes
                    if (expect > 0 && done != expect) {
                        // Short read (link dropped mid-file?): retry once
                        // from scratch before failing the item.
                        if (++attempt <= 1) {
                            tmp.delete()
                            done = 0L
                            dropClient()
                            ensureClientResume()
                            return@use // exits use{}; loop retries
                        }
                        tmp.delete()
                        throw IOException("short read: got $done of $expect bytes")
                    }
                    if (!verifyHead(tmp, item.path)) {
                        tmp.delete()
                        throw IOException("downloaded file failed type check")
                    }
                    val sha = sha256Of(tmp)
                    publish(tmp, item)
                    _state.update { it.copy(activeBytes = done, activeTotal = expect) }
                    return@withContext sha
                }
            }
            // Unreachable: the loop only exits via return or throw.
            throw IOException("unreachable")
        }
    }

    /**
     * Staging file for [item]. Keyed by full camera path + size + taken
     * time so same-named files in different DCF folders never collide.
     */
    private fun stagingFile(item: DlItem): File {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val id = "${item.path}|${item.sizeBytes}|${item.takenAt}"
        val h = md.digest(id.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "dl_$h.tmp")
    }

    /**
     * Expected total for this fetch: Content-Range total when resumed,
     * else Content-Length, else the listing size.
     * Also validates that the resumed start matches our offset —
     * a camera answering the wrong range would silently corrupt the file.
     */
    private fun contentTotal(r: okhttp3.Response, listed: Long, done: Long, resumed: Boolean): Long {
        if (resumed) {
            // "bytes 100-199/1000" -> start 100, total 1000.
            val cr = r.header("Content-Range") ?: ""
            val m = Regex("""bytes (\d+)-(\d+)/(\d+)""").find(cr)
            if (m == null) throw IOException("bad Content-Range: $cr")
            val start = m.groupValues[1].toLong()
            val total = m.groupValues[3].toLong()
            if (start != done) {
                throw IOException("range start mismatch (want $done, got $start)")
            }
            if (total > 0) return total
        }
        val len = r.body!!.contentLength()
        if (len > 0) return if (resumed) done + len else len
        return listed
    }

    private fun verifyHead(tmp: File, path: String): Boolean {
        return try {
            val head = ByteArray(64)
            val n = java.io.FileInputStream(tmp).use { it.read(head) }
            if (n <= 0) return false
            !FileSniff.looksLikeError(head.copyOf(n)) &&
                FileSniff.matches(path, head.copyOf(n))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * SHA-256 of a staged file, hex. Computed once at publish; ~50ms/MB
     * on modern phones, hidden inside the copy window.
     */
    private fun sha256Of(f: File): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(f).use { ins ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    /** Copy a verified temp file into MediaStore, then delete the temp. */
    private suspend fun publish(tmp: File, item: DlItem) {
        val name = item.path.substringAfterLast('/')
        val resolver = contentResolver
        val ext = name.substringAfterLast('.', "").uppercase()
        val isMovie = ext == "MOV" || ext == "AVI" || ext == "MP4"
        val collection = if (isMovie) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val baseDir = if (isMovie) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniquify(collection, name))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/OMLight")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (item.takenAt > 0) {
                put(MediaStore.MediaColumns.DATE_TAKEN, item.takenAt)
            }
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed")
        try {
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "w")!!.use { out ->
                    java.io.FileInputStream(tmp).use { it.copyTo(out) }
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            // Re-read check after publish: SIZE is only reliable once
            // IS_PENDING=0 (during pending it may report 0).
            val published = withContext(Dispatchers.IO) {
                resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getLong(0) else -1L
                } ?: -1L
            }
            if (published >= 0 && published != tmp.length()) {
                runCatching { resolver.delete(uri, null, null) }
                // Corrupt publish: drop the temp too (bytes are suspect).
                tmp.delete()
                throw IOException("MediaStore size mismatch ($published != ${tmp.length()})")
            }
            // Success: temp fully consumed.
            tmp.delete()
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            // Keep the temp on MediaStore-side failures (insert/copy):
            // the bytes verified OK, only the publish failed, so a retry
            // reuses them instead of re-downloading. (Size-mismatch
            // already deleted the temp above.)
            throw e
        }
    }

    /**
     * Avoid overwriting an existing row with the same display name:
     * append " (n)" like desktop file managers do.
     */
    private fun uniquify(collection: android.net.Uri, name: String): String {
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = name
        var n = 1
        while (true) {
            val exists = contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(candidate),
                null,
            )?.use { it.count > 0 } ?: false
            if (!exists) return candidate
            n++
            candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            if (n > 999) return candidate // absurd; let MediaStore handle it
        }
    }

    private suspend fun ensureClientResume() {
        // Re-probe after a mid-file drop; throws when the AP is gone.
        dropClient()
        ensureClient()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").uppercase()) {
        "JPG", "JPEG" -> "image/jpeg"
        "ORF" -> "image/x-olympus-orf"
        "MOV" -> "video/quicktime"
        "AVI" -> "video/x-msvideo"
        "MP4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    // -- notification --------------------------------------------------------------

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "ダウンロード", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun refresh() {
        val s = _state.value
        val text = when {
            s.active != null && s.activeTotal > 0 ->
                "${s.active.substringAfterLast('/')} " +
                    "${s.activeBytes / 1024} / ${s.activeTotal / 1024} KB"
            s.active != null -> s.active.substringAfterLast('/')
            else -> "待機中…"
        }
        val max = s.activeTotal.toInt().coerceAtLeast(0)
        val cur = s.activeBytes.toInt().coerceAtMost(max)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, progressNotification(text, max, cur, s.activeTotal <= 0))
    }

    private fun progressNotification(
        text: String,
        max: Int,
        progress: Int,
        indeterminate: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("OMLight ダウンロード")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(max, progress, indeterminate)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        worker?.cancel()
        queue.close()
        if (holding) {
            holding = false
            // Synchronous release on a daemon thread: lifecycleScope is
            // being torn down, so a launch{} here might never run.
            val t = Thread({
                try {
                    kotlinx.coroutines.runBlocking { CamSession.release() }
                } catch (_: Exception) { /* best-effort */ }
            }, "omlight-svc-release")
            t.isDaemon = true
            t.start()
            runCatching { t.join(3_000) }
        }
        super.onDestroy()
    }

    private class Cancelled : IOException("cancelled")
    private class Paused : IOException("paused")

    companion object {
        const val ACTION_ENQUEUE = "com.omlight.dl.ENQUEUE"
        const val ACTION_CANCEL = "com.omlight.dl.CANCEL"
        const val ACTION_PAUSE = "com.omlight.dl.PAUSE"
        const val ACTION_RESUME = "com.omlight.dl.RESUME"
        const val ACTION_RETRY_FAILED = "com.omlight.dl.RETRY_FAILED"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_SIZES = "sizes"
        const val EXTRA_TAKEN = "taken"
        private const val CHANNEL = "dl"
        private const val NOTIF_ID = 1

        private val _state = MutableStateFlow(DlState())
        val state: StateFlow<DlState> = _state.asStateFlow()

        fun enqueue(ctx: Context, items: List<DlItem>) {
            val i = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_PATHS, items.map { it.path }.toTypedArray())
                putExtra(EXTRA_SIZES, items.map { it.sizeBytes }.toLongArray())
                putExtra(EXTRA_TAKEN, items.map { it.takenAt }.toLongArray())
            }
            ContextCompat_startForegroundService(ctx, i)
            // Count is updated by the service itself (post dedup skip);
            // never touch it here or it double-counts.
        }

        fun pause(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_PAUSE
            })
        }

        fun resume(ctx: Context) {
            ctx.startService(Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_RESUME
            })
        }

        fun retryFailed(ctx: Context, items: List<DlItem>) {
            val i = Intent(ctx, DownloadService::class.java).apply {
                action = ACTION_RETRY_FAILED
                putExtra(EXTRA_PATHS, items.map { it.path }.toTypedArray())
                putExtra(EXTRA_SIZES, items.map { it.sizeBytes }.toLongArray())
                putExtra(EXTRA_TAKEN, items.map { it.takenAt }.toLongArray())
            }
            ContextCompat_startForegroundService(ctx, i)
        }

        // androidx.core.content.ContextCompat has this; local alias avoids
        // importing the whole compat surface in UI code.
        private fun ContextCompat_startForegroundService(ctx: Context, i: Intent) {
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }
    }
}
