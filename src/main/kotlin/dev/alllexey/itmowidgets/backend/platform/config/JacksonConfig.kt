package dev.alllexey.itmowidgets.backend.platform.config

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.DeserializationFeature

/**
 * Keeps request reading as lenient as it was on Jackson 2, which installed clients were built against:
 * `null` for a JVM primitive reads as its default, and content after the JSON value is ignored.
 * Jackson 3 rejects both by default.
 */
@Configuration
class JacksonConfig {

    @Bean
    fun jackson2RequestReading(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { builder ->
        builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }
}
