package dev.alllexey.itmowidgets.backend.feature.push.persistence

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.testing.persistUser
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [DeviceRepository.recordClientVersion]: which device a request's build lands on, and when it is not rewritten. */
class ClientVersionPersistenceTest @Autowired constructor(private val devices: DeviceRepository, private val em: TestEntityManager) :
    PostgreSqlRepositoryTest() {

    @Test
    fun `the only device of a user takes the build`() {
        val user = em.persistUser(962001)
        val phone = device(user, "synthetic-token-962001")

        assertEquals(1, record(user, BETA))

        assertEquals(listOf<Any?>("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github", NOW), reported(phone))
    }

    @Test
    fun `two devices that could have sent the build are left alone`() {
        val user = em.persistUser(962002)
        val first = device(user, "synthetic-token-962002-a")
        val second = device(user, "synthetic-token-962002-b")

        assertEquals(0, record(user, BETA))

        assertNull(reported(first)[0])
        assertNull(reported(second)[0])
    }

    @Test
    fun `an iOS build never lands on a device that has not reported`() {
        val user = em.persistUser(962003)
        val android = device(user, "synthetic-token-962003")

        assertEquals(0, record(user, IOS))

        assertEquals(listOf<Any?>(null, null, null, null, null), reported(android))
    }

    @Test
    fun `each platform's build finds the device of that platform`() {
        val user = em.persistUser(962007)
        val android = device(user, "synthetic-token-962007-a")
        val iphone = device(user, "synthetic-token-962007-i", reported = IOS)

        assertEquals(1, record(user, BETA))
        assertEquals(1, record(user, IOS.copy(build = 20292, version = "2.3.0-beta.2")))

        assertEquals(listOf<Any?>("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github", NOW), reported(android))
        assertEquals(listOf<Any?>("2.3.0-beta.2", 20292, AppPlatform.IOS, "appstore", NOW), reported(iphone))
    }

    @Test
    fun `an unchanged build is not rewritten until it is stale, a changed one at once`() {
        val user = em.persistUser(962004)
        val phone = device(user, "synthetic-token-962004")
        val earlier = NOW.minus(Duration.ofMinutes(30))
        assertEquals(1, record(user, BETA, at = earlier))

        assertEquals(0, record(user, BETA))
        assertEquals(earlier, reported(phone)[4])

        assertEquals(1, record(user, BETA.copy(distribution = "play")))
        assertEquals(listOf<Any?>("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "play", NOW), reported(phone))

        val later = NOW.plus(REFRESH)
        assertEquals(1, record(user, BETA.copy(distribution = "play"), at = later))
        assertEquals(later, reported(phone)[4])
    }

    @Test
    fun `another user's devices are never touched`() {
        val owner = em.persistUser(962005)
        val other = em.persistUser(962006)
        val othersPhone = device(other, "synthetic-token-962006")

        assertEquals(0, record(owner, BETA))

        assertNull(reported(othersPhone)[0])
    }

    private fun record(user: User, version: ClientVersion, at: Instant = NOW): Int = devices.recordClientVersion(
        user.id,
        version.version,
        version.build,
        version.platform.name,
        version.distribution,
        at,
        at.minus(REFRESH),
    ).also { em.clear() }

    private fun device(user: User, token: String, reported: ClientVersion? = null): UUID = em.persistAndFlush(
        Device(user = user, fcmToken = token, deviceName = "Synthetic phone", lastLogin = NOW.minus(Duration.ofDays(3))).apply {
            if (reported != null) reportClientVersion(reported, NOW.minus(Duration.ofDays(2)))
        },
    ).id

    private fun reported(id: UUID): List<Any?> = em.find(Device::class.java, id)!!.run {
        listOf(appVersion, appBuild, appPlatform, appDistribution, appVersionSeenAt)
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-09T09:00:00Z")
        val REFRESH: Duration = Duration.ofHours(1)
        val BETA = ClientVersion("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github")
        val IOS = ClientVersion("2.3.0-beta.1", 20291, AppPlatform.IOS, "appstore")
    }
}
