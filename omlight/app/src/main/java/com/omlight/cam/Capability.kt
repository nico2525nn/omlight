package com.omlight.cam

/**
 * Structured camera capabilities parsed from `get_commandlist.cgi`.
 *
 * Real bodies nest `cmd1/param1/cmd2/param2/...` (E-M5 II verified);
 * flattening that to `arg=value` strings loses WHICH value enables a
 * nested argument (e.g. lvqty exists only under mode=rec). This tree
 * keeps the parent chain so dialect code asks precise questions.
 *
 * Example — switch_cammode:
 * ```
 * CommandCaps(
 *   method = GET,
 *   args = [ ArgTree("mode", values=[
 *     Value("rec", nested=[ ArgTree("lvqty", values=[
 *       Value("0320x0240"), Value("0640x0480"), … ]) ]) ,
 *     Value("play"), Value("shutter") ]) ]
 * )
 * ```
 */
data class CapabilityDoc(
    /** Protocol version string from `<version>`, "" when absent. */
    val version: String = "",
    /** `<support func="…">` flags: web/remote/gps/release/… */
    val support: Set<String> = emptySet(),
    /** command name (no .cgi) -> capabilities. */
    val commands: Map<String, CommandCaps> = emptyMap(),
) {
    fun supports(cmd: String): Boolean = commands.containsKey(cmd)

    /** Top-level "arg=value" pairs (shallow, any depth collapsed). */
    fun pairs(cmd: String): Set<String> {
        val out = LinkedHashSet<String>()
        commands[cmd]?.args?.forEach { it.collectPairs(out) }
        return out
    }

    fun supportsArg(cmd: String, arg: String, value: String): Boolean =
        pairs(cmd).contains("$arg=$value")

    /**
     * Values of [nestedArg] valid under [arg]=[value] in [cmd].
     * E.g. lvqtySizes("switch_cammode") under mode=rec.
     */
    fun nestedValues(cmd: String, arg: String, value: String, nestedArg: String): List<String> {
        val out = ArrayList<String>()
        commands[cmd]?.args?.forEach { it.collectNested(arg, value, nestedArg, out) }
        return out
    }

    /** All values accepted for top-level [arg] in [cmd]. */
    fun argValues(cmd: String, arg: String): List<String> {
        val out = ArrayList<String>()
        commands[cmd]?.args?.forEach {
            if (it.name == arg) out.addAll(it.values.map { v -> v.value })
        }
        return out.distinct()
    }
}

enum class HttpMethod { GET, POST }

data class CommandCaps(
    val method: HttpMethod = HttpMethod.GET,
    val args: List<ArgTree> = emptyList(),
)

data class ArgTree(
    val name: String,
    val values: List<Value> = emptyList(),
) {
    fun collectPairsPublic(out: MutableSet<String>) = collectPairs(out)

    internal fun collectPairs(out: MutableSet<String>) {
        for (v in values) {
            if (name.isNotEmpty() && v.value.isNotEmpty()) {
                out.add("$name=${v.value}")
            }
            v.nested.forEach { it.collectPairs(out) }
        }
        // Bare arg with no values (e.g. <cmd1 name="DIR"/>) still counts
        // as present for supportsArg(cmd, arg, *) checks via name match.
    }

    internal fun collectNested(
        arg: String,
        value: String,
        nestedArg: String,
        out: MutableList<String>,
    ) {
        for (v in values) {
            if (name == arg && v.value == value) {
                v.nested.forEach { n ->
                    if (n.name == nestedArg) out.addAll(n.values.map { it.value })
                }
            }
            v.nested.forEach { it.collectNested(arg, value, nestedArg, out) }
        }
    }
}

data class Value(
    val value: String,
    val nested: List<ArgTree> = emptyList(),
)
