package dev.alllexey.itmowidgets.backend.contract

import dev.alllexey.itmowidgets.backend.contract.ContractSamples.AGENT_TOKEN_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.AUTO_ENTRY_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CASE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CHALLENGE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.FREE_ENTRY_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.FRIEND_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.INSTALL_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.LESSON_DATE
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.LINK_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.MCP_CONNECTION_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.NOW
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.OTHER_INSTALL_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PAIR_CODE
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PAIR_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PERIOD
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PROTOTYPE_LESSON_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.RESTRICTION_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.REVIEW_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.SECTION_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.SPORT_LESSON_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.SUBJECT_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.TEACHER_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.VIEWER_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.VIEWER_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.WEB_CONNECTION_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.WEEK_END
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.WEEK_START
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.friend
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.friendData
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.profile
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.viewer
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationService
import dev.alllexey.itmowidgets.backend.feature.app.model.AppPlatform
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.app.web.AppController
import dev.alllexey.itmowidgets.backend.feature.clients.service.ClientActivityService
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientEventRow
import dev.alllexey.itmowidgets.backend.feature.connections.model.ClientType
import dev.alllexey.itmowidgets.backend.feature.connections.model.ConnectionScope
import dev.alllexey.itmowidgets.backend.feature.connections.model.ItmoStatus
import dev.alllexey.itmowidgets.backend.feature.connections.model.PairKind
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientEventsSlice
import dev.alllexey.itmowidgets.backend.feature.connections.service.ClientPollService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionNotificationService
import dev.alllexey.itmowidgets.backend.feature.connections.service.ConnectionService
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingClaim
import dev.alllexey.itmowidgets.backend.feature.connections.service.PairingService
import dev.alllexey.itmowidgets.backend.feature.connections.web.ApprovePairingRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.ClientController
import dev.alllexey.itmowidgets.backend.feature.connections.web.ConnectionsController
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreateChallengeRequest
import dev.alllexey.itmowidgets.backend.feature.connections.web.CreatedChallenge
import dev.alllexey.itmowidgets.backend.feature.connections.web.ItmoSession
import dev.alllexey.itmowidgets.backend.feature.connections.web.PairingController
import dev.alllexey.itmowidgets.backend.feature.connections.web.PairingPreview
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkController
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationController
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.push.web.DeviceController
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewService
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewController
import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonContextService
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService
import dev.alllexey.itmowidgets.backend.feature.schedule.web.ScheduleController
import dev.alllexey.itmowidgets.backend.feature.social.web.FriendController
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportAgentBookedNotificationService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportAutoSignService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportFreeSignService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportSectionService
import dev.alllexey.itmowidgets.backend.feature.sport.service.UserSportLessonService
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportAutoSignController
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportController
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportFreeSignController
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportSectionController
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.service.AccountDeletionService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService.Action
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.RelationshipState
import dev.alllexey.itmowidgets.backend.feature.users.web.UserController
import dev.alllexey.itmowidgets.backend.platform.security.BearerAuthentication
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import dev.alllexey.itmowidgets.backend.platform.security.UserDetailsServiceImpl.Companion.principal
import dev.alllexey.itmowidgets.backend.testing.TestConnections
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.reset
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Import
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.http.MediaType
import org.springframework.security.core.Authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders every app route through the real controllers, security chain and Spring's Jackson configuration with
 * mocked services, and posts every request fixture the way released clients send it.
 */
