package com.omlight.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.omlight.cam.CamEntry
import com.omlight.cam.CamProp
import com.omlight.cam.CamSession
import com.omlight.cam.EventReceiver
import com.omlight.cam.LiveviewReceiver
import com.omlight.cam.OpcClient
import com.omlight.cam.Thumb
import com.omlight.dl.DlItem
import com.omlight.dl.DownloadService
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Conn {
    data object Idle : Conn
    data object Searching : Conn
    data class Failed(val msg: String) : Conn
    data class Ready(val model: String) : Conn
}

/**
 * Single screen-model for the whole app. Deliberately one ViewModel:
 * three tabs share one camera session, and splitting would just add
 * cross-VM plumbing for zero benefit at this size.
 */
class CamViewModel(app: Application) : AndroidViewModel(app) {

    var conn: Conn by mutableStateOf(Conn.Idle)
        private set
    var entries: List<CamEntry> by mutableStateOf(emptyList())
        private set
    /** Hint shown when the camera list is empty (One-Time vs truly empty). */
    var emptyHint: String? by mutableStateOf(null)
        private set
    /** yyyy-MM-dd filter, or null for all dates. */
    var filterDate: String? by mutableStateOf(null)
        private set
    val visibleEntries: List<CamEntry>
        get() {
            val f = filterDate ?: return entries
            return entries.filter { dayOf(it.takenAtMillis) == f }
        }

    /**
     * Google-Photos-style sections: [(day, items)] newest-first.
     * Unknown-date files trail in a "" section rendered as その他.
     */
    val sections: List<Pair<String, List<CamEntry>>>
        get() {
            val list = visibleEntries
            if (list.isEmpty()) return emptyList()
            val out = ArrayList<Pair<String, List<CamEntry>>>()
            var curDay: String? = null
            var cur = ArrayList<CamEntry>()
            fun flush() {
                if (cur.isNotEmpty() && curDay != null) {
                    out.add(curDay!! to cur.toList())
                    cur = ArrayList()
                }
            }
            for (e in list) {
                val d = dayOf(e.takenAtMillis)
                if (d != curDay) {
                    flush()
                    curDay = d
                }
                cur.add(e)
            }
            flush()
            return out
        }

