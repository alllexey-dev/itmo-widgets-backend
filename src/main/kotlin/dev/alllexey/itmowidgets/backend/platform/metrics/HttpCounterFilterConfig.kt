package dev.alllexey.itmowidgets.backend.platform.metrics

import jakarta.servlet.DispatcherType
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@Configuration(proxyBeanMethods = false)
class HttpCounterFilterConfig {
    /**
     * The highest precedence puts it outside every other filter: the body limit (next), Spring Security (-100) and the
     * client filters after it, so an answer any of them gives is counted. The handler mapping is resolved on first use:
     * filters are created before the MVC infrastructure.
     */
    @Bean
    fun httpCounterFilter(
        counters: DailyCounters,
        mappings: ObjectProvider<RequestMappingHandlerMapping>,
    ): FilterRegistrationBean<HttpCounterFilter> = FilterRegistrationBean(HttpCounterFilter(counters, mappings)).apply {
        order = Ordered.HIGHEST_PRECEDENCE
        addUrlPatterns("/api/*")
        setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC)
    }
}
