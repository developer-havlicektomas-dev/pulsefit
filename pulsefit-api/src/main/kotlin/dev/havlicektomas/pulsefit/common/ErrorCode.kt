package dev.havlicektomas.pulsefit.common

import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode

/** Machine-readable `code` carried by every problem response (backend-spec §6.4). */
enum class ErrorCode(val status: HttpStatus) {
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    ID_CONFLICT(HttpStatus.CONFLICT),
    DELETED(HttpStatus.GONE),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    companion object {
        /** Default code for a status when the thrower did not pick one. */
        fun forStatus(status: HttpStatusCode): ErrorCode =
            entries.firstOrNull { it.status.value() == status.value() }
                ?: if (status.is5xxServerError) INTERNAL_ERROR else MALFORMED_REQUEST
    }
}
