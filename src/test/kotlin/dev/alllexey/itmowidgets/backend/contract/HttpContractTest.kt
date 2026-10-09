package dev.alllexey.itmowidgets.backend.contract

import dev.alllexey.itmowidgets.backend.contract.ContractSamples.AUTO_ENTRY_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CASE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.CHALLENGE_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.FREE_ENTRY_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.FRIEND_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.LESSON_DATE
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.LINK_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.NOW
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PAIR_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PERIOD
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.PROTOTYPE_LESSON_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.RESTRICTION_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.REVIEW_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.SPORT_LESSON_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.SUBJECT_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.TEACHER_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.VIEWER_ID
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.VIEWER_ISU
import dev.alllexey.itmowidgets.backend.contract.ContractSamples.WEB_LOGIN_CODE
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
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportAutoSignService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportFreeSignService
import dev.alllexey.itmowidgets.backend.feature.sport.service.UserSportLessonService
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportAutoSignController
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportController
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportFreeSignController
import dev.alllexey.itmowidgets.backend.feature.users.model.UserRole
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService.Action
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.users.web.RelationshipState
import dev.alllexey.itmowidgets.backend.feature.users.web.UnavailableStudyGroupsConfig
import dev.alllexey.itmowidgets.backend.feature.users.web.UserController
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.SecurityConfig
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.mockito.ArgumentMatchers.any
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
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
    AppController::class, DeviceController::class, FriendController::class, ModerationController::class,
    ScheduleController::class, SportAutoSignController::class, SportController::class, SportFreeSignController::class,
    SubjectLinkController::class, TeacherReviewController::class, UserController::class,
)
@Import(SecurityConfig::class, UnavailableStudyGroupsConfig::class, HttpContractTest.TimeConfig::class)
class HttpContractTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    @param:Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter

    @MockitoBean private lateinit var webSessions: WebSessionService

    @MockitoBean private lateinit var appVersions: AppVersionSettings

    @MockitoBean private lateinit var devices: DeviceService

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var privacyService: UserPrivacyService

    @MockitoBean private lateinit var restrictionService: RestrictionService

    @MockitoBean private lateinit var access: AdminAccess

    @MockitoBean private lateinit var webLogins: WebLoginService

    @MockitoBean private lateinit var lessonService: LessonService

    @MockitoBean private lateinit var lessonRepository: LessonRepository

    @MockitoBean private lateinit var lessonContext: LessonContextService

    @MockitoBean private lateinit var links: SubjectLinkService

    @MockitoBean private lateinit var reviews: TeacherReviewService

    @MockitoBean private lateinit var sportLessons: UserSportLessonService

    @MockitoBean private lateinit var freeSignService: SportFreeSignService

    @MockitoBean private lateinit var autoSignService: SportAutoSignService

    @MockitoBean private lateinit var moderation: ModerationService

    @MockitoBean private lateinit var settingsService: ModerationSettingsService

    @MockitoBean private lateinit var adminModeration: AdminModerationService

    @TestConfiguration(proxyBeanMethods = false)
    class TimeConfig {
        @Bean fun clock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC)
    }

    /** [uri] fills the catalog path; [stub] answers the call the route makes; [check] asserts calls without a reply. */
    private class Case(val uri: String, val stub: () -> Unit = {}, val check: () -> Unit = {})

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
                val call = request(route.method, case.uri).with(user(VIEWER_ID.toString()))
                route.request?.let { call.contentType(MediaType.APPLICATION_JSON).content(ContractRequests.body(it)) }
                val body = mvc.perform(call).andExpect(status().isOk).andReturn().response.contentAsByteArray
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
        // A route has a fixture per variant its query selects (`appVersionInfoIos`), so the catalog may list it twice.
        assertEquals(ContractCatalog.routes.map { "${it.method.name()} ${it.path}" }.distinct().sorted(), mapped.sorted())
    }

    @Test
    fun `every controller outside admin and web sign-in is under contract`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(RestController::class.java)) }
        val all = scanner.findCandidateComponents("dev.alllexey.itmowidgets.backend").map { Class.forName(it.beanClassName) }
        val outside = all.filter { type ->
            type.getAnnotation(RequestMapping::class.java)?.value.orEmpty()
                .any { it.startsWith("/api/admin/") || it.startsWith("/api/web/") }
        }
        assertEquals(appControllers().map { it.name }.sorted(), (all - outside.toSet()).map { it.name }.sorted())
    }

    private fun appControllers(): List<Class<*>> = HttpContractTest::class.java.getAnnotation(WebMvcTest::class.java).value.map { it.java }

    private fun serviceMocks(): Array<Any> = arrayOf(
        appVersions, devices, profiles, users, privacyService,
        restrictionService, access, webLogins, lessonService, lessonRepository, lessonContext, links, reviews, sportLessons,
        freeSignService, autoSignService, moderation, settingsService, adminModeration,
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
                assertEquals(
                    listOf(VIEWER_ID, registerDevice.fcmToken, registerDevice.deviceName, null),
                    called(devices, "registerOrUpdateDevice"),
                )
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
            "webLoginPreview" to Case("/api/users/me/web-login/$WEB_LOGIN_CODE", {
                `when`(webLogins.preview(VIEWER_ID, WEB_LOGIN_CODE)).thenReturn(webLogin)
            }),
            "approveWebLogin" to Case("/api/users/me/web-login/$CHALLENGE_ID/approve", check = {
                assertEquals(listOf(VIEWER_ID, CHALLENGE_ID), called(webLogins, "approve"))
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
