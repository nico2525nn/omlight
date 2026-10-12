package com.omlight.cam

import java.io.IOException
import okhttp3.Response

/**
 * High-level OPC camera session. Thread-safe; mode switches are serialized.
 *
 * Real cameras speak one of two dialects:
 * - AIR A01-era: `switch_cameramode.cgi?mode=rec|play|standalone|playmaintenance`
 * - Shipping OM-D/PEN: `switch_cammode.cgi?mode=rec|play|shutter` (+ lvqty)
 * Capabilities come from `get_commandlist.cgi`; where it is missing we
 * probe both spellings once and pin the working one.
 */
class OpcClient(private val t: OpcTransport) {

    enum class Mode(val wire: String) { REC("rec"), PLAY("play"), STANDBY("standalone") }

    private val lock = Any()

    @Volatile var info: CameraInfo = CameraInfo(model = "")
        private set
    @Volatile private var useCammode = true // switch_cammode (new) vs switch_cameramode (AIR)
    @Volatile private var mode: Mode? = null

    // -- handshake -------------------------------------------------------------

    /**
     * Negotiation sequence (spec §7): connectmode -> commandlist -> caminfo ->
     * play mode. Idempotent; safe to call on reconnect.
     */
    @Throws(IOException::class)
    fun connect() {
        val cm = OpcParse.xmlFlat(t.getString("get_connectmode.cgi"))
        if (cm["connectmode"] != "OPC") {
            throw IOException("not an OPC camera (connectmode=${cm["connectmode"]})")
        }
        val cmds: Map<String, Set<String>> = try {
            OpcParse.commandList(t.getString("get_commandlist.cgi"))
        } catch (_: IOException) {
            emptyMap()
        }
        val meta: CameraInfo = try {
            OpcParse.camInfo(t.getString("get_caminfo.cgi"))
        } catch (_: IOException) {
            CameraInfo(model = "")
        }
        info = meta.copy(commands = cmds)
        // Pin the mode-switch dialect.
        useCammode = when {
            cmds.containsKey("switch_cammode") -> true
            cmds.containsKey("switch_cameramode") -> false
            else -> probeDialect()
        }
        setMode(Mode.PLAY)
    }

    private fun probeDialect(): Boolean {
        // Try new spelling first; AIR spelling as fallback.
        try {
            t.getString("switch_cammode.cgi", "mode" to "play")
            return true
        } catch (_: IOException) { /* fall through */ }
        t.getString("switch_cameramode.cgi", "mode" to "play")
        return false
    }

    @Throws(IOException::class)
    fun setMode(m: Mode) {
        synchronized(lock) {
            if (mode == m) return
            val cgi = if (useCammode) "switch_cammode.cgi" else "switch_cameramode.cgi"
            // Lvqty is only valid with rec on the new dialect. Prefer the
            // camera's advertised sizes; fall back to the 640 default.
            val body = if (m == Mode.REC && useCammode) {
                t.getString(cgi, "mode" to m.wire, "lvqty" to pickLvqty())
            } else {
                // Old dialect + standalone: "standalone". New dialect has no
                // standalone; play is the idle mode.
                val wire = if (m == Mode.STANDBY && useCammode) Mode.PLAY.wire else m.wire
                t.getString(cgi, "mode" to wire)
            }
            checkOk(body, "switch mode")
            mode = m
        }
    }

    /**
     * Best liveview size the camera advertises. Prefers 640x480 (bandwidth
     * sweet spot); falls back to the smallest advertised, then default.
     */
    fun pickLvqty(): String {
        val sizes = info.commands["switch_cammode"].orEmpty()
            .mapNotNull {
                Regex("""lvqty=(\d+x\d+)""").find(it)?.groupValues?.get(1)
            }
            .distinct()
        if (sizes.isEmpty()) return DEFAULT_LVQTY
        if (DEFAULT_LVQTY in sizes) return DEFAULT_LVQTY
        return sizes.minByOrNull {
            it.split('x').let { p -> p[0].toInt() * p[1].toInt() }
        } ?: DEFAULT_LVQTY
    }

