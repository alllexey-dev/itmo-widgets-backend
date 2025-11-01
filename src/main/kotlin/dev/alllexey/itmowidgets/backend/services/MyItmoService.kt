package dev.alllexey.itmowidgets.backend.services

import api.myitmo.MyItmo
import api.myitmo.storage.Storage
import dev.alllexey.itmowidgets.backend.configs.MyItmoConfig
import dev.alllexey.itmowidgets.backend.model.MyItmoStorage
import dev.alllexey.itmowidgets.backend.repositories.MyItmoRepository
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Order(1)
class MyItmoService(private val myItmoRepository: MyItmoRepository, private val myItmoConfig: MyItmoConfig) : Storage, ApplicationListener<ContextRefreshedEvent> {

    lateinit var myItmo: MyItmo

    @Transactional
    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        if (!myItmoConfig.refreshToken.isNullOrBlank()) {
            refreshToken = myItmoConfig.refreshToken
            refreshExpiresAt = Long.MAX_VALUE
        }
        myItmo = MyItmo()
        myItmo.storage = this
    }
    fun getStorage(): MyItmoStorage {
        return myItmoRepository.findById(1L).orElseGet {
            val storage = MyItmoStorage(
                1L,
                refreshToken = null,
                refreshTokenExpiresAt = 0,
                accessToken = null,
                accessTokenExpiresAt = 0,
                idToken = null
            )

            myItmoRepository.save(storage)
        }
    }

    @Transactional
    override fun getAccessToken() = getStorage().accessToken

    @Transactional
    override fun getAccessExpiresAt() = getStorage().accessTokenExpiresAt

    @Transactional
    override fun getRefreshToken() = getStorage().refreshToken

    @Transactional
    override fun getRefreshExpiresAt() = getStorage().refreshTokenExpiresAt

    @Transactional
    override fun getIdToken() = getStorage().idToken

    @Transactional
    override fun setAccessToken(accessToken: String?) {
        getStorage().accessToken = accessToken
    }

    @Transactional
    override fun setAccessExpiresAt(accessExpiresAt: Long) {
        getStorage().accessTokenExpiresAt = accessExpiresAt
    }

    @Transactional
    override fun setRefreshToken(refreshToken: String?) {
        getStorage().refreshToken = refreshToken
    }

    @Transactional
    override fun setRefreshExpiresAt(refreshExpiresAt: Long) {
        getStorage().refreshTokenExpiresAt = refreshExpiresAt
    }

    @Transactional
    override fun setIdToken(idToken: String?) {
        getStorage().idToken = idToken
    }
}