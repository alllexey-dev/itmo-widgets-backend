package dev.alllexey.itmowidgets.backend.feature.push.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import dev.alllexey.itmowidgets.backend.contract.ContractSamples
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.push.model.ClientVersion
import dev.alllexey.itmowidgets.backend.feature.push.model.Device
import dev.alllexey.itmowidgets.backend.feature.push.persistence.DeviceRepository
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmTypedWrapper
import dev.alllexey.itmowidgets.backend.feature.push.web.RegisterDeviceRequest
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.testing.TestClock
import dev.alllexey.itmowidgets.backend.testing.TestUsers
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceServiceTest {

    private val repository = mock(DeviceRepository::class.java)
    private val fcmService = mock(FcmService::class.java)
    private val userService = mock(UserService::class.java)
    private val deliveryStore = mock(DeviceDeliveryStore::class.java)
    private val service = DeviceService(repository, fcmService, userService, deliveryStore, TestClock.CLOCK)
    private val expiresAt = TestClock.now().plusSeconds(3600)

    private val logger = LoggerFactory.getLogger(DeviceService::class.java) as Logger
    private lateinit var logs: ListAppender<ILoggingEvent>

    @BeforeEach
    fun captureLogs() {
        logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
    }

    @AfterEach
    fun stopCapturingLogs() {
        logger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `registration stores the reported build on the registered device`() {
        val user = TestUsers.user(123456, name = null)
        val device = device(user, "current-token")
        val version = ClientVersion("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github")
        `when`(userService.findUserById(user.id)).thenReturn(user)
        `when`(repository.findByFcmToken("current-token")).thenReturn(device)

        service.registerOrUpdateDevice(user.id, RegisterDeviceRequest("current-token", "Pixel"), version)

        verify(repository).save(device)
        assertEquals(listOf<Any?>("2.3.0-beta.1", 20291, AppPlatform.ANDROID, "github", TestClock.now()), device.reported())
    }

    @Test
    fun `registration without a header keeps the build the device reported before`() {
        val user = TestUsers.user(123456, name = null)
        val device = device(user, "current-token").apply {
            reportClientVersion(ClientVersion("2.3.0", 20300, AppPlatform.ANDROID, "play"), TestClock.now())
        }
        `when`(userService.findUserById(user.id)).thenReturn(user)
        `when`(repository.findByFcmToken("current-token")).thenReturn(device)

        service.registerOrUpdateDevice(user.id, RegisterDeviceRequest("current-token", "Pixel"))

        assertEquals(listOf<Any?>("2.3.0", 20300, AppPlatform.ANDROID, "play", TestClock.now()), device.reported())
    }

    @Test
    fun `the body released Core sends registers an Android device with alerts at the injected time`() {
        val user = TestUsers.user(123456, name = null)
        `when`(userService.findUserById(user.id)).thenReturn(user)
        val saved = ArgumentCaptor.forClass(Device::class.java)

        // The value the Core 1.2.0 and 1.7.0 fixture decodes into (HttpContractTest).
        service.registerOrUpdateDevice(user.id, ContractSamples.registerDevice)

        verify(repository).save(saved.capture())
        assertEquals(listOf<Any?>(AppPlatform.ANDROID, true, null, "Pixel 8 (synthetic)", TestClock.now()), saved.value.registration())
    }

    @Test
    fun `registration trims the token like unregistration`() {
        val user = TestUsers.user(123456, name = null)
        `when`(userService.findUserById(user.id)).thenReturn(user)
        val saved = ArgumentCaptor.forClass(Device::class.java)

        service.registerOrUpdateDevice(user.id, RegisterDeviceRequest("  new-token  ", "Pixel"))

        verify(repository).findByFcmToken("new-token")
        verify(repository).save(saved.capture())
        assertEquals("new-token", saved.value.fcmToken)
    }

    @Test
    fun `re-registering a known token applies the request and heals an iOS device stored as Android`() {
        val user = TestUsers.user(123456, name = null)
        val device = device(user, "ios-token")
        `when`(userService.findUserById(user.id)).thenReturn(user)
        `when`(repository.findByFcmToken("ios-token")).thenReturn(device)

        service.registerOrUpdateDevice(user.id, RegisterDeviceRequest("ios-token", "iPhone", AppPlatform.IOS, false, "2.3.0"))

        assertEquals(listOf<Any?>(AppPlatform.IOS, false, "2.3.0", "iPhone", TestClock.now()), device.registration())

        service.registerOrUpdateDevice(user.id, RegisterDeviceRequest("ios-token", "iPhone"))

        // Absent fields fall back to their defaults; only the version name is kept.
        assertEquals(listOf<Any?>(AppPlatform.ANDROID, true, "2.3.0", "iPhone", TestClock.now()), device.registration())
    }

    @Test
    fun `the header build wins over the body version and an oversized body version is ignored`() {
        val user = TestUsers.user(123456, name = null)
        val device = device(user, "current-token")
        `when`(userService.findUserById(user.id)).thenReturn(user)
        `when`(repository.findByFcmToken("current-token")).thenReturn(device)

        service.registerOrUpdateDevice(user.id, RegisterDeviceRequest("current-token", "Pixel", appVersion = "1".repeat(33)))
        assertNull(device.appVersion)

        val header = ClientVersion("2.3.1", 20310, AppPlatform.ANDROID, "play")
        service.registerOrUpdateDevice(user.id, RegisterDeviceRequest("current-token", "Pixel", appVersion = "2.3.0"), header)
        assertEquals("2.3.1", device.appVersion)
    }

    @Test
    fun `unregisters a device owned by the authenticated user`() {
        val user = TestUsers.user(123456, name = null)
        val device = device(user, "current-token")
        `when`(repository.findByFcmToken("current-token")).thenReturn(device)

        service.unregisterDevice(user.id, "  current-token  ")

        verify(repository).delete(device)
    }

    @Test
    fun `does not unregister another user's device`() {
        val owner = TestUsers.user(123456, name = null)
        val caller = TestUsers.user(654321, name = null)
        val device = device(owner, "foreign-token")
        `when`(repository.findByFcmToken("foreign-token")).thenReturn(device)

        service.unregisterDevice(caller.id, "foreign-token")

        verify(repository, never()).delete(device)
    }

    @Test
    fun `does not expose whether a token exists`() {
        val caller = TestUsers.user(123456, name = null)
        `when`(repository.findByFcmToken("missing-token")).thenReturn(null)

        service.unregisterDevice(caller.id, "missing-token")

        verify(repository, never()).delete(any(Device::class.java))
    }

    @Test
    fun `ignores a blank token without querying the repository`() {
        service.unregisterDevice(UUID.randomUUID(), "   ")

        verify(repository, never()).findByFcmToken(anyString())
    }

    @Test
    fun `UNREGISTERED removes only the failed registration and continues delivering to other devices`() {
        val owner = TestUsers.user(123456, name = null)
        val failed = device(owner, "synthetic-unregistered-token")
        val valid = device(owner, "synthetic-valid-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(failed.target(), valid.target()))
        val failure = firebaseFailure(MessagingErrorCode.UNREGISTERED)
        doAnswer { throw failure }.`when`(fcmService).sendDataMessage(failed.fcmToken, payload, owner.isu, expiresAt)

        service.sendDataMessageToUser(owner.id, payload, expiresAt)

        verify(deliveryStore).removeIfTokenMatches(failed.target())
        verify(deliveryStore, never()).removeIfTokenMatches(valid.target())
        verifyNoInteractions(repository)
        verify(fcmService).sendDataMessage(valid.fcmToken, payload, owner.isu, expiresAt)
        assertSafeLogs(failed.fcmToken, valid.fcmToken, payload.payload!!)
    }

    @Test
    fun `every Firebase error other than UNREGISTERED retains registration`() {
        val owner = TestUsers.user(123456, name = null)
        val device = device(owner, "synthetic-private-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(device.target()))

        (MessagingErrorCode.entries.filter { it != MessagingErrorCode.UNREGISTERED } + listOf(null)).forEach { code ->
            val failure = firebaseFailure(code)
            doAnswer { throw failure }.`when`(fcmService).sendDataMessage(device.fcmToken, payload, owner.isu, expiresAt)
            service.sendDataMessageToUser(owner.id, payload, expiresAt)
        }

        verify(repository, never()).delete(any(Device::class.java))
        verify(deliveryStore, never()).removeIfTokenMatches(device.target())
        verify(repository, never()).findByFcmToken(anyString())
        assertTrue(logs.list.isNotEmpty())
        assertTrue(logs.list.any { it.formattedMessage.contains(device.id.toString()) })
        assertSafeLogs(device.fcmToken, payload.payload!!)
        assertReportedFailuresCarryCause()
    }

    @Test
    fun `generic not found message is not evidence that an FCM token is invalid`() {
        val owner = TestUsers.user(123456, name = null)
        val device = device(owner, "synthetic-private-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(device.target()))
        val failure = IllegalStateException(
            "not found: synthetic-provider-message ${device.fcmToken} ${payload.payload}",
            IllegalArgumentException("synthetic-nested-cause"),
        )
        doAnswer { throw failure }.`when`(fcmService).sendDataMessage(device.fcmToken, payload, owner.isu, expiresAt)

        service.sendDataMessageToUser(owner.id, payload, expiresAt)

        verify(repository, never()).delete(any(Device::class.java))
        verify(deliveryStore, never()).removeIfTokenMatches(device.target())
        verify(repository, never()).findByFcmToken(anyString())
        assertTrue(logs.list.isNotEmpty())
        assertSafeLogs(device.fcmToken, payload.payload!!)
        assertReportedFailuresCarryCause()
    }

    @Test
    fun `successful delivery does not remove or expose registration`() {
        val owner = TestUsers.user(123456, name = null)
        val device = device(owner, "synthetic-private-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(device.target()))

        service.sendDataMessageToUser(owner.id, payload, expiresAt)

        verify(fcmService).sendDataMessage(device.fcmToken, payload, owner.isu, expiresAt)
        verify(repository, never()).delete(any(Device::class.java))
        verify(deliveryStore, never()).removeIfTokenMatches(device.target())
        assertSafeLogs(device.fcmToken, payload.payload!!)
    }

    @Test
    fun `no registered devices do not trigger FCM or expose the payload`() {
        val owner = TestUsers.user(123456, name = null)
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(emptyList())

        service.sendDataMessageToUser(owner.id, payload, expiresAt)

        verifyNoInteractions(fcmService)
        assertSafeLogs(payload.payload!!)
    }

    @Test
    fun `transport target diagnostics never expose its token`() {
        val target = DeviceDeliveryTarget(UUID.randomUUID(), "synthetic-hidden-device-token")
        assertTrue(target.toString().contains(target.deviceId.toString()))
        assertFalse(target.toString().contains(target.fcmToken))
    }

    private fun Device.target() = DeviceDeliveryTarget(id, fcmToken, user.isu)

    private fun firebaseFailure(code: MessagingErrorCode?): FirebaseMessagingException = mock(FirebaseMessagingException::class.java).also {
        `when`(it.messagingErrorCode).thenReturn(code)
        `when`(it.message).thenReturn("synthetic-provider-message")
        `when`(it.cause).thenReturn(IllegalArgumentException("synthetic-nested-cause"))
    }

    /** Holds for every line, including the ones that report no failure at all. */
    private fun assertSafeLogs(vararg secrets: String) {
        val forbidden = secrets.toList() + listOf("synthetic-provider-message", "synthetic-nested-cause")
        logs.list.forEach { event ->
            val text = event.formattedMessage + event.message + event.argumentArray.orEmpty().joinToString()
            forbidden.forEach { assertFalse(text.contains(it), "A synthetic secret escaped into application diagnostics") }
        }
    }

    /** A reported failure is useless to an operator without the cause its message deliberately omits. */
    private fun assertReportedFailuresCarryCause() {
        val failures = logs.list.filter { it.formattedMessage.startsWith("Failed to") }
        assertTrue(failures.isNotEmpty(), "Expected at least one reported failure")
        failures.forEach { assertNotNull(it.throwableProxy, "Operators need the provider cause") }
    }

    private fun Device.registration() = listOf(platform, alertsAllowed, appVersion, deviceName, lastLogin)

    private fun Device.reported() = listOf(appVersion, appBuild, appPlatform, appDistribution, appVersionSeenAt)

    private fun device(user: User, token: String) = Device(
        user = user,
        fcmToken = token,
        deviceName = "Android",
        lastLogin = TestClock.now().minusSeconds(86_400),
    )
}
