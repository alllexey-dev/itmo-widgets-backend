package dev.alllexey.itmowidgets.backend.services

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.LoggerFactory
import dev.alllexey.itmowidgets.backend.dto.DeviceDeliveryTarget
import dev.alllexey.itmowidgets.backend.model.Device
import dev.alllexey.itmowidgets.backend.model.User
import dev.alllexey.itmowidgets.backend.repositories.DeviceRepository
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class DeviceServiceTest {

    private val repository = mock(DeviceRepository::class.java)
    private val fcmService = mock(FcmService::class.java)
    private val userService = mock(UserService::class.java)
    private val deliveryStore = mock(DeviceDeliveryStore::class.java)
    private val service = DeviceService(repository, fcmService, userService, deliveryStore)

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
    fun `unregisters a device owned by the authenticated user`() {
        val user = user(123456)
        val device = device(user, "current-token")
        `when`(repository.findByFcmToken("current-token")).thenReturn(device)

        service.unregisterDevice(user.id, "  current-token  ")

        verify(repository).delete(device)
    }

    @Test
    fun `does not unregister another user's device`() {
        val owner = user(123456)
        val caller = user(654321)
        val device = device(owner, "foreign-token")
        `when`(repository.findByFcmToken("foreign-token")).thenReturn(device)

        service.unregisterDevice(caller.id, "foreign-token")

        verify(repository, never()).delete(device)
    }

    @Test
    fun `does not expose whether a token exists`() {
        val caller = user(123456)
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
        val owner = user(123456)
        val failed = device(owner, "synthetic-unregistered-token")
        val valid = device(owner, "synthetic-valid-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(failed.target(), valid.target()))
        val failure = firebaseFailure(MessagingErrorCode.UNREGISTERED)
        doAnswer { throw failure }.`when`(fcmService).sendDataMessage(failed.fcmToken, payload)

        service.sendDataMessageToUser(owner.id, payload)

        verify(deliveryStore).removeIfTokenMatches(failed.target())
        verify(deliveryStore, never()).removeIfTokenMatches(valid.target())
        verifyNoInteractions(repository)
        verify(fcmService).sendDataMessage(valid.fcmToken, payload)
        assertSafeLogs(failed.fcmToken, valid.fcmToken, payload.payload!!)
    }

    @Test
    fun `every Firebase error other than UNREGISTERED retains registration`() {
        val owner = user(123456)
        val device = device(owner, "synthetic-private-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(device.target()))

        (MessagingErrorCode.entries.filter { it != MessagingErrorCode.UNREGISTERED } + listOf(null)).forEach { code ->
            val failure = firebaseFailure(code)
            doAnswer { throw failure }.`when`(fcmService).sendDataMessage(device.fcmToken, payload)
            service.sendDataMessageToUser(owner.id, payload)
        }

        verify(repository, never()).delete(any(Device::class.java))
        verify(deliveryStore, never()).removeIfTokenMatches(device.target())
        verify(repository, never()).findByFcmToken(anyString())
        assertTrue(logs.list.isNotEmpty())
        assertTrue(logs.list.any { it.formattedMessage.contains(device.id.toString()) })
        assertSafeLogs(device.fcmToken, payload.payload!!)
    }

    @Test
    fun `generic not found message is not evidence that an FCM token is invalid`() {
        val owner = user(123456)
        val device = device(owner, "synthetic-private-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(device.target()))
        val failure = IllegalStateException(
            "not found: synthetic-provider-message ${device.fcmToken} ${payload.payload}",
            IllegalArgumentException("synthetic-nested-cause"),
        )
        doAnswer { throw failure }.`when`(fcmService).sendDataMessage(device.fcmToken, payload)

        service.sendDataMessageToUser(owner.id, payload)

        verify(repository, never()).delete(any(Device::class.java))
        verify(deliveryStore, never()).removeIfTokenMatches(device.target())
        verify(repository, never()).findByFcmToken(anyString())
        assertTrue(logs.list.isNotEmpty())
        assertSafeLogs(device.fcmToken, payload.payload!!)
    }

    @Test
    fun `successful delivery does not remove or expose registration`() {
        val owner = user(123456)
        val device = device(owner, "synthetic-private-token")
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(listOf(device.target()))

        service.sendDataMessageToUser(owner.id, payload)

        verify(fcmService).sendDataMessage(device.fcmToken, payload)
        verify(repository, never()).delete(any(Device::class.java))
        verify(deliveryStore, never()).removeIfTokenMatches(device.target())
        assertSafeLogs(device.fcmToken, payload.payload!!)
    }

    @Test
    fun `no registered devices do not trigger FCM or expose the payload`() {
        val owner = user(123456)
        val payload = FcmTypedWrapper<String?>("synthetic-kind", "synthetic-private-payload")
        `when`(deliveryStore.targetsFor(owner.id)).thenReturn(emptyList())

        service.sendDataMessageToUser(owner.id, payload)

        verifyNoInteractions(fcmService)
        assertSafeLogs(payload.payload!!)
    }

    @Test
    fun `transport target diagnostics never expose its token`() {
        val target = DeviceDeliveryTarget(UUID.randomUUID(), "synthetic-hidden-device-token")
        assertTrue(target.toString().contains(target.deviceId.toString()))
        assertFalse(target.toString().contains(target.fcmToken))
    }

    private fun Device.target() = DeviceDeliveryTarget(id, fcmToken)

    private fun firebaseFailure(code: MessagingErrorCode?): FirebaseMessagingException =
        mock(FirebaseMessagingException::class.java).also {
            `when`(it.messagingErrorCode).thenReturn(code)
            `when`(it.message).thenReturn("synthetic-provider-message")
            `when`(it.cause).thenReturn(IllegalArgumentException("synthetic-nested-cause"))
        }

    private fun assertSafeLogs(vararg secrets: String) {
        val forbidden = secrets.toList() + listOf("synthetic-provider-message", "synthetic-nested-cause")
        logs.list.forEach { event ->
            assertNull(event.throwableProxy, "Provider exception must not be attached to a log event")
            val text = event.formattedMessage + event.message + event.argumentArray.orEmpty().joinToString()
            forbidden.forEach { assertFalse(text.contains(it), "A synthetic secret escaped into application diagnostics") }
        }
    }

    private fun user(isu: Int) = User(
        isu = isu,
        pictureUrl = null,
        name = null
    )

    private fun device(user: User, token: String) = Device(
        user = user,
        fcmToken = token,
        deviceName = "Android"
    )
}
