package dev.havlicektomas.pulsefit.common

/**
 * Thrown by services to end a request with a problem response.
 * [properties] are added to the problem body (e.g. the current representation on a 409).
 */
class ApiException(
    val code: ErrorCode,
    message: String,
    val properties: Map<String, Any?> = emptyMap(),
) : RuntimeException(message)
