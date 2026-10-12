package com.omlight.dl

/**
 * Magic-byte sniffing for camera file types. Guards against saving an
 * HTML/XML error page as a photo. Checks only the head bytes we already
 * buffered — no extra I/O.
 */
object FileSniff {

    enum class Kind { JPEG, ORF, MOV, AVI, MP4, UNKNOWN }

    fun kindOf(head: ByteArray): Kind {
        if (head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() &&
            head[2] == 0xFF.toByte()
        ) return Kind.JPEG
        // ORF (TIFF-based): II*\0 or MM\0*.
        if (head.size >= 4) {
            val le = head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte()
            val be = head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte()
            if (le && head[2] == 42.toByte() && head[3] == 0.toByte()) return Kind.ORF
            if (be && head[2] == 0.toByte() && head[3] == 42.toByte()) return Kind.ORF
        }
        // MOV/MP4: ....ftyp.
        if (head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) {
            // Brand check is overkill; treat ftyp as movie.
            return Kind.MOV
        }
        // AVI: RIFF....AVI .
        if (head.size >= 12 && head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'A'.code.toByte() && head[9] == 'V'.code.toByte() &&
            head[10] == 'I'.code.toByte()
        ) return Kind.AVI
        return Kind.UNKNOWN
    }

    /** True when the sniffed kind matches the file extension. */
    fun matches(name: String, head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        val kind = kindOf(head)
        if (kind == Kind.UNKNOWN) return false
        return when (name.substringAfterLast('.', "").uppercase()) {
            "JPG", "JPEG" -> kind == Kind.JPEG
            "ORF" -> kind == Kind.ORF
            "MOV", "MP4", "M4V" -> kind == Kind.MOV
            "AVI" -> kind == Kind.AVI
            // Unknown extension: accept any recognized image/movie.
            else -> true
        }
    }

    /**
     * True when the body looks like an error page instead of media.
     * Cheap pre-check before the stricter [matches].
     */
    fun looksLikeError(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        val s = String(head, 0, minOf(head.size, 256), Charsets.UTF_8).trimStart()
        return s.startsWith("<") || s.startsWith("{") ||
            s.startsWith("Error", ignoreCase = true)
    }
}
