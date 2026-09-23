package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import org.junit.jupiter.api.Test
import kotlin.test.*

class ResourceUrlPolicyTest {
    @Test
    fun `only allowlisted HTTPS hosts without credentials or alternate ports are accepted`() {
        for (url in listOf("http://github.com/x", "https://evil.example/x", "https://github.com.evil.example/x",
            "https://notion.site.evil.example/x", "https://notion.site/x", "https://user@github.com/x",
            "https://github.com:8443/x", "file:///tmp/a", "//github.com/x", "https://127.0.0.1/x",
            "https://github.com/" + "x".repeat(2000), "https://github.com/with space")) {
            assertFailsWith<InvalidRequestDataException>(url) { ResourceUrlPolicy.normalize(url) }
        }
        for (host in listOf("docs.google.com", "drive.google.com", "notion.so", "team.notion.site", "github.com", "disk.yandex.ru", "lms.itmo.ru")) {
            assertEquals("https://$host/path", ResourceUrlPolicy.normalize("https://$host/path").url)
        }
    }

    @Test
    fun `sheet gid is preserved while all sheet representations deduplicate`() {
        val base = "https://docs.google.com/spreadsheets/d/aBc_123-4"
        val query = ResourceUrlPolicy.normalize("$base/edit?usp=sharing&gid=17&utm_source=example")
        val fragment = ResourceUrlPolicy.normalize("$base/view#gid=17")
        assertEquals(ResourceType.GOOGLE_SHEET, query.type)
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
    }
}
