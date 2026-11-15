package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.activity.ActivityPostgreSqlTest
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserLastSeenTest @Autowired constructor(private val lastSeen: UserLastSeen) : ActivityPostgreSqlTest() {

    @Test
    fun `the last activity is the latest client request, device login or connection use`() {
        val byClient = jdbc.insertUser(967001)
        val byDevice = jdbc.insertUser(967002)
        val byConnection = jdbc.insertUser(967003)
        val quiet = jdbc.insertUser(967004)
        clientSeen(byClient, AT.minusSeconds(60))
        clientSeen(byClient, AT.minusSeconds(7200))
        deviceLogin(byClient, AT.minusSeconds(3600))
        connectionUsed(byClient, AT.minusSeconds(600))
        deviceLogin(byDevice, AT)
        clientSeen(byDevice, AT.minusSeconds(60))
        connectionUsed(byConnection, AT.plusSeconds(60))
        connectionUsed(byConnection, null)
        deviceLogin(byConnection, AT)
        connectionUsed(quiet, null)

        val all = lastSeen.all()

        assertEquals(AT.minusSeconds(60), lastSeen.of(byClient))
        assertEquals(AT, lastSeen.of(byDevice))
        assertEquals(AT.plusSeconds(60), lastSeen.of(byConnection))
        assertNull(lastSeen.of(quiet))
        assertEquals(
            mapOf(byClient to AT.minusSeconds(60), byDevice to AT, byConnection to AT.plusSeconds(60)),
            all.filterKeys { it in setOf(byClient, byDevice, byConnection, quiet) },
        )
    }

    @Test
    fun `a deleted account has no last activity`() {
        val deleted = jdbc.insertUser(967011)
        clientSeen(deleted, AT)
        connectionUsed(deleted, AT)
        jdbc.update("DELETE FROM users WHERE id = ?", deleted)

        assertNull(lastSeen.of(deleted))
        assertNull(lastSeen.all()[deleted])
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-10T09:00:00Z")
    }
}
