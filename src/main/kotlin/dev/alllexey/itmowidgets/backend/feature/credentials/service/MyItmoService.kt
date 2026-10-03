package dev.alllexey.itmowidgets.backend.feature.credentials.service

import api.myitmo.MyItmo
import api.myitmo.model.other.TokenResponse
import api.myitmo.storage.Storage
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

@Service
@Order(1)
class MyItmoService(
    private val credentials: ServiceCredentialStore,
    private val myItmoConfig: MyItmoConfig,
) : Storage, ApplicationListener<ContextRefreshedEvent> {

    lateinit var myItmo: MyItmo

    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        credentials.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, myItmoConfig.refreshToken)
        myItmo = MyItmo().also { it.storage = this }
    }

    override fun update(response: TokenResponse) = credentials.rotateMyItmo(response)

    override fun getAccessToken() = credentials.myItmoSnapshot().accessToken

    override fun getAccessExpiresAt() = credentials.myItmoSnapshot().accessExpiresAt

    override fun getRefreshToken() = credentials.myItmoSnapshot().refreshToken

    override fun getRefreshExpiresAt() = credentials.myItmoSnapshot().refreshExpiresAt

    override fun getIdToken() = credentials.myItmoSnapshot().idToken

    override fun setAccessToken(accessToken: String?) = credentials.write(ServiceCredential.MY_ITMO_ACCESS_TOKEN, accessToken)

    override fun setAccessExpiresAt(accessExpiresAt: Long) =
        credentials.writeExpiry(ServiceCredential.MY_ITMO_ACCESS_TOKEN, accessExpiresAt)

    override fun setRefreshToken(refreshToken: String?) = credentials.write(ServiceCredential.MY_ITMO_REFRESH_TOKEN, refreshToken)

    override fun setRefreshExpiresAt(refreshExpiresAt: Long) =
        credentials.writeExpiry(ServiceCredential.MY_ITMO_REFRESH_TOKEN, refreshExpiresAt)

    override fun setIdToken(idToken: String?) = credentials.write(ServiceCredential.MY_ITMO_ID_TOKEN, idToken)

    override fun toTokenResponse(): TokenResponse {
        // The Storage default reads three times and can combine different rotations.
        val snapshot = credentials.myItmoSnapshot()
        return TokenResponse().apply {
            accessToken = snapshot.accessToken
            refreshToken = snapshot.refreshToken
            idToken = snapshot.idToken
        }
    }

    /** MyITMO rejected the technical credential while serving [where]. */
    fun recordAuthFailure(where: String) =
        credentials.recordFailure(ServiceCredential.MY_ITMO_REFRESH_TOKEN, ServiceCredentialStatus.FAILED, "AUTH $where")
}
