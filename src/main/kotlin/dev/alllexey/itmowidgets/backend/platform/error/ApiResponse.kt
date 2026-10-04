package dev.alllexey.itmowidgets.backend.platform.error

import io.swagger.v3.oas.annotations.media.Schema

/** The envelope of every JSON response; `error.code` strings are part of the contract. */
data class ApiResponse<T>(val success: Boolean, val data: T?, val error: ErrorDetails?) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(success = true, data = data, error = null)

        fun error(message: String, code: ErrorCode): ApiResponse<Unit> =
            ApiResponse(success = false, data = null, error = ErrorDetails(message, code.wire))
    }

    /** [code] stays a string on the wire; docs/openapi.json lists the [ErrorCode] values. */
    data class ErrorDetails(val message: String, @get:Schema(implementation = ErrorCode::class) val code: String)
}
