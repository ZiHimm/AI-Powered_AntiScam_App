package com.antiscam.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteTest {
    private val inv = Invite("https://brave-lion-owl.trycloudflare.com", "K7Q2-9XPD-M4TR", "Tan family & friends",
        "son", "Son Tan", "ab:cd:ef:01:23:45")

    @Test fun roundTripThroughTheLink() {
        val link = inv.toLink()
        assertTrue(link.startsWith(Invite.PAGE + "#"))
        assertEquals(inv, Invite.parse(link))
    }
    @Test fun appSchemeFromTheJoinPage() {
        val q = inv.toLink().substringAfter('#')
        assertEquals(inv, Invite.parse("antiscam://join?$q"))
    }
    @Test fun secretsStayAfterTheHash() {
        val link = inv.toLink()
        assertTrue(link.indexOf("K7Q2") > link.indexOf('#'))
    }
    @Test fun roomAddress() = assertEquals("https://brave-lion-owl.trycloudflare.com/f/K7Q2-9XPD-M4TR", inv.relayRoom)
    @Test fun baseOfStripsRoomAndWs() {
        assertEquals("https://x.dev", Invite.baseOf("https://x.dev/f/ABCD-EFGH/ws"))
        assertEquals("192.168.1.4:8000", Invite.baseOf("192.168.1.4:8000/"))
    }
    @Test fun codesLookLikeTheRelays() {
        val c = Invite.newCode(java.util.Random(1))
        assertTrue(Regex("^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$").matches(c))
    }
    @Test fun rejectsJunk() {
        assertNull(Invite.parse("https://example.com/whatever"))
        assertNull(Invite.parse(Invite.PAGE + "#r=x&f=SHORT"))
    }
}
