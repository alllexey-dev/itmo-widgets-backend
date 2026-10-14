package dev.alllexey.itmowidgets.backend.feature.agent.service

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** The allow-list matrix: every listed route with and without its scope, and the routes an agent must never reach. */
class AgentRoutePolicyTest {
    @ParameterizedTest(name = "{0} {1} with [{2}] -> {3}")
    @CsvSource(
        delimiter = '|',
        value = [
            "GET | /api/agent/me | schedule:write | true",
            "GET | /api/agent/events | friends:read | true",
            "POST | /api/agent/pair/session | schedule:write | true",
            "DELETE | /api/agent/self | friends:read | true",
            "POST | /api/agent/self | sport | false",
            "DELETE | /api/agent/self/extra | sport | false",
            "GET | /api/agent/pair/session | sport | false",
            "POST | /api/users/me/pair/7c9e/approve | sport | false",
            "POST | /api/users/me/connections/7c9e/revoke | sport | false",
            "GET | /api/sport/free-sign/entry/my | sport | true",
            "GET | /api/sport/auto-sign/entry/my | sport | true",
            "POST | /api/sport/free-sign/lesson/42/mark-satisfied | sport | true",
            "POST | /api/sport/auto-sign/lesson/42/cancel | sport | true",
            "POST | /api/sport/sign/sync | sport | true",
            "POST | /api/schedule/lessons/sync | schedule:write | true",
            "GET | /api/sport/free-sign/entry/my | friends:read | false",
            "POST | /api/sport/sign/sync | schedule:write | false",
            "POST | /api/schedule/lessons/sync | sport | false",
            "POST | /api/agent/me | sport | false",
            "POST | /api/sport/free-sign/entry/create | sport | false",
            "POST | /api/sport/free-sign/entry/7/mark-satisfied | sport | false",
            "POST | /api/sport/free-sign/lesson/x/cancel | sport | false",
            "POST | /api/sport/free-sign/lesson/42/cancel/extra | sport | false",
            "GET | /api/sport/free-sign/entry/my/ | sport | false",
            "DELETE | /api/users/me | sport | false",
            "PUT | /api/users/me/privacy | sport | false",
            "GET | /api/users/me/web-login/ABCD2345 | sport | false",
            "POST | /api/users/me/web-login/1/approve | sport | false",
            "POST | /api/users/me/pair/ABCD2345/open | sport | false",
            "GET | /api/users/me/connections | sport | false",
            "POST | /api/agent/tokens | sport | false",
            "GET | /api/agent/access | sport | false",
            "GET | /api/admin/users | sport | false",
            "GET | /api/moderation/cases | sport | false",
            "POST | /api/device/register-device | sport | false",
            "GET | /api/friends | friends:read | false",
        ],
    )
    fun `an agent token reaches only its listed routes`(method: String, path: String, scope: String, allowed: Boolean) {
        assertEquals(allowed, AgentRoutePolicy.permitted(setOf(scope), method, path) != null)
    }
}
