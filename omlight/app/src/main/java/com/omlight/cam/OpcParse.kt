package com.omlight.cam

import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/**
 * Pure parsing helpers for the OPC wire formats. No Android framework
 * dependencies beyond XmlPullParser (available since API 1).
 */
object OpcParse {

    /**
     * Parser source. Production uses the platform parser; unit tests inject
     * kxml2 (android.util.Xml is a stub on the JVM).
     */
    internal var newParser: () -> XmlPullParser = { Xml.newPullParser() }

    // -- get_imglist.cgi -------------------------------------------------------
    // Body: "VER_100\r\n" then one "<dir>,<file>,<size>,<attr>,<date>,<time>"
    // line per entry (spec §6). attr bit4 = directory.

    fun imgList(body: String): List<CamEntry> {
        val out = ArrayList<CamEntry>(128)
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("VER_") }
            .forEach { line ->
                entry(line)?.let(out::add)
            }
        // Spec: list may be unsorted. Newest-last keeps grid stable while
        // shooting; the UI sorts newest-first for display.
        out.sortWith(compareBy({ it.takenAtMillis }, { it.path }))
        return out
    }

    private fun entry(line: String): CamEntry? {
        // Filenames never contain ',' on DCF volumes; simple split is fine.
        val p = line.split(',')
        if (p.size != 6) return null
        val dir = p[0].trim()
        val file = p[1].trim()
        if (dir.isEmpty() || file.isEmpty()) return null
        val size = p[2].toLongOrNull() ?: 0L
        val attr = p[3].toIntOrNull() ?: 0
        if (attr and 0x02 != 0) return null // hidden
        if (attr and 0x04 != 0) return null // system
        if (attr and 0x08 != 0) return null // volume label
        val date = p[4].toIntOrNull() ?: 0
        val time = p[5].toIntOrNull() ?: 0
        val path = if (dir.endsWith('/')) "$dir$file" else "$dir/$file"
        return CamEntry(
            path = path,
            sizeBytes = size,
            isDirectory = attr and 0x10 != 0,
            takenAtMillis = dcfToMillis(date, time),
        )
    }

    /**
     * DCF date/time bit fields (spec §6):
     * date: bits 15-9 year-1980, 8-5 month, 4-0 day.
     * time: bits 15-11 hour, 10-5 minute, 4-0 seconds/2.
     * Camera clock has no tz; interpret as device-local.
     */
    fun dcfToMillis(date: Int, time: Int): Long {
        val year = 1980 + ((date shr 9) and 0x7F)
        val month = (date shr 5) and 0x0F
        val day = date and 0x1F
        val hour = (time shr 11) and 0x1F
        val min = (time shr 5) and 0x3F
        val sec = (time and 0x1F) * 2
        if (month !in 1..12 || day !in 1..31 || hour > 23 || min > 59 || sec > 59) return 0L
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.YEAR, year)
        cal.set(java.util.Calendar.MONTH, month - 1)
        cal.set(java.util.Calendar.DAY_OF_MONTH, day)
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, min)
        cal.set(java.util.Calendar.SECOND, sec)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // -- text/xml responses ----------------------------------------------------
    // Small flat responses: <response><result>OK</result>...</response>.

    /**
     * First-level children of the root element as name -> text.
     * Shipping bodies omit the `<response>` wrapper entirely
     * (`<connectmode>private</connectmode>` at top level), so depth-1
     * text elements are captured too.
     */
    fun xmlFlat(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val p: XmlPullParser = newParser()
            p.setInput(body.reader())
            var event = p.eventType
            var depth = 0
            var key: String? = null
            var keyDepth = 0
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth <= 2) {
                            key = p.name
                            keyDepth = depth
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (key != null && (keyDepth == 2 || (keyDepth == 1 && depth == 1))) {
                            val t = p.text.trim()
                            if (t.isNotEmpty()) out[key] = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == keyDepth) key = null
                        depth--
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
            // Malformed body: return what we got.
        }
        return out
    }

    /** `<desc>` / `<get>` camprop payload. */
    fun camProp(body: String): CamProp? {
        val m = xmlFlat(body)
        // get_camprop com=desc wraps in <desc>, com=get wraps in <get>;
        // xmlFlat flattens one level so both land here. desc carries propname
        // as attribute, which we recover from the nested scan below.
        val value = m["value"] ?: return null
        val attr = m["attribute"] ?: "get"
        val enum = m["enum"]?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
        return CamProp(name = "", value = value, writable = attr == "getset", values = enum)
    }

    /** `<desclist>` payload: repeated `<desc>` elements. */
    fun camPropList(body: String): List<CamProp> {
        val out = ArrayList<CamProp>()
        try {
            val p: XmlPullParser = newParser()
            p.setInput(body.reader())
            var event = p.eventType
            var name: String? = null
            var attr: String? = null
            var value: String? = null
            var enum: String? = null
            var cur: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        cur = p.name
                        if (cur == "desc") {
                            name = null; attr = null; value = null; enum = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = p.text.trim()
                        if (t.isNotEmpty()) when (cur) {
                            "propname" -> name = t
                            "attribute" -> attr = t
                            "value" -> value = t
                            "enum" -> enum = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (p.name == "desc" && name != null && value != null) {
                            out.add(
                                CamProp(
                                    name = name,
                                    value = value,
                                    writable = attr == "getset",
                                    values = enum?.split(' ')?.filter { it.isNotEmpty() }
                                        ?: emptyList(),
                                )
                            )
                        }
                        cur = null
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
            // Return partial list.
        }
        return out
    }

    /** `get_caminfo.cgi`: flat key/values incl. `model`. */
    fun camInfo(body: String): CameraInfo {
        val m = xmlFlat(body)
        return CameraInfo(
            model = m["model"].orEmpty(),
            maker = m["maker"].orEmpty(),
        )
    }

    /** `get_state.cgi`: card/lens/temperature status. */
    fun camState(body: String): CameraState {
        val m = xmlFlat(body)
        val card = m["cardstatus"] ?: m["card_status"] ?: ""
        return CameraState(
            cardMounted = card != "unmount" && card.isNotEmpty(),
            cardFreeBytes = m["cardremainbyte"]?.toLongOrNull() ?: 0L,
            remainShots = m["cardremainnum"]?.toIntOrNull() ?: 0,
        )
    }

    /**
     * `get_commandlist.cgi` -> structured [CapabilityDoc].
     * Handles `<cmd1>/<param1>/<cmd2>/…` (shipping) and `<cmd>/<param>`
     * (draft), cgi names with or without `.cgi`, and Shift-JIS bodies
     * (XmlPullParser honors the XML encoding declaration).
     */
    fun capabilityDoc(body: String): CapabilityDoc {
        var version = ""
        val support = LinkedHashSet<String>()
        val commands = LinkedHashMap<String, CommandCaps>()
        try {
            val p: XmlPullParser = newParser()
            p.setInput(body.reader())
            // Node stack for the current <cgi>: sealed on </cgi>.
            var cgi: String? = null
            var method = HttpMethod.GET
            // Each level: argName under construction + collected values.
            data class Frame(val arg: String, val values: MutableList<Value>)
            // param stack: value under construction + nested args.
            data class PFrame(val value: String, val nested: MutableList<ArgTree>)
            val argStack = ArrayDeque<Frame>()
            val paramStack = ArrayDeque<PFrame>()
            val topArgs = ArrayList<ArgTree>()
            var inMethod = false

            fun closeParam() {
                if (paramStack.isEmpty()) return
                val pf = paramStack.removeLast()
                val v = Value(pf.value, pf.nested.toList())
                if (argStack.isNotEmpty()) {
                    argStack.last().values.add(v)
                }
            }

            fun closeArg() {
                if (argStack.isEmpty()) return
                val f = argStack.removeLast()
                val tree = ArgTree(f.arg, f.values.toList())
                if (paramStack.isNotEmpty()) {
                    paramStack.last().nested.add(tree)
                } else {
                    topArgs.add(tree)
                }
            }

            var event = p.eventType
            var textOf: String? = null // version text holder
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = p.name
                        when {
                            tag == "cgi" -> {
                                cgi = p.getAttributeValue(null, "name")
                                    ?.removeSuffix(".cgi")
                                method = HttpMethod.GET
                                argStack.clear()
                                paramStack.clear()
                                topArgs.clear()
                                inMethod = false
                            }
                            tag == "http_method" -> {
                                inMethod = true
                                val t = p.getAttributeValue(null, "type")
                                method = if (t?.lowercase() == "post") HttpMethod.POST else HttpMethod.GET
                            }
                            tag == "version" -> textOf = "version"
                            tag == "support" -> {
                                p.getAttributeValue(null, "func")?.let { support.add(it) }
                            }
                            tag.startsWith("cmd") -> {
                                if (cgi != null && inMethod) {
                                    argStack.addLast(
                                        Frame(p.getAttributeValue(null, "name")?.trim().orEmpty(), ArrayList())
                                    )
                                }
                            }
                            tag.startsWith("param") -> {
                                if (cgi != null && inMethod) {
                                    paramStack.addLast(
                                        PFrame(p.getAttributeValue(null, "name")?.trim().orEmpty(), ArrayList())
                                    )
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (textOf == "version") {
                            val t = p.text.trim()
                            if (t.isNotEmpty()) version = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = p.name
                        when {
                            tag.startsWith("param") -> closeParam()
                            tag.startsWith("cmd") -> closeArg()
                            tag == "cgi" -> {
                                // Flush unbalanced leftovers (tolerant).
                                while (paramStack.isNotEmpty()) closeParam()
                                while (argStack.isNotEmpty()) closeArg()
                                if (cgi != null) {
                                    commands[cgi!!] = CommandCaps(method, topArgs.toList())
                                }
                                cgi = null
                            }
                            tag == "version" -> textOf = null
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
            // Partial doc still usable.
        }
        return CapabilityDoc(version, support, commands)
    }

    /**
     * `get_commandlist.cgi`: command -> set of "arg=value" capability strings.
     *
     * Legacy flat view over [capabilityDoc]. Kept for the small number of
     * shallow checks; new code should query the AST.
     */
    fun commandList(body: String): Map<String, Set<String>> {
        val doc = capabilityDoc(body)
        return doc.commands.mapValues { (_, caps) ->
            val out = LinkedHashSet<String>()
            caps.args.forEach { it.collectPairs(out) }
            out
        }
    }
}
