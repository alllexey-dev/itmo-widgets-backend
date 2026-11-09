package dev.alllexey.itmowidgets.backend.feature.sport.persistence

import dev.alllexey.itmowidgets.backend.feature.sport.service.SportAutoSignSettings
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SportAutoSignLimitTest : SportQueuePersistenceTest() {
    @Autowired private lateinit var settings: SportAutoSignSettings

    @Test
    fun `without a stored setting everybody has the default and the retired column is not read`() {
        val user = owner()
        jdbc.update("UPDATE user_settings SET auto_sign_limit=1 WHERE user_id=?", user)
        auto(user, lesson())

        val limits = autos.getLimits(user)

        assertEquals(SportAutoSignSettings.DEFAULT_LIMIT, limits.limit)
        assertEquals(SportAutoSignSettings.DEFAULT_LIMIT - 1, limits.available)
    }

    @Test
    fun `a stored limit applies to every user at once`() {
        val admin = owner()
        val busy = owner()
        val idle = owner()
        auto(busy, lesson())
        auto(busy, lesson(roomId = 11))

        settings.store(2, admin, Instant.now(clock))

        assertEquals(listOf(2, 2), listOf(busy, idle).map { autos.getLimits(it).limit })
        assertEquals(listOf(0, 2), listOf(busy, idle).map { autos.getLimits(it).available })
        assertFailsWith<BusinessRuleException> { autos.createEntry(busy, lesson(roomId = 12)) }

        settings.store(3, admin, Instant.now(clock))

        assertEquals(1, autos.getLimits(busy).available)
        autos.createEntry(busy, lesson(roomId = 12))
        assertEquals(0, autos.getLimits(busy).available)
    }

    @Test
    fun `zero stops new entries and keeps the queued ones`() {
        val user = owner()
        val queued = auto(user, lesson())
        autoSignLimit(0)

        assertEquals(0, autos.getLimits(user).available)
        assertFailsWith<BusinessRuleException> { autos.createEntry(user, lesson(roomId = 11)) }
        assertEquals(listOf(queued.entryId), autos.getUserEntries(user).filter { !it.isCancelled }.map { it.id })
    }

    @Test
    fun `a stored value outside the range counts as the default`() {
        val user = owner()
        jdbc.update(
            "INSERT INTO app_settings (key, value, updated_at) VALUES (?, '1000', now())",
            SportAutoSignSettings.LIMIT_KEY,
        )

        assertEquals(SportAutoSignSettings.DEFAULT_LIMIT, autos.getLimits(user).limit)
    }
}