    /** Best-effort return to idle; never throws (call on pause/disconnect). */
    fun idle() {
        try {
            stopEvents()
        } catch (_: Exception) { /* ignore */ }
        try {
            if (live.active) stopLiveview()
        } catch (_: Exception) { /* ignore */ }
        try {
            setMode(if (useCammode) Mode.PLAY else Mode.STANDBY)
        } catch (_: Exception) { /* ignore */ }
    }

    // -- event notification --------------------------------------------------------

    /**
     * Live event stream. Created by [startEvents], null otherwise.
     * The receiver runs until [stopEvents]/[idle]. Port 0 = pick from the
     * ephemeral range by binding a probe socket first.
     */
    @Volatile var events: EventReceiver? = null
        private set
    @Volatile private var eventPort = 0

    /**
     * Start camera event notification (spec §5/§7). The camera opens [port]
     * and we connect back. Safe to call when already started.
     * Port 0 = probe a free ephemeral port first.
     */
    @Throws(IOException::class)
    fun startEvents(port: Int = 0) {
        synchronized(lock) {
            if (events?.running == true) return
            val p = if (port > 0) port else freePort()
            t.getString("start_pushevent.cgi", "port" to p.toString())
            val rx = EventReceiver(t.host)
            rx.start(p)
            events = rx
            eventPort = p
        }
    }

    fun stopEvents() {
        synchronized(lock) {
            try {
                t.getString("stop_pushevent.cgi")
            } catch (_: IOException) { /* already stopped */ }
            events?.close()
            events = null
            eventPort = 0
        }
    }

    // -- browsing --------------------------------------------------------------

    /** Image list for one DCF directory. 404 = empty (OSS finding). */
    @Throws(IOException::class)
    fun imgList(dir: String): List<CamEntry> {
        ensureMode(Mode.PLAY)
        try {
            return OpcParse.imgList(t.getString("get_imglist.cgi", "DIR" to dir))
        } catch (e: IOException) {
            if (e.message?.contains("HTTP 404") == true) return emptyList()
            throw e
        }
    }

