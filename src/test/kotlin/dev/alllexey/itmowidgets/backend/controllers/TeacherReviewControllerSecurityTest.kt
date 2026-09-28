package dev.alllexey.itmowidgets.backend.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.dto.ExternalTeacherReview
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.services.TeacherReviewService
import dev.alllexey.itmowidgets.backend.services.WebSessionService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(TeacherReviewController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class)
class TeacherReviewControllerSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
) {
    @MockitoBean private lateinit var jwt: JwtAuthFilter
    @MockitoBean private lateinit var webSessions: WebSessionService
    @MockitoBean private lateinit var service: TeacherReviewService
    private val exactDateId = UUID.randomUUID()
    private val beforeYearId = UUID.randomUUID()

    @BeforeEach
    fun fixture() {
        doAnswer { it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1)); null }.`when`(jwt).doFilter(any(), any(), any())
        `when`(webSessions.resolve(SESSION)).thenReturn(VIEWER_ID)
    }

    @Test
    fun `anonymous requests are forbidden before the service`() {
        mvc.perform(get("/api/teachers/100001/reviews")).andExpect(status().isForbidden)

        verifyNoInteractions(service)
    }

    @Test
    fun `authenticated requests expose only review wire fields with iso dates and explicit nulls`() {
        `when`(service.reviews(100001)).thenReturn(response())

        val body = mvc.perform(get("/api/teachers/100001/reviews").with(user(VIEWER_ID.toString())))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val response = json.readTree(body)["data"]

        assertEquals(RESPONSE_KEYS, response.keys())
        assertEquals(100001, response["teacherIsu"].intValue())
        assertEquals("https://onetwozzzplus.github.io/reviews/#/teacher/100001", response["providerUrl"].textValue())
        assertEquals(2, response["external"].size())
        response["external"].forEach { assertEquals(REVIEW_KEYS, it.keys()) }
        val exactDate = response["external"][0]
        assertEquals(exactDateId.toString(), exactDate["id"].textValue())
        assertEquals("2025-01-25", exactDate["writtenOn"].textValue())
        assertEquals("Synthetic review", exactDate["text"].textValue())
        for (field in listOf("subjectTitle", "writtenBeforeYear", "sourceTitle", "sourceLink")) {
            assertTrue(exactDate[field].isNull, field)
        }
        val beforeYear = response["external"][1]
        assertEquals(beforeYearId.toString(), beforeYear["id"].textValue())
        assertTrue(beforeYear["writtenOn"].isNull)
        assertEquals(2024, beforeYear["writtenBeforeYear"].intValue())
        assertEquals("Synthetic subject", beforeYear["subjectTitle"].textValue())
        assertEquals("Synthetic source", beforeYear["sourceTitle"].textValue())
        assertEquals("https://example.org/review", beforeYear["sourceLink"].textValue())
        verify(service).reviews(100001)
    }

    @Test
    fun `a web session cookie authenticates a get without the web request header`() {
        `when`(service.reviews(100001)).thenReturn(response())

        mvc.perform(get("/api/teachers/100001/reviews").cookie(COOKIE))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.teacherIsu").value(100001))

        verify(webSessions).resolve(SESSION)
        verify(service).reviews(100001)
    }

    @Test
    fun `an empty review list is still a successful response`() {
        `when`(service.reviews(100001)).thenReturn(response().copy(external = emptyList()))

        mvc.perform(get("/api/teachers/100001/reviews").with(user(VIEWER_ID.toString())))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.external").isArray)
            .andExpect(jsonPath("$.data.external").isEmpty)
    }

    @Test
    fun `nonpositive isus return the invalid request data error`() {
        for (isu in listOf(0, -1)) {
            `when`(service.reviews(isu)).thenThrow(InvalidRequestDataException("ISU must be positive"))

            mvc.perform(get("/api/teachers/$isu/reviews").with(user(VIEWER_ID.toString())))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("invalid_request_data"))
                .andExpect(jsonPath("$.error.message").value("ISU must be positive"))
        }
    }

    @Test
    fun `a nonnumeric isu is rejected before the service`() {
        mvc.perform(get("/api/teachers/abc/reviews").with(user(VIEWER_ID.toString())))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("invalid_request"))

        verifyNoInteractions(service)
    }

    private fun response() = TeacherReviewsResponse(100001, "https://onetwozzzplus.github.io/reviews/#/teacher/100001", listOf(
        ExternalTeacherReview(exactDateId, null, LocalDate.of(2025, 1, 25), null, null, null, "Synthetic review"),
        ExternalTeacherReview(beforeYearId, "Synthetic subject", null, 2024, "Synthetic source",
            "https://example.org/review", "Synthetic older review"),
    ))

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    private companion object {
        const val SESSION = "synthetic-viewer-session"
        val COOKIE = Cookie("iw_session", SESSION)
        val VIEWER_ID: UUID = UUID.randomUUID()
        val RESPONSE_KEYS = setOf("teacherIsu", "providerUrl", "external")
        val REVIEW_KEYS = setOf("id", "subjectTitle", "writtenOn", "writtenBeforeYear", "sourceTitle", "sourceLink", "text")
    }
}
