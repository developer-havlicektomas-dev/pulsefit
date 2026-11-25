package dev.havlicektomas.pulsefit.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Turns every error into an RFC 9457 `application/problem+json` body with a `code`
 * from [ErrorCode]; validation failures also carry `errors: [{field, message}]`.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    data class FieldError(val field: String, val message: String)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(ex: ApiException, request: WebRequest): ResponseEntity<Any>? {
        val problem = ProblemDetail.forStatusAndDetail(ex.code.status, ex.message)
        problem.setProperty(CODE, ex.code.name)
        ex.properties.forEach(problem::setProperty)
        return handleExceptionInternal(ex, problem, HttpHeaders(), ex.code.status, request)
    }

    /** Reached from the security filter chain via its authentication entry point. */
    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException, request: WebRequest): ResponseEntity<Any>? {
        val status = HttpStatus.UNAUTHORIZED
        val problem = ProblemDetail.forStatusAndDetail(status, "Missing, invalid or expired access token.")
        val headers = HttpHeaders().apply { set(HttpHeaders.WWW_AUTHENTICATE, "Bearer") }
        return handleExceptionInternal(ex, problem, headers, status, request)
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception, request: WebRequest): ResponseEntity<Any>? {
        log.error("Unhandled exception", ex)
        val status = HttpStatus.INTERNAL_SERVER_ERROR
        val problem = ProblemDetail.forStatusAndDetail(status, "Unexpected server error.")
        return handleExceptionInternal(ex, problem, HttpHeaders(), status, request)
    }

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.bindingResult.fieldErrors.map { FieldError(it.field, it.defaultMessage ?: INVALID) } +
            ex.bindingResult.globalErrors.map { FieldError(it.objectName, it.defaultMessage ?: INVALID) }
        return validationFailed(ex, errors, headers, request)
    }

    override fun handleHandlerMethodValidationException(
        ex: HandlerMethodValidationException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors = ex.parameterValidationResults.flatMap { result ->
            val field = result.methodParameter.parameterName ?: "arg${result.methodParameter.parameterIndex}"
            result.resolvableErrors.map { FieldError(field, it.defaultMessage ?: INVALID) }
        }
        return validationFailed(ex, errors, headers, request)
    }

    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem = body ?: (ex as? ErrorResponse)?.body
        if (problem is ProblemDetail && problem.properties?.containsKey(CODE) != true) {
            problem.setProperty(CODE, ErrorCode.forStatus(statusCode).name)
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request)
    }

    private fun validationFailed(
        ex: Exception,
        errors: List<FieldError>,
        headers: HttpHeaders,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val status = ErrorCode.VALIDATION_FAILED.status
        val problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.")
        problem.setProperty(CODE, ErrorCode.VALIDATION_FAILED.name)
        problem.setProperty("errors", errors)
        return handleExceptionInternal(ex, problem, headers, status, request)
    }

    private companion object {
        const val CODE = "code"
        const val INVALID = "is invalid"
    }
}
