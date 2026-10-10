package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmoapi.core.defaultEngine
import io.ktor.client.engine.HttpClientEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MyItmoEngineConfig {
    /** MyItmoApi's OkHttp engine without cookies, cache or redirects; the application owns and closes it. */
    @Bean(destroyMethod = "close")
    fun myItmoEngine(): HttpClientEngine = defaultEngine()
}
