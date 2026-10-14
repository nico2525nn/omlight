package com.omlight.cam

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Shooting + color info for the detail sheet.
 *
 * Two sources, merged with camera CGI winning on conflicts:
 * 1. `get_exif.cgi?DIR=` / `get_imageinfo.cgi` (camera CGI, when advertised).
 *    Response shapes vary by body; parsed best-effort as flat XML.
 * 2. EXIF of the preview JPEG itself (always available, no extra request).
 *
 * Displayed rows are preformatted "label -> value" in that order.
 */
data class PhotoInfo(
    val rows: List<Pair<String, String>> = emptyList(),
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

object PhotoInfoParse {

    /**
     * Parse EXIF tags from JPEG bytes into display rows.
     * Covers the usual shooting info + color/white-balance info.
     */
    fun fromJpeg(jpeg: ByteArray): PhotoInfo {
        val rows = ArrayList<Pair<String, String>>()
        try {
            val exif = ExifInterface(ByteArrayInputStream(jpeg))
            fun tag(t: String): String? =
                exif.getAttribute(t)?.takeIf { it.isNotBlank() }

            tag(ExifInterface.TAG_MODEL)?.let { rows.add("カメラ" to it.trim()) }
            tag(ExifInterface.TAG_LENS_MODEL)?.let { rows.add("レンズ" to it.trim()) }
            tag(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                rows.add("撮影日時" to it.replaceFirst(" ", " ").trim())
            }
            // Exposure trio.
            val f = tag(ExifInterface.TAG_F_NUMBER)?.let { "F${formatRational(it)}" }
            val ss = tag(ExifInterface.TAG_EXPOSURE_TIME)?.let { formatShutter(it) }
            val iso = tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: tag(ExifInterface.TAG_ISO_SPEED_RATINGS)
            listOfNotNull(
                f?.let { "絞り" to it },
                ss?.let { "シャッター" to it },
                iso?.let { "ISO" to it },
            ).forEach(rows::add)
            tag(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let {
                rows.add("露出補正" to "${formatRational(it)}EV")
            }
            tag(ExifInterface.TAG_FOCAL_LENGTH)?.let {
                rows.add("焦点距離" to "${formatRational(it)}mm")
            }
            // Color / WB info.
            tag(ExifInterface.TAG_WHITE_BALANCE)?.let {
                rows.add("ホワイトバランス" to wbName(it))
            }
            tag(ExifInterface.TAG_COLOR_SPACE)?.let {
                rows.add("色空間" to colorSpaceName(it))
            }
            tag(ExifInterface.TAG_SATURATION)?.let {
                rows.add("彩度" to levelName(it))
            }
            tag(ExifInterface.TAG_SHARPNESS)?.let {
                rows.add("シャープネス" to levelName(it))
            }
            tag(ExifInterface.TAG_CONTRAST)?.let {
                rows.add("コントラスト" to levelName(it))
            }
            // Flash / metering / misc.
            tag(ExifInterface.TAG_FLASH)?.let {
                rows.add("フラッシュ" to flashName(it))
            }
            tag(ExifInterface.TAG_METERING_MODE)?.let {
                rows.add("測光" to meteringName(it))
            }
            tag(ExifInterface.TAG_EXPOSURE_PROGRAM)?.let {
                rows.add("露出プログラム" to expProgName(it))
            }
            tag(ExifInterface.TAG_EXPOSURE_MODE)?.let {
                rows.add("露出モード" to expModeName(it))
            }
            val w = tag(ExifInterface.TAG_IMAGE_WIDTH)
                ?: tag(ExifInterface.TAG_PIXEL_X_DIMENSION)
            val h = tag(ExifInterface.TAG_IMAGE_LENGTH)
                ?: tag(ExifInterface.TAG_PIXEL_Y_DIMENSION)
            if (w != null && h != null && w != "0" && h != "0") {
                rows.add("サイズ" to "${w}×${h}")
            }
            tag(ExifInterface.TAG_GPS_LATITUDE)?.let {
                val latRef = tag(ExifInterface.TAG_GPS_LATITUDE_REF).orEmpty()
                rows.add("緯度" to "$it $latRef".trim())
            }
            tag(ExifInterface.TAG_GPS_LONGITUDE)?.let {
                val lonRef = tag(ExifInterface.TAG_GPS_LONGITUDE_REF).orEmpty()
                rows.add("経度" to "$it $lonRef".trim())
            }
        } catch (_: Exception) {
            // No EXIF (screennail strips it on some bodies): empty rows.
        }
        return PhotoInfo(rows)
    }

    /**
     * Merge camera-CGI info (flat XML map) into rows. Known OLYMPUS keys
     * are prettified; unknown keys pass through as-is (better than
     * dropping body-specific info like art filter / color creator).
     */
    fun fromCgi(map: Map<String, String>): PhotoInfo {
        if (map.isEmpty()) return PhotoInfo()
        val pretty = mapOf(
            "shutspeedvalue" to "シャッター",
            "focalvalue" to "絞り",
            "expcomp" to "露出補正",
            "isospeedvalue" to "ISO",
            "wbvalue" to "ホワイトバランス",
            "colortone" to "カラー",
            "colorphase" to "色相",
            "artfilter" to "アートフィルター",
            "takemode" to "撮影モード",
            "drivemode" to "ドライブ",
            "focallength" to "焦点距離",
        )
        // Suffixes the camera omits (values are bare numbers).
        val suffix = mapOf(
            "focalvalue" to "",
            "expcomp" to "EV",
            "focallength" to "mm",
        )
        val rows = map.entries
            .filter { it.value.isNotBlank() }
            .map { (k, v) ->
                val t = v.trim()
                val label = pretty[k.lowercase()] ?: k
                val s = suffix[k.lowercase()].orEmpty()
                label to if (s.isNotEmpty() && !t.endsWith(s)) "$t$s" else t
            }
        return PhotoInfo(rows)
    }

    /** CGI rows first, then EXIF rows for keys not already covered. */
    fun merge(cgi: PhotoInfo, exif: PhotoInfo): PhotoInfo {
        if (cgi.isEmpty) return exif
        if (exif.isEmpty) return cgi
        val seen = cgi.rows.map { it.first }.toHashSet()
        return PhotoInfo(cgi.rows + exif.rows.filter { it.first !in seen })
    }

    /** Drop the size row (preview transcodings report lying dimensions). */
    fun PhotoInfo.withoutSize(): PhotoInfo =
        PhotoInfo(rows.filter { it.first != "サイズ" })

    /**
     * JPEG dimensions from SOF markers (SOF0-SOF15 except DHT/DAC).
     * Reads the actual frame header, not EXIF tags: works even when
     * ExifInterface drops PixelXDimension on truncated heads, and
     * always reflects the bytes at hand. Null when no SOF found.
     */
    fun jpegDims(jpeg: ByteArray): Pair<Int, Int>? {
        try {
            var i = 2 // skip SOI
            while (i + 9 < jpeg.size) {
                if (jpeg[i].toInt() and 0xFF != 0xFF) {
                    i++
                    continue
                }
                val m = jpeg[i + 1].toInt() and 0xFF
                // SOF0-SOF15 minus C4 (DHT), C8 (JPG), CC (DAC).
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    val h = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                    val w = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                    if (w > 0 && h > 0 && w <= 65535 && h <= 65535) return w to h
                    return null
                }
                if (m == 0xD8 || m == 0xD9 || (m in 0xD0..0xD7) || m == 0x01) {
                    i += 2 // standalone markers
                    continue
                }
                if (i + 3 >= jpeg.size) return null
                val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
                if (len < 2) return null
                i += 2 + len
            }
        } catch (_: Exception) { /* malformed: fall through */ }
        return null
    }

    /**
     * Minimal TIFF/ORF IFD reader for original-file heads. ExifInterface
     * cannot parse ORF; this extracts the shooting basics (model, exposure
     * trio, ISO, focal length, date) from IFD0 + ExifIFD directly.
     * Best-effort: any malformed structure returns what it got so far.
     */
    fun fromTiff(head: ByteArray): PhotoInfo {
        val rows = ArrayList<Pair<String, String>>()
        try {
            if (head.size < 8) return PhotoInfo()
            val le = when {
                head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> true
                head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> false
                else -> return PhotoInfo()
            }
            fun u16(off: Int): Int {
                if (off + 2 > head.size) return 0
                val a = head[off].toInt() and 0xFF
                val b = head[off + 1].toInt() and 0xFF
                return if (le) a or (b shl 8) else (a shl 8) or b
            }
            fun u32(off: Int): Long {
                if (off + 4 > head.size) return 0
                val a = head[off].toInt() and 0xFF
                val b = head[off + 1].toInt() and 0xFF
                val c = head[off + 2].toInt() and 0xFF
                val d = head[off + 3].toInt() and 0xFF
                return if (le) {
                    (a or (b shl 8) or (c shl 16) or (d shl 24)).toLong() and 0xFFFFFFFFL
                } else {
                    ((a shl 24) or (b shl 16) or (c shl 8) or d).toLong() and 0xFFFFFFFFL
                }
            }
            // ORF header is "IIRO"/"MMOR"/"IIRS" + version, NOT standard
            // TIFF magic: accept it and read IFD0 offset from [4..8) like
            // normal TIFF. Plain TIFF (magic 42) works the same way.
            val sig = head.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            val isOrf = sig == "IIRO" || sig == "MMOR" || sig == "IIRS"
            if (!isOrf && u16(2) != 42) return PhotoInfo()
            fun ascii(off: Long, len: Long): String? {
                if (off < 0 || off + len > head.size || len <= 0 || len > 256) return null
                val end = (off until off + len).firstOrNull { head[it.toInt()] == 0.toByte() }
                    ?: (off + len)
                if (end <= off) return null
                return head.copyOfRange(off.toInt(), end.toInt())
                    .toString(Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
            }
            fun rational(off: Long): String? {
                if (off < 0 || off + 8 > head.size) return null
                val n = u32(off.toInt())
                val d = u32(off.toInt() + 4)
                if (d == 0L) return null
                return "$n/$d"
            }
            // tag -> (type, count, valueOffset). Inline values (<5 bytes)
            // live in the entry itself; ascii/rational need the offset.
            fun readIfd(ifdOff: Long, want: Set<Int>): Map<Int, String> {
                val out = HashMap<Int, String>()
                if (ifdOff < 0 || ifdOff + 2 > head.size) return out
                val n = u16(ifdOff.toInt()).coerceAtMost(200)
                var p = ifdOff.toInt() + 2
                repeat(n) {
                    if (p + 12 > head.size) return out
                    val tag = u16(p)
                    val type = u16(p + 2)
                    val count = u32(p + 4)
                    val valOff = u32(p + 8)
                    if (tag in want) {
                        when (type) {
                            2 -> ascii(valOff, count)?.let { out[tag] = it }
                            3 -> out[tag] = if (le) {
                                u16(p + 8).toString()
                            } else {
                                u16(p + 8).toString()
                            }
                            4 -> out[tag] = valOff.toString()
                            5 -> if (count == 1L) rational(valOff)?.let { out[tag] = it }
                            7 -> if (tag == 0x927C && count > 0) {
                                // MakerNote blob: skip (Olympus-specific).
                            }
                        }
                    }
                    p += 12
                }
                return out
            }
            val ifd0 = u32(4)
            // IFD0: model, datetime, ExifIFD pointer.
            val base = readIfd(ifd0, setOf(0x010F, 0x0110, 0x0132, 0x8769))
            base[0x0110]?.let { rows.add("カメラ" to it) }
            base[0x010F]?.let { if (rows.none { r -> r.first == "カメラ" }) rows.add("メーカー" to it) }
            base[0x0132]?.let { rows.add("撮影日時" to it) }
            // ExifIFD: exposure trio + lens.
            val exifOff = readIfd(ifd0, setOf(0x8769))[0x8769]?.toLongOrNull()
            if (exifOff != null && exifOff > 0) {
                val e = readIfd(
                    exifOff,
                    setOf(0x829A, 0x829D, 0x8827, 0x920A, 0x9201, 0x9202, 0x9204, 0xA002, 0xA003),
                )
                e[0x829D]?.let { rows.add("絞り" to "F${formatRational(it)}") }
                e[0x829A]?.let { rows.add("シャッター" to formatShutter(it)) }
                e[0x8827]?.let { rows.add("ISO" to it) }
                e[0x920A]?.let { rows.add("焦点距離" to "${formatRational(it)}mm") }
                e[0x9201]?.let { rows.add("露出補正" to "${formatRational(it)}EV") }
                e[0x9202]?.let { rows.add("測光" to meteringName(it)) }
                e[0x9204]?.let { rows.add("露出プログラム" to expProgName(it)) }
                val w = e[0xA002]
                val h = e[0xA003]
                if (w != null && h != null && w != "0" && h != "0") {
                    rows.add("サイズ" to "${w}×${h}")
                }
            }
        } catch (_: Exception) { /* partial rows stand */ }
        return PhotoInfo(rows)
    }

    // -- EXIF enum names ---------------------------------------------------------------

    /** "1/250" stays, "0.00625" -> "1/160", "2" -> "2s". */
    private fun formatShutter(v: String): String {
        val t = v.trim()
        if (t.contains('/')) return "${t}s"
        val f = t.toDoubleOrNull() ?: return "${t}s"
        if (f >= 1.0) return "${formatRational(t)}s"
        if (f <= 0.0) return t
        val denom = (1.0 / f).toInt()
        return "1/$denom"
    }

    /** "2500/100" -> "25", "1/250" stays, plain numbers pass through. */
    private fun formatRational(v: String): String {
        val t = v.trim()
        val m = Regex("""^(-?\d+)/(\d+)$""").matchEntire(t) ?: return t
        val num = m.groupValues[1].toLongOrNull() ?: return t
        val den = m.groupValues[2].toLongOrNull()?.takeIf { it != 0L } ?: return t
        if (num % den == 0L) return (num / den).toString()
        // Keep common photo fractions readable: 1/250, 28/10 -> 2.8.
        val f = num.toDouble() / den
        return if (num == 1L || den == 1L || f < 10) {
            // Trim trailing zeros: 2.80 -> 2.8.
            ("%.2f".format(f)).trimEnd('0').trimEnd('.')
        } else {
            t
        }
    }

    private fun wbName(v: String) = when (v.trim()) {
        "0" -> "オート"
        "1" -> "マニュアル"
        else -> v
    }

    private fun colorSpaceName(v: String) = when (v.trim()) {
        "1" -> "sRGB"
        "2" -> "Adobe RGB"
        "65535" -> "Uncalibrated"
        else -> v
    }

    private fun levelName(v: String) = when (v.trim()) {
        "0" -> "標準"
        "1" -> "弱め"
        "2" -> "強め"
        else -> v
    }

    private fun flashName(v: String): String {
        val n = v.trim().toIntOrNull() ?: return v
        return if (n and 1 != 0) "発光" else "非発光"
    }

    private fun meteringName(v: String) = when (v.trim()) {
        "0" -> "不明"
        "1" -> "平均"
        "2" -> "中央重点"
        "3" -> "スポット"
        "5" -> "マルチ"
        "6" -> "部分"
        else -> v
    }

    private fun expProgName(v: String) = when (v.trim()) {
        "0" -> "未定義"
        "1" -> "マニュアル"
        "2" -> "プログラム"
        "3" -> "絞り優先"
        "4" -> "シャッター優先"
        else -> v
    }

    private fun expModeName(v: String) = when (v.trim()) {
        "0" -> "自動"
        "1" -> "マニュアル"
        "2" -> "オートブラケット"
        else -> v
    }
}
