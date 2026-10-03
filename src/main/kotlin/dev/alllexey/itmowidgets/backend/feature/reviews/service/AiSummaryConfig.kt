package dev.alllexey.itmowidgets.backend.feature.reviews.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.time.ZoneId

/**
 * AI summaries of teacher reviews through the Gemini API, reached only through the `gemini-proxy` sidecar.
 * [apiKey] only seeds the `GEMINI_API_KEY` row of `service_credentials`; the stored value is authoritative.
 * [dailyRequestBudget] is shared by the nightly run and admin actions and counts per [budgetZone] day.
 */
@ConfigurationProperties("itmowidgets.ai-summary")
data class AiSummaryConfig(
    val enabled: Boolean = false,
    val apiKey: String? = null,
    val model: String = "",
    val baseUrl: URI = URI.create("https://generativelanguage.googleapis.com"),
    val proxyHost: String = "",
    val proxyPort: Int = 3128,
    val dailyRequestBudget: Int = 0,
    val requestDelay: Duration = Duration.ofSeconds(10),
    val budgetZone: ZoneId = ZoneId.of("America/Los_Angeles"),
    val maxInputReviews: Int = 60,
    val maxInputChars: Int = 60_000,
    val maxAttempts: Int = 3,
    val temperature: Double = 0.2,
    val maxOutputTokens: Int = 2048,
    val thinkingBudget: Int? = null,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(90),
) {
    init {
        require(baseUrl.scheme in setOf("http", "https") && baseUrl.host != null) { "Gemini base URL must be an http(s) URL" }
        require(!requestDelay.isNegative) { "AI summary request delay must not be negative" }
        require(connectTimeout > Duration.ZERO) { "Gemini connect timeout must be positive" }
        require(requestTimeout > Duration.ZERO) { "Gemini request timeout must be positive" }
        require(maxInputReviews in 3..200) { "AI summary input reviews must be between 3 and 200" }
        require(maxInputChars in 1000..200_000) { "AI summary input characters must be between 1000 and 200000" }
        require(maxAttempts in 1..10) { "AI summary attempts must be between 1 and 10" }
        require(temperature in 0.0..2.0) { "Gemini temperature must be between 0 and 2" }
        require(maxOutputTokens in 256..65_536) { "Gemini output tokens must be between 256 and 65536" }
        require(thinkingBudget == null || thinkingBudget >= 0) { "Gemini thinking budget must not be negative" }
        if (enabled) {
            require(MODEL.matches(model)) { "Gemini model must be a model id" }
            require(proxyHost.isNotBlank()) { "Gemini proxy host must be set" }
            require(proxyPort in 1..65_535) { "Gemini proxy port must be between 1 and 65535" }
            require(dailyRequestBudget > 0) { "AI summary daily request budget must be positive" }
        }
    }

    override fun toString(): String =
        "AiSummaryConfig(enabled=$enabled, apiKey=${if (apiKey.isNullOrBlank()) "none" else "redacted"}, model=$model, " +
            "baseUrl=$baseUrl, proxyHost=$proxyHost, proxyPort=$proxyPort, dailyRequestBudget=$dailyRequestBudget, " +
            "requestDelay=$requestDelay, budgetZone=$budgetZone, maxInputReviews=$maxInputReviews, " +
            "maxInputChars=$maxInputChars, maxAttempts=$maxAttempts, temperature=$temperature, " +
            "maxOutputTokens=$maxOutputTokens, thinkingBudget=$thinkingBudget, connectTimeout=$connectTimeout, " +
            "requestTimeout=$requestTimeout)"

    private companion object {
        val MODEL = Regex("^[a-z0-9][a-z0-9.-]{2,99}$")
    }
}
