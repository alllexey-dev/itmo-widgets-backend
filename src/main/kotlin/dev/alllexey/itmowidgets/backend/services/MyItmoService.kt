package dev.alllexey.itmowidgets.backend.services

import api.myitmo.MyItmo
import api.myitmo.model.other.TokenResponse
import api.myitmo.storage.Storage
import dev.alllexey.itmowidgets.backend.configs.MyItmoConfig
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

@Service
@Order(1)
class MyItmoService(
    private val tokenStore: MyItmoTokenStore,
    private val myItmoConfig: MyItmoConfig,
) : Storage, ApplicationListener<ContextRefreshedEvent> {

    lateinit var myItmo: MyItmo

    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        tokenStore.initializeFromBootstrap(myItmoConfig.refreshToken)
        myItmo = MyItmo().also { it.storage = this }
    }

    override fun update(response: TokenResponse) = tokenStore.update(response)

    override fun getAccessToken() = tokenStore.readSnapshot().accessToken

    override fun getAccessExpiresAt() = tokenStore.readSnapshot().accessExpiresAt

    override fun getRefreshToken() = tokenStore.readSnapshot().refreshToken

    override fun getRefreshExpiresAt() = tokenStore.readSnapshot().refreshExpiresAt

    override fun getIdToken() = tokenStore.readSnapshot().idToken

    override fun setAccessToken(accessToken: String?) = tokenStore.setAccessToken(accessToken)

    override fun setAccessExpiresAt(accessExpiresAt: Long) = tokenStore.setAccessExpiresAt(accessExpiresAt)

    override fun setRefreshToken(refreshToken: String?) = tokenStore.setRefreshToken(refreshToken)

    override fun setRefreshExpiresAt(refreshExpiresAt: Long) = tokenStore.setRefreshExpiresAt(refreshExpiresAt)

    override fun setIdToken(idToken: String?) = tokenStore.setIdToken(idToken)

    override fun toTokenResponse(): TokenResponse {
        // The Storage default reads three times and can combine different rotations.
        val snapshot = tokenStore.readSnapshot()
        return TokenResponse().apply {
            accessToken = snapshot.accessToken
            refreshToken = snapshot.refreshToken
            idToken = snapshot.idToken
        }
    }
}
