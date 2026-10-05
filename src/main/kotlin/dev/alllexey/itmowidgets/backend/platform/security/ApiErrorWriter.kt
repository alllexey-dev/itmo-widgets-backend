package dev.alllexey.itmowidgets.backend.platform.security

import dev.alllexey.itmowidgets.backend.platform.error.ApiResponse
import dev.alllexey.itmowidgets.backend.platform.error.ErrorCode
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper

/** Writes an [ApiResponse] error where no controller advice runs: in a servlet filter or Spring Security. */
internal fun HttpServletResponse.writeApiError(jsonMapper: JsonMapper, status: HttpStatus, message: String, code: ErrorCode) {
    this.status = status.value()
    contentType = MediaType.APPLICATION_JSON_VALUE
    characterEncoding = Charsets.UTF_8.name()
    jsonMapper.writeValue(writer, ApiResponse.error(message, code))
}
