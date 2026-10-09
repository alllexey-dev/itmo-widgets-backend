package dev.alllexey.itmowidgets.backend.feature.push.model

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClientVersionTest {
    @Test
    fun `reads every platform and distribution the apps send`() {
        assertEquals(
            ClientVersion("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github"),
            ClientVersion.parse("2.3.0-beta.1 (20291); android; github"),
        )
        assertEquals(ClientVersion("2.3.0", 20300, AppPlatform.ANDROID, "play"), ClientVersion.parse("2.3.0 (20300); android; play"))
        assertEquals(ClientVersion("2.3", 1, AppPlatform.IOS, "appstore"), ClientVersion.parse("2.3 (1); ios; appstore"))
        assertEquals(
            ClientVersion("2.3.1.4", 999_999_999, AppPlatform.IOS, "testflight"),
            ClientVersion.parse("2.3.1.4 (999999999); ios; testflight"),
        )
    }

    @Test
    fun `ignores an absent or malformed header`() {
        listOf(
            null,
            "",
            "okhttp/4.12.0",
            "2.3.0-beta.1",
            "2.3.0 (20291)",
            "2.3.0 (20291); android",
            "2.3.0 (20291); windows; github",
            "2.3.0 (20291); Android; github",
            "2.3.0 (20291); android; GitHub",
            "2.3.0 (20291); android; github; extra",
            "2.3.0 (20291);android;github",
            " 2.3.0 (20291); android; github",
            "2.3.0 (20291); android; github ",
            "2.3.0 (0); android; github",
            "2.3.0 (020291); android; github",
            "2.3.0 (1234567890); android; github",
            "2 (20291); android; github",
            "v2.3.0 (20291); android; github",
            "2.3.0- (20291); android; github",
            "2.3.0-beta 1 (20291); android; github",
            "2.3.0 (20291); android; -github",
            "2.3.0 (20291); android; github\n",
            "2.3.0 (20291); android; ${"d".repeat(17)}",
        ).forEach { assertNull(ClientVersion.parse(it), "accepted: $it") }
    }

    @Test
    fun `ignores an oversized header before matching it`() {
        val longest = "1234.1234.1234.1234-${"a".repeat(13)} (123456789); android; ${"d".repeat(16)}"
        assertNull(ClientVersion.parse(longest), "a version name longer than its column")
        val fits = "1234.1234.1234-${"a".repeat(16)} (123456789); android; ${"d".repeat(16)}"
        assertEquals(31, ClientVersion.parse(fits)?.version?.length)
        assertNull(ClientVersion.parse(fits + " ".repeat(ClientVersion.MAX_HEADER_LENGTH)))
        assertNull(ClientVersion.parse("2.3.0 (20291); android; github".padEnd(ClientVersion.MAX_HEADER_LENGTH + 1, 'x')))
    }
}
