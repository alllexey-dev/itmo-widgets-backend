package dev.alllexey.itmowidgets.backend.feature.activity.service

import dev.alllexey.itmowidgets.backend.feature.activity.web.ActiveWeekFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration(proxyBeanMethods = false)
class ActiveWeekFilterConfig {
    /** The lowest precedence puts it after Spring Security's chain, so the request's authentication is known. */
    @Bean
    fun activeWeekFilter(recorder: ActiveWeekRecorder): FilterRegistrationBean<ActiveWeekFilter> =
        FilterRegistrationBean(ActiveWeekFilter(recorder)).apply {
            order = Ordered.LOWEST_PRECEDENCE
            addUrlPatterns("/api/*")
        }
}
