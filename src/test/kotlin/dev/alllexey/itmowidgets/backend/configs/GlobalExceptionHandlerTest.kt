package dev.alllexey.itmowidgets.backend.configs

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.alllexey.itmowidgets.backend.controllers.DeviceController
import dev.alllexey.itmowidgets.backend.controllers.UnregisterDeviceRequest
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.NotFoundException
import dev.alllexey.itmowidgets.backend.exceptions.PermissionDeniedException
import dev.alllexey.itmowidgets.backend.services.DeviceService
import jakarta.servlet.FilterChain
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verifyNoInteractions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.core.MethodParameter
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

@WebMvcTest(DeviceController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class)
class GlobalExceptionHandlerTest @Autowired constructor(
    private val mvc: MockMvc,
    private val environment: Environment,
) {
    @MockitoBean
    private lateinit var deviceService: DeviceService

    @MockitoBean
    private lateinit var jwtAuthFilter: JwtAuthFilter
    @MockitoBean private lateinit var webSessions: dev.alllexey.itmowidgets.backend.services.WebSessionService

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger
    private lateinit var logs: ListAppender<ILoggingEvent>
    private val ownerId = UUID.randomUUID()

    @BeforeEach
    fun prepare() {
        logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        doAnswer { invocation ->
            invocation.getArgument<FilterChain>(2).doFilter(invocation.getArgument(0), invocation.getArgument(1))
            null
        }.`when`(jwtAuthFilter).doFilter(any(), any(), any())
    }

    @AfterEach
    fun cleanup() {
        logger.detachAppender(logs)
        logs.stop()
    }

    @Test
    fun `raw Hibernate driver logging stays disabled in the application configuration`() {
        assertTrue(environment.getRequiredProperty("logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper").equals("OFF", true))
        assertFalse(LoggerFactory.getLogger("org.hibernate.engine.jdbc.spi.SqlExceptionHelper").isErrorEnabled)
    }

    @Test
    fun `unknown runtime error is a generic 500 with no raw message or cause in body and logs`() {
        failWith(IllegalStateException(SECRET, IllegalArgumentException(NESTED_SECRET)))
        expectError(500, "internal_server_error", "An internal server error occurred")
        assertTrue(logs.list.isNotEmpty())
    }

    @Test
    fun `checked exception is a generic 500 with no raw message or cause`() {
        failWith(Exception(SECRET, Exception(NESTED_SECRET)))
        expectError(500, "internal_server_error", "An internal server error occurred")
    }

    @Test
    fun `unknown SQL violation is a generic 500 and diagnostic contains only safe SQLSTATE`() {
        failWith(integrityFailure("23514", SECRET))
        expectError(500, "internal_server_error", "An internal server error occurred")
        assertTrue(logs.list.any { it.formattedMessage.contains("sqlState=23514") })
    }

    @Test
    fun `known unique constraints produce safe conflicts without exposing SQL or row values`() {
        listOf(
            "uq_auto_sign_not_cancelled", "uq_free_sign_not_cancelled", "uq_devices_fcm_token",
            "uq_friendships_pair", "uq_users_isu",
            "uq_subject_link_revisions_pending", "uq_subject_link_revisions_number", "uq_moderation_reports_reporter", "uq_moderation_cases_open",
        ).forEach { constraint ->
            failWith(integrityFailure("23505", constraint))
            expectError(409, "conflict", "Resource already exists")
            assertTrue(logs.list.any { it.formattedMessage.contains("constraint=$constraint") })
        }
    }

    @Test
    fun `unknown unique constraint is not misclassified as expected business conflict`() {
        failWith(integrityFailure("23505", SECRET))
        expectError(500, "internal_server_error", "An internal server error occurred")
    }

    @Test
    fun `known constraint name without unique violation SQLSTATE is still server failure`() {
        failWith(integrityFailure("23503", "uq_devices_fcm_token"))
        expectError(500, "internal_server_error", "An internal server error occurred")
    }

    @Test
    fun `untrusted SQLSTATE and constraint metadata are never logged as safe metadata`() {
        failWith(integrityFailure(SECRET, NESTED_SECRET))
        expectError(500, "internal_server_error", "An internal server error occurred")
        assertTrue(logs.list.none { it.formattedMessage.contains("sqlState=") || it.formattedMessage.contains("constraint=") })
    }

    @Test
    fun `controlled domain statuses and messages are preserved`() {
        listOf(
            Triple(InvalidRequestDataException("Invalid requested range"), 400, "invalid_request_data"),
            Triple(PermissionDeniedException("Schedule is private"), 403, "permission_denied"),
            Triple(dev.alllexey.itmowidgets.backend.exceptions.RestrictedException(
                dev.alllexey.itmowidgets.backend.model.RestrictionCapability.VOTE, null, "Action restricted by moderation"), 403, "restricted"),
            Triple(NotFoundException("User not found"), 404, "not_found"),
            Triple(BusinessRuleException("Queue quota reached"), 409, "business_rule_violation"),
        ).forEach { (failure, httpStatus, code) ->
            failWith(failure)
            expectError(httpStatus, code, failure.message!!)
        }
    }

    @Test
    fun `Spring Core and JNDI authentication errors retain 401 without provider messages`() {
        listOf(
            BadCredentialsException(SECRET, IllegalStateException(NESTED_SECRET)),
            dev.alllexey.itmowidgets.core.utils.AuthenticationException(SECRET, IllegalStateException(NESTED_SECRET)),
            javax.naming.AuthenticationException(SECRET),
        ).forEach { failure ->
            failWith(failure)
            expectError(401, "unauthorized", "Authentication failed")
        }
    }

    @Test
    fun `Spring access denial retains 403 without raw exception text`() {
        failWith(AccessDeniedException(SECRET, IllegalStateException(NESTED_SECRET)))
        expectError(403, "access_denied", "Access denied")
    }

    @Test
    fun `malformed request returns safe 400 without the submitted body`() {
        val result = mvc.perform(
            delete("/api/device/current").with(user(ownerId.toString()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"fcmToken\": \"$SECRET"),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("invalid_request"))
            .andExpect(jsonPath("$.error.message").value("Invalid request body"))
            .andReturn()
        assertSafe(result.response.contentAsString)
    }

    @Test
    fun `malformed parameter stays 400 and does not expose the rejected value`() {
        failWith(org.springframework.beans.TypeMismatchException(SECRET, Int::class.java, NumberFormatException(NESTED_SECRET)))
        expectError(400, "invalid_request", "Invalid request parameter")
    }

    @Test
    fun `validation failure does not expose rejected value or interpolated validator message`() {
        val binding = BeanPropertyBindingResult(UnregisterDeviceRequest(SECRET), "request")
        binding.addError(FieldError("request", "fcmToken", SECRET, false, null, null, NESTED_SECRET))
        val method = DeviceController::class.java.getMethod(
            "unregisterCurrentDevice", UnregisterDeviceRequest::class.java, Authentication::class.java,
        )
        failWith(MethodArgumentNotValidException(MethodParameter(method, 0), binding))
        expectError(400, "validation_error", "Invalid request fields")
    }

    @Test
    fun `missing authenticated route remains 404 with no request path detail`() {
        val result = mvc.perform(get("/api/$SECRET").with(user(ownerId.toString())))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("not_found"))
            .andReturn()
        assertSafe(result.response.contentAsString)
    }

    @Test
    fun `unsupported HTTP method keeps framework 405 rather than generic 500`() {
        val result = mvc.perform(put("/api/device/current").with(user(ownerId.toString())))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.error.code").value("invalid_request"))
            .andReturn()
        assertSafe(result.response.contentAsString)
    }

    @Test
    fun `security still rejects anonymous requests before reaching the controller`() {
        mvc.perform(delete("/api/device/current").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
            .andExpect(status().isForbidden)
        verifyNoInteractions(deviceService)
        assertSafe("")
    }

    private fun failWith(failure: Throwable) {
        doAnswer { throw failure }.`when`(deviceService).unregisterDevice(ownerId, SECRET)
    }

    private fun expectError(httpStatus: Int, code: String, message: String) {
        val result = mvc.perform(
            delete("/api/device/current").with(user(ownerId.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST),
        ).andExpect(status().`is`(httpStatus))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.data").isEmpty)
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.message").value(message))
            .andReturn()
        assertSafe(result.response.contentAsString)
    }

    private fun integrityFailure(state: String, constraint: String) = DataIntegrityViolationException(
        SECRET,
        ConstraintViolationException(NESTED_SECRET, SQLException(SECRET, state), "INSERT $SECRET", constraint),
    )

    /** The response stays redacted; the log keeps the rendered line redacted but carries the cause. */
    private fun assertSafe(body: String) {
        listOf(SECRET, NESTED_SECRET).forEach { assertFalse(body.contains(it)) }
        logs.list.forEach { event ->
            assertNotNull(event.throwableProxy, "Operators need the cause chain the response omits")
            val text = event.formattedMessage + event.message + event.argumentArray.orEmpty().joinToString()
            listOf(SECRET, NESTED_SECRET).forEach { assertFalse(text.contains(it)) }
        }
    }

    companion object {
        private const val SECRET = "synthetic-private-fcm-token"
        private const val NESTED_SECRET = "synthetic-private-database-row"
        private const val REQUEST = "{\"fcmToken\":\"$SECRET\"}"
    }
}
