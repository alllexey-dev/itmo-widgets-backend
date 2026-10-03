package dev.alllexey.itmowidgets.backend.feature.reviews.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/**
 * ISU access for the "taught the author" check. [keycloakIdentity] only seeds the
 * `ISU_KEYCLOAK_IDENTITY` row of `service_credentials`; the stored value is authoritative.
 */
@ConfigurationProperties("itmowidgets.isu")
data class IsuConfig(
    val keycloakIdentity: String? = null,
    val baseUrl: URI = URI.create("https://isu.ifmo.ru"),
    val identityUrl: URI = URI.create("https://id.itmo.ru/auth/realms/itmo/"),
    val requestDelay: Duration = Duration.ofSeconds(2),
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val userAgent: String = BROWSER_USER_AGENT,
) {
    init {
        require(isWebUrl(baseUrl)) { "ISU base URL must be an http(s) URL" }
        require(isWebUrl(identityUrl)) { "ISU identity URL must be an http(s) URL" }
        require(!requestDelay.isNegative) { "ISU request delay must not be negative" }
        require(connectTimeout > Duration.ZERO) { "ISU connect timeout must be positive" }
        require(requestTimeout > Duration.ZERO) { "ISU request timeout must be positive" }
        require(userAgent.isNotBlank()) { "ISU user agent must not be blank" }
    }

    override fun toString(): String =
        "IsuConfig(keycloakIdentity=${if (keycloakIdentity.isNullOrBlank()) "none" else "redacted"}, baseUrl=$baseUrl, " +
            "identityUrl=$identityUrl, requestDelay=$requestDelay, connectTimeout=$connectTimeout, requestTimeout=$requestTimeout)"

    companion object {
        /** ISU serves its pages only to browser user agents. */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36"

        private fun isWebUrl(uri: URI): Boolean = uri.scheme in setOf("http", "https") && uri.host != null
    }
}
