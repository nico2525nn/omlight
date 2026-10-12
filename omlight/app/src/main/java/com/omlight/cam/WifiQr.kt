package com.omlight.cam

/**
 * Wi-Fi QR payload parsing.
 *
 * Olympus/OM bodies show a standard `WIFI:T:WPA;S:<ssid>;P:<pass>;;` QR
 * (same encoding phone cameras already understand). We don't bundle a
 * scanner — the user scans with the system camera once, then either
 * joins the AP in system settings (usual flow) or pastes the payload
 * here for in-app join. Keeps the APK lean (no CameraX/MLKit).
 */
object WifiQr {

    data class Cred(val ssid: String, val pass: String, val hidden: Boolean = false)

    /** Parse `WIFI:...` payloads. Returns null when not recognized. */
    fun parse(raw: String): Cred? {
        val t = raw.trim()
        if (!t.startsWith("WIFI:")) return null
        // Unescape \; \" \, \: per the WIFI: scheme.
        fun unesc(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }
        var ssid: String? = null
        var pass = ""
        var hidden = false
        // Split on unescaped ';'.
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        var i = 5
        while (i < t.length) {
            val c = t[i]
            if (c == '\\' && i + 1 < t.length) {
                cur.append(c).append(t[i + 1])
                i += 2
            } else if (c == ';') {
                parts.add(cur.toString())
                cur.clear()
                i++
            } else {
                cur.append(c)
                i++
            }
        }
        for (p in parts) {
            when {
                p.startsWith("S:") -> ssid = unesc(p.substring(2))
                p.startsWith("P:") -> pass = unesc(p.substring(2))
                p.startsWith("H:") -> hidden = p.substring(2).equals("true", true)
            }
        }
        if (ssid.isNullOrEmpty()) return null
        return Cred(ssid, pass, hidden)
    }

    /** Heuristic: does this look like a camera AP SSID? */
    fun looksLikeCamera(ssid: String): Boolean {
        val u = ssid.uppercase()
        return u.startsWith("OLYMPUS") || u.startsWith("OM-") ||
            u.startsWith("OM_") || u.startsWith("E-M") ||
            u.contains("OLYMPUS") || u.contains("OM SYSTEM")
    }
}
