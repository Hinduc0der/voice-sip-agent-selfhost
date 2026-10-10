package io.github.sparkcyf.voicesipagent.sip.media

/** The single audio stream we care about in an SDP body. */
data class SdpAudio(
    val address: String,
    val port: Int,
    val payloadTypes: List<Int>,
    val rtpmap: Map<Int, String>,
    val direction: String,
) {
    /** First payload type in the peer's preference order that we can decode. */
    fun firstSupportedCodec(): AudioCodec? {
        for (pt in payloadTypes) {
            val name = rtpmap[pt]?.substringBefore('/')
            val codec = if (name != null) AudioCodec.byName(name) else AudioCodec.entries.firstOrNull { it.payloadType == pt }
            if (codec != null && (rtpmap[pt] == null || rtpmap[pt]!!.contains("/8000"))) return codec
        }
        return null
    }

    fun payloadFor(codec: AudioCodec): Int =
        payloadTypes.firstOrNull { pt -> rtpmap[pt]?.substringBefore('/')?.equals(codec.encodingName, true) ?: (pt == codec.payloadType) }
            ?: codec.payloadType

    val telephoneEventPt: Int?
        get() = rtpmap.entries.firstOrNull { it.value.startsWith("telephone-event/8000", ignoreCase = true) && it.key in payloadTypes }?.key

    /** True if the peer is not going to send us audio / doesn't want ours (hold). */
    val isOnHold: Boolean get() = address == "0.0.0.0" || direction == "sendonly" || direction == "inactive"
}

object Sdp {
    const val DEFAULT_TE_PT = 101

    fun build(
        sessionId: Long,
        version: Long,
        localIp: String,
        rtpPort: Int,
        codecs: List<Pair<Int, AudioCodec>>,
        telephoneEventPt: Int?,
        direction: String = "sendrecv",
    ): String {
        val pts = codecs.map { it.first } + listOfNotNull(telephoneEventPt)
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=- $sessionId $version IN IP4 $localIp\r\n")
        sb.append("s=voice-sip-agent\r\n")
        sb.append("c=IN IP4 $localIp\r\n")
        sb.append("t=0 0\r\n")
        sb.append("m=audio $rtpPort RTP/AVP ${pts.joinToString(" ")}\r\n")
        for ((pt, codec) in codecs) sb.append("a=rtpmap:$pt ${codec.encodingName}/8000\r\n")
        if (telephoneEventPt != null) {
            sb.append("a=rtpmap:$telephoneEventPt telephone-event/8000\r\n")
            sb.append("a=fmtp:$telephoneEventPt 0-16\r\n")
        }
        sb.append("a=ptime:20\r\n")
        sb.append("a=$direction\r\n")
        return sb.toString()
    }

    fun parse(text: String): SdpAudio? {
        var sessionConn: String? = null
        var mediaConn: String? = null
        var port = -1
        var pts = emptyList<Int>()
        val rtpmap = HashMap<Int, String>()
        var direction = "sendrecv"
        var inAudio = false
        var seenAudio = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.length < 2 || line[1] != '=') continue
            val v = line.substring(2)
            when (line[0]) {
                'm' -> {
                    if (seenAudio) { inAudio = false; continue }
                    val parts = v.split(' ')
                    inAudio = parts[0] == "audio" && parts.size >= 4 && parts[1].substringBefore('/').toIntOrNull() != null
                    if (inAudio) {
                        seenAudio = true
                        port = parts[1].substringBefore('/').toInt()
                        pts = parts.drop(3).mapNotNull { it.toIntOrNull() }
                    }
                }
                'c' -> {
                    val addr = v.split(' ').getOrNull(2)?.substringBefore('/')
                    if (inAudio) mediaConn = addr else if (!seenAudio) sessionConn = addr
                }
                'a' -> {
                    if (seenAudio && !inAudio) continue
                    when {
                        v.startsWith("rtpmap:") -> {
                            val pt = v.substring(7).substringBefore(' ').toIntOrNull()
                            if (pt != null) rtpmap[pt] = v.substringAfter(' ').trim()
                        }
                        v == "sendrecv" || v == "sendonly" || v == "recvonly" || v == "inactive" -> direction = v
                    }
                }
            }
        }
        if (!seenAudio) return null
        val addr = mediaConn ?: sessionConn ?: return null
        return SdpAudio(addr, port, pts, rtpmap, if (port == 0) "inactive" else direction)
    }

    /** Direction to answer with, mirroring the offer. */
    fun answerDirection(offerDirection: String): String = when (offerDirection) {
        "sendonly" -> "recvonly"
        "recvonly" -> "sendonly"
        "inactive" -> "inactive"
        else -> "sendrecv"
    }
}
