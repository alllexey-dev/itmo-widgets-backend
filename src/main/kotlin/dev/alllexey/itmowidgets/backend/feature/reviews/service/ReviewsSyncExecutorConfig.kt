package dev.alllexey.itmowidgets.backend.feature.reviews.service

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class ReviewsSyncExecutorConfig {
    /** One run at a time off the single scheduler thread, so a long sync never delays the sport jobs. */
    @Bean
    fun reviewsSyncExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        // No queue: a second run is rejected instead of waiting behind the current one.
        queueCapacity = 0
        setThreadNamePrefix("reviews-sync-")
        setWaitForTasksToCompleteOnShutdown(false)
    }
}
