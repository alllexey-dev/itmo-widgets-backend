package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmoapi.itmoid.TokenSet
import dev.alllexey.itmoapi.itmoid.TokenStorage
import dev.alllexey.itmoapi.myitmo.MyItmoClient
import dev.alllexey.itmoapi.myitmo.MyItmoConfiguration
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredentialStatus
import io.ktor.client.engine.HttpClientEngine
import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Clock
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/** The MyITMO client of the technical account; its session lives in [ServiceCredentialStore]. */
@Service
@Order(1)
class MyItmoService(
    private val credentials: ServiceCredentialStore,
    private val myItmoConfig: MyItmoConfig,
    private val myItmoEngine: HttpClientEngine,
    private val clock: Clock,
) : TokenStorage,
    ApplicationListener<ContextRefreshedEvent> {

    lateinit var myItmo: MyItmoClient

    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        credentials.initializeFromBootstrap(ServiceCredential.MY_ITMO_REFRESH_TOKEN, myItmoConfig.refreshToken)
        val time = object : kotlin.time.Clock {
            override fun now() = clock.instant().toKotlinInstant()
        }
        myItmo = MyItmoClient(MyItmoConfiguration.DEFAULT, this, myItmoEngine, time)
    }

    override suspend fun read(): TokenSet? {
        val snapshot = credentials.myItmoSnapshot()
        val refreshToken = snapshot.refreshToken ?: return null
        // A seeded or replaced refresh token has no tokens issued yet: an expired placeholder makes the client refresh first.
        val issued = snapshot.accessToken != null && snapshot.idToken != null
        return TokenSet(
            snapshot.accessToken ?: NOT_ISSUED,
            epochMillis(if (issued) snapshot.accessExpiresAt else 0),
            refreshToken,
            epochMillis(snapshot.refreshExpiresAt),
            snapshot.idToken ?: NOT_ISSUED,
        )
    }

    /** Only the client's refresh writes here; Backend never ends the technical account's session. */
    override suspend fun write(tokens: TokenSet?) {
        credentials.rotateMyItmo(checkNotNull(tokens) { "Backend never ends the MyITMO session" })
    }

    /** MyITMO rejected the technical credential while serving [where]. */
    fun recordAuthFailure(where: String) =
        credentials.recordFailure(ServiceCredential.MY_ITMO_REFRESH_TOKEN, ServiceCredentialStatus.FAILED, "AUTH $where")

    /** `0`, an unknown expiry, reads as expired, as it did on 1.x. */
    private fun epochMillis(value: Long) = Instant.fromEpochMilliseconds(value)

    private companion object {
        const val NOT_ISSUED = "not-issued"
    }
}
