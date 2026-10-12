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
     * Mandatory User-Agent (spec §3: camera rejects other values).
     * The 1.0a spec mandates `OlympusCameraKit`; shipping cameras also
     * accept the OI.Share UA. We default to the spec value and fall back
     * to the OI.Share UA once if the handshake fails.
     */
    const val UA_SPEC = "OlympusCameraKit"
    const val UA_OISHARE = "OI.Share v2"

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
) {
    /** True when the camera advertises the command (or when unknown — try anyway). */
    fun supports(cmd: String): Boolean = commands.isEmpty() || commands.containsKey(cmd)

    fun supportsArg(cmd: String, arg: String, value: String): Boolean {
        val args = commands[cmd] ?: return true
        return args.isEmpty() || args.contains("$arg=$value")
    }
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
