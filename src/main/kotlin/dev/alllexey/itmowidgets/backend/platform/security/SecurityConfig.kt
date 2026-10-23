package dev.alllexey.itmowidgets.backend.platform.security

import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.json.JsonMapper

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val connectionAuthFilter: ConnectionAuthFilter,
    private val jwtAuthFilter: JwtAuthFilter,
    private val jsonMapper: JsonMapper,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    // The auth filters run once per request and skip these re-dispatches, so they carry no context; the
                    // REQUEST dispatch before them was already authorized (a parked agent poll answers via ASYNC).
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                    .requestMatchers("/api/app/**").permitAll()
                    // A client asks for and claims a pairing before it has any credential.
                    .requestMatchers(HttpMethod.POST, "/api/pair/challenges").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/pair/challenges/*/claim").permitAll()
                    .anyRequest().authenticated()
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // Anonymous callers of a protected route get 401; access denials of a signed-in caller stay 403.
            .exceptionHandling { it.authenticationEntryPoint(UnauthorizedEntryPoint(jsonMapper)) }
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(connectionAuthFilter, JwtAuthFilter::class.java)

        return http.build()
    }
}
