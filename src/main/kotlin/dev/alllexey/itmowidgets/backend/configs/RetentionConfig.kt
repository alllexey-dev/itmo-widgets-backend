package dev.alllexey.itmowidgets.backend.configs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("itmowidgets.retention")
data class RetentionConfig(
    val sportUpdateLogDays: Int = 90,
    val batchSize: Int = 1000,
) {
    init {
        require(sportUpdateLogDays > 0) { "Sport update log retention days must be positive" }
        require(batchSize > 0) { "Technical log retention batch size must be positive" }
    }
}
