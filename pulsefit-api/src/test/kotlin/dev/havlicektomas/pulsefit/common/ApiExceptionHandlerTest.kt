package dev.havlicektomas.pulsefit.common

import dev.havlicektomas.pulsefit.IntegrationTest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Import(ApiExceptionHandlerTest.ProbeController::class)
class ApiExceptionHandlerTest : IntegrationTest() {

    data class TitleRequest(@field:NotBlank @field:Size(max = 80) val title: String)

    @RestController
    class ProbeController {
        @PostMapping("/v1/probe/titles")
        fun create(@Valid @RequestBody body: TitleRequest) = body

        @GetMapping("/v1/probe/items/{id}")
        fun item(@PathVariable id: UUID): Nothing = throw ApiException(ErrorCode.NOT_FOUND, "No item $id.")

        @GetMapping("/v1/probe/conflict")
        fun conflict(): Nothing =
            throw ApiException(ErrorCode.VERSION_CONFLICT, "Stale version.", mapOf("current" to mapOf("version" to 4)))

        @GetMapping("/v1/probe/boom")
        fun boom(): Nothing = error("boom")
    }

    @Test
    fun `bean validation failure is 422 with field errors`() {
        mockMvc.post("/v1/probe/titles") {
            with(jwt())
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":" "}"""
        }.andExpect {
            status { isUnprocessableContent() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.errors[0].field") { value("title") }
            jsonPath("$.errors[0].message") { isNotEmpty() }
        }
    }

    @Test
    fun `unparseable body is 400 malformed`() {
        mockMvc.post("/v1/probe/titles") {
            with(jwt())
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MALFORMED_REQUEST") }
        }
    }

    @Test
    fun `bad uuid is 400 malformed`() {
        mockMvc.get("/v1/probe/items/not-a-uuid") { with(jwt()) }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MALFORMED_REQUEST") }
        }
    }

    @Test
    fun `api exception keeps its code, detail and extra properties`() {
        val id = UUID.randomUUID()
        mockMvc.get("/v1/probe/items/$id") { with(jwt()) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
            jsonPath("$.detail") { value("No item $id.") }
        }
        mockMvc.get("/v1/probe/conflict") { with(jwt()) }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("VERSION_CONFLICT") }
            jsonPath("$.current.version") { value(4) }
        }
    }

    @Test
    fun `unknown route is 404 not found`() {
        mockMvc.get("/v1/no-such-thing") { with(jwt()) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_FOUND") }
        }
    }

    @Test
    fun `unexpected exception is 500 without internals`() {
        mockMvc.get("/v1/probe/boom") { with(jwt()) }.andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("INTERNAL_ERROR") }
            jsonPath("$.detail") { value("Unexpected server error.") }
        }
    }
}
