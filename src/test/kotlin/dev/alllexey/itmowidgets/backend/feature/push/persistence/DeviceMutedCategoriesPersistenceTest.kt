package dev.alllexey.itmowidgets.backend.feature.push.persistence

import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.push.model.PushCategory
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Instant
import kotlin.test.assertEquals

/** `muted_push_categories` (V22): stored by name, empty for none, and a row from an older image reads as none. */
class DeviceMutedCategoriesPersistenceTest @Autowired constructor(
    private val devices: DeviceRepository,
    private val em: TestEntityManager,
) : PostgreSqlRepositoryTest() {

    @Test
    fun `the switched off categories come back from the column`() {
        val user = em.persistUser(962101)
        val muted = Device(user = user, fcmToken = "synthetic-token-962101-a", deviceName = "Pixel", lastLogin = Instant.EPOCH)
            .apply { mutedPushCategories = setOf(PushCategory.FRIENDS, PushCategory.SPORT) }
        val plain = Device(user = user, fcmToken = "synthetic-token-962101-b", deviceName = "iPhone", lastLogin = Instant.EPOCH)
        em.persist(muted)
        em.persist(plain)
        em.flush()
        em.clear()

        val stored = devices.findDeliverableByUserId(user.id).associate { it.fcmToken to it.mutedPushCategories }

        assertEquals(setOf(PushCategory.FRIENDS, PushCategory.SPORT), stored["synthetic-token-962101-a"])
        assertEquals(emptySet(), stored["synthetic-token-962101-b"])
        val column = em.entityManager
            .createNativeQuery("SELECT muted_push_categories FROM devices WHERE fcm_token = 'synthetic-token-962101-a'")
            .singleResult
        assertEquals("FRIENDS,SPORT", column)
    }
}
