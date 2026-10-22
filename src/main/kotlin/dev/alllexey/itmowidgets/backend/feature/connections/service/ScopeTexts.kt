package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.web.ScopeInfo
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.stereotype.Service
import java.util.Locale

/**
 * Scope titles and descriptions in the caller's language (`Accept-Language`, through [LocaleContextHolder]) from
 * `messages/scopes_<lang>.properties`; a language without a file gets [FALLBACK] (docs/contracts/connections.md#scope-texts).
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
        messages.getMessage("scope.${scope.name}.title", null, locale),
        messages.getMessage("scope.${scope.name}.description", null, locale),
    )

    companion object {
        val FALLBACK: Locale = Locale.of("ru")
    }
}
