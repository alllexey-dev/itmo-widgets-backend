package dev.alllexey.itmowidgets.backend.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.alllexey.itmowidgets.backend.configs.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.configs.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.configs.SecurityConfig
import dev.alllexey.itmowidgets.backend.dto.*
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import dev.alllexey.itmowidgets.backend.model.LinkCategory
import dev.alllexey.itmowidgets.backend.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.model.ReportReason
import dev.alllexey.itmowidgets.backend.services.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.services.OfficialStudyGroup
import dev.alllexey.itmowidgets.backend.services.OfficialStudyGroupsSource
import dev.alllexey.itmowidgets.backend.services.SubjectLinkService
import dev.alllexey.itmowidgets.core.model.GroupData
import jakarta.servlet.FilterChain
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(SubjectLinkController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class, SubjectLinkControllerSecurityTest.GroupsConfig::class)
class SubjectLinkControllerSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
) {
    @MockitoBean private lateinit var jwt: JwtAuthFilter
    @MockitoBean private lateinit var webSessions: dev.alllexey.itmowidgets.backend.services.WebSessionService
    @MockitoBean private lateinit var service: SubjectLinkService
    private val viewer = UUID.randomUUID()
    private val id = UUID.randomUUID()

    /** The directory reports the author's current group, which must replace the stored one. */
    @TestConfiguration(proxyBeanMethods = false)
    class GroupsConfig {
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-09-22T09:00:00Z"), ZoneOffset.UTC)
        @Bean fun currentStudyGroups(clock: Clock) = CurrentStudyGroupsService(
            OfficialStudyGroupsSource { listOf(OfficialStudyGroup("P3219", 2, "ФПИиКТ")) }, clock)
    }

    @BeforeEach
    fun fixture() {
        doAnswer { it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1)); null }.`when`(jwt).doFilter(any(), any(), any())
    }

    private fun routes(): List<MockHttpServletRequestBuilder> = listOf(
        get("/api/subjects/42/links?period=2026-1"),
        put("/api/links/$id").content(SAVE_BODY),
        delete("/api/links/$id"),
        put("/api/subjects/42/links/pin").content("""{"periodKey":"2026-1","linkId":"$id"}"""),
        put("/api/links/$id/vote").content("""{"value":1}"""),
        post("/api/links/$id/report").content("""{"reason":"BROKEN"}"""),
    )

    @Test
    fun `every route rejects anonymous callers before the service`() {
        routes().forEach { mvc.perform(it.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden) }
        verifyNoInteractions(service)
    }

    @Test
    fun `the links response has the exact wire keys and authors carry current groups`() {
        `when`(service.links(viewer, 42, "2026-1")).thenReturn(response())
        val data = data(get("/api/subjects/42/links?period=2026-1"))

        assertEquals(RESPONSE_KEYS, data.keys())
        assertEquals(LINK_KEYS, data["shared"][0].keys())
        assertEquals(LINK_KEYS, data["mine"][0].keys())
        assertEquals(AUDIENCE_KEYS, data["audiences"][0].keys())
        assertEquals(7103L, data["audiences"][0]["flowId"].longValue())
        assertEquals("ФИЗ ПИИКТ 3.2.1", data["audiences"][0]["label"].textValue())
        assertEquals(2, data["audiences"][0]["typeId"].intValue())
        assertEquals(3, data["audiences"][0]["depth"].intValue())
        assertEquals("FLOW", data["mine"][0]["visibility"].textValue())
        assertEquals(7103L, data["mine"][0]["flowId"].longValue())
        assertEquals("ФИЗ ПИИКТ 3.2.1", data["mine"][0]["audienceLabel"].textValue())
        assertEquals(true, data["shared"][0]["flowId"].isNull)
        assertEquals(true, data["mine"][0]["isMine"].booleanValue())
        assertEquals("PUBLISHED", data["shared"][0]["status"].textValue())
        assertEquals("MATERIALS", data["shared"][0]["category"].textValue())
        assertEquals("2026-09-22T09:00:00Z", data["shared"][0]["updatedAt"].textValue())
        assertEquals("P3219", data["shared"][0]["author"]["groups"][0]["name"].textValue())
        assertEquals(true, data["mine"][0]["author"].isNull)
        assertEquals(id.toString(), data["pinnedId"].textValue())
    }

    @Test
    fun `save sends the parsed body and a missing title and flow are accepted`() {
        `when`(service.save(viewer, id, SAVE)).thenReturn(link(mine = true))
        val data = data(put("/api/links/$id").content(SAVE_BODY))
        assertEquals(LINK_KEYS, data.keys())
        verify(service).save(viewer, id, SAVE)

        val flow = SAVE.copy(visibility = LinkVisibility.FLOW, flowId = 7103)
        `when`(service.save(viewer, id, flow)).thenReturn(link(mine = true))
        assertEquals(LINK_KEYS, data(put("/api/links/$id").content(SAVE_BODY.replace("\"ALL\"}", "\"FLOW\",\"flowId\":7103}"))).keys())
        verify(service).save(viewer, id, flow)
    }

    @Test
    fun `numeric unknown and missing enum values are rejected before the service`() {
        for (body in listOf(SAVE_BODY.replace("\"ALL\"", "2"), SAVE_BODY.replace("\"ALL\"", "\"FRIENDS\""),
            SAVE_BODY.replace("\"ALL\"", "\"GROUP\""), SAVE_BODY.replace("\"CHAT\"", "\"UNKNOWN\""),
            SAVE_BODY.replace(",\"visibility\":\"ALL\"", ""))) {
            mvc.perform(put("/api/links/$id").with(user(viewer.toString())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
        mvc.perform(put("/api/links/not-a-uuid").with(user(viewer.toString())).contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
            .andExpect(status().isBadRequest)
        mvc.perform(get("/api/subjects/42/links").with(user(viewer.toString()))).andExpect(status().isBadRequest)
        verifyNoInteractions(service)
    }

    @Test
    fun `pin vote report and delete pass their bodies`() {
        `when`(service.pin(viewer, 42, PinSubjectLinkRequest("2026-1", null))).thenReturn(response())
        `when`(service.vote(viewer, id, -1)).thenReturn(link())
        `when`(service.report(viewer, id, ModerationReportRequest(ReportReason.BROKEN))).thenReturn(link())

        assertEquals(RESPONSE_KEYS, data(put("/api/subjects/42/links/pin").content("""{"periodKey":"2026-1"}""")).keys())
        assertEquals(LINK_KEYS, data(put("/api/links/$id/vote").content("""{"value":-1}""")).keys())
        assertEquals(LINK_KEYS, data(post("/api/links/$id/report").content("""{"reason":"BROKEN"}""")).keys())
        assertEquals(0, data(delete("/api/links/$id")).size())
        verify(service).delete(viewer, id)
    }

    @Test
    fun `the removed saved route is not found`() {
        mvc.perform(put("/api/links/$id/saved").with(user(viewer.toString())).contentType(MediaType.APPLICATION_JSON)
            .content("""{"saved":true}""")).andExpect(status().isNotFound)
        verifyNoInteractions(service)
    }

    @Test
    fun `an unavailable audience is a bad request with its reason`() {
        `when`(service.save(eqAny(viewer), eqAny(id), any(SaveSubjectLinkRequest::class.java) ?: SAVE))
            .thenThrow(InvalidRequestDataException("audience_unavailable"))
        mvc.perform(put("/api/links/$id").with(user(viewer.toString())).contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("invalid_request_data"))
            .andExpect(jsonPath("$.error.message").value("audience_unavailable"))
        verify(service, never()).links(eqAny(viewer), anyLong(), any(String::class.java) ?: "")
    }

    private fun <T> eqAny(value: T): T = eq(value) ?: value

    private fun data(request: MockHttpServletRequestBuilder): JsonNode {
        val body = mvc.perform(request.with(user(viewer.toString())).contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return json.readTree(body)["data"]
    }

    private fun JsonNode.keys(): Set<String> = fieldNames().asSequence().toSet()

    private fun response() = SubjectLinksResponse(listOf(link(mine = true)), listOf(link()), emptyList(), id,
        listOf(LinkAudience(7103, "ФИЗ ПИИКТ 3.2.1", typeId = 2, depth = 3)), premoderation = true)

    private fun link(mine: Boolean = false) = SubjectLink(id, 42, "Предмет", "2026-1", LinkCategory.MATERIALS,
        "https://example.org/materials", null, if (mine) LinkVisibility.FLOW else LinkVisibility.ALL, if (mine) 7103 else null,
        if (mine) "ФИЗ ПИИКТ 3.2.1" else null, SubjectLinkStatus.PUBLISHED, null, 1, 0,
        isMine = mine, reportedByMe = false,
        author = if (mine) null else UserData(970001, "Synthetic user", null, listOf(GroupData("P3119", 1, "ФПИиКТ")),
            UserCapabilities(false, false, false)),
        updatedAt = Instant.parse("2026-09-22T09:00:00Z"))

    private companion object {
        const val SAVE_BODY = """{"subjectId":42,"subjectName":"Предмет","periodKey":"2026-1","category":"CHAT","url":"https://t.me/chat","visibility":"ALL"}"""
        val SAVE = SaveSubjectLinkRequest(42, "Предмет", "2026-1", LinkCategory.CHAT, "https://t.me/chat", null, LinkVisibility.ALL)
        val AUDIENCE_KEYS = setOf("flowId", "label", "typeId", "depth")
        val RESPONSE_KEYS = setOf("mine", "shared", "previous", "pinnedId", "audiences", "premoderation")
        val LINK_KEYS = setOf("id", "subjectId", "subjectName", "periodKey", "category", "url", "title", "visibility",
            "flowId", "audienceLabel", "status", "reviewNote", "score", "myVote", "isMine", "reportedByMe", "author", "updatedAt")
    }
}
