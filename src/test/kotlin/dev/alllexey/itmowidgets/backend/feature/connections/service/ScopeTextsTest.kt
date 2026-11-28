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
    fun `identity has its texts in Russian and English, and English falls back to Russian for the scopes`() {
        assertEquals("Имя и номер ИСУ" to "Ваши ФИО и номер ИСУ, один раз при подключении", texts.identity(Locale.of("ru")))
        assertEquals("Имя и номер ИСУ" to "Ваши ФИО и номер ИСУ, один раз при подключении", texts.identity(Locale.GERMANY))
        for (english in listOf(Locale.ENGLISH, Locale.forLanguageTag("en-US"))) {
            assertEquals("Name and ISU number" to "Your full name and ISU number, once when connecting", texts.identity(english))
            assertEquals(texts.info(ConnectionScope.SPORT, Locale.of("ru")), texts.info(ConnectionScope.SPORT, english))
        }
    }

    @Test
    fun `a language without its own scope texts gets the Russian ones, whatever the system locale`() {
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
