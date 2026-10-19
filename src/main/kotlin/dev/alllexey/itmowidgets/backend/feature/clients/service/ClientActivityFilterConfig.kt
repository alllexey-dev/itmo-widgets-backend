package dev.alllexey.itmowidgets.backend.feature.clients.service

import dev.alllexey.itmowidgets.backend.feature.clients.web.ClientActivityFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration(proxyBeanMethods = false)
class ClientActivityFilterConfig {
    /** The lowest precedence puts it after Spring Security's chain, so the request's authentication is known. */
    @Bean
    fun clientActivityFilter(clients: ClientActivityService): FilterRegistrationBean<ClientActivityFilter> =
        FilterRegistrationBean(ClientActivityFilter(clients)).apply {
            order = Ordered.LOWEST_PRECEDENCE
            addUrlPatterns("/api/*")
        }
}
