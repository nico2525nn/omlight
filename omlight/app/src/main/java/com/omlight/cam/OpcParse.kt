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

    /** First-level children of the root element as name -> text. */
    fun xmlFlat(body: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val p: XmlPullParser = newParser()
            p.setInput(body.reader())
            var event = p.eventType
            var depth = 0
            var key: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 2) key = p.name
                    }
                    XmlPullParser.TEXT -> {
                        if (depth == 2 && key != null) {
                            val t = p.text.trim()
                            if (t.isNotEmpty()) out[key] = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == 2) key = null
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

    /** `get_commandlist.cgi`: command -> set of "arg=value" capability strings. */
    fun commandList(body: String): Map<String, Set<String>> {
        val cmds = LinkedHashMap<String, MutableSet<String>>()
        try {
            val p: XmlPullParser = newParser()
            p.setInput(body.reader())
            var event = p.eventType
            var cgi: String? = null
            // Stack of (argName, value) pairs inside <cmd>/<param> nesting.
            val stack = ArrayDeque<Pair<String, String?>>()
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (p.name) {
                            "cgi" -> {
                                cgi = p.getAttributeValue(null, "name")
                                    ?.removeSuffix(".cgi")
                                if (cgi != null) {
                                    cmds.getOrPut(cgi!!) { LinkedHashSet() }
                                }
                                stack.clear()
                            }
                            "cmd", "param" -> {
                                val n = p.getAttributeValue(null, "name")?.trim()
                                if (n != null) {
                                    // Parent with a concrete value forms "arg=value".
                                    val parent = stack.lastOrNull()
                                    if (cgi != null && parent != null &&
                                        parent.second == null && parent.first.isNotEmpty()
                                    ) {
                                        cmds[cgi]?.add("${parent.first}=$n")
                                    }
                                    stack.addLast(n to null)
                                } else {
                                    stack.addLast("" to null)
                                }
                                // Value attribute directly on the tag.
                                val v = p.getAttributeValue(null, "value")?.trim()
                                if (v != null && cgi != null && n != null) {
                                    cmds[cgi]?.add("$n=$v")
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = p.text.trim()
                        if (t.isNotEmpty() && cgi != null) {
                            val top = stack.lastOrNull()
                            if (top != null && top.first.isNotEmpty()) {
                                cmds[cgi]?.add("${top.first}=$t")
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (p.name) {
                            "cmd", "param" -> if (stack.isNotEmpty()) stack.removeLast()
                            "cgi" -> { cgi = null; stack.clear() }
                        }
                    }
                }
                event = p.next()
            }
        } catch (_: Exception) {
            // Partial capability map still usable.
        }
        return cmds
    }
}
