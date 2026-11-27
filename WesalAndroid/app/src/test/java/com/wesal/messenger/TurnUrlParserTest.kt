package com.wesal.messenger

import com.wesal.messenger.calls.TurnUrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-3 regression tests for TURN URL parsing. The pre-fix parser split only on
 * commas/semicolons/newlines and never validated the scheme, so a TURN secret stored
 * as a bare host or a space-separated list produced zero usable TURN servers while
 * the app still reported "TURN configured" — calls then had no media path at all
 * behind carrier NAT.
 */
class TurnUrlParserTest {

    @Test
    fun blankRawYieldsNoUrls() {
        assertEquals(emptyList<String>(), TurnUrlParser.parse(""))
        assertEquals(emptyList<String>(), TurnUrlParser.parse("   \n , ; "))
    }

    @Test
    fun bareHostGetsTurnSchemePrepended() {
        assertEquals(
            listOf("turn:relay.example.com:3478"),
            TurnUrlParser.parse("relay.example.com:3478")
        )
    }

    @Test
    fun schemedEntriesAreKeptVerbatim() {
        assertEquals(
            listOf("turn:relay.example.com:3478", "turns:relay.example.com:5349"),
            TurnUrlParser.parse("turn:relay.example.com:3478,turns:relay.example.com:5349")
        )
        assertEquals(
            listOf("turn://relay.example.com:3478"),
            TurnUrlParser.parse("turn://relay.example.com:3478")
        )
        assertEquals(
            listOf("stun:stun.example.com:19302"),
            TurnUrlParser.parse("stun:stun.example.com:19302")
        )
    }

    @Test
    fun splitsOnCommasSemicolonsNewlinesAndWhitespace() {
        val raw = "turn:a.example.com:3478; relay.b.example.com:3478\n" +
            "turns:c.example.com:5349\tturn:d.example.com:3478"
        assertEquals(
            listOf(
                "turn:a.example.com:3478",
                "turn:relay.b.example.com:3478",
                "turns:c.example.com:5349",
                "turn:d.example.com:3478"
            ),
            TurnUrlParser.parse(raw)
        )
    }

    @Test
    fun spaceSeparatedSecretBecomesSeparateServers() {
        // Regression case: this used to become ONE garbage "URL" containing a space,
        // which libwebrtc silently discarded.
        assertEquals(
            listOf("turn:one.example.com:3478", "turn:two.example.com:3478"),
            TurnUrlParser.parse("one.example.com:3478 two.example.com:3478")
        )
    }

    @Test
    fun duplicatesCollapseAfterNormalisation() {
        assertEquals(
            listOf("turn:relay.example.com:3478"),
            TurnUrlParser.parse("relay.example.com:3478, turn:relay.example.com:3478, relay.example.com:3478")
        )
    }

    @Test
    fun turnQueryParametersSurviveNormalisation() {
        assertEquals(
            listOf("turn:relay.example.com:3478?transport=udp"),
            TurnUrlParser.parse("turn:relay.example.com:3478?transport=udp")
        )
        assertEquals(
            listOf("turn:relay.example.com:3478?transport=udp"),
            TurnUrlParser.parse("relay.example.com:3478?transport=udp")
        )
    }

    @Test
    fun isTurnUrlRecognisesOnlyTurnSchemes() {
        assertTrue(TurnUrlParser.isTurnUrl("turn:relay.example.com:3478"))
        assertTrue(TurnUrlParser.isTurnUrl("turns:relay.example.com:5349"))
        assertTrue(TurnUrlParser.isTurnUrl("TURN:relay.example.com:3478"))
        assertTrue(TurnUrlParser.isTurnUrl("turn://relay.example.com:3478"))
        assertFalse(TurnUrlParser.isTurnUrl("stun:stun.example.com:19302"))
        assertFalse(TurnUrlParser.isTurnUrl("https://example.com"))
    }

    @Test
    fun stunOnlySecretYieldsNoTurnUrls() {
        // A STUN-only value must NOT count as "TURN configured" (post-validation flag).
        val parsed = TurnUrlParser.parse("stun:stun.example.com:19302")
        assertTrue(parsed.none { TurnUrlParser.isTurnUrl(it) })
    }
}
