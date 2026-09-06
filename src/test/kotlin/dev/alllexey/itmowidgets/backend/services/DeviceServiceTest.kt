package dev.alllexey.itmowidgets.backend.services

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
    private val service = DeviceService(repository, fcmService, userService)

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
