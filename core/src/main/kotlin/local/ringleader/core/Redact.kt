package local.ringleader.core

import java.net.URI
import java.net.URLDecoder

/** Diagnostic boundary: structured secret fields and bodies are discarded before storage. */
object Redact {
    const val MASK = "<redacted>"
    private val sensitive = Regex("(?i)authorization|cookie|csrf|password|passwd|totp|otp|ticket|secret|token|tfa-challenge|private.?key|credential")
    private val headers = Regex("""(?im)(authorization|cookie|set-cookie|csrfpreventiontoken)["']?\s*[:=]\s*[^\r\n]+""")
    private val assignment = Regex("(?i)([\\\"']?(?:password|passwd|totp|otp|vncticket|ticket|token|secret|PVEAuthCookie|CSRFPreventionToken|tfa-challenge)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s&,;}]+)")
    private val apiToken = Regex("(?i)(PVEAPIToken|PDMAPIToken)=[^\\s,;\\\"']+")
    private val ticket = Regex("(?i)(?:PVESPICEPROXY|PVEVNC|PVNC|PTERM|PVE|PDM):[^\\s&\\\"'<>]+")
    fun key(name: String) = sensitive.containsMatchIn(name)
    fun text(value: String): String {
        val queries = Regex("([?&])([^=&\\s]+)=([^&\\s]*)").replace(value) { m ->
            val name=runCatching { URLDecoder.decode(m.groupValues[2], "UTF-8") }.getOrDefault(m.groupValues[2])
            if(key(name) || name.equals("key",true)) "${m.groupValues[1]}${m.groupValues[2]}=$MASK" else m.value
        }
        var s = headers.replace(queries) { "${it.groupValues[1]}: $MASK" }
        s = apiToken.replace(s) { "${it.groupValues[1]}=$MASK" }
        s = assignment.replace(s) { "${it.groupValues[1]}$MASK" }
        s = ticket.replace(s, MASK)
        // URL user-info is never diagnostic data.
        return s.replace(Regex("(?i)(https?|wss?)://[^/\\s@]+@"), "$1://$MASK@")
    }
    fun url(value: String): String = runCatching {
        val u = URI(value)
        val query = u.rawQuery?.split('&')?.joinToString("&") { part ->
            val k = part.substringBefore('=')
            if (key(URLDecoder.decode(k, "UTF-8")) || k.equals("key", true)) "$k=$MASK" else text(part)
        }
        buildString {
            u.scheme?.let { append(it).append("://") }
            u.rawAuthority?.let { append(it.substringAfterLast('@')) }
            append(u.rawPath.orEmpty())
            query?.let { append('?').append(it) }
            // Fragments can carry login keys and are never needed for request diagnosis.
        }
    }.getOrElse { text(value.substringBefore('#')) }
    /** Opaque plugin commands and scripts may carry unlabelled credentials; never copy their arguments. */
    fun commandDetails(command: List<String>): Map<String,String> {
        val program=command.firstOrNull().orEmpty().substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe").lowercase()
        val safe=program in setOf("ping","tracert","traceroute","iperf3")
        var hideNext=false
        val args=if(!safe) "<opaque arguments omitted>" else command.drop(1).joinToString(" ") { arg ->
            val hide=hideNext
            hideNext=key(arg.substringBefore('=')) || arg in setOf("-p","-U","-A")
            if(hide) MASK else text(arg)
        }
        return mapOf("argumentCount" to (command.size-1).coerceAtLeast(0).toString(),"arguments" to args)
    }
    fun details(values: Map<String, String>): Map<String, String> = values.entries.associate { (k,v) ->
        text(k).take(100) to when {
            key(k) || k.lowercase().contains("body") || k.lowercase().contains("payload") -> MASK
            k.equals("url", true) -> url(v)
            else -> text(v).take(2000)
        }
    }
}
