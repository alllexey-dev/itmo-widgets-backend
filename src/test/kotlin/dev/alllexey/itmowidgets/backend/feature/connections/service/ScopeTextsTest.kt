package dev.alllexey.itmowidgets.backend.feature.connections.service

import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopeTextsTest {
    private val texts = ScopeTexts()

    @Test
    fun `every scope has a Russian title and description`() {
        for (scope in ConnectionScope.entries) {
            val info = texts.info(scope, Locale.of("ru"))
            assertEquals(scope, info.id)
            assertTrue(info.title.isNotBlank() && info.description.isNotBlank(), scope.name)
        }
        assertEquals("Запись на физкультуру", texts.info(ConnectionScope.SPORT, Locale.of("ru")).title)
    }

    @Test
    fun `a language without its own file gets the Russian texts, whatever the system locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val russian = texts.info(ConnectionScope.LINKS_READ, Locale.of("ru"))
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("en-US"), Locale.GERMANY, Locale.ROOT)) {
                assertEquals(russian, texts.info(ConnectionScope.LINKS_READ, locale), locale.toLanguageTag())
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
