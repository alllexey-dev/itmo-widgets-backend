package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import org.junit.jupiter.api.Test
import kotlin.test.*

class ResourceUrlPolicyTest {
    @Test
    fun `any HTTPS host is accepted`() {
        for (host in listOf("docs.google.com", "github.com", "evil.example", "vk.com", "t.me", "my.itmo.ru", "127.0.0.1", "team.notion.site")) {
            assertEquals("https://$host/path", ResourceUrlPolicy.normalize("https://$host/path").url)
        }
    }

    @Test
    fun `internationalized hosts are converted to Punycode`() {
        assertEquals("https://xn--e1afmkfd.xn--p1ai/%D0%BF%D1%83%D1%82%D1%8C",
            ResourceUrlPolicy.normalize("https://пример.рф/путь").url)
    }

    @Test
    fun `plain HTTP credentials alternate ports and malformed input are rejected`() {
        for (url in listOf("http://github.com/x", "https://user@github.com/x", "https://user:secret@example.org/x",
            "https://github.com:8443/x", "https://example.org:80/x", "file:///tmp/a", "//github.com/x", "javascript:alert(1)",
            "mailto:someone@example.org", "https://github.com/" + "x".repeat(2000), "https://github.com/with space", "", "   ")) {
            assertFailsWith<InvalidRequestDataException>(url) { ResourceUrlPolicy.normalize(url) }
        }
        assertEquals("https://github.com/x", ResourceUrlPolicy.normalize("https://github.com:443/x").url)
    }

    @Test
    fun `sheet gid is preserved while all sheet representations deduplicate`() {
        val base = "https://docs.google.com/spreadsheets/d/aBc_123-4"
        val query = ResourceUrlPolicy.normalize("$base/edit?usp=sharing&gid=17&utm_source=example")
        val fragment = ResourceUrlPolicy.normalize("$base/view#gid=17")
        assertEquals(base, query.normalizedUrl)
        assertEquals(query, fragment)
        assertEquals("$base#gid=17", query.url)
        assertEquals(base, ResourceUrlPolicy.normalize("$base#gid=not-a-number").url)
    }

    @Test
    fun `links normalize host strip tracking fragments and trailing slashes but retain useful query`() {
        assertEquals("https://github.com/example?tab=readme", ResourceUrlPolicy.normalize(
            " HTTPS://GITHUB.COM:443/example/?utm_source=app&tab=readme&fbclid=discard#intro ").url)
        assertEquals("https://github.com", ResourceUrlPolicy.normalize("https://github.com/").normalizedUrl)
        assertEquals("https://github.com/example?a=1", ResourceUrlPolicy.normalize("https://github.com/example?%75tm_source=x&a=1").url)
        val link = ResourceUrlPolicy.normalize("https://example.org/page#section")
        assertEquals(link.url, link.normalizedUrl)
        assertEquals("https://example.org/page", link.url)
    }
}
