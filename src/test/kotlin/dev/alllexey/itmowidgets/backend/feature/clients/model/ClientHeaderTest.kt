package dev.alllexey.itmowidgets.backend.feature.clients.model

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientHeaderTest {
    @Test
    fun `parses every key of an app header`() {
        val install = UUID.randomUUID()
        val header = ClientHeader.parse(
            "kind=android; version=2.3.0; build=20300; channel=play; source=alllexey-dev/ITMO.Widgets; commit=abc1234; " +
                "os=36; install=$install",
        )
        assertEquals(
            ClientHeader(ClientKind.ANDROID, "2.3.0", "20300", "play", "alllexey-dev/ITMO.Widgets", "abc1234", null, "36", null, install),
            header,
        )
        assertFalse(header!!.custom)
        assertEquals("2.3.0", header.groupVersion)
    }

    @Test
    fun `skips invalid pairs and unknown keys and keeps the first of duplicates`() {
        val header = ClientHeader.parse("kind=web;version=abc123;browser=chrome/131;browser=firefox/1;x=y;bad;note=a b;channel=Web!")
        assertEquals(ClientHeader(ClientKind.WEB, "abc123", browser = "chrome/131"), header)
        assertNull(ClientHeader.parse("kind=ios; version=2.3.0; install=not-a-uuid")!!.install)
    }

    @Test
    fun `a header without a known kind or a version or too long is ignored`() {
        for (value in listOf(null, "", "version=2.3.0", "kind=android", "kind=desktop; version=1", "kind=ANDROID; version=1")) {
            assertNull(ClientHeader.parse(value), value)
        }
        assertNull(ClientHeader.parse("kind=android; version=1; " + "os=1; ".repeat(100)))
    }

    @Test
    fun `forks and self-built clients are custom and count under their upstream version`() {
        val fork = ClientHeader(ClientKind.IOS, "2.3.0-fork", channel = "custom", source = "someone/ITMO.Widgets", base = "2.3.0")
        assertTrue(fork.custom)
        assertEquals("2.3.0", fork.groupVersion)
        assertTrue(ClientHeader(ClientKind.ANDROID, "2.3.0", channel = "github", source = "someone/ITMO.Widgets").custom)
        assertTrue(ClientHeader(ClientKind.AGENT, "1.4.0", channel = "custom", source = "local").custom)
        assertEquals("2.3.1", ClientHeader(ClientKind.ANDROID, "2.3.1", channel = "custom").groupVersion)
        assertFalse(ClientHeader(ClientKind.AGENT, "1.4.0", channel = "github", source = "alllexey-dev/itmo-widgets-agent").custom)
        assertFalse(ClientHeader(ClientKind.ANDROID, "2.3.0").custom)
    }

    @Test
    fun `an agent from before the header is named by its user agent`() {
        assertEquals(ClientHeader(ClientKind.AGENT, "1.2.0"), ClientHeader.fromAgentUserAgent("itmo-widgets-agent/1.2.0"))
        assertNull(ClientHeader.fromAgentUserAgent("Mozilla/5.0"))
        assertNull(ClientHeader.fromAgentUserAgent(null))
    }
}