    /**
     * Full recursive listing under `/DCIM`, newest-first. Directories are
     * enumerated depth-first; hidden/system/volume entries are dropped by
     * the parser. A visited-set guards against cyclic listings from
     * broken firmware.
     */
    @Throws(IOException::class)
    fun allImages(): List<CamEntry> {
        val out = ArrayList<CamEntry>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast("/DCIM")
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            if (!seen.add(dir)) continue
            if (seen.size > MAX_DIRS) break // runaway guard
            for (e in imgList(dir)) {
                if (e.isDirectory) {
                    // Queue only; marked visited when dequeued above.
                    if (!seen.contains(e.path)) stack.addLast(e.path)
                } else {
                    out.add(e)
                }
            }
            if (out.size > MAX_FILES) break
        }
        out.sortWith(compareByDescending<CamEntry> { it.takenAtMillis }.thenByDescending { it.path })
        return out
    }

    /** Thumbnail bytes (160x120 JPEG) for a grid cell. */
    @Throws(IOException::class)
    fun thumbnail(path: String): ByteArray {
        ensureMode(Mode.PLAY)
        t.get("get_thumbnail.cgi", "DIR" to path).use { r ->
            if (!r.isSuccessful) throw OpcTransport.statusError(r, "thumbnail")
            return r.body!!.bytes()
        }
    }

    /**
     * Display-size JPEG (<=1920x1440, spec §6 `get_screennail.cgi`).
     * Falls back to `get_resizeimg.cgi?size=1920` on cameras without it.
     */
    @Throws(IOException::class)
    fun preview(path: String): ByteArray {
        ensureMode(Mode.PLAY)
        if (info.supports("get_screennail")) {
            try {
                t.get("get_screennail.cgi", "DIR" to path).use { r ->
                    if (r.isSuccessful) return r.body!!.bytes()
                }
            } catch (_: IOException) { /* fall through to resize */ }
        }
        t.get("get_resizeimg.cgi", "DIR" to path, "size" to "1920").use { r ->
            if (!r.isSuccessful) throw OpcTransport.statusError(r, "preview")
            return r.body!!.bytes()
        }
    }

    /** Original file bytes via plain HTTP GET (spec §7 Get Image). */
    @Throws(IOException::class)
    fun original(path: String): Response = originalFrom(path, 0L)

    /**
     * Original file bytes from [from] (resume). [from]<=0 fetches whole.
     * Honored only when the camera answers 206; callers must handle 200
     * (full body) by restarting the file.
     */
    @Throws(IOException::class)
    fun originalFrom(path: String, from: Long): Response =
        t.getRange(path.trimStart('/'), from)

    // -- shooting ---------------------------------------------------------------

    /** Single shot. Uses shutter-mode dialect when advertised, else rec+takemotion. */
    @Throws(IOException::class)
    fun takePicture() {
        if (info.supportsArg("switch_cammode", "mode", "shutter") ||
            info.commands.isEmpty() && shutterProbe()
        ) {
            shutterTake()
        } else {
            setMode(Mode.REC)
            checkOk(t.getString("exec_takemotion.cgi", "com" to "newstarttake"), "take")
        }
    }

    /**
     * Single shot focused at the liveview coordinate ([x], [y]).
     * Coordinates are in the current lvqty space, e.g. 0..639 x 0..479 for
     * 0640x0480 (spec §6 AF Point). Only the takemotion dialect supports
     * this; shutter-mode bodies ignore the point and shoot normally.
     */
    @Throws(IOException::class)
    fun takePictureAt(x: Int, y: Int) {
        if (info.supportsArg("switch_cammode", "mode", "shutter") &&
            info.commands.isNotEmpty()
        ) {
            // Shutter dialect has no AF-point option; plain shot.
            shutterTake()
            return
        }
        setMode(Mode.REC)
        val point = "%04dx%04d".format(x.coerceIn(0, 9999), y.coerceIn(0, 9999))
        checkOk(
            t.getString("exec_takemotion.cgi", "com" to "newstarttake", "point" to point),
            "take@point",
        )
    }

    private var shutterCache: Boolean? = null

    private fun shutterProbe(): Boolean {
        shutterCache?.let { return it }
        val ok = try {
            t.getString("switch_cammode.cgi", "mode" to "shutter")
            true
        } catch (_: IOException) {
            false
        }
        shutterCache = ok
        return ok
    }

    @Throws(IOException::class)
    private fun shutterTake() {
        t.getString("switch_cammode.cgi", "mode" to "shutter")
        try {
            checkOk(t.getString("exec_shutter.cgi", "com" to "1st2ndpush"), "shutter")
            Thread.sleep(400)
            checkOk(t.getString("exec_shutter.cgi", "com" to "2nd1strelease"), "shutter")
        } finally {
            setMode(Mode.PLAY)
        }
    }

    /** Start/stop movie recording (rec mode). */
    @Throws(IOException::class)
    fun movieRecord(start: Boolean) {
        setMode(Mode.REC)
        val com = if (start) "newstartmovietake" else "newstopmovietake"
        checkOk(t.getString("exec_takemotion.cgi", "com" to com), "movie")
    }

    // -- live view ---------------------------------------------------------------

    /**
     * Live view state holder. RTP reassembly lives in [LiveviewReceiver];
     * this only tracks the session the UI needs to restart after actions.
     */
    val live = LiveState()

    class LiveState {
        @Volatile var active = false
            internal set
        @Volatile var port = 0
            internal set
        @Volatile var lvqty = DEFAULT_LVQTY
            internal set
    }

    /**
     * Start RTP live view on [port]. Caller must have bound the UDP socket
     * first (spec §7). Returns after the camera ACKs; frames arrive async.
     * Empty [lvqty] = auto-pick from the camera's advertised sizes.
     */
    @Throws(IOException::class)
    fun startLiveview(port: Int, lvqty: String = "") {
        setMode(Mode.REC)
        t.getString("exec_takemisc.cgi", "com" to "startliveview", "port" to port.toString())
        live.active = true
        live.port = port
        live.lvqty = lvqty.ifEmpty { pickLvqty() }
    }

    @Throws(IOException::class)
    fun changeLiveviewSize(lvqty: String) {
        checkOk(
            t.getString("exec_takemisc.cgi", "com" to "changelvqty", "lvqty" to lvqty),
            "changelvqty",
        )
        live.lvqty = lvqty
    }

    fun stopLiveview() {
        try {
            t.getString("exec_takemisc.cgi", "com" to "stopliveview")
        } catch (_: IOException) { /* already stopped */ }
        live.active = false
    }

    /** Preview (recview) JPEG right after a shot. */
    @Throws(IOException::class)
    fun recview(): ByteArray {
        t.get("exec_takemisc.cgi", "com" to "getrecview").use { r ->
            if (!r.isSuccessful) throw OpcTransport.statusError(r, "recview")
            return r.body!!.bytes()
        }
    }

    // -- optical zoom ------------------------------------------------------------------

    enum class ZoomDir(val wire: String) { TELE("tele"), WIDE("wide") }

    /**
     * Drive optical zoom while held (spec §7 Drive Optical Zoom).
     * [start] begins motion in [dir]; pair with [zoomStop]. Speed is
     * fixed to medium — fine for a phone UI hold gesture.
     */
    @Throws(IOException::class)
    fun zoomStart(dir: ZoomDir) {
        setMode(Mode.REC)
        checkOk(
            t.getString(
                "exec_takemisc.cgi", "com" to "newctrlzoom",
                "ctrl" to "start", "dir" to dir.wire, "method" to "contnormal",
            ),
            "zoom",
        )
    }

    @Throws(IOException::class)
    fun zoomStop(dir: ZoomDir) {
        checkOk(
            t.getString(
                "exec_takemisc.cgi", "com" to "newctrlzoom",
                "ctrl" to "stop", "dir" to dir.wire,
            ),
            "zoom",
        )
    }

    /** Jump zoom to [focalMm]. Best-effort: not all lenses support fix zoom. */
    @Throws(IOException::class)
    fun zoomTo(focalMm: Int) {
        setMode(Mode.REC)
        checkOk(
            t.getString(
                "exec_takemisc.cgi", "com" to "newctrlzoom",
                "ctrl" to "start", "dir" to "fix", "focallen" to focalMm.toString(),
            ),
            "zoom",
        )
    }

    // -- administration (playmaintenance mode) ---------------------------------------------

    /**
     * Run [block] in Playback-Administration mode, restoring PLAY after.
     * Delete/protect require admin mode (spec §7 support matrix).
     */
    @Throws(IOException::class)
    private inline fun <R> inAdmin(block: () -> R): R {
        val cgi = if (useCammode) "switch_cammode.cgi" else "switch_cameramode.cgi"
        // New dialect has no playmaintenance; admin ops ride on play.
        if (useCammode) return block()
        synchronized(lock) {
            t.getString(cgi, "mode" to "playmaintenance")
            try {
                return block()
            } finally {
                t.getString(cgi, "mode" to "play")
                mode = Mode.PLAY
            }
        }
    }

    /** Delete one file. Returns false when the camera refuses. */
    @Throws(IOException::class)
    fun delete(path: String): Boolean = inAdmin {
        val m = OpcParse.xmlFlat(t.getString("exec_erase.cgi", "DIR" to path))
        m["result"] != "NG"
    }

    /** Protect/unprotect one file. Returns false when the camera refuses. */
    @Throws(IOException::class)
    fun protect(path: String, on: Boolean): Boolean = inAdmin {
        val com = if (on) "set" else "release"
        val m = OpcParse.xmlFlat(t.getString("exec_protect.cgi", "com" to com, "DIR" to path))
        m["result"] != "NG"
    }

    // -- properties & state -------------------------------------------------------

    @Throws(IOException::class)
    fun camProp(name: String): CamProp? {
        val m = ensureModeForProp()
        val body = t.getString("get_camprop.cgi", "com" to "get", "propname" to name)
        return OpcParse.camProp(body)?.copy(name = name).also { restoreMode(m) }
    }

    /** Writable props + enums (rec mode). Empty when the camera lacks desclist. */
    @Throws(IOException::class)
    fun camPropList(): List<CamProp> {
        if (!info.supports("get_camprop")) return emptyList()
        val m = ensureModeForProp()
        return try {
            OpcParse.camPropList(t.getString("get_camprop.cgi", "com" to "desc", "propname" to "desclist"))
        } catch (_: IOException) {
            emptyList()
        } finally {
            restoreMode(m)
        }
    }

    @Throws(IOException::class)
    fun setCamProp(name: String, value: String) {
        val m = ensureModeForProp()
        try {
            val xml = "<?xml version=\"1.0\"?>\r\n<set>\r\n<value>$value</value>\r\n</set>\r\n"
            t.post("set_camprop.cgi", xml, "com" to "set", "propname" to name).use { r ->
                if (!r.isSuccessful) throw OpcTransport.statusError(r, "set $name")
            }
        } finally {
            restoreMode(m)
        }
    }

    @Throws(IOException::class)
    fun state(): CameraState = OpcParse.camState(t.getString("get_state.cgi"))

    /**
     * Sync the camera clock to the phone (spec: set_utctimediff).
     * Best-effort: silently ignored when the camera lacks the command.
     * Keeps FAT timestamps (and our date filter) sane.
     */
    fun syncClock() {
        if (!info.supports("set_utctimediff")) return
        try {
            val utc = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date())
            val offMin = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
            val sign = if (offMin >= 0) "+" else "-"
            val diff = "%s%02d%02d".format(sign, kotlin.math.abs(offMin) / 60, kotlin.math.abs(offMin) % 60)
            t.getString("set_utctimediff.cgi", "utctime" to utc, "diff" to diff)
        } catch (_: IOException) { /* non-fatal */ }
    }

    // -- internals ------------------------------------------------------------------

    private fun ensureMode(want: Mode) {
        if (mode != want) setMode(want)
    }

    /** Props need rec on most bodies; remember the previous mode to restore. */
    private fun ensureModeForProp(): Mode? {
        val prev = mode
        if (prev != Mode.REC) setMode(Mode.REC)
        return prev
    }

    private fun restoreMode(prev: Mode?) {
        if (prev != null && prev != mode) {
            try {
                setMode(prev)
            } catch (_: IOException) { /* stay where we are */ }
        }
    }

    private fun checkOk(body: String, what: String) {
        val m = OpcParse.xmlFlat(body)
        val r = m["result"]
        // Empty body = old firmware ACK. Missing result with no error = OK.
        if (r != null && r != "OK") throw IOException("$what failed: $body")
    }

    companion object {
        const val DEFAULT_LVQTY = "0640x0480"
        /** Runaway guards for broken firmware listings. */
        const val MAX_DIRS = 512
        const val MAX_FILES = 20000

        /**
         * Find a free TCP port by binding port 0. TOCTOU-race safe
         * enough here: the camera binds it within milliseconds, and
         * startEvents fails loudly on collision so the session can retry.
         */
        private fun freePort(): Int {
            try {
                java.net.ServerSocket(0).use { return it.localPort }
            } catch (_: IOException) {
                return 65000 // spec §7 example as last resort
            }
        }
    }
}
