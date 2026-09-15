package dev.alllexey.itmowidgets.backend.configs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class FriendshipNotificationConfig {
    @Bean
    fun friendshipNotificationExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 256
        setThreadNamePrefix("friendship-notification-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(10)
        // Abort on saturation: never run FCM on the request thread via CallerRunsPolicy.
    }
}