@WebMvcTest(
    ClientController::class, ConnectionsController::class, PairingController::class, AppController::class, DeviceController::class,
    FriendController::class, ModerationController::class,
    ScheduleController::class, SportAutoSignController::class, SportController::class, SportFreeSignController::class,
    SportSectionController::class,
    SubjectLinkController::class, TeacherReviewController::class, UserController::class,
)
@Import(
    SecurityConfig::class,
    HttpContractTest.TimeConfig::class,
)
class HttpContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    @param:Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter

    @MockitoBean private lateinit var appVersions: AppVersionSettings

    @MockitoBean private lateinit var devices: DeviceService

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var privacyService: UserPrivacyService

    @MockitoBean private lateinit var restrictionService: RestrictionService

    @MockitoBean private lateinit var access: AdminAccess

    @MockitoBean private lateinit var accountDeletion: AccountDeletionService

    @MockitoBean private lateinit var lessonService: LessonService

    @MockitoBean private lateinit var lessonRepository: LessonRepository

    @MockitoBean private lateinit var lessonContext: LessonContextService

    @MockitoBean private lateinit var links: SubjectLinkService

    @MockitoBean private lateinit var reviews: TeacherReviewService

    @MockitoBean private lateinit var sportLessons: UserSportLessonService

    @MockitoBean private lateinit var freeSignService: SportFreeSignService

    @MockitoBean private lateinit var autoSignService: SportAutoSignService

    @MockitoBean private lateinit var sectionService: SportSectionService

    @MockitoBean private lateinit var moderation: ModerationService

    @MockitoBean private lateinit var settingsService: ModerationSettingsService

    @MockitoBean private lateinit var adminModeration: AdminModerationService

    @MockitoBean private lateinit var agentBooked: SportAgentBookedNotificationService

    @MockitoBean private lateinit var clients: ClientActivityService

    @MockitoBean private lateinit var connections: ConnectionService

    @MockitoBean private lateinit var pairings: PairingService

    @MockitoBean private lateinit var polls: ClientPollService

    @MockitoBean private lateinit var connectionNotifications: ConnectionNotificationService

    private companion object {
        val COMMITMENT: String = PairingService.encode(ByteArray(32) { 1 })
        val BUNDLE: String = PairingService.encode(ByteArray(48) { 7 })
        const val CLAIM_SECRET = "cccccccccccccccccccccccccccccccccccccccccccc"
        const val ITMO_SID = "8d1f0c2e-1b6a-4c55-9a43-0f2d7e6b1a01"
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    /**
     * [uri] fills the catalog path; [stub] answers the call the route makes; [check] asserts calls without a reply.
     * [auth] replaces the app's bearer (agent routes); [body] is a request no released Core sends, so it has no fixture.
     */
    private class Case(
        val uri: String,
        val stub: () -> Unit = {},
        val check: () -> Unit = {},
        val auth: Authentication? = null,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    private val agent = TestConnections.client(
        VIEWER_ID,
        ClientType.AGENT,
        setOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ),
        id = AGENT_TOKEN_ID,
    )
    private val web = TestConnections.web(VIEWER_ID, NOW, id = WEB_CONNECTION_ID)

    @BeforeEach
    fun passRequestsThroughJwtFilterMock() {
        doAnswer {
            it.getArgument<FilterChain>(2).doFilter(it.getArgument(0), it.getArgument(1))
            null
        }
            .`when`(jwtAuthFilter).doFilter(any(), any(), any())
    }

    @TestFactory
    fun `request fixtures decode into Backend types`(): List<DynamicTest> = ContractCatalog.requests.map { request ->
        dynamicTest(request.id) {
            ContractFiles.create(request.file, ContractRequests.asCoreWrites(request.id))
            val sample = ContractRequests.samples.getValue(request.id)
            val decoded = json.readValue<Any>(ContractRequests.body(request.id), json.typeFactory.constructType(sample.type))
            assertEquals(sample.value, decoded)
        }
    }

    @TestFactory
    fun `app routes match their fixtures`(): List<DynamicTest> {
        val cases = cases()
        assertEquals(ContractCatalog.routes.map { it.id }.toSet(), cases.keys, "Every catalog route needs exactly one case")
        return ContractCatalog.routes.map { route ->
            dynamicTest(route.id) {
                val case = cases.getValue(route.id)
                reset(*serviceMocks())
                case.stub()
                // A bearer signed in just now, as the app's requests arrive; `deleteMyAccount` takes nothing older.
                val call = request(
                    route.method,
                    case.uri,
                ).with(authentication(case.auth ?: BearerAuthentication(principal(VIEWER_ID), NOW)))
                route.request?.let { call.contentType(MediaType.APPLICATION_JSON).content(ContractRequests.body(it)) }
                case.body?.let { call.contentType(MediaType.APPLICATION_JSON).content(it) }
                case.headers.forEach { (name, value) -> call.header(name, value) }
                // The agent long-poll answers through a DeferredResult.
                val started = mvc.perform(call)
                val answered = started.andReturn().let { if (it.request.isAsyncStarted) mvc.perform(asyncDispatch(it)) else started }
                val body = answered.andExpect(status().isOk).andReturn().response.contentAsByteArray
                val node = ContractJson.parse(body)
                assertTrue(node["success"].booleanValue() && !node["data"].isNull, "${route.id} returned no data: $node")
                case.check()
                ContractFiles.check(route.file, node)
            }
        }
    }

    @Test
    fun `catalog lists exactly the mappings of the app controllers`() {
        val controllers = appControllers().toSet()
        val mapped = mappings.handlerMethods.filter { (_, method) -> method.beanType in controllers }.flatMap { (info, _) ->
            val patterns = info.pathPatternsCondition?.patternValues ?: info.patternValues
            info.methodsCondition.methods.flatMap { method -> patterns.map { "${method.name} $it" } }
        }
        assertEquals(mapped.size, mapped.toSet().size)
        // A route has a fixture per variant its query or body selects (`appVersionInfoIos`, `registerDeviceIos`), so the
        // catalog may list it twice.
        assertEquals(ContractCatalog.routes.map { "${it.method.name()} ${it.path}" }.distinct().sorted(), mapped.sorted())
    }

    @Test
    fun `every controller outside admin is under contract`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
        val all = scanner.findCandidateComponents("dev.alllexey.itmowidgets.backend").map { Class.forName(it.beanClassName) }
        val outside = all.filter { type ->
            type.getAnnotation(RequestMapping::class.java)?.value.orEmpty()
                .any { it.startsWith("/api/admin/") }
        }
        assertEquals(appControllers().map { it.name }.sorted(), (all - outside.toSet()).map { it.name }.sorted())
    }

    private fun appControllers(): List<Class<*>> = HttpContractTest::class.java.getAnnotation(WebMvcTest::class.java).value.map { it.java }

    private fun serviceMocks(): Array<Any> = arrayOf(
        appVersions, devices, profiles, users, privacyService, accountDeletion,
        restrictionService, access, lessonService, lessonRepository, lessonContext, links, reviews, sportLessons,
        freeSignService, autoSignService, moderation, settingsService, adminModeration, agentBooked, clients, connections, pairings,
        polls, connectionNotifications, sectionService,
    )

    /** The arguments of the one call [mock] received to [method]. */
    private fun called(mock: Any, method: String): List<Any?> =
        mockingDetails(mock).invocations.filter { it.method.name == method }.single().arguments.toList()

    private fun viewerResolves() {
        `when`(users.findUserById(VIEWER_ID)).thenReturn(viewer)
        `when`(users.findUserByIsu(FRIEND_ISU)).thenReturn(friend)
    }

    private fun friendAction(action: Action, state: RelationshipState) = Case("/api/friends/$FRIEND_ISU/${action.name.lowercase()}", {
        `when`(profiles.act(VIEWER_ID, FRIEND_ISU, action)).thenReturn(profile(friendData, state))
    })

    private fun cases(): Map<String, Case> = with(ContractSamples) {
        val version = AppVersionSettings.AppVersion(latest = "2.2", minimum = "2.1", note = "Синтетическая заметка о версии")
        val iosVersion = AppVersionSettings.AppVersion(latest = "2.3.1", minimum = "2.3", note = "")
        mapOf(
            "registerDevice" to Case("/api/device/register-device", check = {
                assertEquals(listOf(VIEWER_ID, registerDevice, null, null), called(devices, "registerOrUpdateDevice"))
            }),
            "registerDeviceIos" to Case("/api/device/register-device", check = {
                assertEquals(listOf(VIEWER_ID, registerDeviceIos, null, null), called(devices, "registerOrUpdateDevice"))
            }),
            "unregisterCurrentDevice" to Case("/api/device/current", check = {
                assertEquals(listOf(VIEWER_ID, unregisterDevice.fcmToken), called(devices, "unregisterDevice"))
            }),

            "latestAppVersion" to Case("/api/app/version", {
                `when`(appVersions.current(AppPlatform.ANDROID)).thenReturn(version)
            }),
            "appVersionInfo" to Case("/api/app/version-info", {
                `when`(appVersions.current(AppPlatform.ANDROID)).thenReturn(version)
            }),
            "appVersionInfoIos" to Case("/api/app/version-info?platform=IOS", {
                `when`(appVersions.current(AppPlatform.IOS)).thenReturn(iosVersion)
            }),

            "syncLessons" to Case("/api/schedule/lessons/sync", { viewerResolves() }, check = {
                val arguments = called(lessonService, "syncLessons")
                assertEquals(listOf(VIEWER_ISU, WEEK_START, WEEK_END), arguments.take(3))
                @Suppress("UNCHECKED_CAST")
                assertEquals(lessonSync.lessons.map { it.pairId }, (arguments[3] as List<LessonEntity>).map { it.pairId })
            }),
            "userLessons" to Case("/api/schedule/lessons/user/$FRIEND_ISU?from=$WEEK_START&to=$WEEK_END", {
                viewerResolves()
                `when`(privacyService.canViewSchedule(viewer, friend)).thenReturn(true)
                `when`(lessonRepository.findAllByIsuAndDates(FRIEND_ISU, WEEK_START, WEEK_END)).thenReturn(lessons)
            }),
            "friendsOnLesson" to Case("/api/schedule/lessons/$PAIR_ID/friends?date=$LESSON_DATE", {
                viewerResolves()
                `when`(lessonContext.friendsOnLesson(viewer, PAIR_ID, LESSON_DATE)).thenReturn(friends)
            }),

            "sendFriendRequest" to friendAction(Action.REQUEST, RelationshipState.OUTGOING),
            "acceptFriendRequest" to friendAction(Action.ACCEPT, RelationshipState.FRIENDS),
            "rejectFriendRequest" to friendAction(Action.REJECT, RelationshipState.NONE),
            "cancelFriendRequest" to friendAction(Action.CANCEL, RelationshipState.NONE),
            "removeFriend" to Case("/api/friends/$FRIEND_ISU", {
                `when`(profiles.act(VIEWER_ID, FRIEND_ISU, Action.REMOVE)).thenReturn(profile(friendData, RelationshipState.NONE))
            }),
            "friends" to Case("/api/friends", { `when`(profiles.friends(VIEWER_ID)).thenReturn(friends) }),
            "incomingFriendRequests" to Case("/api/friends/requests/incoming", {
                `when`(profiles.incoming(VIEWER_ID)).thenReturn(incoming)
            }),
            "outgoingFriendRequests" to Case("/api/friends/requests/outgoing", {
                `when`(profiles.outgoing(VIEWER_ID)).thenReturn(outgoing)
            }),

            "userProfile" to Case("/api/users/$FRIEND_ISU", {
                `when`(profiles.profile(VIEWER_ID, FRIEND_ISU)).thenReturn(profile(friendData, RelationshipState.FRIENDS))
            }),
            "userFriends" to Case("/api/users/$FRIEND_ISU/friends", {
                `when`(profiles.userFriends(VIEWER_ID, FRIEND_ISU)).thenReturn(mixed)
            }),
            "lookupUsers" to Case("/api/users/lookup", { `when`(profiles.lookup(VIEWER_ID, lookupRequest)).thenReturn(lookup) }),
            "myPrivacySettings" to Case("/api/users/me/privacy", {
                viewerResolves()
                `when`(users.privacySettings(viewer)).thenReturn(privacy)
            }),
            "updateMyPrivacySettings" to Case("/api/users/me/privacy", {
                viewerResolves()
                `when`(users.updatePrivacySettings(viewer, privacyRequest)).thenReturn(privacyRequest)
            }),
            "updateIdTokenData" to Case("/api/users/me/id-token", { viewerResolves() }, check = {
                assertEquals(listOf(viewer, idToken.idToken), called(users, "updateDataFromIdToken"))
            }),
            "myUserData" to Case("/api/users/me/data", {
                viewerResolves()
                `when`(privacyService.userDataFor(viewer, viewer)).thenReturn(me)
            }),
            "myRestrictions" to Case("/api/users/me/restrictions", {
                `when`(restrictionService.activeFor(VIEWER_ID)).thenReturn(restrictions)
            }),
            "myRoles" to Case("/api/users/me/roles", {
                `when`(access.rolesOf(VIEWER_ID)).thenReturn(setOf(UserRole.ADMIN, UserRole.MODERATOR))
            }),
            "updateConsent" to Case("/api/users/me/consent", check = {
                assertEquals(listOf(VIEWER_ID, 1), called(users, "enroll"))
            }, body = """{"version":1}"""),
            "deleteMyAccount" to Case("/api/users/me", {
                `when`(users.isuOf(VIEWER_ID)).thenReturn(VIEWER_ISU)
            }, check = {
                assertEquals(listOf(VIEWER_ISU), called(accountDeletion, "delete"))
            }),

            "subjectLinks" to Case("/api/subjects/$SUBJECT_ID/links?period=$PERIOD", {
                `when`(links.links(VIEWER_ID, SUBJECT_ID, PERIOD)).thenReturn(subjectLinks)
            }),
            "saveSubjectLink" to Case("/api/links/$LINK_ID", {
                `when`(links.save(VIEWER_ID, LINK_ID, saveLink)).thenReturn(savedLink)
            }),
            "deleteSubjectLink" to Case("/api/links/$LINK_ID", check = {
                assertEquals(listOf(VIEWER_ID, LINK_ID), called(links, "delete"))
            }),
            "pinSubjectLink" to Case("/api/subjects/$SUBJECT_ID/links/pin", {
                `when`(links.pin(VIEWER_ID, SUBJECT_ID, pinLink)).thenReturn(pinnedLinks)
            }),
            "voteSubjectLink" to Case("/api/links/$LINK_ID/vote", {
                `when`(links.vote(VIEWER_ID, LINK_ID, vote.value)).thenReturn(sharedLink)
            }),
            "reportSubjectLink" to Case("/api/links/$LINK_ID/report", {
                `when`(links.report(VIEWER_ID, LINK_ID, report)).thenReturn(sharedLink)
            }),

            "teacherReviews" to Case("/api/teachers/$TEACHER_ISU/reviews", {
                `when`(reviews.reviews(VIEWER_ID, TEACHER_ISU)).thenReturn(teacherReviews)
            }),
            "saveMyTeacherReview" to Case("/api/teachers/$TEACHER_ISU/reviews/mine", {
                `when`(reviews.save(VIEWER_ID, TEACHER_ISU, saveReview)).thenReturn(savedReview)
            }),
            "deleteMyTeacherReview" to Case("/api/teachers/$TEACHER_ISU/reviews/mine", {
                `when`(reviews.delete(VIEWER_ID, TEACHER_ISU)).thenReturn(deletedReview)
            }),
            "voteTeacherReview" to Case("/api/reviews/$REVIEW_ID/vote", {
                `when`(reviews.vote(VIEWER_ID, REVIEW_ID, vote.value)).thenReturn(votedReview)
            }),
            "reportTeacherReview" to Case("/api/reviews/$REVIEW_ID/report", {
                `when`(reviews.report(VIEWER_ID, REVIEW_ID, report)).thenReturn(reportedReview)
            }),
            "teacherSummaryLevels" to Case(
                "/api/teachers/summary-levels?" + summaryLevels.joinToString("&") { "isu=${it.teacherIsu}" },
                { `when`(reviews.summaryLevels(summaryLevels.map { it.teacherIsu })).thenReturn(summaryLevels) },
            ),

            "syncSportLessons" to Case("/api/sport/sign/sync", check = {
                assertEquals(listOf(VIEWER_ID, sportLessonIds), called(sportLessons, "syncLessons"))
            }),
            "friendsSportBookings" to Case("/api/sport/friends/sport-bookings", {
                `when`(sportLessons.getUserFriendsBookings(VIEWER_ID)).thenReturn(friendBookings)
            }),
            "userSportBookings" to Case("/api/sport/users/$FRIEND_ISU/bookings", {
                `when`(sportLessons.getUserBookings(VIEWER_ID, FRIEND_ISU)).thenReturn(userBookings)
            }),

            "mySportFreeSignEntries" to Case("/api/sport/free-sign/entry/my", {
                `when`(freeSignService.getUserEntries(VIEWER_ID)).thenReturn(freeEntries)
            }),
            "createSportFreeSignEntry" to Case("/api/sport/free-sign/entry/create", {
                `when`(freeSignService.createEntry(VIEWER_ID, SPORT_LESSON_ID, true)).thenReturn(createdFree)
            }),
            "cancelSportFreeSignEntry" to Case("/api/sport/free-sign/entry/$FREE_ENTRY_ID/cancel", check = {
                assertEquals(listOf(VIEWER_ID, FREE_ENTRY_ID), called(freeSignService, "cancelEntry"))
            }),
            "cancelSportFreeSignEntryByLesson" to Case("/api/sport/free-sign/lesson/$SPORT_LESSON_ID/cancel", check = {
                assertEquals(listOf(VIEWER_ID, SPORT_LESSON_ID), called(freeSignService, "cancelEntryByLesson"))
            }),
            "currentSportFreeSignQueues" to Case("/api/sport/free-sign/queue/current", {
                `when`(freeSignService.getCurrentQueues()).thenReturn(freeQueues)
            }),
            "markSportFreeSignEntrySatisfied" to Case("/api/sport/free-sign/entry/$FREE_ENTRY_ID/mark-satisfied", check = {
                assertEquals(listOf(VIEWER_ID, FREE_ENTRY_ID), called(freeSignService, "markEntrySatisfied"))
            }),
            "markSportFreeSignEntrySatisfiedByLesson" to Case(
                "/api/sport/free-sign/lesson/$SPORT_LESSON_ID/mark-satisfied",
                check = {
                    assertEquals(
                        listOf(VIEWER_ID, SPORT_LESSON_ID),
                        called(freeSignService, "markEntrySatisfiedByLesson"),
                    )
                },
            ),

            "sportAutoSignLimits" to Case("/api/sport/auto-sign/limits", {
                `when`(autoSignService.getLimits(VIEWER_ID)).thenReturn(autoLimits)
            }),
            "mySportAutoSignEntries" to Case("/api/sport/auto-sign/entry/my", {
                `when`(autoSignService.getUserEntries(VIEWER_ID)).thenReturn(autoEntries)
            }),
            "createSportAutoSignEntry" to Case("/api/sport/auto-sign/entry/create", {
                `when`(autoSignService.createEntry(VIEWER_ID, PROTOTYPE_LESSON_ID)).thenReturn(createdAuto)
            }),
            "cancelSportAutoSignEntry" to Case("/api/sport/auto-sign/entry/$AUTO_ENTRY_ID/cancel", check = {
                assertEquals(listOf(VIEWER_ID, AUTO_ENTRY_ID), called(autoSignService, "cancelEntry"))
            }),
            "cancelSportAutoSignEntryByLesson" to Case("/api/sport/auto-sign/lesson/$SPORT_LESSON_ID/cancel", check = {
                assertEquals(listOf(VIEWER_ID, SPORT_LESSON_ID), called(autoSignService, "cancelEntryByLesson"))
            }),
            "currentSportAutoSignQueues" to Case("/api/sport/auto-sign/queue/current", {
                `when`(autoSignService.getCurrentQueues()).thenReturn(autoQueues)
            }),
            "markSportAutoSignEntrySatisfied" to Case("/api/sport/auto-sign/entry/$AUTO_ENTRY_ID/mark-satisfied", check = {
                assertEquals(listOf(VIEWER_ID, AUTO_ENTRY_ID), called(autoSignService, "markEntrySatisfied"))
            }),
            "markSportAutoSignEntrySatisfiedByLesson" to Case(
                "/api/sport/auto-sign/lesson/$SPORT_LESSON_ID/mark-satisfied",
                check = {
                    assertEquals(
                        listOf(VIEWER_ID, SPORT_LESSON_ID),
                        called(autoSignService, "markEntrySatisfiedByLesson"),
                    )
                },
            ),

            "mySportSections" to Case("/api/sport/sections/my", {
                `when`(sectionService.getMine(VIEWER_ID)).thenReturn(mySections)
            }),
            "sportSectionSlots" to Case("/api/sport/sections/slots?lessonIds=$SPORT_LESSON_ID,${SPORT_LESSON_ID + 1}", {
                `when`(sectionService.getSlots(VIEWER_ID, listOf(SPORT_LESSON_ID, SPORT_LESSON_ID + 1))).thenReturn(sectionSlots)
            }),
            "createSportSection" to Case("/api/sport/sections/create", {
                `when`(sectionService.subscribe(VIEWER_ID, SPORT_LESSON_ID)).thenReturn(mySection)
            }),
            "updateSportSectionOccurrence" to Case("/api/sport/sections/$SECTION_ID/occurrence", {
                `when`(sectionService.setSkipped(VIEWER_ID, SECTION_ID, sectionOccurrence.start, true)).thenReturn(mySection)
            }),
            "stopSportSection" to Case("/api/sport/sections/$SECTION_ID/stop", check = {
                assertEquals(listOf(VIEWER_ID, SECTION_ID), called(sectionService, "stop"))
            }),

            "createPairChallenge" to Case(
                "/api/pair/challenges",
                {
                    val request = CreateChallengeRequest(
                        PairKind.CONNECT,
                        ClientType.AGENT,
                        COMMITMENT,
                        "nas",
                        "1.2.0",
                        "linux",
                        listOf(ConnectionScope.SPORT),
                    )
                    `when`(pairings.create(request, "127.0.0.1"))
                        .thenReturn(CreatedChallenge(CHALLENGE_ID, PAIR_CODE, CLAIM_SECRET, NOW.plusSeconds(120)))
                },
                body = """{"kind":"CONNECT","client":"agent","keyCommitment":"$COMMITMENT","name":"nas","version":"1.2.0",""" +
                    """"platform":"linux","scopes":["sport"]}""",
            ),
            "claimPairChallenge" to Case("/api/pair/challenges/$CHALLENGE_ID/claim", {
                `when`(pairings.claim(CHALLENGE_ID, CLAIM_SECRET)).thenReturn(
                    PairingClaim.Claimed(VIEWER_ID, PairKind.CONNECT, "iwc_" + "A".repeat(43), agentConnection, ByteArray(48) { 7 }),
                )
            }, check = {
                assertEquals(listOf(VIEWER_ID, AGENT_TOKEN_ID, ClientType.AGENT, "nas"), called(connectionNotifications, "notifyConnected"))
            }, body = """{"claimSecret":"$CLAIM_SECRET"}"""),
            "openPairing" to Case("/api/users/me/pair/$PAIR_CODE/open", {
                `when`(pairings.open(VIEWER_ID, PAIR_CODE)).thenReturn(
                    PairingPreview(
                        CHALLENGE_ID, PairKind.RENEW, ClientType.AGENT, "nas", "1.2.0", "linux",
                        listOf(ConnectionScope.SPORT, ConnectionScope.FRIENDS_READ).map(::scopeInfo), COMMITMENT, AGENT_TOKEN_ID,
                        NOW.plusSeconds(600),
                    ),
                )
            }),
            "approvePairing" to Case("/api/users/me/pair/$CHALLENGE_ID/approve", {
                val request =
                    ApprovePairingRequest(BUNDLE, listOf(ConnectionScope.SPORT), ItmoSession(ITMO_SID, NOW.plusSeconds(7_776_000)))
                `when`(pairings.approve(VIEWER_ID, CHALLENGE_ID, request)).thenReturn(AGENT_TOKEN_ID)
            }, body = """{"sealedBundle":"$BUNDLE","scopes":["sport"],"itmo":{"sid":"$ITMO_SID","expiresAt":"2027-01-03T09:00:00Z"}}"""),
            "userDevices" to Case("/api/users/me/devices", {
                `when`(connections.devices(VIEWER_ID, INSTALL_ID)).thenReturn(userDevices)
            }, headers = mapOf("X-Client" to "kind=android; version=2.4.0; install=$INSTALL_ID")),
            "updateConnectionScopes" to Case("/api/users/me/connections/$AGENT_TOKEN_ID/scopes", {
                `when`(connections.updateScopes(VIEWER_ID, AGENT_TOKEN_ID, setOf(ConnectionScope.SPORT), true)).thenReturn(agentConnection)
            }, body = """{"scopes":["sport"]}"""),
            "revokeConnection" to Case("/api/users/me/connections/$AGENT_TOKEN_ID/revoke", check = {
                assertEquals(listOf(VIEWER_ID, AGENT_TOKEN_ID), called(connections, "revoke"))
            }),
            "connectionItmoEnded" to Case("/api/users/me/connections/$MCP_CONNECTION_ID/itmo-ended", check = {
                assertEquals(listOf(VIEWER_ID, MCP_CONNECTION_ID), called(connections, "itmoEnded"))
            }),
            "signOutInstall" to Case("/api/users/me/installs/$OTHER_INSTALL_ID/sign-out", check = {
                assertEquals(listOf(VIEWER_ID, OTHER_INSTALL_ID, INSTALL_ID), called(connections, "signOutInstall"))
            }, headers = mapOf("X-Client" to "kind=android; version=2.4.0; install=$INSTALL_ID")),

            "clientMe" to Case("/api/client/me", {
                `when`(users.isuOf(VIEWER_ID)).thenReturn(VIEWER_ISU)
                `when`(connections.get(WEB_CONNECTION_ID)).thenReturn(webConnection)
            }, auth = web),
            "clientEvents" to Case("/api/client/events?after=4&wait=0&itmo=ok", {
                // The poll itself (heartbeat, parking) is ClientPollService's; here it answers at once.
                doAnswer {
                    @Suppress("UNCHECKED_CAST")
                    (it.getArgument<Any>(4) as (ClientEventsSlice) -> Unit)(
                        ClientEventsSlice(
                            listOf(
                                ClientEventRow(
                                    5,
                                    "sport-free:$FREE_ENTRY_ID:1",
                                    "SPORT_FREE_SIGN_LESSONS_PAYLOAD",
                                    json.writeValueAsString(mapOf("sportLessons" to fcmLessons.take(1))),
                                    NOW,
                                    NOW.plusSeconds(3_600),
                                ),
                            ),
                            nextCursor = 7,
                            hasMore = false,
                            gap = false,
                        ),
                    )
                }.`when`(polls).poll(any() ?: agent, anyLong(), anyInt(), anyString(), any() ?: {}, any() ?: {})
            }, auth = agent),
            "updateClientStatus" to Case("/api/client/status", check = {
                assertEquals(listOf(AGENT_TOKEN_ID, ItmoStatus.CLOSED, "1.2.1"), called(connections, "reportStatus"))
            }, auth = agent, body = """{"itmo":"closed","version":"1.2.1"}"""),
            "renewPairing" to Case("/api/client/pair/renew", {
                `when`(pairings.renew(AGENT_TOKEN_ID, COMMITMENT, "127.0.0.1"))
                    .thenReturn(CreatedChallenge(CHALLENGE_ID, PAIR_CODE, CLAIM_SECRET, NOW.plusSeconds(120)))
            }, auth = agent, body = """{"keyCommitment":"$COMMITMENT"}"""),
            "revokeClientSelf" to Case("/api/client/self", check = {
                assertEquals(listOf(VIEWER_ID, AGENT_TOKEN_ID), called(connections, "revoke"))
            }, auth = agent),

            "moderationCases" to Case("/api/moderation/cases?status=OPEN", {
                `when`(moderation.cases(VIEWER_ID, ModerationCaseStatus.OPEN)).thenReturn(cases)
            }),
            "decide" to Case("/api/moderation/cases/$CASE_ID/decisions", {
                `when`(moderation.decide(VIEWER_ID, CASE_ID, decision)).thenReturn(decided)
            }),
            "userRestrictions" to Case("/api/moderation/restrictions?isu=$FRIEND_ISU", {
                `when`(restrictionService.forUser(VIEWER_ID, FRIEND_ISU)).thenReturn(restrictions)
            }),
            "revokeRestriction" to Case("/api/moderation/restrictions/$RESTRICTION_ID/revoke", check = {
                assertEquals(listOf(RESTRICTION_ID, VIEWER_ID), called(restrictionService, "revoke"))
            }),
            "moderationSettings" to Case("/api/moderation/settings", {
                `when`(settingsService.settings(VIEWER_ID)).thenReturn(moderationSettings)
            }),
            "updateModerationSettings" to Case("/api/moderation/settings", {
                `when`(adminModeration.updateSettings(VIEWER_ID, moderationSettings)).thenReturn(moderationSettings)
            }),
        )
    }
}
