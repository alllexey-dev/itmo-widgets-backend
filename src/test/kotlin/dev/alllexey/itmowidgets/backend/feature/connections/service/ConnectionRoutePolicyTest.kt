package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** The policy matrix: every listed route with and without its scope and client type, and the routes a client must never reach. */
class ConnectionRoutePolicyTest {
    @ParameterizedTest(name = "{0} {1} {2} with [{3}] -> {4}")
    @CsvSource(
        delimiter = '|',
        value = [
            "agent | GET | /api/client/me | schedule:write | true",
            "mcp | GET | /api/client/me | links:read | true",
            "web | GET | /api/client/me | user | true",
            "agent | GET | /api/client/events | friends:read | true",
            "mcp | GET | /api/client/events | links:read | false",
            "web | GET | /api/client/events | user | false",
            "agent | PUT | /api/client/status | sport | true",
            "mcp | PUT | /api/client/status | reviews:read | true",
            "web | PUT | /api/client/status | user | false",
            "agent | POST | /api/client/pair/renew | sport | true",
            "web | POST | /api/client/pair/renew | user | true",
            "agent | DELETE | /api/client/self | sport | true",
            "web | DELETE | /api/client/self | user | true",
            "agent | POST | /api/client/self | sport | false",
            "agent | GET | /api/app/version-info | sport | true",
            "agent | GET | /api/sport/free-sign/entry/my | sport | true",
            "agent | POST | /api/sport/auto-sign/lesson/42/cancel | sport | true",
            "agent | POST | /api/sport/free-sign/lesson/42/mark-satisfied | sport | true",
            "agent | POST | /api/sport/sign/sync | sport | true",
            "agent | POST | /api/schedule/lessons/sync | schedule:write | true",
            "agent | GET | /api/friends | friends:read | true",
            "agent | GET | /api/friends | sport | false",
            "agent | GET | /api/sport/free-sign/entry/my | friends:read | false",
            "agent | POST | /api/schedule/lessons/sync | sport | false",
            "agent | POST | /api/sport/free-sign/entry/create | sport | false",
            "agent | POST | /api/sport/free-sign/lesson/x/cancel | sport | false",
            "agent | GET | /api/sport/free-sign/entry/my/ | sport | false",
            "mcp | GET | /api/teachers/123/reviews | reviews:read | true",
            "mcp | GET | /api/teachers/summary-levels | reviews:read | true",
            "mcp | GET | /api/teachers/123/reviews | links:read | false",
            "mcp | PUT | /api/teachers/123/reviews/mine | reviews:read | false",
            "mcp | GET | /api/teachers/123/reviews/hints | reviews:read | false",
            "mcp | GET | /api/subjects/77/links | links:read | true",
            "mcp | PUT | /api/links/7c9e | links:read | false",
            "mcp | GET | /api/sport/auto-sign/limits | sport:queue | true",
            "mcp | POST | /api/sport/free-sign/entry/create | sport:queue | true",
            "mcp | POST | /api/sport/auto-sign/entry/create | sport:queue | true",
            "mcp | GET | /api/sport/auto-sign/entry/my | sport:queue | true",
            "mcp | POST | /api/sport/auto-sign/entry/9/cancel | sport:queue | true",
            "mcp | POST | /api/sport/free-sign/lesson/42/mark-satisfied | sport:queue | false",
            "mcp | POST | /api/sport/sign/sync | sport:queue | false",
            "mcp | POST | /api/sport/free-sign/entry/create | links:read | false",
            "agent | GET | /api/users/me/devices | sport | false",
            "mcp | POST | /api/users/me/connections/7c9e/revoke | links:read | false",
            "agent | DELETE | /api/users/me | sport | false",
            "agent | GET | /api/admin/users | sport | false",
            "mcp | GET | /api/moderation/cases | reviews:read | false",
            "web | GET | /api/users/me/devices | user | true",
            "web | PUT | /api/users/me/connections/7c9e/scopes | user | true",
            "web | POST | /api/users/me/connections/7c9e/revoke | user | true",
            "web | POST | /api/users/me/installs/7c9e/sign-out | user | true",
            "web | GET | /api/admin/users | user | true",
            "web | GET | /api/moderation/cases | user | true",
            "web | DELETE | /api/users/me | user | true",
            "web | GET | /api/sport/free-sign/entry/my | user | true",
            "web | POST | /api/users/me/connections/7c9e/itmo-ended | user | false",
            "web | POST | /api/users/me/pair/ABCD2345/open | user | false",
            "web | POST | /api/users/me/pair/7c9e/approve | user | false",
            "web | PUT | /api/users/me/consent | user | false",
            "web | PUT | /api/users/me/id-token | user | false",
            "web | POST | /api/device/register-device | user | false",
            "web | DELETE | /api/device/current | user | false",
        ],
    )
    fun `a connection reaches only what its client type and scopes allow`(
        client: String,
        method: String,
        path: String,
        scope: String,
        allowed: Boolean,
    ) {
        val scopes = setOf(checkNotNull(ConnectionScope.fromWire(scope)))
        assertEquals(allowed, ConnectionRoutePolicy.permitted(checkNotNull(ClientType.fromWire(client)), scopes, method, path) != null)
    }
}
