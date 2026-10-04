package dev.alllexey.itmowidgets.backend.contract

import dev.alllexey.itmowidgets.backend.contract.AdminWebContractSamples.CREDENTIAL_VALUE
import dev.alllexey.itmowidgets.backend.contract.AdminWebContractSamples.POLL_SECRET
import dev.alllexey.itmowidgets.backend.contract.AdminWebContractSamples.SESSION_TOKEN
import dev.alllexey.itmowidgets.backend.contract.AdminWebContractSamples.USER_AGENT
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CASE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CHALLENGE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.FRIEND_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.RESTRICTION_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.TEACHER_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.VIEWER_ID
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAiSummariesService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminDashboardService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminReviewsService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminSystemService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUsersService
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminAuditController
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminDashboardController
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminModerationController
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminPage
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminReviewsController
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminSystemController
import dev.alllexey.itmowidgets.backend.feature.admin.web.AdminUsersController
import dev.alllexey.itmowidgets.backend.feature.admin.web.ServiceCredentialRequest
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.credentials.model.ServiceCredential
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.ClaimResult
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.WebAuthController
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Import
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders every admin and web sign-in route through the real controllers, security chain and Spring's Jackson
 * configuration with mocked services, against the response fixtures Web reads ([ContractCatalog.webRoutes]).
 */
