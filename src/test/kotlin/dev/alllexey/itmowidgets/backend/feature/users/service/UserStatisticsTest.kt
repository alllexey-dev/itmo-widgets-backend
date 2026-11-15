package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.users.model.PrivacyCounts
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility.ALL
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility.FRIENDS
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility.NOBODY
import dev.alllexey.itmowidgets.backend.feature.users.model.UserStats
import dev.alllexey.itmowidgets.backend.feature.users.persistence.UserStatisticsRepository
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.insertUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import kotlin.test.assertEquals

/** The account figures on real PostgreSQL; they are the state now, so other tests' accounts are taken out as a delta. */
@Import(UserStatistics::class, UserStatisticsRepository::class)
class UserStatisticsTest @Autowired constructor(private val statistics: UserStatistics, private val jdbc: JdbcTemplate) :
    PostgreSqlRepositoryTest() {
    @Test
    fun `accounts and their privacy settings count every account but the placeholders of deleted ones`() {
        val before = statistics.stats()
        jdbc.insertUser(984_700)
        val open = jdbc.insertUser(984_701)
        jdbc.update(
            "UPDATE user_settings SET schedule_visibility = 'ALL', sport_visibility = 'NOBODY', friends_visibility = 'FRIENDS' WHERE user_id = ?",
            open,
        )
        // A placeholder as AccountDeletionService creates it.
        val placeholder = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name) VALUES (?, -984700, 'Deleted account')", placeholder)
        jdbc.update(
            """
            INSERT INTO user_settings (user_id, auto_sign_limit, sport_visibility, schedule_visibility, friends_visibility)
            VALUES (?, 0, 'NOBODY', 'NOBODY', 'NOBODY')
            """,
            placeholder,
        )

        val after = statistics.stats()

        assertEquals(2, after.accounts - before.accounts)
        assertEquals(counts(ALL to 1, FRIENDS to 1), delta(before, after) { it.schedule })
        assertEquals(counts(FRIENDS to 1, NOBODY to 1), delta(before, after) { it.sport })
        assertEquals(counts(ALL to 1, FRIENDS to 1), delta(before, after) { it.friends })
    }

    private fun delta(
        before: UserStats,
        after: UserStats,
        setting: (PrivacyCounts) -> Map<SharingVisibility, Long>,
    ): Map<SharingVisibility, Long> = SharingVisibility.entries.associateWith {
        setting(after.privacy).getValue(it) -
            setting(before.privacy).getValue(it)
    }

    /** Every value, 0 unless given. */
    private fun counts(vararg given: Pair<SharingVisibility, Int>): Map<SharingVisibility, Long> {
        val map = given.toMap()
        return SharingVisibility.entries.associateWith { (map[it] ?: 0).toLong() }
    }
}
