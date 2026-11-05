package dev.alllexey.itmowidgets.backend.feature.sheets.web

import dev.alllexey.itmowidgets.backend.contract.ContractSamples
import dev.alllexey.itmowidgets.backend.feature.sheets.service.SheetRuleService
import dev.alllexey.itmowidgets.backend.platform.error.BusinessRuleException
import dev.alllexey.itmowidgets.backend.platform.error.GlobalExceptionHandler
import dev.alllexey.itmowidgets.backend.platform.error.NotFoundException
import dev.alllexey.itmowidgets.backend.platform.error.TooManyRequestsException
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@WebMvcTest(SheetRuleController::class)
@Import(SecurityConfig::class, GlobalExceptionHandler::class)
class SheetRuleControllerSecurityTest @Autowired constructor(private val mvc: MockMvc, private val json: ObjectMapper) {
    @MockitoBean private lateinit var jwt: JwtAuthFilter

    @MockitoBean private lateinit var service: SheetRuleService
    private val viewer = UUID.randomUUID()
    private val id = UUID.randomUUID()

    @BeforeEach
    fun fixture() {
        doAnswer {
            it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1))
            null
        }.`when`(jwt).doFilter(any(), any(), any())
    }

    private fun routes(): List<MockHttpServletRequestBuilder> = listOf(get(PATH.format(id)), post(PATH.format(id)))

    @Test
    fun `both routes reject anonymous callers before the service`() {
        routes().forEach { mvc.perform(it).andExpect(status().isUnauthorized) }
        verifyNoInteractions(service)
    }

    @Test
    fun `the rule has the exact wire keys and passes the analyzer rule through`() {
        `when`(service.rule(viewer, id)).thenReturn(SheetRuleResponse(SheetRuleState.READY, ContractSamples.sheetRule, AT, null))

        val body = mvc.perform(get(PATH.format(id)).with(user(viewer.toString())))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val data = json.readTree(body)["data"]

        assertEquals(setOf("status", "rule", "analyzedAt", "reason"), data.propertyNames().toSet())
        assertEquals("READY", data["status"].stringValue())
        assertEquals(ContractSamples.sheetRule, data["rule"])
        assertEquals("2026-10-04T12:00:00Z", data["analyzedAt"].stringValue())
        assertEquals(true, data["reason"].isNull)
    }

    @Test
    fun `a request is accepted while it waits and answered once it has a result`() {
        `when`(service.request(viewer, id)).thenReturn(SheetRuleResponse(SheetRuleState.PENDING, null, null, null))
        mvc.perform(post(PATH.format(id)).with(user(viewer.toString())))
            .andExpect(status().isAccepted).andExpect(jsonPath("$.data.status").value("PENDING"))

        `when`(service.request(viewer, id)).thenReturn(SheetRuleResponse(SheetRuleState.RUNNING, null, null, null))
        mvc.perform(post(PATH.format(id)).with(user(viewer.toString()))).andExpect(status().isAccepted)

        `when`(service.request(viewer, id)).thenReturn(SheetRuleResponse(SheetRuleState.FAILED, null, null, "closed"))
        mvc.perform(post(PATH.format(id)).with(user(viewer.toString())))
            .andExpect(status().isOk).andExpect(jsonPath("$.data.reason").value("closed"))
    }

    @Test
    fun `denied paths answer with their stable codes`() {
        `when`(service.rule(viewer, id)).thenThrow(NotFoundException("Link not found"))
        mvc.perform(get(PATH.format(id)).with(user(viewer.toString())))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.error.code").value("not_found"))

        for ((error, code) in listOf(
            BusinessRuleException(SheetRuleService.NOT_A_SHEET) to "not_a_google_sheet",
            BusinessRuleException(SheetRuleService.DISABLED) to "sheet_rules_disabled",
        )) {
            doThrow(error).`when`(service).request(viewer, id)
            mvc.perform(post(PATH.format(id)).with(user(viewer.toString())))
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.error.code").value("business_rule_violation"))
                .andExpect(jsonPath("$.error.message").value(code))
        }

        doThrow(TooManyRequestsException("Too many sheet analyses, try again later")).`when`(service).request(viewer, id)
        mvc.perform(post(PATH.format(id)).with(user(viewer.toString())))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.error.code").value("rate_limited"))
    }

    @Test
    fun `a malformed link id is rejected before the service`() {
        mvc.perform(get(PATH.format("not-a-uuid")).with(user(viewer.toString()))).andExpect(status().isBadRequest)
        mvc.perform(post(PATH.format("not-a-uuid")).with(user(viewer.toString()))).andExpect(status().isBadRequest)
        verifyNoInteractions(service)
    }

    private companion object {
        const val PATH = "/api/links/%s/sheet-rule"
        val AT: Instant = Instant.parse("2026-10-04T12:00:00Z")
    }
}
