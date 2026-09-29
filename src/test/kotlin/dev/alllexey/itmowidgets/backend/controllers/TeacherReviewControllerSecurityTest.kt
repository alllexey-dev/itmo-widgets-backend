package dev.alllexey.itmowidgets.backend.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.dto.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.dto.OwnTeacherReview
import dev.alllexey.itmowidgets.backend.dto.SaveTeacherReviewRequest
import dev.alllexey.itmowidgets.backend.dto.TeacherReview
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewStatus
import dev.alllexey.itmowidgets.backend.dto.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.dto.TeacherSummary
import dev.alllexey.itmowidgets.backend.dto.TeacherSummaryLevel
import dev.alllexey.itmowidgets.backend.dto.TeacherSummaryScale
import dev.alllexey.itmowidgets.backend.dto.UserCapabilities
import dev.alllexey.itmowidgets.backend.dto.UserData
import dev.alllexey.itmowidgets.backend.exceptions.BusinessRuleException
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.exceptions.RestrictedException
import dev.alllexey.itmowidgets.backend.model.ReportReason
import dev.alllexey.itmowidgets.backend.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.model.SummaryTag
import dev.alllexey.itmowidgets.backend.services.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.services.TeacherReviewService
import dev.alllexey.itmowidgets.backend.services.WebSessionService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.Cookie
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
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
    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService
    private val anonymousId = UUID.randomUUID()
    private val namedId = UUID.randomUUID()
    private val copyId = UUID.randomUUID()
    private val ownId = UUID.randomUUID()

    @BeforeEach
    fun fixture() {
        doAnswer { it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1)); null }.`when`(jwt).doFilter(any(), any(), any())
        `when`(webSessions.resolve(SESSION)).thenReturn(VIEWER_ID)
        doAnswer { it.getArgument<UserData>(0).copy(name = "Current name") }.`when`(currentGroups).userData(any() ?: AUTHOR)
    }

    private fun routes(): List<MockHttpServletRequestBuilder> = listOf(
        get("/api/teachers/$TEACHER/reviews"),
        get("/api/teachers/summary-levels?isu=$TEACHER"),
        put("/api/teachers/$TEACHER/reviews/mine").content(SAVE_BODY),
        delete("/api/teachers/$TEACHER/reviews/mine"),
        put("/api/reviews/$namedId/vote").content("""{"value":1}"""),
        post("/api/reviews/$namedId/report").content("""{"reason":"OFFENSIVE"}"""),
    )

    @Test
    fun `every route rejects anonymous callers before the service`() {
        routes().forEach { mvc.perform(it.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden) }
        verifyNoInteractions(service)
    }

    @Test
    fun `cookie writes need the web request header`() {
        stubAll()
        routes().drop(2).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).cookie(COOKIE))
                .andExpect(status().isForbidden).andExpect(jsonPath("$.error.code").value("csrf"))
        }
        verifyNoInteractions(service)

        routes().forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).cookie(COOKIE).header("X-Web-Request", "1"))
                .andExpect(status().isOk)
        }
        verify(service).reviews(VIEWER_ID, TEACHER)
        verify(service).summaryLevels(listOf(TEACHER))
        verify(service).save(VIEWER_ID, TEACHER, SAVE)
        verify(service).delete(VIEWER_ID, TEACHER)
        verify(service).vote(VIEWER_ID, namedId, 1)
        verify(service).report(VIEWER_ID, namedId, ModerationReportRequest(ReportReason.OFFENSIVE))
    }

    @Test
    fun `the response has the exact wire keys and never names an anonymous author`() {
        `when`(service.reviews(VIEWER_ID, TEACHER)).thenReturn(response())

        val data = data(get("/api/teachers/$TEACHER/reviews"))

        assertEquals(RESPONSE_KEYS, data.keys())
        assertEquals(TEACHER, data["teacherIsu"].intValue())
        assertEquals(true, data["canWrite"].booleanValue())
        assertEquals(true, data["knownTeacher"].booleanValue())
        data["reviews"].forEach { assertEquals(REVIEW_KEYS, it.keys()) }
        val (anonymous, named, copy) = data["reviews"].toList()
        assertEquals(anonymousId.toString(), anonymous["id"].textValue())
        assertEquals("COMMUNITY", anonymous["kind"].textValue())
        assertTrue(anonymous["author"].isNull)
        assertEquals("2026-09-23", anonymous["writtenOn"].textValue())
        assertEquals(true, anonymous["verified"].booleanValue())
        assertEquals(AUTHOR.isu, named["author"]["isu"].intValue())
        assertEquals("Current name", named["author"]["name"].textValue())
        assertEquals("REVIEWS", copy["kind"].textValue())
        assertEquals(2024, copy["writtenBeforeYear"].intValue())
        assertTrue(copy["author"].isNull)
        assertEquals("https://example.org/review", copy["sourceLink"].textValue())
        assertEquals(OWN_KEYS, data["mine"].keys())
        assertEquals("REJECTED", data["mine"]["status"].textValue())
        assertEquals("Не о преподавателе", data["mine"]["reviewNote"].textValue())
        assertEquals(true, data["mine"]["anonymous"].booleanValue())

        `when`(service.reviews(VIEWER_ID, TEACHER)).thenReturn(response().copy(mine = null, reviews = emptyList()))
        val empty = data(get("/api/teachers/$TEACHER/reviews"))
        assertTrue(empty["mine"].isNull)
        assertTrue(empty["reviews"].isEmpty)
    }

    @Test
    fun `a body without anonymous is anonymous and a non boolean anonymous is unreadable`() {
        stubAll()
        val implicit = SaveTeacherReviewRequest(text = TEXT)
        `when`(service.save(VIEWER_ID, TEACHER, implicit)).thenReturn(response())
        data(put("/api/teachers/$TEACHER/reviews/mine").content("""{"text":"$TEXT"}"""))
        verify(service).save(VIEWER_ID, TEACHER, implicit)

        for (body in listOf("""{"text":"$TEXT","anonymous":1}""", """{"text":"$TEXT","anonymous":"true"}""",
            """{"text":"$TEXT","anonymous":null}""", """{"subjectTitle":"Математика"}""")) {
            mvc.perform(put("/api/teachers/$TEACHER/reviews/mine").with(user(VIEWER_ID.toString()))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("invalid_request"))
        }
        verify(service, times(1)).save(any() ?: VIEWER_ID, anyInt(), any() ?: SAVE)
    }

    @Test
    fun `service refusals map to restricted conflict and invalid data`() {
        `when`(service.save(VIEWER_ID, TEACHER, SAVE))
            .thenThrow(RestrictedException(RestrictionCapability.WRITE_REVIEWS, null, "Action restricted by moderation"))
        `when`(service.vote(VIEWER_ID, namedId, 1)).thenThrow(BusinessRuleException("Not allowed on own reviews"))
        `when`(service.report(VIEWER_ID, copyId, ModerationReportRequest(ReportReason.SPAM)))
            .thenThrow(BusinessRuleException("Copied reviews cannot be reported"))
        `when`(service.reviews(VIEWER_ID, 0)).thenThrow(InvalidRequestDataException("ISU must be positive"))

        expect(put("/api/teachers/$TEACHER/reviews/mine").content(SAVE_BODY), 403, "restricted")
        expect(put("/api/reviews/$namedId/vote").content("""{"value":1}"""), 409, "business_rule_violation")
        expect(post("/api/reviews/$copyId/report").content("""{"reason":"SPAM"}"""), 409, "business_rule_violation")
        expect(get("/api/teachers/0/reviews"), 400, "invalid_request_data")
    }

    @Test
    fun `malformed ids and bodies are rejected before the service`() {
        expect(put("/api/reviews/abc/vote").content("""{"value":1}"""), 400, "invalid_request")
        expect(post("/api/reviews/abc/report").content("""{"reason":"SPAM"}"""), 400, "invalid_request")
        expect(get("/api/teachers/abc/reviews"), 400, "invalid_request")
        expect(post("/api/reviews/$namedId/report").content("""{"reason":"UNKNOWN"}"""), 400, "invalid_request")
        verifyNoInteractions(service)
    }

    @Test
    fun `summary levels are listed for a user with exact keys and invalid lists are refused`() {
        `when`(service.summaryLevels(listOf(TEACHER, OTHER_TEACHER, TEACHER))).thenReturn(listOf(
            TeacherSummaryLevel(TEACHER, SummaryLevel.POSITIVE), TeacherSummaryLevel(OTHER_TEACHER, SummaryLevel.VERY_NEGATIVE)))

        val levels = data(get("/api/teachers/summary-levels?isu=$TEACHER&isu=$OTHER_TEACHER&isu=$TEACHER"))

        assertEquals(2, levels.size())
        levels.forEach { assertEquals(setOf("teacherIsu", "level"), it.keys()) }
        assertEquals(TEACHER, levels[0]["teacherIsu"].intValue())
        assertEquals("POSITIVE", levels[0]["level"].textValue())
        assertEquals("VERY_NEGATIVE", levels[1]["level"].textValue())

        val tooMany = (1..51).map { 470_000 + it }
        `when`(service.summaryLevels(tooMany)).thenThrow(InvalidRequestDataException("Invalid teacher ISU list"))
        `when`(service.summaryLevels(listOf(99_999))).thenThrow(InvalidRequestDataException("Invalid teacher ISU list"))
        expect(get("/api/teachers/summary-levels?" + tooMany.joinToString("&") { "isu=$it" }), 400, "invalid_request_data")
        expect(get("/api/teachers/summary-levels?isu=99999"), 400, "invalid_request_data")
        clearInvocations(service)
        expect(get("/api/teachers/summary-levels?isu=abc"), 400, "invalid_request")
        expect(get("/api/teachers/summary-levels"), 400, "invalid_request")
        verifyNoInteractions(service)
    }

    @Test
    fun `the reviews carry the summary with exact keys or null`() {
        `when`(service.reviews(VIEWER_ID, TEACHER)).thenReturn(response().copy(summary = SUMMARY))

        val summary = data(get("/api/teachers/$TEACHER/reviews"))["summary"]

        assertEquals(SUMMARY_KEYS, summary.keys())
        assertEquals(3, summary["reviewCount"].intValue())
        assertEquals(listOf("AUTOMAT"), summary["tags"].map { it.textValue() })
        assertEquals("MEDIUM", summary["confidence"].textValue())
        assertEquals("2026-09-29T09:00:00Z", summary["generatedAt"].textValue())
        assertEquals(5, summary["scales"].size())
        summary["scales"].forEach { assertEquals(setOf("kind", "value", "reason"), it.keys()) }
        assertEquals("EXPLAINS", summary["scales"][0]["kind"].textValue())
        assertEquals("Понятно", summary["scales"][0]["reason"].textValue())
        assertTrue(summary["scales"][4]["reason"].isNull)

        `when`(service.reviews(VIEWER_ID, TEACHER)).thenReturn(response())
        assertTrue(data(get("/api/teachers/$TEACHER/reviews"))["summary"].isNull)
    }

    private fun stubAll() {
        `when`(service.reviews(VIEWER_ID, TEACHER)).thenReturn(response())
        `when`(service.summaryLevels(listOf(TEACHER))).thenReturn(emptyList())
        `when`(service.save(VIEWER_ID, TEACHER, SAVE)).thenReturn(response())
        `when`(service.delete(VIEWER_ID, TEACHER)).thenReturn(response().copy(mine = null))
        `when`(service.vote(VIEWER_ID, namedId, 1)).thenReturn(response())
        `when`(service.report(VIEWER_ID, namedId, ModerationReportRequest(ReportReason.OFFENSIVE))).thenReturn(response())
    }

    private fun expect(request: MockHttpServletRequestBuilder, httpStatus: Int, code: String) {
        mvc.perform(request.with(user(VIEWER_ID.toString())).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().`is`(httpStatus)).andExpect(jsonPath("$.error.code").value(code))
    }

    private fun data(request: MockHttpServletRequestBuilder): JsonNode {
        val body = mvc.perform(request.with(user(VIEWER_ID.toString())).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return json.readTree(body)["data"]
    }

    private fun response() = TeacherReviewsResponse(
        teacherIsu = TEACHER,
        providerUrl = "https://onetwozzzplus.github.io/reviews/#/teacher/$TEACHER",
        reviews = listOf(
            TeacherReview(anonymousId, TeacherReviewKind.COMMUNITY, "Математика", LocalDate.of(2026, 9, 23), null, TEXT,
                score = 2, myVote = 1, verified = true, reportedByMe = false, author = null, sourceTitle = null, sourceLink = null),
            TeacherReview(namedId, TeacherReviewKind.COMMUNITY, null, LocalDate.of(2026, 9, 20), null, TEXT,
                score = 0, myVote = 0, verified = false, reportedByMe = true, author = AUTHOR, sourceTitle = null, sourceLink = null),
            TeacherReview(copyId, TeacherReviewKind.REVIEWS, null, null, 2024, "Synthetic copied review",
                score = 0, myVote = -1, verified = false, reportedByMe = false, author = null,
                sourceTitle = "Synthetic source", sourceLink = "https://example.org/review"),
        ),
        mine = OwnTeacherReview(ownId, null, TEXT, anonymous = true, status = TeacherReviewStatus.REJECTED,
            reviewNote = "Не о преподавателе", score = 0, verified = false, writtenOn = LocalDate.of(2026, 9, 23)),
        canWrite = true,
        canVote = true,
        canReport = true,
        knownTeacher = true,
        summary = null,
    )

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    private companion object {
        const val TEACHER = 142415
        const val SESSION = "synthetic-viewer-session"
        const val TEXT = "Объясняет понятно, на вопросы отвечает подробно."
        const val SAVE_BODY = """{"subjectTitle":"Математика","text":"$TEXT","anonymous":false,"flowIds":[93724]}"""
        val SAVE = SaveTeacherReviewRequest("Математика", TEXT, anonymous = false, flowIds = listOf(93724))
        val COOKIE = Cookie("iw_session", SESSION)
        val VIEWER_ID: UUID = UUID.randomUUID()
        val AUTHOR = UserData(965001, "Stored name", null, emptyList(), UserCapabilities(false, false, false))
        const val OTHER_TEACHER = 471029
        val RESPONSE_KEYS = setOf("teacherIsu", "providerUrl", "reviews", "mine", "canWrite", "canVote", "canReport", "knownTeacher",
            "summary")
        val SUMMARY_KEYS = setOf("reviewCount", "description", "pros", "cons", "tags", "scales", "level", "confidence", "generatedAt")
        val SUMMARY = TeacherSummary(3, "Синтетическое описание сводки.", listOf("Понятные лекции"), emptyList(), listOf(SummaryTag.AUTOMAT),
            SummaryScaleKind.entries.map {
                if (it == SummaryScaleKind.EXPLAINS) TeacherSummaryScale(it, SummaryScaleValue.HIGH, "Понятно")
                else TeacherSummaryScale(it, SummaryScaleValue.NOT_ENOUGH_DATA, null)
            },
            SummaryLevel.POSITIVE, SummaryConfidence.MEDIUM, Instant.parse("2026-09-29T09:00:00Z"))
        val REVIEW_KEYS = setOf("id", "kind", "subjectTitle", "writtenOn", "writtenBeforeYear", "text", "score", "myVote",
            "verified", "reportedByMe", "author", "sourceTitle", "sourceLink")
        val OWN_KEYS = setOf("id", "subjectTitle", "text", "anonymous", "status", "reviewNote", "score", "verified", "writtenOn")
    }
}
