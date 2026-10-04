package dev.alllexey.itmowidgets.backend.platform.error

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(RestrictedException::class)
    fun handleRestrictedException(ex: RestrictedException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.FORBIDDEN, ex.message ?: "Action restricted by moderation", ErrorCode.RESTRICTED)

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFoundException(ex: NotFoundException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.NOT_FOUND, ex.message ?: "Resource not found", ErrorCode.NOT_FOUND)

    @ExceptionHandler(PermissionDeniedException::class)
    fun handlePermissionDeniedException(ex: PermissionDeniedException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.FORBIDDEN, ex.message ?: "Access denied", ErrorCode.PERMISSION_DENIED)

    @ExceptionHandler(BusinessRuleException::class)
    fun handleBusinessRuleException(ex: BusinessRuleException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.CONFLICT, ex.message ?: "Conflict with business rules", ErrorCode.BUSINESS_RULE_VIOLATION)

    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequestsException(ex: TooManyRequestsException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.TOO_MANY_REQUESTS, ex.message ?: "Too many requests", ErrorCode.RATE_LIMITED)

    @ExceptionHandler(InvalidRequestDataException::class)
    fun handleInvalidRequestDataException(ex: InvalidRequestDataException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid data provided", ErrorCode.INVALID_REQUEST_DATA)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableRequest(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, "Invalid request body", ErrorCode.INVALID_REQUEST)

    @ExceptionHandler(org.springframework.beans.TypeMismatchException::class)
    fun handleInvalidParameter(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, "Invalid request parameter", ErrorCode.INVALID_REQUEST)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, "Invalid request fields", ErrorCode.VALIDATION_ERROR)

    @ExceptionHandler(
        javax.naming.AuthenticationException::class,
        org.springframework.security.core.AuthenticationException::class,
    )
    fun handleAuthenticationException(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.UNAUTHORIZED, "Authentication failed", ErrorCode.UNAUTHORIZED)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.FORBIDDEN, "Access denied", ErrorCode.ACCESS_DENIED)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn("Persistence operation failed: {}", SafeDiagnostics.describe(ex), ex)
        return if (SafeDiagnostics.isExpectedUniqueConflict(ex)) {
            response(HttpStatus.CONFLICT, "Resource already exists", ErrorCode.CONFLICT)
        } else {
            response(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred", ErrorCode.INTERNAL_SERVER_ERROR)
        }
    }

    @ExceptionHandler(RuntimeException::class)
    fun handleRuntimeException(ex: RuntimeException): ResponseEntity<ApiResponse<Unit>> = unexpected(ex)

    @ExceptionHandler(Exception::class)
    fun handleAllExceptions(ex: Exception): ResponseEntity<ApiResponse<Unit>> = unexpected(ex)

    private fun unexpected(ex: Exception): ResponseEntity<ApiResponse<Unit>> {
        // Preserve framework 4xx (including removed routes), never expose its request/SQL details.
        if (ex is ErrorResponse && ex.statusCode.is4xxClientError) {
            val code = if (ex.statusCode.value() == 404) ErrorCode.NOT_FOUND else ErrorCode.INVALID_REQUEST
            return ResponseEntity.status(ex.statusCode).body(ApiResponse.error("Request could not be handled", code))
        }
        logger.error("Unhandled operation failure: {}", SafeDiagnostics.describe(ex), ex)
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred", ErrorCode.INTERNAL_SERVER_ERROR)
    }

    private fun response(status: HttpStatus, message: String, code: ErrorCode): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity.status(status).body(ApiResponse.error(message, code))

    companion object {
        private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
