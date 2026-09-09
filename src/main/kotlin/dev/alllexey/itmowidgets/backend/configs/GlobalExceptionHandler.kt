package dev.alllexey.itmowidgets.backend.configs

import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.exceptions.SafeDiagnostics
import dev.alllexey.itmowidgets.core.model.ApiResponse
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
    @ExceptionHandler(NotFoundException::class)
    fun handleNotFoundException(ex: NotFoundException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.NOT_FOUND, ex.message ?: "Resource not found", "not_found")

    @ExceptionHandler(PermissionDeniedException::class)
    fun handlePermissionDeniedException(ex: PermissionDeniedException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.FORBIDDEN, ex.message ?: "Access denied", "permission_denied")

    @ExceptionHandler(BusinessRuleException::class)
    fun handleBusinessRuleException(ex: BusinessRuleException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.CONFLICT, ex.message ?: "Conflict with business rules", "business_rule_violation")

    @ExceptionHandler(InvalidRequestDataException::class)
    fun handleInvalidRequestDataException(ex: InvalidRequestDataException): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, ex.message ?: "Invalid data provided", "invalid_request_data")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableRequest(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, "Invalid request body", "invalid_request")

    @ExceptionHandler(org.springframework.beans.TypeMismatchException::class)
    fun handleInvalidParameter(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, "Invalid request parameter", "invalid_request")

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationExceptions(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.BAD_REQUEST, "Invalid request fields", "validation_error")

    @ExceptionHandler(
        javax.naming.AuthenticationException::class,
        org.springframework.security.core.AuthenticationException::class,
        dev.alllexey.itmowidgets.core.utils.AuthenticationException::class,
    )
    fun handleAuthenticationException(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.UNAUTHORIZED, "Authentication failed", "unauthorized")

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDeniedException(): ResponseEntity<ApiResponse<Unit>> =
        response(HttpStatus.FORBIDDEN, "Access denied", "access_denied")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ApiResponse<Unit>> {
        logger.warn("Persistence operation failed: {}", SafeDiagnostics.describe(ex))
        return if (SafeDiagnostics.isExpectedUniqueConflict(ex)) {
            response(HttpStatus.CONFLICT, "Resource already exists", "conflict")
        } else {
            response(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred", "internal_server_error")
        }
    }

    @ExceptionHandler(RuntimeException::class)
    fun handleRuntimeException(ex: RuntimeException): ResponseEntity<ApiResponse<Unit>> = unexpected(ex)

    @ExceptionHandler(Exception::class)
    fun handleAllExceptions(ex: Exception): ResponseEntity<ApiResponse<Unit>> = unexpected(ex)

    private fun unexpected(ex: Exception): ResponseEntity<ApiResponse<Unit>> {
        // Preserve framework 4xx (including removed routes), never expose its request/SQL details.
        if (ex is ErrorResponse && ex.statusCode.is4xxClientError) {
            val code = if (ex.statusCode.value() == 404) "not_found" else "invalid_request"
            return ResponseEntity.status(ex.statusCode).body(ApiResponse.error("Request could not be handled", code))
        }
        logger.error("Unhandled operation failure: {}", SafeDiagnostics.describe(ex))
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred", "internal_server_error")
    }

    private fun response(status: HttpStatus, message: String, code: String): ResponseEntity<ApiResponse<Unit>> =
        ResponseEntity.status(status).body(ApiResponse.error(message, code))

    companion object {
        private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
