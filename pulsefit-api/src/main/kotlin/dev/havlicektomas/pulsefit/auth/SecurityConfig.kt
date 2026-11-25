package dev.havlicektomas.pulsefit.auth

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * Stateless bearer-token API (backend-spec §4). Health is public; everything else needs a
 * Supabase access token. Audience checks and the current-user resolver come in step 1.3.
 */
@Configuration
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        @Qualifier("handlerExceptionResolver") exceptionResolver: HandlerExceptionResolver,
    ): SecurityFilterChain {
        // Hand authentication failures to ApiExceptionHandler so 401s are problem responses too.
        val entryPoint = AuthenticationEntryPoint { request, response, ex ->
            exceptionResolver.resolveException(request, response, null, ex)
        }
        http {
            csrf { disable() }
            cors { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize("/actuator/health", permitAll)
                authorize("/actuator/health/**", permitAll)
                authorize("/error", permitAll)
                authorize(anyRequest, authenticated)
            }
            oauth2ResourceServer {
                jwt { }
                authenticationEntryPoint = entryPoint
            }
            exceptionHandling { authenticationEntryPoint = entryPoint }
        }
        return http.build()
    }
}
