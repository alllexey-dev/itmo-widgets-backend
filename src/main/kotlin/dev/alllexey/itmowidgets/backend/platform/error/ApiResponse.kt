package dev.alllexey.itmowidgets.backend.platform.error

/** The envelope of every JSON response; `error.code` strings are part of the contract. */
data class ApiResponse<T>(
    val success: Boolean,
    val data: T?,
    val error: ErrorDetails?,
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(success = true, data = data, error = null)

        fun error(message: String, code: String? = null): ApiResponse<Unit> =
            ApiResponse(success = false, data = null, error = ErrorDetails(message, code))
    }

    data class ErrorDetails(
        val message: String,
        val code: String?,
    )
}
