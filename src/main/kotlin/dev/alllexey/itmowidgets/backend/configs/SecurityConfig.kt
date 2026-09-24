package dev.alllexey.itmowidgets.backend.configs

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthFilter: JwtAuthFilter,
    private val webSessionFilter: WebSessionFilter,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/app/**").permitAll()
                    // A browser starts and polls a phone-approved login before it has a session.
                    .requestMatchers(HttpMethod.POST, "/api/web/auth/challenges").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/web/auth/challenges/*").permitAll()
                    .anyRequest().authenticated()
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            // Bearer first; the web session cookie only applies to requests without one.
            .addFilterAfter(webSessionFilter, JwtAuthFilter::class.java)

        return http.build()
    }
}
