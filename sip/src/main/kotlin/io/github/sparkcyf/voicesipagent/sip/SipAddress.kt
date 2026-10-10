package io.github.sparkcyf.voicesipagent.sip

/** Minimal sip: URI: sip:user@host:port;params */
data class SipUri(
    val user: String?,
    val host: String,
    val port: Int? = null,
    val params: Map<String, String?> = emptyMap(),
    val scheme: String = "sip",
) {
    override fun toString(): String {
        val sb = StringBuilder(scheme).append(':')
        if (!user.isNullOrEmpty()) sb.append(user).append('@')
        sb.append(host)
        if (port != null) sb.append(':').append(port)
        params.forEach { (k, v) -> sb.append(';').append(k); if (v != null) sb.append('=').append(v) }
        return sb.toString()
    }

    companion object {
        fun parse(s: String): SipUri? {
            val t = s.trim()
            val colon = t.indexOf(':')
            if (colon <= 0) return null
            val scheme = t.substring(0, colon).lowercase()
            if (scheme != "sip" && scheme != "sips" && scheme != "tel") return null
            var rest = t.substring(colon + 1).substringBefore('?')
            val paramPart = rest.substringAfter(';', "")
            rest = rest.substringBefore(';')
            val user = if (rest.contains('@')) rest.substringBeforeLast('@') else null
            val hostPort = rest.substringAfterLast('@')
            val (host, port) = if (hostPort.startsWith("[")) {
                val h = hostPort.substringBefore(']') + "]"
                h to hostPort.substringAfter("]:", "").toIntOrNull()
            } else {
                hostPort.substringBefore(':') to hostPort.substringAfter(':', "").toIntOrNull()
            }
            return SipUri(user, host, port, parseParams(paramPart), scheme)
        }
    }
}

/** `"Display" <uri>;tag=x` or `uri;tag=x` */
data class NameAddr(
    val displayName: String?,
    val uri: String,
    val params: Map<String, String?> = emptyMap(),
) {
    val tag: String? get() = params["tag"]
    val sipUri: SipUri? get() = SipUri.parse(uri)

    fun withTag(tag: String): NameAddr = copy(params = params + ("tag" to tag))

    override fun toString(): String {
        val sb = StringBuilder()
        if (!displayName.isNullOrEmpty()) sb.append('"').append(displayName.replace("\"", "")).append("\" ")
        sb.append('<').append(uri).append('>')
        params.forEach { (k, v) -> sb.append(';').append(k); if (v != null) sb.append('=').append(v) }
        return sb.toString()
    }

    companion object {
        fun parse(s: String): NameAddr? {
            val t = s.trim()
            val lt = t.indexOf('<')
            return if (lt >= 0) {
                val gt = t.indexOf('>', lt)
                if (gt < 0) return null
                val display = t.substring(0, lt).trim().trim('"').takeIf { it.isNotEmpty() }
                NameAddr(display, t.substring(lt + 1, gt), parseParams(t.substring(gt + 1).trimStart(';')))
            } else {
                // addr-spec form: header params follow the URI, so URI params cannot be told apart.
                NameAddr(null, t.substringBefore(';'), parseParams(t.substringAfter(';', "")))
            }
        }
    }
}

data class Via(
    val transport: String,
    val host: String,
    val port: Int?,
    val params: Map<String, String?>,
) {
    val branch: String? get() = params["branch"]

    companion object {
        fun parse(s: String): Via? {
            val t = s.trim()
            val sp = t.indexOf(' ')
            if (sp < 0) return null
            val proto = t.substring(0, sp)
            val rest = t.substring(sp + 1).trim()
            val sentBy = rest.substringBefore(';').trim()
            val host = sentBy.substringBefore(':')
            val port = sentBy.substringAfter(':', "").toIntOrNull()
            return Via(proto.substringAfterLast('/'), host, port, parseParams(rest.substringAfter(';', "")))
        }
    }
}

internal fun parseParams(s: String): Map<String, String?> {
    if (s.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, String?>()
    for (p in s.split(';')) {
        val kv = p.trim()
        if (kv.isEmpty()) continue
        val eq = kv.indexOf('=')
        if (eq < 0) out[kv.lowercase()] = null else out[kv.substring(0, eq).trim().lowercase()] = kv.substring(eq + 1).trim()
    }
    return out
}
