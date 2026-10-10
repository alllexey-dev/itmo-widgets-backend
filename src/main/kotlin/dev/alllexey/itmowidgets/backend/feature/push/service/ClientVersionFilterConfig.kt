package dev.alllexey.itmowidgets.backend.feature.push.service

import dev.alllexey.itmowidgets.backend.feature.push.web.ClientVersionFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration(proxyBeanMethods = false)
class ClientVersionFilterConfig {
    /** The lowest precedence puts it after Spring Security's chain, so the request's authentication is known. */
    @Bean
    fun clientVersionFilter(clientVersions: ClientVersionService): FilterRegistrationBean<ClientVersionFilter> =
        FilterRegistrationBean(ClientVersionFilter(clientVersions)).apply {
            order = Ordered.LOWEST_PRECEDENCE
            addUrlPatterns("/api/*")
        }
}
