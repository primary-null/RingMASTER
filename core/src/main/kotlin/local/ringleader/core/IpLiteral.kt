package local.ringleader.core

/**
 * Strict IP address literals. Nothing here looks a name up: every function reads text and answers from the text alone,
 * so it is safe to call from a resolver, a proxy selector or an input check.
 *
 * Only ASCII digits and hex letters count (Kotlin's `toIntOrNull` also reads a leading `+` and non-ASCII digits).
 */
object IpLiteral {
    /** Four bytes for strict dotted-decimal IPv4 (`a.b.c.d`, each 0 to 255, no sign, no leading zero), else null. */
    fun parseV4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val n = part.toInt()
            if (n > 255) return null
            out[i] = n.toByte()
        }
        return out
    }

    /** True for text made only of digits and dots that is not strictly an IPv4 address (`1.2.3`, `192.0.002.010`, `999.1.1.1`). */
    fun looksLikeBrokenV4(text: String): Boolean =
        text.isNotEmpty() && text.all { it in '0'..'9' || it == '.' } && '.' in text && parseV4(text) == null

    /**
     * Sixteen bytes for an IPv6 literal, else null. No brackets and no zone. Accepts every form RFC 4291 allows: eight groups,
     * one `::` for one or more zero groups (so `1:2:3:4:5:6:7::` and `::2:3:4:5:6:7:8` are valid), 1 to 4 hex digits per group
     * in either case, and a strict dotted-decimal IPv4 address in place of the last two groups.
     */
    fun parseV6(text: String): ByteArray? {
        if (text.length < 2 || text.length > 45 || ':' !in text) return null
        if (text.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' && it != '.' }) return null
        if (text.startsWith(":") && !text.startsWith("::")) return null
        if (text.endsWith(":") && !text.endsWith("::")) return null
        val doubled = text.indexOf("::")
        if (doubled >= 0 && text.indexOf("::", doubled + 1) >= 0) return null
        val head = if (doubled >= 0) text.substring(0, doubled) else text
        val tail = if (doubled >= 0) text.substring(doubled + 2) else ""
        val headGroups = groupsOf(head, allowV4Tail = doubled < 0) ?: return null
        val tailGroups = if (doubled >= 0) groupsOf(tail, allowV4Tail = true) ?: return null else emptyList()
        val total = headGroups.size + tailGroups.size
        if (doubled < 0 && total != 8) return null
        if (doubled >= 0 && total > 7) return null
        val groups = IntArray(8)
        headGroups.forEachIndexed { i, g -> groups[i] = g }
        tailGroups.forEachIndexed { i, g -> groups[8 - tailGroups.size + i] = g }
        val out = ByteArray(16)
        for (i in 0 until 8) { out[2 * i] = (groups[i] shr 8).toByte(); out[2 * i + 1] = groups[i].toByte() }
        return out
    }

    /** The 16-bit groups of a colon-separated run. An IPv4 tail counts as two groups and must be last. Null when malformed. */
    private fun groupsOf(run: String, allowV4Tail: Boolean): List<Int>? {
        if (run.isEmpty()) return emptyList()
        val parts = run.split(':')
        val out = ArrayList<Int>(8)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) return null
            if ('.' in part) {
                if (!allowV4Tail || i != parts.lastIndex) return null
                val v4 = parseV4(part) ?: return null
                out += ((v4[0].toInt() and 255) shl 8) or (v4[1].toInt() and 255)
                out += ((v4[2].toInt() and 255) shl 8) or (v4[3].toInt() and 255)
            } else {
                if (part.length > 4) return null
                out += part.toInt(16)
            }
        }
        return out
    }

    /** `a.b.c.d` for four bytes. */
    fun formatV4(bytes: ByteArray): String = bytes.joinToString(".") { (it.toInt() and 255).toString() }

    /**
     * RFC 5952 text for sixteen bytes: lower case, no leading zeros, the longest run of two or more zero groups becomes `::`
     * (the first when two runs tie), and an IPv4-mapped address (`::ffff:a.b.c.d`) keeps its dotted tail as RFC 5952 section 5 allows.
     */
    fun formatV6(bytes: ByteArray): String {
        require(bytes.size == 16)
        val groups = IntArray(8) { ((bytes[2 * it].toInt() and 255) shl 8) or (bytes[2 * it + 1].toInt() and 255) }
        if (groups.take(5).all { it == 0 } && groups[5] == 0xffff) {
            return "::ffff:" + formatV4(bytes.copyOfRange(12, 16))
        }
        var bestStart = -1; var bestLength = 0
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) { i++; continue }
            var j = i
            while (j < 8 && groups[j] == 0) j++
            if (j - i > bestLength) { bestStart = i; bestLength = j - i }
            i = j
        }
        if (bestLength < 2) return groups.joinToString(":") { it.toString(16) }
        val left = groups.take(bestStart).joinToString(":") { it.toString(16) }
        val right = groups.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
        return "$left::$right"
    }

    /** True when [text] (no brackets, no zone) is an IPv4 or IPv6 literal. */
    fun isLiteral(text: String): Boolean = parseV4(text) != null || parseV6(text) != null

    /** [text] without one pair of surrounding square brackets, as written in a URL or typed beside a port. */
    fun unbracket(text: String): String = if (text.length >= 2 && text.startsWith("[") && text.endsWith("]")) text.substring(1, text.length - 1) else text
}
