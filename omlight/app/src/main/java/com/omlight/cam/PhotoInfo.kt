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
            val f = tag(ExifInterface.TAG_F_NUMBER)?.let { "F$it" }
            val ss = tag(ExifInterface.TAG_EXPOSURE_TIME)?.let { "${it}s" }
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

    // -- EXIF enum names ---------------------------------------------------------------

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
