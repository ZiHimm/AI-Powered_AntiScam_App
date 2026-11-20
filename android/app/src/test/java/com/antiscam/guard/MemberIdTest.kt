package com.antiscam.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberIdTest {
    private val relayRule = Regex("^[a-z][a-z0-9-]{1,23}$")   // same as mobile/relay/server.js MEMBER_ID

    @Test fun classicNamesStayCompatible() {
        assertEquals("mum", memberIdFor("Mum")); assertEquals("son", memberIdFor(" Son ")); assertEquals("daughter", memberIdFor("DAUGHTER"))
    }
    @Test fun spacesAndSymbols() {
        assertEquals("grandma-lim", memberIdFor("Grandma Lim")); assertEquals("aunty-mei-ling", memberIdFor("Aunty Mei-Ling!"))
    }
    @Test fun leadingDigitAndShortNames() {
        assertEquals("m-2nd-son", memberIdFor("2nd Son")); assertEquals("a-1", memberIdFor("A")); assertEquals("", memberIdFor("   "))
    }
    @Test fun nonLatinNamesGetAStableId() {
        val a = memberIdFor("陈"); val b = memberIdFor("陈")
        assertTrue(a.startsWith("m-")); assertEquals(a, b); assertTrue(memberIdFor("சிவா") != a)
    }
    @Test fun everyIdTheRelayAccepts() {
        for (n in listOf("Mum", "Dad", "Grandma Lim", "2nd Son", "A", "Ah Kow", "Siti Nur Aisyah binti Abdullah", "José", "陈", "சிவா", "!!"))
            assertTrue("$n -> ${memberIdFor(n)}", relayRule.matches(memberIdFor(n)))
    }
}
