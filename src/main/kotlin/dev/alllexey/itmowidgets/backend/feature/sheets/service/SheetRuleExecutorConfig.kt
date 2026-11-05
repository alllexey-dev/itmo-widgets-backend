package dev.alllexey.itmowidgets.backend.feature.sheets.service

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration(proxyBeanMethods = false)
class SheetRuleExecutorConfig {
    /** One analysis run at a time off the request and scheduler threads; the analyzer takes one sheet at a time too. */
    @Bean
    fun sheetRuleExecutor() = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 1
        // No queue: a kick during a run is rejected, and the run picks the new request up itself.
        queueCapacity = 0
        setThreadNamePrefix("sheet-rules-")
        setWaitForTasksToCompleteOnShutdown(false)
    }
}