@WebMvcTest(
    AdminAuditController::class,
    AdminDashboardController::class,
    AdminModerationController::class,
    AdminReviewsController::class,
    AdminSystemController::class,
    AdminUsersController::class,
    WebAuthController::class,
)
@Import(SecurityConfig::class)
class AdminWebContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    @param:Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var webLogins: WebLoginService

    @MockitoBean private lateinit var userService: UserService

    @MockitoBean private lateinit var privacyService: UserPrivacyService

    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @MockitoBean private lateinit var access: AdminAccess

    @MockitoBean private lateinit var auditService: AdminAuditService

    @MockitoBean private lateinit var dashboardService: AdminDashboardService

    @MockitoBean private lateinit var moderationService: AdminModerationService

    @MockitoBean private lateinit var reviewsService: AdminReviewsService

    @MockitoBean private lateinit var summaryService: AdminAiSummariesService

    @MockitoBean private lateinit var systemService: AdminSystemService

    @MockitoBean private lateinit var usersService: AdminUsersService

    /** [uri] fills the catalog path; [build] adds headers or a body; [stub] answers the call; [check] asserts the call. */
    private class Case(
        val uri: String,
        val build: (MockHttpServletRequestBuilder) -> Unit = {},
        val stub: () -> Unit = {},
        val check: () -> Unit = {},
    )

    @BeforeEach
    fun passRequestsThroughJwtFilterMock() {
        doAnswer {
            it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1))
            null
        }
            .`when`(jwtAuthFilter).doFilter(any(), any(), any())
    }

    @TestFactory
    fun `admin and web sign-in routes match their fixtures`(): List<DynamicTest> {
        val cases = cases()
        assertEquals(ContractCatalog.webRoutes.map { it.id }.toSet(), cases.keys, "Every web route needs exactly one case")
        return ContractCatalog.webRoutes.map { route ->
            dynamicTest(route.id) {
                val case = cases.getValue(route.id)
                reset(*serviceMocks())
                case.stub()
                val call = request(route.method, case.uri).with(user(VIEWER_ID.toString()))
                case.build(call)
                val body = mvc.perform(call).andExpect(status().isOk).andReturn().response.contentAsByteArray
                val node = ContractJson.parse(body)
                assertTrue(node["success"].booleanValue() && !node["data"].isNull, "${route.id} returned no data: $node")
                case.check()
                ContractFiles.check(route.file, node)
            }
        }
    }

    @Test
    fun `web catalog lists exactly the mappings of the admin and web sign-in controllers`() {
        val controllers = webControllers().toSet()
        val mapped = mappings.handlerMethods.filter { (_, method) -> method.beanType in controllers }.flatMap { (info, _) ->
            val patterns = info.pathPatternsCondition?.patternValues ?: info.patternValues
            info.methodsCondition.methods.flatMap { method -> patterns.map { "${method.name} $it" } }
        }
        assertEquals(mapped.size, mapped.toSet().size)
        assertEquals(ContractCatalog.webRoutes.map { "${it.method.name()} ${it.path}" }.sorted(), mapped.sorted())
    }

    @Test
    fun `every admin and web sign-in controller is under contract`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
        val web = scanner.findCandidateComponents("dev.alllexey.itmowidgets.backend").map { Class.forName(it.beanClassName) }
            .filter { type ->
                type.getAnnotation(RequestMapping::class.java)?.value.orEmpty()
                    .any { it.startsWith("/api/admin/") || it.startsWith("/api/web/") }
            }
        assertEquals(webControllers().map { it.name }.sorted(), web.map { it.name }.sorted())
    }

    private fun webControllers(): List<Class<*>> =
        AdminWebContractTest::class.java.getAnnotation(WebMvcTest::class.java).value.map { it.java }

    private fun serviceMocks(): Array<Any> = arrayOf(
        webLogins, userService, privacyService, currentGroups, access, auditService, dashboardService, moderationService,
        reviewsService, summaryService, systemService, usersService,
    )

    /** The arguments of the one call [mock] received to [method]. */
    private fun called(mock: Any, method: String): List<Any?> =
        mockingDetails(mock).invocations.filter { it.method.name == method }.single().arguments.toList()

    private fun body(value: Any): (MockHttpServletRequestBuilder) -> Unit = {
        it.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value))
    }

    private fun fixture(request: String): (MockHttpServletRequestBuilder) -> Unit = {
        it.contentType(MediaType.APPLICATION_JSON).content(ContractRequests.body(request))
    }

    private fun cases(): Map<String, Case> = with(AdminWebContractSamples) {
        val size = AdminPage.DEFAULT_SIZE
        val credentialKey = ServiceCredential.ISU_KEYCLOAK_IDENTITY
        mapOf(
            "adminDashboard_dashboard" to Case("/api/admin/dashboard", stub = {
                `when`(dashboardService.dashboard(VIEWER_ID)).thenReturn(dashboard)
            }),

            "adminSystem_sport" to Case("/api/admin/system/sport", stub = { `when`(systemService.sport(VIEWER_ID)).thenReturn(sport) }),
            "adminSystem_appVersion" to Case("/api/admin/system/app-version", stub = {
                `when`(systemService.appVersion(VIEWER_ID, AppPlatform.ANDROID)).thenReturn(appVersion)
            }),
            "adminSystem_updateAppVersion" to Case("/api/admin/system/app-version", body(appVersionRequest), stub = {
                `when`(systemService.updateAppVersion(VIEWER_ID, AppPlatform.ANDROID, appVersionRequest)).thenReturn(updatedAppVersion)
            }),
            "adminSystem_credentials" to Case("/api/admin/system/credentials", stub = {
                `when`(systemService.credentials(VIEWER_ID)).thenReturn(credentials)
            }),
            "adminSystem_replaceCredential" to Case(
                "/api/admin/system/credentials/$credentialKey",
                { it.contentType(MediaType.APPLICATION_JSON).content("""{"value":"$CREDENTIAL_VALUE"}""") },
                stub = {
                    // The request has no equals (its value is a secret); the matchers fall back to non-null stand-ins.
                    `when`(
                        systemService.replaceCredential(
                            eq(VIEWER_ID) ?: VIEWER_ID,
                            eq(credentialKey) ?: credentialKey,
                            argThat<ServiceCredentialRequest> { it.value == CREDENTIAL_VALUE } ?: ServiceCredentialRequest(""),
                        ),
                    ).thenReturn(credentials)
                },
            ),

            "adminModeration_cases" to Case("/api/admin/moderation/cases", stub = {
                `when`(moderationService.cases(VIEWER_ID, ModerationCaseStatus.OPEN, null, 0, size)).thenReturn(cases)
            }),
            "adminModeration_case" to Case("/api/admin/moderation/cases/$CASE_ID", stub = {
                `when`(moderationService.case(VIEWER_ID, CASE_ID)).thenReturn(ContractSamples.cases.first())
            }),
            "adminModeration_decide" to Case(
                "/api/admin/moderation/cases/$CASE_ID/decisions",
                fixture("ModerationDecisionRequest"),
                stub = {
                    `when`(moderationService.decide(VIEWER_ID, CASE_ID, ContractSamples.decision)).thenReturn(ContractSamples.decided)
                },
            ),
            "adminModeration_restrictions" to Case("/api/admin/moderation/restrictions", stub = {
                `when`(moderationService.restrictions(VIEWER_ID, null, true, 0, size)).thenReturn(restrictions)
            }),
            "adminModeration_revoke" to Case("/api/admin/moderation/restrictions/$RESTRICTION_ID/revoke", check = {
                assertEquals(listOf(VIEWER_ID, RESTRICTION_ID), called(moderationService, "revokeRestriction"))
            }),
            "adminModeration_settings" to Case("/api/admin/moderation/settings", stub = {
                `when`(moderationService.settings(VIEWER_ID)).thenReturn(ContractSamples.moderationSettings)
            }),
            "adminModeration_updateSettings" to Case("/api/admin/moderation/settings", fixture("ModerationSettings"), stub = {
                `when`(moderationService.updateSettings(VIEWER_ID, ContractSamples.moderationSettings))
                    .thenReturn(ContractSamples.moderationSettings)
            }),

            "adminUsers_search" to Case("/api/admin/users", stub = {
                `when`(usersService.search(VIEWER_ID, null, 0, size)).thenReturn(users)
            }),
            "adminUsers_detail" to Case("/api/admin/users/$FRIEND_ISU", stub = {
                `when`(usersService.detail(VIEWER_ID, FRIEND_ISU)).thenReturn(user)
            }),
            "adminUsers_grant" to Case("/api/admin/users/$FRIEND_ISU/roles/MODERATOR", stub = {
                `when`(usersService.grant(VIEWER_ID, FRIEND_ISU, "MODERATOR")).thenReturn(grantedRoles)
            }),
            "adminUsers_revoke" to Case("/api/admin/users/$FRIEND_ISU/roles/MODERATOR", stub = {
                `when`(usersService.revoke(VIEWER_ID, FRIEND_ISU, "MODERATOR")).thenReturn(revokedRoles)
            }),

            "adminReviews_sync" to
                Case("/api/admin/reviews/sync", stub = { `when`(reviewsService.sync(VIEWER_ID)).thenReturn(reviewsSync) }),
            "adminReviews_startSync" to Case("/api/admin/reviews/sync", stub = {
                `when`(reviewsService.startSync(VIEWER_ID)).thenReturn(startedReviewsSync)
            }),
            "adminReviews_verification" to Case("/api/admin/reviews/verification", stub = {
                `when`(reviewsService.verification(VIEWER_ID)).thenReturn(verification)
            }),
            "adminReviews_summaries" to Case("/api/admin/reviews/summaries", stub = {
                `when`(summaryService.state(VIEWER_ID)).thenReturn(aiSummaries)
            }),
            "adminReviews_runSummaries" to Case("/api/admin/reviews/summaries/run", stub = {
                `when`(summaryService.start(VIEWER_ID)).thenReturn(startedAiSummaries)
            }),
            "adminReviews_summaryTeachers" to Case("/api/admin/reviews/summaries/teachers", stub = {
                `when`(summaryService.teachers(VIEWER_ID, null, 0, size)).thenReturn(summaryTeachers)
            }),
            "adminReviews_setSummaryHidden" to Case("/api/admin/reviews/summaries/$TEACHER_ISU/hidden", body(hideSummary), stub = {
                `when`(summaryService.setHidden(VIEWER_ID, TEACHER_ISU, hideSummary)).thenReturn(hiddenSummary)
            }),
            "adminReviews_regenerateSummary" to Case("/api/admin/reviews/summaries/$TEACHER_ISU/regenerate", stub = {
                `when`(summaryService.regenerate(VIEWER_ID, TEACHER_ISU)).thenReturn(regeneratedSummary)
            }),

            "adminAudit_audit" to Case("/api/admin/audit", stub = {
                `when`(auditService.page(VIEWER_ID, 0, size)).thenReturn(audit)
            }),

            "webAuth_createChallenge" to Case("/api/web/auth/challenges", { it.header(HttpHeaders.USER_AGENT, USER_AGENT) }, stub = {
                `when`(webLogins.createChallenge(USER_AGENT, "127.0.0.1")).thenReturn(challenge)
            }),
            "webAuth_poll" to
                Case("/api/web/auth/challenges/$CHALLENGE_ID", { it.header(WebAuthController.POLL_SECRET_HEADER, POLL_SECRET) }, stub = {
                    `when`(webLogins.claim(CHALLENGE_ID, POLL_SECRET)).thenReturn(ClaimResult.Approved(SESSION_TOKEN))
                }),
            "webAuth_logout" to Case("/api/web/auth/logout"),
            "webAuth_me" to Case("/api/web/auth/me", stub = {
                `when`(userService.findUserById(VIEWER_ID)).thenReturn(ContractSamples.viewer)
                `when`(privacyService.userDataFor(ContractSamples.viewer, ContractSamples.viewer)).thenReturn(ContractSamples.me)
                `when`(currentGroups.userData(ContractSamples.me)).thenReturn(ContractSamples.me)
                `when`(access.rolesOf(VIEWER_ID)).thenReturn(setOf(UserRole.MODERATOR, UserRole.ADMIN))
            }),
        )
    }
}
