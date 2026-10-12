package com.omlight.cam

/**
 * OPC (Open Platform Camera) wire constants.
 * Spec: OPC Communication Protocol 1.0a (Olympus, 2015) + real-camera
 * behavior observed by the olympus-wifi OSS project.
 */
object Opc {
    /** Static camera IP in AP mode (spec §2). */
    const val HOST = "192.168.0.10"
    const val PORT = 80

    /**
     * Mandatory User-Agent. Shipping bodies (E-M5 II era onward) accept
     * ONLY the OI.Share UA and silently drop anything else — including
     * the spec's `OlympusCameraKit`. So OI.Share first, spec second.
     * Verified on a real E-M5 II (2026-10-12): OlympusCameraKit gets
     * zero bytes back, `OI.Share v2` answers instantly.
     */
    const val UA_DEFAULT = "OI.Share v2"
    const val UA_SPEC = "OlympusCameraKit"

    fun baseUrl(host: String = HOST) = "http://$host/"
}

/** One DCF file or directory entry from `get_imglist.cgi` (spec §6, text/plain). */
data class CamEntry(
    /** Full path, e.g. `/DCIM/100OLYMP/P1010042.JPG`. */
    val path: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    /** DCF date/time decoded to epoch millis (local, no tz), 0 if unparseable. */
    val takenAtMillis: Long,
) {
    val name: String get() = path.substringAfterLast('/')
    val isMovie: Boolean get() {
        val ext = name.substringAfterLast('.', "").uppercase()
        return ext == "MOV" || ext == "AVI" || ext == "MP4"
    }
    val isRaw: Boolean get() = name.substringAfterLast('.', "").uppercase() == "ORF"
}

/** Camera identity from `get_caminfo.cgi` / `get_commandlist.cgi`. */
data class CameraInfo(
    val model: String,
    val maker: String = "",
    /** Raw command -> allowed args tree, for capability checks. Empty = unknown. */
    val commands: Map<String, Set<String>> = emptyMap(),
    /** Structured capability document (preferred over [commands]). */
    val caps: CapabilityDoc = CapabilityDoc(),
) {
    /** True when the camera advertises the command (or when unknown — try anyway). */
    fun supports(cmd: String): Boolean = commands.isEmpty() || commands.containsKey(cmd)

    fun supportsArg(cmd: String, arg: String, value: String): Boolean {
        val args = commands[cmd] ?: return true
        return args.isEmpty() || args.contains("$arg=$value")
    }
}

/**
 * Wire dialect: which command spellings this body speaks.
 * Detected from connectmode + capabilities, never from the model name.
 */
enum class Dialect {
    /** OI.Share private bodies (E-M5 II verified): starttake, ctrlzoom, … */
    PRIVATE,
    /** AIR A01-era draft: newstarttake, newctrlzoom, switch_cameramode. */
    AIR,
    /** No command list: probe both spellings per call. */
    UNKNOWN,
}

data class CamProp(
    val name: String,
    val value: String,
    val writable: Boolean,
    val values: List<String> = emptyList(),
)

/** Minimal camera state from `get_state.cgi`. */
data class CameraState(
    val cardMounted: Boolean,
    val cardFreeBytes: Long,
    val remainShots: Int,
)
