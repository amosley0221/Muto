package dev.muto.core

import dev.muto.core.dns.BlockMode
import dev.muto.core.filter.BlocklistParser
import dev.muto.core.filter.DomainNames
import dev.muto.core.filter.DomainSet
import dev.muto.core.filter.FilterEngine
import dev.muto.core.filter.FilterReason
import dev.muto.core.filter.RuleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainSetTest {

    private val set = DomainSet.of(listOf("doubleclick.net", "ads.example.com", "Tracker.IO"))

    @Test
    fun `matches an exact entry`() {
        assertEquals("doubleclick.net", set.matchingSuffix("doubleclick.net"))
    }

    @Test
    fun `matches a subdomain of an entry`() {
        assertEquals("doubleclick.net", set.matchingSuffix("stats.g.doubleclick.net"))
        assertEquals("ads.example.com", set.matchingSuffix("eu.ads.example.com"))
    }

    @Test
    fun `does not match a parent of an entry`() {
        assertNull(set.matchingSuffix("example.com"))
    }

    @Test
    fun `does not match a name that merely ends with the same text`() {
        // "notdoubleclick.net" must not match "doubleclick.net" - suffix matching is per label.
        assertNull(set.matchingSuffix("notdoubleclick.net"))
        assertNull(set.matchingSuffix("xads.example.com"))
    }

    @Test
    fun `normalises case when the set is built`() {
        assertTrue(set.matches("cdn.tracker.io"))
    }

    @Test
    fun `never matches on a bare TLD`() {
        // A list containing "com" is a bug in the list; honouring it would break the internet.
        val careless = DomainSet.of(listOf("com"))
        assertNull(careless.matchingSuffix("example.com"))
    }

    @Test
    fun `empty set matches nothing`() {
        assertFalse(DomainSet.EMPTY.matches("anything.example.com"))
        assertTrue(DomainSet.EMPTY.isEmpty())
    }

    @Test
    fun `deduplicates entries`() {
        val duplicated = DomainSet.of(listOf("a.example.com", "a.example.com", "A.EXAMPLE.COM"))
        assertEquals(1, duplicated.size)
    }

    @Test
    fun `skips entries that are not domains`() {
        val mixed = DomainSet.of(listOf("0.0.0.0", "localhost", "", "  ", "ads.example.com"))
        assertEquals(1, mixed.size)
    }

    @Test
    fun `survives a round trip through its hash array`() {
        val restored = DomainSet.fromHashArray(set.toHashArray())
        assertEquals(set.size, restored.size)
        assertTrue(restored.matches("stats.g.doubleclick.net"))
    }

    @Test
    fun `containsExact ignores parents`() {
        assertTrue(set.containsExact("doubleclick.net"))
        assertFalse(set.containsExact("stats.g.doubleclick.net"))
    }
}

class DomainNamesTest {

    @Test
    fun `normalises the common shapes`() {
        assertEquals("ads.example.com", DomainNames.normalize("  ADS.Example.Com.  "))
        assertEquals("ads.example.com", DomainNames.normalize("*.ads.example.com"))
    }

    @Test
    fun `rejects addresses, single labels and malformed names`() {
        assertNull(DomainNames.normalize("0.0.0.0"))
        assertNull(DomainNames.normalize("::1"))
        assertNull(DomainNames.normalize("192.168.1.1"))
        assertNull(DomainNames.normalize("localhost"))
        assertNull(DomainNames.normalize("ads..example.com"))
        assertNull(DomainNames.normalize(".example.com"))
        assertNull(DomainNames.normalize("-bad.example.com"))
        assertNull(DomainNames.normalize("bad-.example.com"))
        assertNull(DomainNames.normalize("ads example.com"))
        assertNull(DomainNames.normalize("http://ads.example.com"))
        assertNull(DomainNames.normalize(""))
    }

    @Test
    fun `rejects over-long names and labels`() {
        assertNull(DomainNames.normalize("a".repeat(64) + ".com"))
        assertNull(DomainNames.normalize(("a".repeat(60) + ".").repeat(5) + "com"))
    }

    @Test
    fun `lists suffixes longest first and stops before the TLD`() {
        assertEquals(
            listOf("a.b.example.com", "b.example.com", "example.com"),
            DomainNames.suffixes("a.b.example.com"),
        )
    }
}

class BlocklistParserTest {

    @Test
    fun `reads hosts-file syntax`() {
        val result = BlocklistParser.parse(
            """
            # Title: Example hosts
            127.0.0.1 localhost
            ::1 ip6-localhost
            0.0.0.0 ads.example.com
            0.0.0.0 tracker.example.net # inline comment
            127.0.0.1 a.example.org b.example.org
            """.trimIndent(),
        )
        assertEquals(
            listOf("ads.example.com", "tracker.example.net", "a.example.org", "b.example.org"),
            result.blocked,
        )
    }

    @Test
    fun `reads plain domain lists`() {
        val result = BlocklistParser.parse("ads.example.com\ntracker.example.net\n\n! comment\n")
        assertEquals(listOf("ads.example.com", "tracker.example.net"), result.blocked)
    }

    @Test
    fun `reads whole-domain adblock rules`() {
        val result = BlocklistParser.parse("||ads.example.com^\n||tracker.example.net^|\n")
        assertEquals(listOf("ads.example.com", "tracker.example.net"), result.blocked)
    }

