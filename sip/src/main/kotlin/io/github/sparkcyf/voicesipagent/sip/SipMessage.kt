package io.github.sparkcyf.voicesipagent.sip

import java.nio.charset.StandardCharsets

/** Ordered, case-insensitive header list with SIP compact-form support. */
class SipHeaders {
    private val entries = ArrayList<Pair<String, String>>()

    fun add(name: String, value: String): SipHeaders {
        entries += canonical(name) to value
        return this
    }

    fun set(name: String, value: String): SipHeaders {
        remove(name)
        return add(name, value)
    }

    fun remove(name: String) {
        val n = canonical(name)
        entries.removeAll { it.first.equals(n, ignoreCase = true) }
    }

    operator fun get(name: String): String? {
        val n = canonical(name)
        return entries.firstOrNull { it.first.equals(n, ignoreCase = true) }?.second
    }

    /** All values, with comma-joined lists split for headers that allow it. */
    fun getAll(name: String): List<String> {
        val n = canonical(name)
        val raw = entries.filter { it.first.equals(n, ignoreCase = true) }.map { it.second }
        return if (n.lowercase() in LIST_HEADERS) raw.flatMap { splitList(it) } else raw
    }

    fun names(): List<String> = entries.map { it.first }

    fun copyFrom(other: SipHeaders, name: String) {
        other.entries.filter { it.first.equals(canonical(name), ignoreCase = true) }.forEach { entries += it }
    }

    internal fun forEach(action: (String, String) -> Unit) = entries.forEach { action(it.first, it.second) }

    companion object {
        private val COMPACT = mapOf(
            "v" to "Via", "f" to "From", "t" to "To", "i" to "Call-ID", "m" to "Contact",
            "l" to "Content-Length", "c" to "Content-Type", "k" to "Supported", "s" to "Subject",
            "e" to "Content-Encoding", "o" to "Event", "u" to "Allow-Events",
        )
        private val LIST_HEADERS = setOf("via", "route", "record-route", "contact", "allow", "supported", "require")

        fun canonical(name: String): String = COMPACT[name.lowercase()] ?: name

        /** Splits on commas that are outside quotes and angle brackets. */
        fun splitList(value: String): List<String> {
            val out = ArrayList<String>()
            var depth = 0
            var quoted = false
            val sb = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '\\' && quoted && i + 1 < value.length -> { sb.append(c).append(value[i + 1]); i += 2; continue }
                    c == '"' -> quoted = !quoted
                    !quoted && c == '<' -> depth++
                    !quoted && c == '>' -> depth--
                    !quoted && depth == 0 && c == ',' -> {
                        sb.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                        sb.setLength(0)
                        i++
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
            return out
        }
    }
}

sealed class SipMessage(val headers: SipHeaders, var body: ByteArray) {
    abstract val startLine: String

    val callId: String? get() = headers["Call-ID"]
    val cseq: CSeq? get() = headers["CSeq"]?.let(CSeq::parse)
    val from: NameAddr? get() = headers["From"]?.let(NameAddr::parse)
    val to: NameAddr? get() = headers["To"]?.let(NameAddr::parse)
    val topVia: Via? get() = headers.getAll("Via").firstOrNull()?.let(Via::parse)
    val bodyText: String get() = String(body, StandardCharsets.UTF_8)
    val contentType: String? get() = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase()

    fun encode(): ByteArray {
        val sb = StringBuilder()
        sb.append(startLine).append("\r\n")
        headers.forEach { n, v ->
            if (!n.equals("Content-Length", ignoreCase = true)) sb.append(n).append(": ").append(v).append("\r\n")
        }
        sb.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        val head = sb.toString().toByteArray(StandardCharsets.UTF_8)
        return head + body
    }

    override fun toString(): String = String(encode(), StandardCharsets.UTF_8)
}

class SipRequest(
    val method: String,
    var uri: String,
    headers: SipHeaders = SipHeaders(),
    body: ByteArray = ByteArray(0),
) : SipMessage(headers, body) {
    override val startLine: String get() = "$method $uri SIP/2.0"
}

class SipResponse(
    val code: Int,
    val reason: String,
    headers: SipHeaders = SipHeaders(),
    body: ByteArray = ByteArray(0),
) : SipMessage(headers, body) {
    override val startLine: String get() = "SIP/2.0 $code $reason"
    val isProvisional: Boolean get() = code in 100..199
    val isSuccess: Boolean get() = code in 200..299
}

data class CSeq(val seq: Long, val method: String) {
    override fun toString() = "$seq $method"

    companion object {
        fun parse(v: String): CSeq? {
            val parts = v.trim().split(Regex("\\s+"))
            if (parts.size != 2) return null
            return CSeq(parts[0].toLongOrNull() ?: return null, parts[1].uppercase())
        }
    }
}

object SipParser {
    /** Parses one UDP datagram. Returns null for keep-alives and garbage. */
    fun parse(data: ByteArray, length: Int = data.size): SipMessage? {
        val text = String(data, 0, length, StandardCharsets.UTF_8)
        if (text.isBlank()) return null
        val headerEnd = text.indexOf("\r\n\r\n").let { if (it >= 0) it to 4 else text.indexOf("\n\n") to 2 }
        val (end, sepLen) = headerEnd
        val headPart = if (end >= 0) text.substring(0, end) else text
        val lines = unfold(headPart.split(Regex("\r?\n")))
        if (lines.isEmpty()) return null
        val start = lines[0].trim()
        val headers = SipHeaders()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers.add(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
        }
        val body: ByteArray = if (end >= 0) {
            // Locate the body in bytes, not chars, so multi-byte header text cannot shift it.
            val headBytes = text.substring(0, end + sepLen).toByteArray(StandardCharsets.UTF_8).size
            val declared = headers["Content-Length"]?.trim()?.toIntOrNull()
            val available = length - headBytes
            val n = if (declared != null) minOf(declared, available) else available
            if (n > 0) data.copyOfRange(headBytes, headBytes + n) else ByteArray(0)
        } else {
            ByteArray(0)
        }
        return if (start.startsWith("SIP/2.0")) {
            val parts = start.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
            SipResponse(code, parts.getOrElse(2) { "" }, headers, body)
        } else {
            val parts = start.split(' ')
            if (parts.size < 3 || !parts[2].startsWith("SIP/2.0")) return null
            SipRequest(parts[0].uppercase(), parts[1], headers, body)
        }
    }

    private fun unfold(lines: List<String>): List<String> {
        val out = ArrayList<String>()
        for (l in lines) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + " " + l.trim()
            } else {
                out += l
            }
        }
        return out
    }
}