    /** Pretty day label: 今日/昨日 or M月d日(曜). */
    fun dayLabel(day: String): String {
        if (day.isEmpty()) return "日付不明"
        return try {
            val p = day.split('-')
            val c = java.util.Calendar.getInstance()
            val today = "%04d-%02d-%02d".format(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
            )
            if (day == today) return "今日"
            c.add(java.util.Calendar.DAY_OF_MONTH, -1)
            val yest = "%04d-%02d-%02d".format(
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH),
            )
            if (day == yest) return "昨日"
            val w = arrayOf("日", "月", "火", "水", "木", "金", "土")
            c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
            "${p[1].toInt()}月${p[2].toInt()}日(${w[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]})"
        } catch (_: Exception) {
            day
        }
    }
    /** Distinct shoot days present, newest-first, for the filter chips. */
    val shootDays: List<String>
        get() = entries.mapNotNull {
            val d = dayOf(it.takenAtMillis)
            if (d.isEmpty()) null else d
        }.distinct().sortedDescending()

    private fun dayOf(millis: Long): String {
        if (millis <= 0) return ""
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = millis
        return "%04d-%02d-%02d".format(
            c.get(java.util.Calendar.YEAR),
            c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    fun selectFilterDate(day: String?) {
        filterDate = day
    }
    var refreshing: Boolean by mutableStateOf(false)
        private set
    var shooting: Boolean by mutableStateOf(false)
        private set
    var recording: Boolean by mutableStateOf(false)
        private set
    /** Self-timer armed (best-effort; hidden when unsupported). */
    var selfTimer: Boolean by mutableStateOf(false)
        private set
    var selfTimerSupported: Boolean by mutableStateOf(false)
        private set
    /** path -> thumbnail for grid cells. Bounded: evicted on refresh. */
    val thumbs = mutableStateMapOf<String, Thumb>()
    private val thumbStore by lazy { ThumbStore(getApplication()) }
    /** Latest liveview JPEG, or null when stopped. */
    var liveFrame: ByteArray? by mutableStateOf(null)
        private set
    var liveRunning: Boolean by mutableStateOf(false)
        private set
    var statusMsg: String? by mutableStateOf(null)
        private set
    /** Last capture event text for the shoot tab (AF ok, capturing…). */
    var captureState: String? by mutableStateOf(null)
        private set
    /** Just-taken photo preview (recview/getlastjpg) shown after a shot. */
    var lastShot: ByteArray? by mutableStateOf(null)
        private set
    /** AF lock point as 0..1 fractions, null when none. */
    var afPoint: Pair<Float, Float>? by mutableStateOf(null)
        private set
    /** True while the shoot tab is visible (drives auto-liveview). */
    var shootVisible: Boolean by mutableStateOf(false)
        private set
    /** Full-size preview for the detail sheet, path -> JPEG. */
    var detailJpeg: ByteArray? by mutableStateOf(null)
        private set
    var detailPath: String? by mutableStateOf(null)
        private set
    var detailLoading: Boolean by mutableStateOf(false)
        private set
    /** Movie length text for the detail sheet, null when n/a. */
    var detailMovieLen: String? by mutableStateOf(null)
        private set
    /**
     * In-memory detail bundle cache: path -> (jpeg, movieLen, info).
     * Best-effort LRU so reopening a photo is instant without hammering
     * the camera. Bounded small: previews are ~100-600KB each.
     */
    private val detailCache = object : LinkedHashMap<String, DetailBundle>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DetailBundle>?): Boolean =
            size > 12
    }

    private data class DetailBundle(
        val jpeg: ByteArray,
        val movieLen: String?,
        val info: com.omlight.cam.PhotoInfo,
    )
    /** Shooting/color info rows for the detail sheet. */
    var detailInfo: com.omlight.cam.PhotoInfo by mutableStateOf(com.omlight.cam.PhotoInfo())
        private set
    /** RGB histogram for the detail sheet, null until computed. */
    var detailHist: Histogram.Rgb? by mutableStateOf(null)
        private set
    /** Fullscreen viewer path, null when closed. */
    var viewerPath: String? by mutableStateOf(null)
        private set
    /** EXIF orientation degrees for the viewer (0/90/180/270). */
    var viewerRotation: Float by mutableStateOf(0f)
        private set
    /** Writable camera props for the settings sheet. */
    var props: List<CamProp> by mutableStateOf(emptyList())
        private set
    var propsLoading: Boolean by mutableStateOf(false)
        private set
    /** Consecutive transport failures; drives auto-reconnect. */
    var linkDown: Boolean by mutableStateOf(false)
        private set

    private var liveJob: Job? = null
    private var thumbJob: Job? = null
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null
    private var failures = 0
    /** Our outstanding CamSession ref (true between connect and disconnect). */
    private var holding = false

    /** Current shared client, or null when disconnected. */
    private suspend fun client(): OpcClient? =
        CamSession.peek()?.also { lastClient = it }

    /** Camera card free space text, loaded with props. Null = unknown. */
    var cardFree: String? by mutableStateOf(null)
        private set
    /** Active media slot (dual-slot bodies), -1 = unknown/n/a. */
    var playSlot: Int by mutableStateOf(-1)
        private set

    /** Remote power-off (with UI disconnect after). */
    fun powerOff() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            try {
                withContext(Dispatchers.IO) { c.powerOff() }
            } catch (_: Exception) { /* AP dies with it */ }
            statusMsg = "カメラの電源を切りました"
            disconnect()
        }
    }

    /** Remote reboot (with UI disconnect after). Caller confirms first. */
    fun reboot() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            try {
                withContext(Dispatchers.IO) { c.reboot() }
            } catch (_: Exception) { /* AP dies with it */ }
            statusMsg = "カメラを再起動しました"
            disconnect()
        }
    }

    /** MySet backup progress text, null when idle. */
    var mysetMsg: String? by mutableStateOf(null)
        private set

    /** Download the body's MySet blob into app storage. Best-effort. */
    fun mysetBackup() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            mysetMsg = "取得中…"
            try {
                val ok = withContext(Dispatchers.IO) {
                    if (!c.mysetBeginGet("all")) return@withContext false
                    val size = c.mysetSize()
                    if (size <= 0 || size > 4 * 1024 * 1024) return@withContext false
                    val out = java.io.ByteArrayOutputStream(size.toInt().coerceAtMost(4 * 1024 * 1024))
                    var off = 0L
                    while (off < size) {
                        val chunk = c.mysetChunk(off, 65536) ?: return@withContext false
                        if (chunk.isEmpty()) break
                        out.write(chunk)
                        off += chunk.size
                    }
                    if (off != size) return@withContext false
                    val f = java.io.File(
                        getApplication<Application>().filesDir,
                        "myset-${c.info.model.ifEmpty { "cam" }}.bin",
                    )
                    f.writeBytes(out.toByteArray())
                    true
                }
                mysetMsg = null
                statusMsg = if (ok) "設定を保存しました" else "このカメラは設定保存に対応していません"
            } catch (e: Exception) {
                mysetMsg = null
                statusMsg = "設定の保存に失敗: ${e.message}"
            }
        }
    }

    /** Restore the previously saved MySet blob. Caller confirms first. */
    fun mysetRestore() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            mysetMsg = "書き込み中…"
            try {
                val ok = withContext(Dispatchers.IO) {
                    val dir = getApplication<Application>().filesDir
                    val f = dir.listFiles { f: java.io.File ->
                        f.name.startsWith("myset-") && f.name.endsWith(".bin")
                    }?.maxByOrNull { it.lastModified() } ?: return@withContext false
                    val blob = f.readBytes()
                    if (blob.isEmpty() || blob.size > 4 * 1024 * 1024) return@withContext false
                    if (!c.mysetBeginRestore(blob.size.toLong())) return@withContext false
                    var off = 0
                    while (off < blob.size) {
                        val end = minOf(off + 65536, blob.size)
                        if (!c.mysetSendChunk(off.toLong(), blob.copyOfRange(off, end))) {
                            return@withContext false
                        }
                        off = end
                    }
                    c.mysetCommitRestore()
                }
                mysetMsg = null
                statusMsg = if (ok) "設定を復元しました" else "復元に失敗しました"
                if (ok) loadProps()
            } catch (e: Exception) {
                mysetMsg = null
                statusMsg = "復元に失敗: ${e.message}"
            }
        }
    }

    /** Last connection diagnosis, shown on the gate after a failure. */
    var diagText: String? by mutableStateOf(null)
        private set
    /** False when the body lacks delete/protect (E-M5 II hides the buttons). */
    var adminSupported: Boolean by mutableStateOf(true)
        private set
    /** True on share-limited One-Time connections: banner the restriction. */
    var oneTime: Boolean by mutableStateOf(false)
        private set
    /** Paths verified present in MediaStore (refreshed on grid load). */
    var savedPaths: Set<String> by mutableStateOf(emptySet())
        private set
    /** Interrupted download work from a previous run (count, bytes). */
    var pendingResume: Pair<Int, Long>? by mutableStateOf(null)
        private set

    /** Check for resumable work (call on launch + on connect). */
    fun checkResume() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (n, b) = com.omlight.dl.TransferStore(getApplication()).pendingSummary()
                // Only banner when the service isn't already running it.
                pendingResume =
                    if (n > 0 && com.omlight.dl.DownloadService.state.value.active == null &&
                        com.omlight.dl.DownloadService.state.value.queued == 0
                    ) n to b else null
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /** Resume interrupted downloads now. */
    fun resumePending() {
        pendingResume = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = com.omlight.dl.TransferStore(getApplication()).loadQueue()
                if (items.isNotEmpty()) {
                    com.omlight.dl.DownloadService.enqueue(getApplication(), items)
                }
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /** Wipe the transfer history (saved badges clear too). */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.omlight.dl.TransferStore(getApplication()).clearHistory()
                savedPaths = emptySet()
                statusMsg = "保存履歴をクリアしました"
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /** Discard interrupted downloads. */
    fun discardPending() {
        pendingResume = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                com.omlight.dl.TransferStore(getApplication()).clearQueue()
                // Drop orphaned staging temps too.
                getApplication<Application>().cacheDir.listFiles { f ->
                    f.name.startsWith("dl_") && f.name.endsWith(".tmp")
                }?.forEach { runCatching { it.delete() } }
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * Reconcile history against MediaStore: a file the user deleted on
     * the phone stops counting as saved (and becomes downloadable again).
     * Runs off the UI thread; matching is by display name + size within
     * our OMLight folders.
     */
    fun reconcileSaved() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val store = com.omlight.dl.TransferStore(app)
                val hist = store.history()
                if (hist.isEmpty()) {
                    savedPaths = emptySet()
                    return@launch
                }
                val resolver = app.contentResolver
                val found = HashSet<String>()
                for (coll in listOf(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                )) {
                    resolver.query(
                        coll,
                        arrayOf(
                            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                            android.provider.MediaStore.MediaColumns.SIZE,
                        ),
                        "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("%OMLight%"),
                        null,
                    )?.use { cur ->
                        val ni = cur.getColumnIndexOrThrow(
                            android.provider.MediaStore.MediaColumns.DISPLAY_NAME
                        )
                        val si = cur.getColumnIndexOrThrow(
                            android.provider.MediaStore.MediaColumns.SIZE
                        )
                        val rows = HashSet<Pair<String, Long>>()
                        while (cur.moveToNext()) {
                            rows.add(cur.getString(ni) to cur.getLong(si))
                        }
                        for (h in hist) {
                            val name = h.path.substringAfterLast('/')
                            if (rows.contains(name to h.size)) found.add(h.path)
                        }
                    }
                }
                savedPaths = found
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    // -- connection ---------------------------------------------------------------

    /**
     * Preflight: returns a user-facing blocker message, or null when
     * connection can proceed. Checks (in order): local-network permission
     * (17+), any Wi-Fi up, VPN warning (non-blocking, sets statusMsg).
     */
    private fun preflight(): String? {
        val app = getApplication<Application>()
        if (android.os.Build.VERSION.SDK_INT >= 37 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                app, "android.permission.ACCESS_LOCAL_NETWORK"
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return "ローカルネットワークの許可が必要です。「接続」を押して許可してください。"
        }
        val wifiUp = try {
            com.omlight.cam.CameraNetwork(app).useDiag().wifiUp
        } catch (_: Exception) {
            false
        }
        if (!wifiUp) {
            return "Wi-FiがOFFか、カメラのWi-Fiに未接続です。設定でカメラのSSIDに接続してください。"
        }
        return null
    }

    fun connect() {
        if (conn == Conn.Searching) return
        preflight()?.let {
            conn = Conn.Failed(it)
            return
        }
        viewModelScope.launch {
            conn = Conn.Searching
            statusMsg = null
            try {
                val c = withContext(Dispatchers.IO) {
                    CamSession.acquire(getApplication())
                }
                holding = true
                val model = c.info.model.ifEmpty { "OPC camera" }
                conn = Conn.Ready(model)
                adminSupported = c.supportsAdmin()
                oneTime = c.isOneTime()
                failures = 0
                linkDown = false
                val net = CamSession.network()
                if (net?.vpnActive() == true) {
                    statusMsg = "VPNが有効です。カメラに繋がらない場合はVPNを切ってください"
                }
                net?.watchLoss {
                    viewModelScope.launch {
                        linkDown = true
                        statusMsg = "カメラのWi-Fiが切れました。再接続してください"
                    }
                }
                diagText = null
                probeShootCaps(c)
                startEventStream(c)
                refresh()
                checkResume()
            } catch (e: Exception) {
                if (holding) {
                    withContext(Dispatchers.IO) { CamSession.release() }
                    holding = false
                }
                // Diagnose for the gate: SSID/permissions/networks tell the
                // user (and us) exactly which layer failed.
                diagText = withContext(Dispatchers.IO) {
                    runCatching {
                        val d = com.omlight.cam.CameraNetwork(getApplication()).useDiag()
                        buildString {
                            append("Wi-Fi: ")
                            append(d.ssid ?: if (d.wifiUp) "(SSID不明)" else "未接続")
                            append(" / ネット数: ${d.totalNets}")
                            if (!d.lnpGranted) append(" / ローカルNW未許可")
                            if (d.vpnActive) append(" / VPN有効")
                        }
                    }.getOrNull()
                }
                conn = Conn.Failed(e.message ?: "接続エラー")
            }
        }
    }

    /** Subscribe to camera push events; drives capture UI + auto refresh. */
    private fun startEventStream(c: OpcClient) {
        eventJob?.cancel()
        eventJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                c.startEvents()
            } catch (_: Exception) {
                // Old bodies may lack push events; app works without them.
                return@launch
            }
            val rx = c.events ?: return@launch
            rx.events.collect { ev ->
                when (ev.eventId) {
                    EventReceiver.Event.AF_RESULT -> {
                        captureState = when (ev.info["result"]) {
                            "ok" -> "AF OK"
                            "ng" -> "AF NG"
                            else -> null
                        }
                    }
                    EventReceiver.Event.READY -> captureState = "撮影準備OK"
                    EventReceiver.Event.CAPTURE_STARTED -> {
                        captureState = "撮影中…"
                        noteSuccess()
                    }
                    EventReceiver.Event.CAPTURE_DONE -> {
                        captureState = null
                        noteSuccess()
                        refreshSoon()
                    }
                    EventReceiver.Event.RECView -> refreshSoon()
                    EventReceiver.Event.MOVIE_STARTED -> {
                        recording = true
                        noteSuccess()
                    }
                    EventReceiver.Event.MOVIE_STOPPED -> {
                        recording = false
                        noteSuccess()
                        refreshSoon()
                    }
                    EventReceiver.Event.CARD_MOUNT,
                    EventReceiver.Event.LENS_MOUNT,
                    EventReceiver.Event.TEMP,
                    -> noteSuccess()
                    EventReceiver.Event.PROP_CHANGED -> {
                        // Value changed on the body; reload props lazily.
                        if (props.isNotEmpty()) loadProps()
                    }
                }
            }
        }
    }

    private fun refreshSoon() {
        viewModelScope.launch {
            delay(600)
            refresh()
        }
    }

    /** Any successful traffic resets the failure counter. */
    private fun noteSuccess() {
        failures = 0
        if (linkDown) linkDown = false
    }

    /**
     * Called when a command fails with a transport error. After 2
     * consecutive failures, mark the link down and try one silent
     * reconnect; the UI also offers a manual retry.
     */
    private fun noteFailure(where: String) {
        failures++
        if (failures >= 2 && conn is Conn.Ready && reconnectJob == null) {
            linkDown = true
            reconnectJob = viewModelScope.launch {
                delay(1_500)
                try {
                    val c = client() ?: throw IOException("no session")
                    withContext(Dispatchers.IO) { c.connect() }
                    noteSuccess()
                    statusMsg = "再接続しました"
                    refresh()
                } catch (_: Exception) {
                    statusMsg = "$where に失敗。再接続してください"
                } finally {
                    reconnectJob = null
                }
            }
        }
    }

    fun disconnect() {
        stopLive()
        eventJob?.cancel()
        eventJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        thumbJob?.cancel()
        if (holding) {
            holding = false
            // closeNow, not release: the disconnect button means "drop the
            // camera now", even if a transfer holds a ref (it will fail
            // fast with a clear error and can retry after reconnect).
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { CamSession.closeNow() }
            }
        }
        entries = emptyList()
        thumbs.clear()
        props = emptyList()
        closeDetail()
        captureState = null
        linkDown = false
        failures = 0
        conn = Conn.Idle
    }

    // -- browsing -------------------------------------------------------------------

    fun refresh() {
        if (refreshing) return
        if (liveRunning) return // listing needs PLAY; LV owns REC
        viewModelScope.launch {
            val c = client() ?: return@launch
            refreshing = true
            try {
                val list = withContext(Dispatchers.IO) {
                    val all = c.allImages()
                    // One-Time: also merge the share-reserved list, which
                    // is the only thing visible there. Dedupe by path.
                    if (c.isOneTime()) {
                        val rsv = c.reserveList()
                        (all + rsv).distinctBy { it.path }
                            .sortedWith(
                                compareByDescending<CamEntry> { it.takenAtMillis }
                                    .thenByDescending { it.path }
                            )
                    } else all
                }
                entries = list
                emptyHint = if (list.isEmpty()) {
                    "カメラの一覧は空でした。カメラ側で「ワンタイム接続」や" +
                        "「シェア予約のみ表示」になっていないか確認してください。"
                } else null
                thumbs.keys.retainAll(list.map { it.path }.toSet())
                prefetchThumbs(list.take(60))
                noteSuccess()
                reconcileSaved()
            } catch (e: Exception) {
                statusMsg = "一覧の更新に失敗: ${e.message}"
                noteFailure("一覧の更新")
            } finally {
                refreshing = false
            }
        }
    }

    private fun prefetchThumbs(first: List<CamEntry>) {
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            // Disk cache first (instant), then network with a small
            // parallelism cap — old camera HTTP stacks fall over with
            // dozens of concurrent connections.
            val missing = ArrayList<CamEntry>()
            for (e in first) {
                if (!isActive) break
                if (thumbs.containsKey(e.path)) continue
                val cached = thumbStore.get(e.path, e.sizeBytes)
                if (cached != null) {
                    thumbs[e.path] = cached
                } else {
                    missing.add(e)
                }
            }
            if (liveRunning) return@launch // LV owns REC; network fetch waits
            fetchThumbs(c, missing)
        }
    }

    /** Shared fetch worker: at most 4 concurrent camera requests. */
    private val thumbSem = kotlinx.coroutines.sync.Semaphore(4)
    private val thumbFetching = java.util.Collections.synchronizedSet(HashSet<String>())

    private suspend fun fetchThumbs(c: OpcClient, list: List<CamEntry>) {
        val jobs = list.map { e ->
            viewModelScope.launch(Dispatchers.IO) {
                thumbSem.acquire()
                try {
                    if (!thumbFetching.add(e.path)) return@launch
                    try {
                        if (thumbs.containsKey(e.path)) return@launch
                        val thumb = c.thumbnail(e.path)
                        thumbStore.put(e.path, e.sizeBytes, thumb)
                        thumbs[e.path] = thumb
                    } finally {
                        thumbFetching.remove(e.path)
                    }
                } catch (_: Exception) {
                    // Offline cell; user can retry via refresh.
                } finally {
                    thumbSem.release()
                }
            }
        }
        jobs.forEach { it.join() }
    }

    /**
     * Warm thumbs for paths about to scroll in. Skips memory/disk hits
     * and in-flight fetches; the shared semaphore caps concurrency.
     */
    fun prefetchAhead(paths: List<String>) {
        if (paths.isEmpty()) return
        val byPath = entries.associateBy { it.path }
        val targets = paths.mapNotNull { byPath[it] }
            .filter { !thumbs.containsKey(it.path) }
        if (targets.isEmpty()) return
        val c = runBlockingPeek() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            fetchThumbs(c, targets.take(24))
        }
    }

    // Non-suspending peek for prefetch (best-effort; null = offline).
    private var lastClient: OpcClient? = null

    private fun runBlockingPeek(): OpcClient? {
        lastClient?.let { return it }
        return null
    }
    fun needThumb(entry: CamEntry) {
        if (thumbs.containsKey(entry.path)) return
        if (!thumbFetching.add(entry.path)) return
        thumbStore.get(entry.path, entry.sizeBytes)?.let {
            thumbs[entry.path] = it
            thumbFetching.remove(entry.path)
            return
        }
        if (liveRunning) {
            // Defer: retry on next bind (tab switch re-binds all).
            thumbFetching.remove(entry.path)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val c = client() ?: run { thumbFetching.remove(entry.path); return@launch }
            thumbSem.acquire()
            try {
                if (thumbs.containsKey(entry.path)) return@launch
                val thumb = c.thumbnail(entry.path)
                thumbStore.put(entry.path, entry.sizeBytes, thumb)
                thumbs[entry.path] = thumb
            } catch (_: Exception) { /* retry on next bind */ }
            finally {
                thumbSem.release()
                thumbFetching.remove(entry.path)
            }
        }
    }

    fun thumbFor(path: String): Thumb? = thumbs[path]

    fun download(paths: List<String>) {
        val items = entries.filter { it.path in paths }
            .map { DlItem(it.path, it.sizeBytes, it.takenAtMillis) }
        if (items.isEmpty()) return
        DownloadService.enqueue(getApplication(), items)
        statusMsg = "${items.size}件をダウンロード開始（バックグラウンドで継続）"
    }

    fun retryFailed() {
        val failed = DownloadService.state.value.failed.toSet()
        if (failed.isEmpty()) return
        val items = entries.filter { it.path in failed }
            .map { DlItem(it.path, it.sizeBytes, it.takenAtMillis) }
        // Entries may have refreshed away; fall back to path-only for those.
        val resolved = items.map { it.path }.toSet()
        val all = items + (failed - resolved).map { DlItem(it, 0L, 0L) }
        DownloadService.retryFailed(getApplication(), all)
    }

    // -- detail -------------------------------------------------------------------------

    private var detailJob: Job? = null

    fun openDetail(path: String) {
        // Cache hit: show instantly, no camera traffic at all.
        synchronized(detailCache) { detailCache[path] }?.let { hit ->
            detailJob?.cancel()
            detailJob = null
            detailPath = path
            detailJpeg = hit.jpeg
            detailMovieLen = hit.movieLen
            detailInfo = hit.info
            detailHist = null
            viewModelScope.launch { detailHist = Histogram.of(hit.jpeg) }
            return
        }
        // New load: cancel any in-flight one so a slow photo A can never
        // overwrite photo B's sheet (the old detailLoading flag dropped
        // the new request instead, forcing a third tap).
        detailJob?.cancel()
        detailPath = path
        detailJpeg = null
        detailMovieLen = null
        detailInfo = com.omlight.cam.PhotoInfo()
        detailHist = null
        detailJob = viewModelScope.launch {
            val c = client() ?: return@launch
            detailLoading = true
            try {
                val jpeg = withContext(Dispatchers.IO) { c.preview(path) }
                if (detailPath != path || !isActive) return@launch
                detailJpeg = jpeg
                // Histogram off the same bytes (cheap, no extra request).
                launch { detailHist = Histogram.of(jpeg) }
                if (path.substringAfterLast('.', "").uppercase() in setOf("MOV", "AVI", "MP4")) {
                    val sec = withContext(Dispatchers.IO) { c.movieSeconds(path) }
                    val info = withContext(Dispatchers.IO) { c.movieInfo(path) }
                    if (detailPath != path || !isActive) return@launch
                    val size = info["moviesize"] ?: info["size"]
                    detailMovieLen = when {
                        sec > 0 && size != null -> "%d:%02d ・ %s".format(sec / 60, sec % 60, size)
                        sec > 0 -> "%d:%02d".format(sec / 60, sec % 60)
                        size != null -> size
                        else -> null
                    }
                }
                // EXIF parse is CPU-bound; CGI fetch is IO. Both best-effort.
                val info = withContext(Dispatchers.IO) { c.photoInfo(path, jpeg) }
                if (detailPath != path || !isActive) return@launch
                detailInfo = info
                synchronized(detailCache) {
                    detailCache[path] = DetailBundle(jpeg, detailMovieLen, detailInfo)
                }
                noteSuccess()
            } catch (e: Exception) {
                if (!isActive) return@launch
                statusMsg = "プレビューの取得に失敗: ${e.message}"
                noteFailure("プレビュー")
            } finally {
                detailLoading = false
            }
        }
    }

    fun closeDetail() {
        detailPath = null
        detailJpeg = null
    }

    fun openViewer(path: String) {
        viewerPath = path
        // ModalBottomSheet lives in its own window above normal content;
        // hide the detail so the viewer is truly fullscreen. detailJpeg
        // stays cached for the viewer + instant return.
        detailPath = null
        // EXIF orientation -> display rotation.
        viewModelScope.launch(Dispatchers.Default) {
            viewerRotation = try {
                val jpeg = detailJpeg ?: return@launch
                val exif = androidx.exifinterface.media.ExifInterface(
                    java.io.ByteArrayInputStream(jpeg)
                )
                when (exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
                )) {
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) {
                0f
            }
        }
    }

    fun closeViewer() {
        viewerPath = null
    }

    fun deleteDetail() {
        val path = detailPath ?: return
        viewModelScope.launch {
            val c = client() ?: return@launch
            try {
                val ok = withContext(Dispatchers.IO) { c.delete(path) }
                if (ok) {
                    statusMsg = "削除しました"
                    closeDetail()
                    refresh()
                } else {
                    statusMsg = "カメラが削除を拒否しました"
                }
                noteSuccess()
            } catch (e: Exception) {
                statusMsg = "削除に失敗: ${e.message}"
                noteFailure("削除")
            }
        }
    }

    fun protectDetail(on: Boolean) {
        val path = detailPath ?: return
        viewModelScope.launch {
            val c = client() ?: return@launch
            try {
                val ok = withContext(Dispatchers.IO) { c.protect(path, on) }
                statusMsg = if (ok) (if (on) "保護しました" else "保護を解除しました")
                else "カメラが拒否しました"
                noteSuccess()
            } catch (e: Exception) {
                statusMsg = "保護の切替に失敗: ${e.message}"
                noteFailure("保護")
            }
        }
    }

    // -- shooting ---------------------------------------------------------------------

    fun shoot() {
        if (shooting) return
        viewModelScope.launch {
            val c = client() ?: return@launch
            shooting = true
            captureState = "ピント合わせ…"
            // Liveview holds REC + the RTP stream; stop it so the shutter
            // handshake gets a quiet bus. Restarted after, if visible.
            val wasLive = liveRunning
            if (wasLive) stopLive()
            try {
                withContext(Dispatchers.IO) { c.takePicture() }
                captureState = "保存中…"
                noteSuccess()
                // Pull the just-taken preview so the tab shows *something*.
                // getlastjpg works even when the body is still writing.
                lastShot = try {
                    withContext(Dispatchers.IO) { c.recview() }
                } catch (_: Exception) {
                    null
                }
                statusMsg = "撮影しました"
                delay(800)
                refresh()
            } catch (e: Exception) {
                statusMsg = "撮影に失敗: ${shootWhy(e)}"
                noteFailure("撮影")
            } finally {
                shooting = false
                captureState = null
                if (wasLive && shootVisible) startLive()
            }
        }
    }

    /** Human cause for a shoot failure (timeout vs AF vs busy). */
    private fun shootWhy(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("ピント", ignoreCase = true) || m.contains("affocus", ignoreCase = true) ->
                "ピントが合いませんでした。明るい所・近い被写体で試してください"
            m.contains("timeout", ignoreCase = true) || m.contains("timed out", ignoreCase = true) ->
                "カメラが応答しません。カメラの電源・Wi-Fiを確認してください"
            m.contains("503", ignoreCase = true) || m.contains("busy", ignoreCase = true) ->
                "カメラが書き込み中です。少し待って再試行してください"
            m.contains("カメラエラー", ignoreCase = true) -> m
            else -> e.message ?: "不明なエラー"
        }
    }

    /**
     * Tap-to-focus shot: ([fx], [fy]) are 0..1 fractions of the liveview
     * frame. Converted to the current lvqty coordinate space.
     */
    fun shootAt(fx: Float, fy: Float) {
        if (shooting) return
        viewModelScope.launch {
            val c = client() ?: return@launch
            shooting = true
            captureState = "AF合わせ…"
            try {
                val (w, h) = liveviewSize(c)
                val x = (fx.coerceIn(0f, 1f) * (w - 1)).toInt()
                val y = (fy.coerceIn(0f, 1f) * (h - 1)).toInt()
                withContext(Dispatchers.IO) { c.takePictureAt(x, y) }
                statusMsg = "撮影しました"
                noteSuccess()
                delay(800)
                refresh()
            } catch (e: Exception) {
                statusMsg = "撮影に失敗: ${e.message}"
                noteFailure("撮影")
            } finally {
                shooting = false
                captureState = null
            }
        }
    }

    private fun liveviewSize(c: OpcClient): Pair<Int, Int> {
        val m = Regex("""(\d+)x(\d+)""").find(c.live.lvqty)
        return if (m != null) {
            m.groupValues[1].toInt() to m.groupValues[2].toInt()
        } else {
            640 to 480
        }
    }

    /**
     * Lock AF at the tapped liveview point (assignafframe) without
     * shooting. Tap again to release. Falls back to shootAt on bodies
     * without AF-frame commands.
     */
    fun toggleAf(fx: Float, fy: Float) {
        viewModelScope.launch {
            val c = client() ?: return@launch
            if (afPoint != null) {
                withContext(Dispatchers.IO) { c.releaseAfLock() }
                afPoint = null
                return@launch
            }
            val (w, h) = liveviewSize(c)
            val x = (fx.coerceIn(0f, 1f) * (w - 1)).toInt()
            val y = (fy.coerceIn(0f, 1f) * (h - 1)).toInt()
            val ok = withContext(Dispatchers.IO) { c.assignAfLock(x, y) }
            if (ok) {
                afPoint = fx to fy
                statusMsg = "AFロックしました (もう一度タップで解除)"
            } else {
                // No AF-frame support: treat as focus-and-shoot instead.
                shootAt(fx, fy)
            }
        }
    }

    fun onShootVisible(v: Boolean) {
        shootVisible = v
        if (v && conn is Conn.Ready && !liveRunning && liveJob == null) {
            startLive()
        } else if (!v) {
            stopLive()
        }
    }

    fun zoomHold(dir: OpcClient.ZoomDir, start: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            try {
                if (start) c.zoomStart(dir) else c.zoomStop(dir)
                noteSuccess()
            } catch (e: Exception) {
                if (start) statusMsg = "ズームに失敗: ${e.message}"
            }
        }
    }

    /** Jump zoom to the wide/tele end (double-tap on W/T). Best-effort. */
    fun zoomEnd(dir: OpcClient.ZoomDir) {
        viewModelScope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            if (!c.zoomEnd(dir)) {
                statusMsg = "このレンズは端移動に対応していません"
            } else {
                noteSuccess()
            }
        }
    }

    fun toggleMovie() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            try {
                val start = !recording
                withContext(Dispatchers.IO) { c.movieRecord(start) }
                recording = start
                statusMsg = if (start) "録画開始" else "録画停止"
                noteSuccess()
                if (!start) {
                    delay(800)
                    refresh()
                }
            } catch (e: Exception) {
                statusMsg = "録画の切替に失敗: ${e.message}"
                noteFailure("録画")
            }
        }
    }

    fun toggleSelfTimer() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            val want = !selfTimer
            val ok = withContext(Dispatchers.IO) { c.selfTimer(want) }
            if (ok) {
                selfTimer = want
                selfTimerSupported = true
            } else if (!selfTimerSupported) {
                // First attempt failed: hide the button, it isn't there.
                statusMsg = "このカメラはセルフタイマー操作に対応していません"
            }
        }
    }

    /** Capability probe after connect (best-effort, no UI spam). */
    private fun probeShootCaps(c: OpcClient) {
        // set_takemode shows up in command lists that have it; when the
        // list is missing entirely we show the button once and let the
        // first toggle decide (toggleSelfTimer hides on failure).
        selfTimerSupported = c.info.commands.isEmpty() ||
            c.info.supports("set_takemode")
    }

    fun toggleLive() {
        if (liveRunning) stopLive() else startLive()
    }

    private fun startLive() {
        if (liveJob != null) return
        liveRunning = true
        val job = viewModelScope.launch(Dispatchers.IO) {
            val c = client() ?: return@launch
            try {
                LiveviewReceiver(network = c.network).use { rx ->
                    withContext(Dispatchers.IO) { c.startLiveview(rx.port) }
                    try {
                        var frames = 0
                        var nulls = 0
                        var lastLog = System.currentTimeMillis()
                        while (isActive) {
                            val jpeg = rx.nextFrame()
                            if (jpeg == null) {
                                nulls++
                            } else {
                                // Validate cheaply: BitmapFactory bounds decode.
                                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                                if (opts.outWidth > 0) {
                                    liveFrame = jpeg
                                    frames++
                                }
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastLog > 10_000) {
                                android.util.Log.i(
                                    "OMLight", "liveview stats: frames=$frames nulls=$nulls port=${rx.port}"
                                )
                                lastLog = now
                            }
                        }
                    } finally {
                        runCatching { c.stopLiveview() }
                    }
                }
            } catch (e: Exception) {
                if (isActive) statusMsg = "ライブビューに失敗: ${e.message}"
            } finally {
                liveRunning = false
                liveFrame = null
            }
        }
        // Clear the handle on ANY exit (error/cancel/end) so a later
        // startLive() never sees a dead job and refuses to start.
        job.invokeOnCompletion { if (liveJob === job) liveJob = null }
        liveJob = job
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        liveRunning = false
        liveFrame = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client()?.stopLiveview() }
        }
    }

    // -- camera props ---------------------------------------------------------------------

    fun loadProps() {
        if (propsLoading) return
        viewModelScope.launch {
            val c = client() ?: return@launch
            propsLoading = true
            try {
                props = withContext(Dispatchers.IO) { c.camPropList() }
                val free = withContext(Dispatchers.IO) { c.freeBytes() }
                cardFree = if (free >= 0) formatBytes(free) else null
                playSlot = withContext(Dispatchers.IO) { c.playSlot() }
                if (props.isEmpty()) statusMsg = "このカメラは設定一覧に対応していません"
                noteSuccess()
            } catch (e: Exception) {
                statusMsg = "設定の取得に失敗: ${e.message}"
                noteFailure("設定")
            } finally {
                propsLoading = false
            }
        }
    }

    fun setProp(name: String, value: String) {
        viewModelScope.launch {
            val c = client() ?: return@launch
            try {
                withContext(Dispatchers.IO) { c.setCamProp(name, value) }
                props = props.map { if (it.name == name) it.copy(value = value) else it }
                noteSuccess()
            } catch (e: Exception) {
                statusMsg = "設定の変更に失敗: ${e.message}"
                noteFailure("設定")
            }
        }
    }

    /** Flip dual-slot bodies between slot 1/2, then refresh the grid. */
    fun toggleSlot() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            val next = if (playSlot == 1) 2 else 1
            val ok = withContext(Dispatchers.IO) { c.setPlaySlot(next) }
            if (ok) {
                playSlot = next
                statusMsg = "スロット$next に切り替えました"
                refresh()
            } else {
                statusMsg = "スロット切替に失敗しました"
            }
        }
    }

    /** Clear One-Time share reservations (confirmed in UI first). */
    fun clearReservations() {
        viewModelScope.launch {
            val c = client() ?: return@launch
            val ok = withContext(Dispatchers.IO) { c.clearReservations() }
            statusMsg = if (ok) "シェア予約を解除しました" else "解除に失敗しました"
            if (ok) refresh()
        }
    }

    // -- SSID connect (Android 10+) ------------------------------------------------------------

    /**
     * Join the camera AP from inside the app (system dialog names this
     * app; system Wi-Fi settings untouched). Needs NEARBY_WIFI_DEVICES
     * (31+) or location (29-30) granted; caller checks first.
     */
    fun connectAp(ssid: String, passphrase: String) {
        if (conn == Conn.Searching) return
        viewModelScope.launch {
            conn = Conn.Searching
            statusMsg = null
            try {
                val c = withContext(Dispatchers.IO) {
                    CamSession.acquireAp(getApplication(), ssid, passphrase)
                }
                holding = true
                conn = Conn.Ready(c.info.model.ifEmpty { "OPC camera" })
                failures = 0
                linkDown = false
                probeShootCaps(c)
                startEventStream(c)
                refresh()
            } catch (e: Exception) {
                if (holding) {
                    withContext(Dispatchers.IO) { CamSession.release() }
                    holding = false
                }
                conn = Conn.Failed(e.message ?: "接続エラー")
            }
        }
    }

    fun retry() {
        if (conn is Conn.Ready) {
            linkDown = false
            failures = 0
            refresh()
        } else {
            connect()
        }
    }

    fun clearStatus() {
        statusMsg = null
    }

    private fun formatBytes(b: Long): String = when {
        b < 0 -> "不明"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024 * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
        else -> "%.1f GB".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    override fun onCleared() {
        liveJob?.cancel()
        eventJob?.cancel()
        reconnectJob?.cancel()
        thumbJob?.cancel()
        // Synchronous teardown: viewModelScope is cancelled right after
        // onCleared returns, so launching here would never run.
        if (holding) {
            holding = false
            val t = Thread({
                // Blocking bridge into the suspend API.
                kotlinx.coroutines.runBlocking { CamSession.release() }
            }, "omlight-teardown")
            t.isDaemon = true
            t.start()
            runCatching { t.join(3_000) }
        }
        super.onCleared()
    }
}
