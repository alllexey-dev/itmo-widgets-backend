package dev.alllexey.itmowidgets.backend.configs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class AiSummaryExecutorConfig {
    /** One summary run at a time off the single scheduler thread; its pauses between requests never delay other jobs. */
    @Bean
    fun aiSummaryExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        // No queue: a second run is rejected instead of waiting behind the current one.
        queueCapacity = 0
        setThreadNamePrefix("ai-summary-")
        setWaitForTasksToCompleteOnShutdown(false)
    }
}
