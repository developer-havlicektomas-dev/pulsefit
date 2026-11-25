package dev.havlicektomas.pulsefit.auth

import dev.havlicektomas.pulsefit.IntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get

class SecurityConfigTest : IntegrationTest() {

    @Test
    fun `api without a token is 401 problem`() {
        mockMvc.get("/v1/me").andExpect {
            status { isUnauthorized() }
            header { string(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(401) }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `api with a malformed token is 401 problem`() {
        mockMvc.get("/v1/me") {
            header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `other actuator endpoints are not public`() {
        mockMvc.get("/actuator/env").andExpect {
            status { isUnauthorized() }
        }
    }
}
