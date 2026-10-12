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
    /** Connection type from get_connectmode: private/onetime/OPC. */
    @Volatile var connectionType: String = ""
        private set
    /** Detected wire dialect (re-detected on every connect). */
    @Volatile var dialect: Dialect = Dialect.UNKNOWN
        private set
    /** Camera network for binding aux sockets (events/liveview). */
    val network: android.net.Network? get() = t.network
    @Volatile private var useCammode = true // switch_cammode (new) vs switch_cameramode (AIR)
    @Volatile private var mode: Mode? = null

    // -- handshake -------------------------------------------------------------

    /**
     * Negotiation sequence (spec §7): connectmode -> commandlist -> caminfo ->
     * play mode. Idempotent; safe to call on reconnect.
     */
    @Throws(IOException::class)
    fun connect() {
        val cm = OpcParse.xmlFlat(t.handshakeGet("get_connectmode.cgi"))
        // Spec says OPC; shipping bodies answer their connection type:
        // "private" (full access) or "onetime" (share-order only).
        // Anything non-empty means a camera answered.
        val mode = cm["connectmode"].orEmpty()
        if (mode.isEmpty()) {
            throw IOException("not a camera (connectmode=$mode)")
        }
        connectionType = mode
        val doc: CapabilityDoc = try {
            OpcParse.capabilityDoc(t.getString("get_commandlist.cgi"))
        } catch (_: IOException) {
            CapabilityDoc()
        }
        val cmds: Map<String, Set<String>> = doc.commands.mapValues { (_, caps) ->
            val out = LinkedHashSet<String>()
            caps.args.forEach { it.collectPairsPublic(out) }
            out
        }
        val meta: CameraInfo = try {
            OpcParse.camInfo(t.getString("get_caminfo.cgi"))
        } catch (_: IOException) {
            CameraInfo(model = "")
        }
        info = meta.copy(commands = cmds, caps = doc)
        dialect = detectDialect(mode, doc)
        // Pin the mode-switch dialect.
        useCammode = when {
            cmds.containsKey("switch_cammode") -> true
            cmds.containsKey("switch_cameramode") -> false
            else -> probeDialect()
        }
        setMode(Mode.PLAY)
    }

    /**
     * Pick the wire dialect. connectmode leads (private => PRIVATE);
     * the take spelling confirms it. Unknown bodies stay UNKNOWN and
     * probe per call.
     */
    private fun detectDialect(connectmode: String, doc: CapabilityDoc): Dialect {
        val takeArgs = doc.pairs("exec_takemotion")
        val hasStart = takeArgs.contains("com=starttake")
        val hasNew = takeArgs.contains("com=newstarttake")
        return when {
            connectmode == "private" || hasStart -> Dialect.PRIVATE
            connectmode == "OPC" || hasNew -> Dialect.AIR
            doc.commands.isEmpty() -> Dialect.UNKNOWN
            else -> Dialect.PRIVATE // shipping default: new bodies are private-like
        }
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
            // Liveview owns REC: a stray PLAY switch (thumbnail prefetch,
            // props restore) would kill the RTP stream mid-frame. Callers
            // that must leave REC stop liveview first.
            if (live.active && m != Mode.REC) {
                throw IOException("liveview active: stop it before leaving REC")
            }
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
     * No-op on bodies without push events (E-M5 II): returns silently.
     */
    @Throws(IOException::class)
    fun startEvents(port: Int = 0) {
        if (!supportsEvents() && info.commands.isNotEmpty()) return
        synchronized(lock) {
            if (events?.running == true) return
            val p = if (port > 0) port else freePort()
            val ack = t.getString("start_pushevent.cgi", "port" to p.toString())
            if (OpcParse.xmlFlat(ack)["errorcode"] != null) return // declined
            val rx = EventReceiver(t.host, t.network)
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

    /**
     * True when the shutter dialect is usable. Advertised capability wins;
     * when the camera gave no command list, probe once and pin the result.
     * Never assume shutter on unknown bodies (a wrong guess leaves the
     * camera in a mode we can't track).
     */
    private fun useShutter(): Boolean {
        if (info.commands.isNotEmpty()) {
            return info.supportsArg("switch_cammode", "mode", "shutter") &&
                info.supports("exec_shutter")
        }
        shutterCache?.let { return it }
        val ok = try {
            t.getString("switch_cammode.cgi", "mode" to "shutter")
            true
        } catch (_: IOException) {
            false
        } finally {
            // Probe leaves the body in shutter or errors out; either way
            // force a tracked return to PLAY below.
            mode = null
        }
        shutterCache = ok
        if (ok) setMode(Mode.PLAY)
        return ok
    }

    /**
     * takemotion subcommand for a single shot. Dialect-led:
     * PRIVATE => starttake, AIR => newstarttake, UNKNOWN => pinned probe.
     */
    private fun takeCom(): String = when (dialect) {
        Dialect.PRIVATE -> "starttake"
        Dialect.AIR -> "newstarttake"
        Dialect.UNKNOWN -> takeComCache ?: "starttake"
    }

    private var takeComCache: String? = null

    /** Movie subcommands, same dialect split as [takeCom]. */
    private fun movieCom(start: Boolean): String {
        val modern = takeCom() == "starttake"
        return if (start) {
            if (modern) "startmovietake" else "newstartmovietake"
        } else {
            if (modern) "stopmovietake" else "newstopmovietake"
        }
    }

    /** Single shot. Uses shutter-mode dialect when usable, else rec+takemotion. */
    @Throws(IOException::class)
    fun takePicture() {
        if (useShutter()) {
            shutterTake()
        } else {
            setMode(Mode.REC)
            takeWithFallback(null)
        }
    }

    /**
     * Run the take command, falling back to the other dialect spelling
     * once on failure. Pins the winner for this session.
     */
    @Throws(IOException::class)
    private fun takeWithFallback(point: String?) {
        val first = takeCom()
        val second = if (first == "starttake") "newstarttake" else "starttake"
        try {
            takeOnce(first, point)
            takeComCache = first
        } catch (e: IOException) {
            if (info.commands.isNotEmpty()) throw e // advertised: trust it
            takeOnce(second, point)
            takeComCache = second
        }
    }

    @Throws(IOException::class)
    private fun takeOnce(com: String, point: String?) {
        if (point == null) {
            checkOk(t.getString("exec_takemotion.cgi", "com" to com), "take")
        } else {
            checkOk(
                t.getString("exec_takemotion.cgi", "com" to com, "point" to point),
                "take@point",
            )
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
        if (useShutter()) {
            // Shutter dialect has no AF-point option; plain shot.
            shutterTake()
            return
        }
        setMode(Mode.REC)
        val point = "%04dx%04d".format(x.coerceIn(0, 9999), y.coerceIn(0, 9999))
        takeWithFallback(point)
    }

    private var shutterCache: Boolean? = null

    @Throws(IOException::class)
    private fun shutterTake() {
        synchronized(lock) {
            t.getString("switch_cammode.cgi", "mode" to "shutter")
            // Track the untracked mode so setMode(PLAY) below actually
            // sends the command instead of short-circuiting.
            mode = null
        }
        try {
            // Full two-stage sequence like the physical button:
            // half-press (AF) -> full-press (capture) -> release both.
            // Single 1st2ndpush alone doesn't fire on E-M5 II.
            try {
                checkOk(t.getString("exec_shutter.cgi", "com" to "1stpush"), "focus")
                Thread.sleep(1200)
            } catch (_: IOException) {
                // Best-effort: some modes reject lone 1stpush.
                Thread.sleep(400)
            }
            checkOk(t.getString("exec_shutter.cgi", "com" to "2ndpush"), "shutter")
            Thread.sleep(600)
            try {
                checkOk(t.getString("exec_shutter.cgi", "com" to "2ndrelease"), "release")
            } catch (_: IOException) { /* best-effort */ }
            try {
                checkOk(t.getString("exec_shutter.cgi", "com" to "1strelease"), "release")
            } catch (_: IOException) { /* best-effort */ }
        } finally {
            setMode(Mode.PLAY)
        }
    }

    /** Start/stop movie recording (rec mode). */
    @Throws(IOException::class)
    fun movieRecord(start: Boolean) {
        setMode(Mode.REC)
        checkOk(t.getString("exec_takemotion.cgi", "com" to movieCom(start)), "movie")
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
        // Shipping bodies: getrecview, else getlastjpg (E-M5 II has both).
        val coms = if (info.commands.isEmpty()) {
            listOf("getrecview", "getlastjpg")
        } else {
            val args = info.commands["exec_takemisc"].orEmpty()
            listOf("getrecview", "getlastjpg").filter { args.contains("com=$it") }
                .ifEmpty { listOf("getrecview", "getlastjpg") }
        }
        var lastErr: IOException? = null
        for (com in coms) {
            try {
                t.get("exec_takemisc.cgi", "com" to com).use { r ->
                    if (r.isSuccessful) return r.body!!.bytes()
                    lastErr = OpcTransport.statusError(r, "recview")
                }
            } catch (e: IOException) {
                lastErr = e
            }
        }
        throw lastErr ?: IOException("recview unavailable")
    }

    // -- optical zoom ------------------------------------------------------------------
    // Two dialects: shipping bodies use exec_takemisc com=ctrlzoom&move=
    // (E-M5 II verified); the AIR-era draft used com=newctrlzoom. Detected
    // from the command list.

    enum class ZoomDir(val wire: String) { TELE("tele"), WIDE("wide") }

    private fun zoomDialectNew(): Boolean = when (dialect) {
        Dialect.AIR -> true
        Dialect.PRIVATE -> false
        Dialect.UNKNOWN -> {
            // No command list: trust the AST when partially known.
            val args = info.commands["exec_takemisc"].orEmpty()
            args.contains("com=newctrlzoom")
        }
    }

    /**
     * Drive optical zoom while held (spec §7 Drive Optical Zoom).
     * [start] begins motion in [dir]; pair with [zoomStop]. Speed is
     * fixed to medium — fine for a phone UI hold gesture.
     */
    @Throws(IOException::class)
    fun zoomStart(dir: ZoomDir) {
        setMode(Mode.REC)
        if (zoomDialectNew()) {
            checkOk(
                t.getString(
                    "exec_takemisc.cgi", "com" to "newctrlzoom",
                    "ctrl" to "start", "dir" to dir.wire, "method" to "contnormal",
                ),
                "zoom",
            )
        } else {
            val move = if (dir == ZoomDir.TELE) "telemove" else "widemove"
            checkOk(
                t.getString("exec_takemisc.cgi", "com" to "ctrlzoom", "move" to move),
                "zoom",
            )
        }
    }

    @Throws(IOException::class)
    fun zoomStop(dir: ZoomDir) {
        if (zoomDialectNew()) {
            checkOk(
                t.getString(
                    "exec_takemisc.cgi", "com" to "newctrlzoom",
                    "ctrl" to "stop", "dir" to dir.wire,
                ),
                "zoom",
            )
        } else {
            checkOk(
                t.getString("exec_takemisc.cgi", "com" to "ctrlzoom", "move" to "off"),
                "zoom",
            )
        }
    }

    /** Jump zoom to [focalMm]. Best-effort: not all lenses support fix zoom. */
    @Throws(IOException::class)
    fun zoomTo(focalMm: Int) {
        setMode(Mode.REC)
        // Fix zoom exists only in the new dialect; shipping bodies without
        // it throw, and callers treat that as "not supported".
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
    fun delete(path: String): Boolean {
        if (!info.supports("exec_erase")) {
            throw IOException("このカメラはアプリからの削除に対応していません")
        }
        return inAdmin {
            val m = OpcParse.xmlFlat(t.getString("exec_erase.cgi", "DIR" to path))
            m["errorcode"] == null && m["result"] != "NG"
        }
    }

    /** Protect/unprotect one file. Returns false when the camera refuses. */
    @Throws(IOException::class)
    fun protect(path: String, on: Boolean): Boolean {
        if (!info.supports("exec_protect")) {
            throw IOException("このカメラはアプリからの保護に対応していません")
        }
        return inAdmin {
            val com = if (on) "set" else "release"
            val m = OpcParse.xmlFlat(t.getString("exec_protect.cgi", "com" to com, "DIR" to path))
            m["errorcode"] == null && m["result"] != "NG"
        }
    }

    /** False on bodies without delete/protect commands (E-M5 II). UI hides the buttons. */
    fun supportsAdmin(): Boolean =
        info.commands.isEmpty() ||
            (info.supports("exec_erase") && info.supports("exec_protect"))

    /** False on bodies without push events (E-M5 II). Skips start_pushevent entirely. */
    fun supportsEvents(): Boolean =
        info.commands.isEmpty() || info.supports("start_pushevent")

    // -- share-order / One-Time ------------------------------------------------------------

    /**
     * Share-reserved image list (One-Time connections only show these).
     * Same VER_100 text shape as get_imglist. Empty when unsupported.
     */
    @Throws(IOException::class)
    fun reserveList(): List<CamEntry> {
        if (info.commands.isNotEmpty() && !info.supports("get_rsvimglist")) {
            return emptyList()
        }
        return try {
            OpcParse.imgList(t.getString("get_rsvimglist.cgi"))
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** True when this looks like a One-Time (share-limited) connection. */
    fun isOneTime(): Boolean = connectionType.equals("onetime", ignoreCase = true)

    // -- AF frame -------------------------------------------------------------------------------

    /**
     * Point AF to the liveview coordinate and lock it there
     * (assignafframe). [x]/[y] in current lvqty space. Best-effort:
     * returns false when the body lacks the command.
     */
    fun assignAf(x: Int, y: Int): Boolean {
        if (info.commands.isNotEmpty() &&
            !info.supportsArg("exec_takemotion", "com", "assignafframe")
        ) return false
        return try {
            setMode(Mode.REC)
            val point = "%04dx%04d".format(x.coerceIn(0, 9999), y.coerceIn(0, 9999))
            checkOk(
                t.getString("exec_takemotion.cgi", "com" to "assignafframe", "point" to point),
                "af",
            )
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Release a locked AF frame. Best-effort. */
    fun releaseAf(): Boolean {
        if (info.commands.isNotEmpty() &&
            !info.supportsArg("exec_takemotion", "com", "releaseafframe")
        ) return false
        return try {
            checkOk(
                t.getString("exec_takemotion.cgi", "com" to "releaseafframe"),
                "af",
            )
            true
        } catch (_: IOException) {
            false
        }
    }

    // -- power & capacity ----------------------------------------------------------------------------

    /** Remote power-off. The Wi-Fi AP dies with it; UI must disconnect. */
    @Throws(IOException::class)
    fun powerOff() {
        // 202 Accepted, empty body. Never throws on transport quirks —
        // the AP vanishes mid-response on some bodies.
        try {
            t.get("exec_pwoff.cgi").close()
        } catch (_: IOException) { /* expected on some bodies */ }
    }

    /** Free bytes on the camera card (get_unusedcapacity), -1 when unknown. */
    fun freeBytes(): Long {
        if (info.commands.isNotEmpty() && !info.supports("get_unusedcapacity")) return -1
        return try {
            val m = OpcParse.xmlFlat(t.getString("get_unusedcapacity.cgi"))
            m.values.firstNotNullOfOrNull { it.toLongOrNull() } ?: -1
        } catch (_: IOException) {
            -1
        }
    }

    /** Movie play time in seconds for [path] (get_movplaytime), -1 when unknown. */
    fun movieSeconds(path: String): Int {
        if (info.commands.isNotEmpty() && !info.supports("get_movplaytime")) return -1
        return try {
            val m = OpcParse.xmlFlat(t.getString("get_movplaytime.cgi", "DIR" to path))
            m.values.firstNotNullOfOrNull { it.toIntOrNull() } ?: -1
        } catch (_: IOException) {
            -1
        }
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
        if (body.isBlank()) return // empty body = old firmware ACK
        val m = OpcParse.xmlFlat(body)
        // Shipping error shape: <errorcode>NNNN</errorcode>.
        m["errorcode"]?.let { throw IOException("$what: カメラエラー $it") }
        // Take result shape: <take>ok|ng</take>.
        m["take"]?.let {
            if (it != "ok") {
                val af = m["affocus"]
                throw IOException(
                    "$what: 撮影できませんでした" +
                        (if (af == "ng") "(ピントが合いません)" else "")
                )
            }
            return
        }
        val r = m["result"]
        // Missing result with no error = OK.
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
