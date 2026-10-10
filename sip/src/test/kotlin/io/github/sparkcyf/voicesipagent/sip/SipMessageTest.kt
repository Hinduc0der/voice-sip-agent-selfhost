package io.github.sparkcyf.voicesipagent.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMessageTest {
    private val invite = (
        "INVITE sip:13800000000@192.168.0.253:5062 SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP 192.168.0.53:5066;rport;branch=z9hG4bKPjabc, SIP/2.0/UDP 10.0.0.1;branch=z9hG4bKx\r\n" +
            "From: \"pixel, voice\" <sip:302@192.168.0.53>;tag=f1\r\n" +
            "t: <sip:13800000000@192.168.0.53>\r\n" +
            "Contact: <sip:asterisk@192.168.0.53:5066>\r\n" +
            "Call-ID: abc123\r\n" +
            "CSeq: 7 INVITE\r\n" +
            "Content-Type: application/sdp\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "v=0\r\nEXTRA"
        ).toByteArray()

    @Test
    fun parsesRequest() {
        val req = SipParser.parse(invite) as SipRequest
        assertEquals("INVITE", req.method)
        assertEquals("13800000000", SipUri.parse(req.uri)!!.user)
        assertEquals(2, req.headers.getAll("Via").size)
        assertEquals("z9hG4bKPjabc", req.topVia!!.branch)
        assertEquals("f1", req.from!!.tag)
        assertEquals("pixel, voice", req.from!!.displayName)
        assertNull(req.to!!.tag)
        assertEquals(CSeq(7, "INVITE"), req.cseq)
        assertEquals("v=0\r\n", req.bodyText) // Content-Length wins over trailing bytes
        assertEquals("application/sdp", req.contentType)
    }

    @Test
    fun roundTrips() {
        val req = SipParser.parse(invite) as SipRequest
        val again = SipParser.parse(req.encode()) as SipRequest
        assertEquals(req.uri, again.uri)
        assertEquals(req.callId, again.callId)
        assertEquals(req.bodyText, again.bodyText)
    }

    @Test
    fun parsesResponseAndContactParams() {
        val raw = "SIP/2.0 200 OK\r\nContact: <sip:401@10.0.0.2:5062>;expires=120\r\nCSeq: 2 REGISTER\r\n\r\n"
        val r = SipParser.parse(raw.toByteArray()) as SipResponse
        assertEquals(200, r.code)
        assertTrue(r.isSuccess)
        val c = NameAddr.parse(r.headers["Contact"]!!)!!
        assertEquals("120", c.params["expires"])
        assertEquals(5062, c.sipUri!!.port)
    }

    @Test
    fun splitsListsOutsideQuotesAndBrackets() {
        val parts = SipHeaders.splitList("\"a,b\" <sip:x@y;p=1,2>, <sip:z@w>")
        assertEquals(2, parts.size)
    }

    @Test
    fun digestMatchesRfc2617Example() {
        val ch = DigestAuth.parseChallenge(
            "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"",
        )!!
        val h = DigestAuth.authorize(ch, "GET", "/dir/index.html", "Mufasa", "Circle Of Life", nc = 1, cnonce = "0a4f113b")
        assertTrue(h, h.contains("response=\"6629fae49393a05397450978507c4ef1\""))
    }
}
