package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.web.ScopeInfo
import org.springframework.context.NoSuchMessageException
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.stereotype.Service
import java.util.Locale

/**
 * Scope titles and descriptions in the caller's language (`Accept-Language`, through [LocaleContextHolder]) from
 * `messages/scopes_<lang>.properties`, and those of the other grants Backend makes (`identity`); a language without a
 * file, or a text its file lacks, gets [FALLBACK] (docs/contracts/connections.md#scope-texts).
 */
@Service
class ScopeTexts {
    private val messages = ResourceBundleMessageSource().apply {
        setBasename("messages/scopes")
        setDefaultEncoding(Charsets.UTF_8.name())
        setFallbackToSystemLocale(false)
        setDefaultLocale(FALLBACK)
    }

    fun info(scope: ConnectionScope, locale: Locale = LocaleContextHolder.getLocale()) = ScopeInfo(
        scope,
        message("scope.${scope.name}.title", locale),
        message("scope.${scope.name}.description", locale),
    )

    /** The title and description of the `identity` grant. */
    fun identity(locale: Locale = LocaleContextHolder.getLocale()): Pair<String, String> =
        message("grant.identity.title", locale) to message("grant.identity.description", locale)

    private fun message(key: String, locale: Locale): String = try {
        messages.getMessage(key, null, locale)
    } catch (_: NoSuchMessageException) {
        messages.getMessage(key, null, FALLBACK)
    }

    companion object {
        val FALLBACK: Locale = Locale.of("ru")
    }
}
