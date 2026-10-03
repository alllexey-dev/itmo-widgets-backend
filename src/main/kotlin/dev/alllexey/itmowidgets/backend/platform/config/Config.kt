package dev.alllexey.itmowidgets.backend.platform.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.annotation.EnableRetry
import java.time.Clock
import java.time.ZoneId

@Configuration
@EnableRetry
class Config {

    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Europe/Moscow"))
}
