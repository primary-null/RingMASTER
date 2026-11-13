package local.ringleader.core

/** Desktop must check paste length before copying and file byte size before decoding. */
const val CREATE_SSH_KEYS_MAX_CHARS = 65536
const val CREATE_SSH_KEYS_MAX_FILE_BYTES = 65536
const val CREATE_SSH_KEY_LINE_MAX_CHARS = 16384
const val CREATE_PASSWORD_MAX_CHARS = 1024
const val CREATE_FORM_MAX_BYTES = 1048576

private val sshTypes = listOf("ssh-rsa", "ssh-dss", "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com")

/** Indexed parsing only: neither options, base64, comments nor decoded fields become Strings. */
internal fun validSshLine(text: CharSequence, start: Int, end: Int): Boolean {
    var i = start
    fun spaces() { while (i < end && (text[i] == ' ' || text[i] == '\t' || text[i] == '\r')) i++ }
    spaces(); if (i == end) return true
    if ((i until end).any { text[it].isISOControl() && text[it] !in "\t\r" }) return false
    fun token(): IntRange? {
        val a = i; var quoted = false
        while (i < end) {
            val c = text[i]
            if (c == '\\' && quoted) { i += 2; if (i > end) return null; continue }
            if (c == '"') quoted = !quoted
            if (!quoted && c.isWhitespace()) break
            i++
        }
        return if (quoted || i == a) null else a until i
    }
    fun type(r: IntRange): String? = sshTypes.firstOrNull { t -> t.length == r.count() && t.indices.all { text[r.first + it] == t[it] } }
    val first = token() ?: return false
    var kind = type(first)
    if (kind == null) { spaces(); kind = type(token() ?: return false) ?: return false }
    spaces(); val encoded = token() ?: return false
    if (encoded.count() > CREATE_SSH_KEY_LINE_MAX_CHARS || encoded.count() % 4 == 1) return false
    val raw = ByteArray(encoded.count())
    var n = 0; var acc = 0; var bits = 0; var pads = 0
    try {
        for (p in encoded) {
            val c = text[p]
            if (c == '=') { pads++; if (pads > 2) return false; continue }
            if (pads > 0) return false
            val v = when(c) { in 'A'..'Z' -> c - 'A'; in 'a'..'z' -> c - 'a' + 26; in '0'..'9' -> c - '0' + 52; '+' -> 62; '/' -> 63; else -> return false }
            acc = (acc shl 6) or v; bits += 6
            if (bits >= 8) { bits -= 8; raw[n++] = (acc shr bits).toByte() }
        }
        if (bits >= 6 || (bits > 0 && acc and ((1 shl bits) - 1) != 0) || (pads > 0 && (encoded.count() % 4 != 0 || pads != bits / 2))) return false
        var pos = 0
        fun field(): IntRange? {
            if (pos + 4 > n) return null
            var len = 0L; repeat(4) { len = (len shl 8) or (raw[pos++].toLong() and 255) }
            if (len > n - pos) return null
            val r = pos until pos + len.toInt(); pos += len.toInt(); return r
        }
        fun literal(r: IntRange?, value: String): Boolean = r != null && r.count() == value.length && value.indices.all { raw[r.first + it].toInt() == value[it].code }
        fun positive(min: Int = 1, max: Int = 1025): Boolean {
            val r = field() ?: return false
            return r.count() in min..max && raw[r.first].toInt() >= 0 && r.any { raw[it].toInt() != 0 }
        }
        if (!literal(field(), kind)) return false
        val valid = when {
            kind == "ssh-rsa" -> positive(1, 8) && positive(128, 2049)
            kind == "ssh-dss" -> positive(128, 129) && positive(20, 21) && positive(1, 129) && positive(1, 129)
            kind.contains("ed25519") -> field()?.count() == 32
            else -> {
                val curve = when { kind.contains("nistp256") -> "nistp256"; kind.contains("nistp384") -> "nistp384"; else -> "nistp521" }
                val count = when(curve) { "nistp256" -> 65; "nistp384" -> 97; else -> 133 }
                literal(field(), curve) && field()?.let { point ->
                    if (point.count() != count || raw[point.first] != 4.toByte()) false
                    else {
                        val spec = java.security.AlgorithmParameters.getInstance("EC").apply {
                            init(java.security.spec.ECGenParameterSpec(when(curve) { "nistp256" -> "secp256r1"; "nistp384" -> "secp384r1"; else -> "secp521r1" }))
                        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
                        val width = (count - 1) / 2
                        val xb = raw.copyOfRange(point.first + 1, point.first + 1 + width)
                        val yb = raw.copyOfRange(point.first + 1 + width, point.last + 1)
                        try {
                            val x = java.math.BigInteger(1, xb); val y = java.math.BigInteger(1, yb)
                            val prime = (spec.curve.field as java.security.spec.ECFieldFp).p
                            x < prime && y < prime && y.multiply(y).mod(prime) == x.multiply(x).multiply(x).add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(prime)
                        } finally { xb.fill(0); yb.fill(0) }
                    }
                } == true
            }
        }
        if (!valid) return false
        if (kind.startsWith("sk-") && field()?.isEmpty() != false) return false
        return pos == n
    } finally { raw.fill(0) }
}
