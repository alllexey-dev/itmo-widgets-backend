package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class ClientVersionServiceTest {
    private val devices = mock(DeviceRepository::class.java)
    private val clock = MutableClock(Instant.parse("2026-10-09T09:00:00Z"))
    private val service = ClientVersionService(devices, clock, ClientVersionConfig(refresh = Duration.ofMinutes(60)))
    private val user = UUID.randomUUID()

    @Test
    fun `an unchanged build reaches the database once per refresh interval`() {
        val start = clock.now
        repeat(3) { service.report(user, BETA) }
        clock.now = start.plus(Duration.ofMinutes(59))
        service.report(user, BETA)
        verify(devices).recordClientVersion(user, "2.3.0-beta.1", 20291, "ANDROID", "github", start, start.minus(REFRESH))

        clock.now = start.plus(REFRESH)
        service.report(user, BETA)
        verify(devices).recordClientVersion(user, "2.3.0-beta.1", 20291, "ANDROID", "github", clock.now, start)
        verifyNoMoreInteractions(devices)
    }

    @Test
    fun `a changed build is written at once`() {
        `when`(devices.recordClientVersion(user, "2.3.0-beta.2", 20292, "IOS", "appstore", clock.now, clock.now.minus(REFRESH)))
            .thenReturn(1)
        service.report(user, BETA)
        service.report(user, BETA.copy(build = 20292, version = "2.3.0-beta.2"))
        service.report(user, BETA.copy(build = 20292, version = "2.3.0-beta.2", platform = AppPlatform.IOS, distribution = "appstore"))

        val staleBefore = clock.now.minus(REFRESH)
        verify(devices).recordClientVersion(user, "2.3.0-beta.1", 20291, "ANDROID", "github", clock.now, staleBefore)
        verify(devices).recordClientVersion(user, "2.3.0-beta.2", 20292, "ANDROID", "github", clock.now, staleBefore)
        verify(devices).recordClientVersion(user, "2.3.0-beta.2", 20292, "IOS", "appstore", clock.now, staleBefore)
        verifyNoMoreInteractions(devices)
    }

    @Test
    fun `an iOS build no device took creates the iOS device without push`() {
        service.report(user, IOS)

        verify(devices).recordClientVersion(user, "2.3.0", 877, "IOS", "sideload", clock.now, clock.now.minus(REFRESH))
        val created = mockingDetails(devices).invocations.single { it.method.name == "insertIosDeviceWithoutPush" }
        assertEquals(listOf<Any?>(user, "iOS, no push token", "2.3.0", 877, "sideload", clock.now), created.arguments.drop(1))
    }

    @Test
    fun `an Android build no device took creates nothing`() {
        service.report(user, BETA)

        verify(devices).recordClientVersion(user, "2.3.0-beta.1", 20291, "ANDROID", "github", clock.now, clock.now.minus(REFRESH))
        verifyNoMoreInteractions(devices)
    }

    @Test
    fun `users are throttled independently`() {
        val other = UUID.randomUUID()
        service.report(user, BETA)
        service.report(other, BETA)
        service.report(user, BETA)

        verify(devices, times(1)).recordClientVersion(user, "2.3.0-beta.1", 20291, "ANDROID", "github", clock.now, clock.now.minus(REFRESH))
        verify(
            devices,
            times(1),
        ).recordClientVersion(other, "2.3.0-beta.1", 20291, "ANDROID", "github", clock.now, clock.now.minus(REFRESH))
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
        override fun instant(): Instant = now
    }

    private companion object {
        val REFRESH: Duration = Duration.ofMinutes(60)
        val BETA = ClientVersion("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github")
        val IOS = ClientVersion("2.3.0", 877, AppPlatform.IOS, "sideload")
    }
}