    @Test
    fun `reads adblock exceptions into the allow list`() {
        val result = BlocklistParser.parse("||ads.example.com^\n@@||ads.example.com^\n")
        assertEquals(listOf("ads.example.com"), result.blocked)
        assertEquals(listOf("ads.example.com"), result.allowed)
    }

    @Test
    fun `skips adblock rules a DNS filter cannot honour`() {
        // Each of these matches something narrower than "every request to this domain". Treating
        // them as domain blocks would take down the whole host.
        val result = BlocklistParser.parse(
            """
            ||example.com/ads/banner.png
            ||example.com^${'$'}third-party
            ||example.com^${'$'}image
            ||example.com^${'$'}domain=other.com
            /ads-banner-
            example.com##.ad-slot
            example.com#@#.ad-slot
            ||*.ads.example.com/*
            """.trimIndent(),
        )
        assertTrue(result.blocked.toString(), result.blocked.isEmpty())
    }

    @Test
    fun `keeps adblock options that do not narrow the match`() {
        val result = BlocklistParser.parse("||ads.example.com^${'$'}important\n||b.example.com^${'$'}all\n")
        assertEquals(listOf("ads.example.com", "b.example.com"), result.blocked)
    }

    @Test
    fun `ignores hosts entries pointing at a real address`() {
        // "10.0.0.5 intranet.example.com" is a redirect someone wants to keep, not a block.
        val result = BlocklistParser.parse("10.0.0.5 intranet.example.com\n")
        assertTrue(result.blocked.isEmpty())
    }

    @Test
    fun `counts what it skipped`() {
        val result = BlocklistParser.parse("0.0.0.0 ads.example.com\n||example.com/path\ngarbage line\n")
        assertEquals(3, result.linesRead)
        assertEquals(1, result.blocked.size)
        assertEquals(2, result.linesSkipped)
    }
}

class FilterEngineTest {

    private fun engine(
        listBlocked: List<String> = emptyList(),
        listAllowed: List<String> = emptyList(),
        userBlocked: List<String> = emptyList(),
        userAllowed: List<String> = emptyList(),
    ) = FilterEngine(
        RuleSnapshot(
            listBlocked = DomainSet.of(listBlocked),
            listAllowed = DomainSet.of(listAllowed),
            userBlocked = DomainSet.of(userBlocked),
            userAllowed = DomainSet.of(userAllowed),
        ),
    )

    @Test
    fun `blocks a listed domain and reports the matching rule`() {
        val verdict = engine(listBlocked = listOf("doubleclick.net")).decide("stats.g.doubleclick.net")
        assertTrue(verdict.blocked)
        assertEquals(FilterReason.LIST_BLOCK, verdict.reason)
        assertEquals("doubleclick.net", verdict.rule)
    }

    @Test
    fun `allows anything no rule matches`() {
        val verdict = engine(listBlocked = listOf("doubleclick.net")).decide("example.com")
        assertFalse(verdict.blocked)
        assertEquals(FilterReason.NO_MATCH, verdict.reason)
    }

    @Test
    fun `a user allow rule beats everything else`() {
        val verdict = engine(
            listBlocked = listOf("example.com"),
            userBlocked = listOf("example.com"),
            userAllowed = listOf("example.com"),
        ).decide("ads.example.com")
        assertFalse(verdict.blocked)
        assertEquals(FilterReason.USER_ALLOW, verdict.reason)
    }

    @Test
    fun `a user block rule beats a list exception`() {
        val verdict = engine(
            listAllowed = listOf("example.com"),
            userBlocked = listOf("example.com"),
        ).decide("ads.example.com")
        assertTrue(verdict.blocked)
        assertEquals(FilterReason.USER_BLOCK, verdict.reason)
    }

    @Test
    fun `a list exception beats a list block`() {
        val verdict = engine(
            listBlocked = listOf("example.com"),
            listAllowed = listOf("cdn.example.com"),
        ).decide("img.cdn.example.com")
        assertFalse(verdict.blocked)
        assertEquals(FilterReason.LIST_ALLOW, verdict.reason)
    }

    @Test
    fun `pausing passes everything through without changing the rules`() {
        val engine = engine(listBlocked = listOf("doubleclick.net"))
        engine.filtering = false
        val paused = engine.decide("stats.g.doubleclick.net")
        assertFalse(paused.blocked)
        assertEquals(FilterReason.NOT_FILTERING, paused.reason)

        engine.filtering = true
        assertTrue(engine.decide("stats.g.doubleclick.net").blocked)
    }

    @Test
    fun `normalises the host before deciding`() {
        val engine = engine(listBlocked = listOf("doubleclick.net"))
        assertTrue(engine.decide("STATS.G.DoubleClick.NET.").blocked)
    }

    @Test
    fun `a published snapshot takes effect immediately`() {
        val engine = engine()
        assertFalse(engine.decide("ads.example.com").blocked)
        engine.update { it.copy(listBlocked = DomainSet.of(listOf("ads.example.com"))) }
        assertTrue(engine.decide("ads.example.com").blocked)
    }

    @Test
    fun `carries the configured block mode`() {
        val engine = FilterEngine(RuleSnapshot(blockMode = BlockMode.NULL_IP))
        assertEquals(BlockMode.NULL_IP, engine.blockMode())
    }
}
