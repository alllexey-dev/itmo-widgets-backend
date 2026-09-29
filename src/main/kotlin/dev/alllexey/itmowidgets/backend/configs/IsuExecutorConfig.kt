package dev.alllexey.itmowidgets.backend.configs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class IsuExecutorConfig {
    /** One ISU session and one request at a time; a kick during a run is dropped, the run rereads the queue. */
    @Bean
    fun isuExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        queueCapacity = 0
        setThreadNamePrefix("isu-")
        setWaitForTasksToCompleteOnShutdown(false)
    }
}
