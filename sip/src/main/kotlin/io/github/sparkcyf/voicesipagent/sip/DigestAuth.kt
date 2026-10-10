package io.github.sparkcyf.voicesipagent.sip

import java.security.MessageDigest
import java.security.SecureRandom

/** RFC 2617 / 3261 digest (MD5, qop=auth or none). */
object DigestAuth {
    private val random = SecureRandom()

    data class Challenge(val params: Map<String, String>) {
        val realm get() = params["realm"].orEmpty()
        val nonce get() = params["nonce"].orEmpty()
        val opaque get() = params["opaque"]
        val algorithm get() = params["algorithm"] ?: "MD5"
        val qopAuth get() = params["qop"]?.split(',')?.any { it.trim().equals("auth", ignoreCase = true) } == true
    }

    fun parseChallenge(header: String): Challenge? {
        val t = header.trim()
        if (!t.regionMatches(0, "Digest", 0, 6, ignoreCase = true)) return null
        val out = HashMap<String, String>()
        for (part in SipHeaders.splitList(t.substring(6))) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            out[part.substring(0, eq).trim().lowercase()] = part.substring(eq + 1).trim().trim('"')
        }
        return Challenge(out)
    }

    /** Builds the Authorization / Proxy-Authorization value. [nc] is the nonce count for this nonce. */
    fun authorize(
        challenge: Challenge,
        method: String,
        uri: String,
        username: String,
        password: String,
        nc: Int = 1,
        cnonce: String = newCnonce(),
    ): String {
        require(challenge.algorithm.equals("MD5", ignoreCase = true)) { "unsupported digest algorithm ${challenge.algorithm}" }
        val ha1 = md5("$username:${challenge.realm}:$password")
        val ha2 = md5("$method:$uri")
        val sb = StringBuilder("Digest username=\"").append(username)
            .append("\", realm=\"").append(challenge.realm)
            .append("\", nonce=\"").append(challenge.nonce)
            .append("\", uri=\"").append(uri).append('"')
        if (challenge.qopAuth) {
            val ncHex = "%08x".format(nc)
            val resp = md5("$ha1:${challenge.nonce}:$ncHex:$cnonce:auth:$ha2")
            sb.append(", response=\"").append(resp).append("\", qop=auth, nc=").append(ncHex)
                .append(", cnonce=\"").append(cnonce).append('"')
        } else {
            sb.append(", response=\"").append(md5("$ha1:${challenge.nonce}:$ha2")).append('"')
        }
        sb.append(", algorithm=MD5")
        challenge.opaque?.let { sb.append(", opaque=\"").append(it).append('"') }
        return sb.toString()
    }

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun newCnonce(): String = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
