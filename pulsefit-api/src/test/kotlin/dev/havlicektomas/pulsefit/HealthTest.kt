package dev.havlicektomas.pulsefit

import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class HealthTest : IntegrationTest() {

    @Test
    fun `health is public and UP with the database connected`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }

    @Test
    fun `readiness probe is public`() {
        mockMvc.get("/actuator/health/readiness").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }
}
