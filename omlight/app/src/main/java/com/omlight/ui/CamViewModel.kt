package com.omlight.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omlight.cam.CamEntry
import com.omlight.cam.CamProp
import com.omlight.cam.CameraNetwork
import com.omlight.cam.EventReceiver
import com.omlight.cam.LiveviewReceiver
import com.omlight.cam.OpcClient
import com.omlight.cam.OpcTransport
import com.omlight.dl.DlItem
import com.omlight.dl.DownloadService
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.omlight.BuildConfig
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
    /** path -> JPEG bytes for grid thumbnails. Bounded: evicted on refresh. */
    val thumbs = mutableStateMapOf<String, ByteArray>()
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
    /** Full-size preview for the detail sheet, path -> JPEG. */
    var detailJpeg: ByteArray? by mutableStateOf(null)
        private set
    var detailPath: String? by mutableStateOf(null)
        private set
    var detailLoading: Boolean by mutableStateOf(false)
        private set
    /** Writable camera props for the settings sheet. */
    var props: List<CamProp> by mutableStateOf(emptyList())
        private set
    var propsLoading: Boolean by mutableStateOf(false)
        private set
    /** Consecutive transport failures; drives auto-reconnect. */
    var linkDown: Boolean by mutableStateOf(false)
        private set

    private var camNet: CameraNetwork? = null
    private var transport: OpcTransport? = null
    private var client: OpcClient? = null
    private var liveJob: Job? = null
    private var thumbJob: Job? = null
    private var eventJob: Job? = null
    private var reconnectJob: Job? = null
    private var failures = 0

    // -- connection ---------------------------------------------------------------

    fun connect() {
        if (conn == Conn.Searching) return
        viewModelScope.launch {
            conn = Conn.Searching
            statusMsg = null
            try {
                val net = (camNet ?: CameraNetwork(getApplication()).also { camNet = it })
                val t = withContext(Dispatchers.IO) {
                    net.findCamera(BuildConfig.CAM_HOST, BuildConfig.CAM_PORT)
                }
                if (t == null) {
                    conn = Conn.Failed("カメラが見つかりません。カメラのWi-Fiに接続してからもう一度。")
                    return@launch
                }
                transport = t
                val c = OpcClient(t)
                withContext(Dispatchers.IO) {
                    c.connect()
                    c.syncClock()
                }
                client = c
                val model = c.info.model.ifEmpty { "OPC camera" }
                conn = Conn.Ready(model)
                failures = 0
                linkDown = false
                if (net.vpnActive()) {
                    statusMsg = "VPNが有効です。カメラに繋がらない場合はVPNを切ってください"
                }
                net.watchLoss {
                    viewModelScope.launch {
                        linkDown = true
                        statusMsg = "カメラのWi-Fiが切れました。再接続してください"
                    }
                }
                startEventStream(c)
                refresh()
            } catch (e: Exception) {
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
                    val c = client
                    if (c != null) withContext(Dispatchers.IO) { c.connect() }
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client?.idle() }
            runCatching { transport?.close() }
            runCatching { camNet?.close() }
        }
        client = null
        transport = null
        camNet = null
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
        val c = client ?: return
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                val list = withContext(Dispatchers.IO) { c.allImages() }
                entries = list
                emptyHint = if (list.isEmpty()) {
                    "カメラの一覧は空でした。カメラ側で「ワンタイム接続」や" +
                        "「シェア予約のみ表示」になっていないか確認してください。"
                } else null
                thumbs.keys.retainAll(list.map { it.path }.toSet())
                prefetchThumbs(list.take(60))
                noteSuccess()
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
        val c = client ?: return
        thumbJob = viewModelScope.launch(Dispatchers.IO) {
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
            kotlinx.coroutines.sync.Semaphore(4).let { sem ->
                val jobs = missing.map { e ->
                    launch {
                        sem.acquire()
                        try {
                            if (!isActive) return@launch
                            val jpeg = c.thumbnail(e.path)
                            thumbStore.put(e.path, e.sizeBytes, jpeg)
                            thumbs[e.path] = jpeg
                        } catch (_: Exception) {
                            // Offline cell; user can retry via refresh.
                        } finally {
                            sem.release()
                        }
                    }
                }
                jobs.forEach { it.join() }
            }
        }
    }

    /**
     * Fetch one thumbnail on demand (grid scrolled it into view).
     * Disk cache → memory → network, in that order.
     */
    fun needThumb(entry: CamEntry) {
        if (thumbs.containsKey(entry.path)) return
        thumbStore.get(entry.path, entry.sizeBytes)?.let {
            thumbs[entry.path] = it
            return
        }
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jpeg = c.thumbnail(entry.path)
                thumbStore.put(entry.path, entry.sizeBytes, jpeg)
                thumbs[entry.path] = jpeg
            } catch (_: Exception) { /* retry on next bind */ }
        }
    }

    fun thumbFor(path: String): ByteArray? = thumbs[path]

    fun download(paths: List<String>) {
        val items = entries.filter { it.path in paths }
            .map { DlItem(it.path, it.sizeBytes, it.takenAtMillis) }
        if (items.isEmpty()) return
        DownloadService.enqueue(getApplication(), items)
        statusMsg = "${items.size}件をダウンロード開始（バックグラウンドで継続）"
    }

    fun downloadAll() = download(entries.map { it.path })

    // -- detail -------------------------------------------------------------------------

    fun openDetail(path: String) {
        val c = client ?: return
        detailPath = path
        detailJpeg = null
        if (detailLoading) return
        viewModelScope.launch {
            detailLoading = true
            try {
                detailJpeg = withContext(Dispatchers.IO) { c.preview(path) }
                noteSuccess()
            } catch (e: Exception) {
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

    fun deleteDetail() {
        val c = client ?: return
        val path = detailPath ?: return
        viewModelScope.launch {
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
        val c = client ?: return
        val path = detailPath ?: return
        viewModelScope.launch {
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
        val c = client ?: return
        if (shooting) return
        viewModelScope.launch {
            shooting = true
            captureState = "撮影中…"
            try {
                withContext(Dispatchers.IO) { c.takePicture() }
                statusMsg = "撮影しました"
                noteSuccess()
                // Event CAPTURE_DONE also refreshes; this is the fallback.
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

    /**
     * Tap-to-focus shot: ([fx], [fy]) are 0..1 fractions of the liveview
     * frame. Converted to the current lvqty coordinate space.
     */
    fun shootAt(fx: Float, fy: Float) {
        val c = client ?: return
        if (shooting) return
        viewModelScope.launch {
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

    fun zoomHold(dir: OpcClient.ZoomDir, start: Boolean) {
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (start) c.zoomStart(dir) else c.zoomStop(dir)
                noteSuccess()
            } catch (e: Exception) {
                if (start) statusMsg = "ズームに失敗: ${e.message}"
            }
        }
    }

    fun toggleMovie() {
        val c = client ?: return
        viewModelScope.launch {
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

    fun toggleLive() {
        if (liveRunning) stopLive() else startLive()
    }

    private fun startLive() {
        val c = client ?: return
        if (liveJob != null) return
        liveRunning = true
        liveJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                LiveviewReceiver().use { rx ->
                    withContext(Dispatchers.IO) { c.startLiveview(rx.port) }
                    try {
                        while (isActive) {
                            val jpeg = rx.nextFrame() ?: continue
                            // Validate cheaply: BitmapFactory bounds decode.
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                            if (opts.outWidth > 0) liveFrame = jpeg
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
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
        liveRunning = false
        liveFrame = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client?.stopLiveview() }
        }
    }

    // -- camera props ---------------------------------------------------------------------

    fun loadProps() {
        val c = client ?: return
        if (propsLoading) return
        viewModelScope.launch {
            propsLoading = true
            try {
                props = withContext(Dispatchers.IO) { c.camPropList() }
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
        val c = client ?: return
        viewModelScope.launch {
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
                val net = (camNet ?: CameraNetwork(getApplication()).also { camNet = it })
                val t = net.requestCameraAp(ssid, passphrase)
                if (t == null) {
                    conn = Conn.Failed("APへの接続をキャンセルしたか、カメラが応答しません。")
                    return@launch
                }
                transport = t
                val c = OpcClient(t)
                withContext(Dispatchers.IO) {
                    c.connect()
                    c.syncClock()
                }
                client = c
                conn = Conn.Ready(c.info.model.ifEmpty { "OPC camera" })
                failures = 0
                linkDown = false
                startEventStream(c)
                refresh()
            } catch (e: Exception) {
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

    override fun onCleared() {
        liveJob?.cancel()
        eventJob?.cancel()
        reconnectJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { client?.idle() }
            runCatching { transport?.close() }
            runCatching { camNet?.close() }
        }
        super.onCleared()
    }
}
