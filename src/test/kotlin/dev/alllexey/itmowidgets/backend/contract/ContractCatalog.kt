package dev.alllexey.itmowidgets.backend.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.http.HttpMethod
import org.springframework.http.HttpMethod.DELETE
import org.springframework.http.HttpMethod.GET
import org.springframework.http.HttpMethod.POST
import org.springframework.http.HttpMethod.PUT

/**
 * Everything released clients exchange with Backend: the 62 app routes of Core 1.7.0 (56 `ItmoWidgetsApi` and
 * 6 `ItmoWidgetsModerationApi` methods, named after them), the request bodies Core sends and the FCM `type`s,
 * plus the route variants only the shared client calls ([NOT_IN_CORE]).
 * [minCore] is the oldest decoded Core release (1.2.0 or 1.7.0) that calls the route or sends the body.
 */
object ContractCatalog {
    const val CORE_120 = "1.2.0"
    const val CORE_170 = "1.7.0"

    /** No released Core calls it: Backend 1.8.0 added it for the shared client, and both decode suites skip it. */
    const val NOT_IN_CORE = "1.8.0"

    data class Route(
        val area: String,
        val id: String,
        val method: HttpMethod,
        val path: String,
        val minCore: String,
        val request: String? = null,
    ) {
        val file get() = "http/$area/$id.json"
    }

    data class Request(val id: String, val minCore: String) {
        val file get() = "requests/$id.json"
    }

    data class Fcm(val id: String, val minCore: String) {
        val file get() = "fcm/$id.json"
    }

    val routes: List<Route> = listOf(
        Route("device", "registerDevice", POST, "/api/device/register-device", CORE_120, "RegisterDeviceRequest"),
        Route("device", "unregisterCurrentDevice", DELETE, "/api/device/current", CORE_120, "UnregisterDeviceRequest"),

        Route("app", "latestAppVersion", GET, "/api/app/version", CORE_120),
        Route("app", "appVersionInfo", GET, "/api/app/version-info", CORE_120),
        Route("app", "appVersionInfoIos", GET, "/api/app/version-info", NOT_IN_CORE),

        Route("schedule", "syncLessons", POST, "/api/schedule/lessons/sync", CORE_120, "LessonSyncRequest"),
        Route("schedule", "userLessons", GET, "/api/schedule/lessons/user/{isu}", CORE_120),
        Route("schedule", "friendsOnLesson", GET, "/api/schedule/lessons/{pairId}/friends", CORE_120),

        Route("friends", "sendFriendRequest", POST, "/api/friends/{isu}/request", CORE_120),
        Route("friends", "acceptFriendRequest", POST, "/api/friends/{isu}/accept", CORE_120),
        Route("friends", "rejectFriendRequest", POST, "/api/friends/{isu}/reject", CORE_120),
        Route("friends", "cancelFriendRequest", POST, "/api/friends/{isu}/cancel", CORE_120),
        Route("friends", "removeFriend", DELETE, "/api/friends/{isu}", CORE_120),
        Route("friends", "friends", GET, "/api/friends", CORE_120),
        Route("friends", "incomingFriendRequests", GET, "/api/friends/requests/incoming", CORE_120),
        Route("friends", "outgoingFriendRequests", GET, "/api/friends/requests/outgoing", CORE_120),

        Route("users", "userProfile", GET, "/api/users/{isu}", CORE_120),
        Route("users", "userFriends", GET, "/api/users/{isu}/friends", CORE_120),
        Route("users", "lookupUsers", POST, "/api/users/lookup", CORE_120, "UserLookupRequest"),
        Route("users", "myPrivacySettings", GET, "/api/users/me/privacy", CORE_120),
        Route("users", "updateMyPrivacySettings", PUT, "/api/users/me/privacy", CORE_120, "UserPrivacySettings"),
        Route("users", "updateIdTokenData", PUT, "/api/users/me/id-token", CORE_120, "IdTokenRequest"),
        Route("users", "myUserData", GET, "/api/users/me/data", CORE_120),
        Route("users", "myRestrictions", GET, "/api/users/me/restrictions", CORE_170),
        Route("users", "myRoles", GET, "/api/users/me/roles", CORE_170),
        Route("users", "webLoginPreview", GET, "/api/users/me/web-login/{code}", CORE_170),
        Route("users", "approveWebLogin", POST, "/api/users/me/web-login/{challengeId}/approve", CORE_170),

        Route("links", "subjectLinks", GET, "/api/subjects/{subjectId}/links", CORE_170),
        Route("links", "saveSubjectLink", PUT, "/api/links/{id}", CORE_170, "SaveSubjectLinkRequest"),
        Route("links", "deleteSubjectLink", DELETE, "/api/links/{id}", CORE_170),
        Route("links", "pinSubjectLink", PUT, "/api/subjects/{subjectId}/links/pin", CORE_170, "PinSubjectLinkRequest"),
        Route("links", "voteSubjectLink", PUT, "/api/links/{id}/vote", CORE_170, "ResourceVoteRequest"),
        Route("links", "reportSubjectLink", POST, "/api/links/{id}/report", CORE_170, "ModerationReportRequest"),

        Route("reviews", "teacherReviews", GET, "/api/teachers/{isu}/reviews", CORE_170),
        Route("reviews", "saveMyTeacherReview", PUT, "/api/teachers/{isu}/reviews/mine", CORE_170, "SaveTeacherReviewRequest"),
        Route("reviews", "deleteMyTeacherReview", DELETE, "/api/teachers/{isu}/reviews/mine", CORE_170),
        Route("reviews", "voteTeacherReview", PUT, "/api/reviews/{id}/vote", CORE_170, "ResourceVoteRequest"),
        Route("reviews", "reportTeacherReview", POST, "/api/reviews/{id}/report", CORE_170, "ModerationReportRequest"),
        Route("reviews", "teacherSummaryLevels", GET, "/api/teachers/summary-levels", CORE_170),

        Route("sport", "syncSportLessons", POST, "/api/sport/sign/sync", CORE_120, "SportLessonIds"),
        Route("sport", "friendsSportBookings", GET, "/api/sport/friends/sport-bookings", CORE_120),
        Route("sport", "userSportBookings", GET, "/api/sport/users/{isu}/bookings", CORE_120),

        Route("sport-free-sign", "mySportFreeSignEntries", GET, "/api/sport/free-sign/entry/my", CORE_120),
        Route(
            "sport-free-sign",
            "createSportFreeSignEntry",
            POST,
            "/api/sport/free-sign/entry/create",
            CORE_120,
            "SportFreeSignRequest",
        ),
        Route("sport-free-sign", "cancelSportFreeSignEntry", POST, "/api/sport/free-sign/entry/{id}/cancel", CORE_120),
        Route(
            "sport-free-sign",
            "cancelSportFreeSignEntryByLesson",
            POST,
            "/api/sport/free-sign/lesson/{lessonId}/cancel",
            CORE_120,
        ),
        Route("sport-free-sign", "currentSportFreeSignQueues", GET, "/api/sport/free-sign/queue/current", CORE_120),
        Route(
            "sport-free-sign",
            "markSportFreeSignEntrySatisfied",
            POST,
            "/api/sport/free-sign/entry/{id}/mark-satisfied",
            CORE_120,
        ),
        Route(
            "sport-free-sign",
            "markSportFreeSignEntrySatisfiedByLesson",
            POST,
            "/api/sport/free-sign/lesson/{lessonId}/mark-satisfied",
            CORE_120,
        ),

        Route("sport-auto-sign", "sportAutoSignLimits", GET, "/api/sport/auto-sign/limits", CORE_120),
        Route("sport-auto-sign", "mySportAutoSignEntries", GET, "/api/sport/auto-sign/entry/my", CORE_120),
        Route(
            "sport-auto-sign",
            "createSportAutoSignEntry",
            POST,
            "/api/sport/auto-sign/entry/create",
            CORE_120,
            "SportAutoSignRequest",
        ),
        Route("sport-auto-sign", "cancelSportAutoSignEntry", POST, "/api/sport/auto-sign/entry/{id}/cancel", CORE_120),
        Route(
            "sport-auto-sign",
            "cancelSportAutoSignEntryByLesson",
            POST,
            "/api/sport/auto-sign/lesson/{lessonId}/cancel",
            CORE_120,
        ),
        Route("sport-auto-sign", "currentSportAutoSignQueues", POST, "/api/sport/auto-sign/queue/current", CORE_120),
        Route(
            "sport-auto-sign",
            "markSportAutoSignEntrySatisfied",
            POST,
            "/api/sport/auto-sign/entry/{id}/mark-satisfied",
            CORE_120,
        ),
        Route(
            "sport-auto-sign",
            "markSportAutoSignEntrySatisfiedByLesson",
            POST,
            "/api/sport/auto-sign/lesson/{lessonId}/mark-satisfied",
            CORE_120,
        ),

        Route("moderation", "moderationCases", GET, "/api/moderation/cases", CORE_170),
        Route("moderation", "decide", POST, "/api/moderation/cases/{id}/decisions", CORE_170, "ModerationDecisionRequest"),
        Route("moderation", "userRestrictions", GET, "/api/moderation/restrictions", CORE_170),
        Route("moderation", "revokeRestriction", POST, "/api/moderation/restrictions/{id}/revoke", CORE_170),
        Route("moderation", "moderationSettings", GET, "/api/moderation/settings", CORE_170),
        Route("moderation", "updateModerationSettings", PUT, "/api/moderation/settings", CORE_170, "ModerationSettings"),
    )

    /** `SportLessonIds` is the bare `List<Long>` of `POST /api/sport/sign/sync`. */
    val requests: List<Request> = listOf(
        Request("RegisterDeviceRequest", CORE_120),
        Request("UnregisterDeviceRequest", CORE_120),
        Request("IdTokenRequest", CORE_120),
        Request("LessonSyncRequest", CORE_120),
        Request("UserLookupRequest", CORE_120),
        Request("UserPrivacySettings", CORE_120),
        Request("SportLessonIds", CORE_120),
        Request("SportFreeSignRequest", CORE_120),
        Request("SportAutoSignRequest", CORE_120),
        Request("SaveSubjectLinkRequest", CORE_170),
        Request("PinSubjectLinkRequest", CORE_170),
        Request("ResourceVoteRequest", CORE_170),
        Request("ModerationReportRequest", CORE_170),
        Request("SaveTeacherReviewRequest", CORE_170),
        Request("ModerationDecisionRequest", CORE_170),
        Request("ModerationSettings", CORE_170),
    )

    val fcm: List<Fcm> = listOf(
        Fcm("FRIENDSHIP_EVENT_PAYLOAD", CORE_120),
        Fcm("SPORT_AUTO_SIGN_LESSONS_PAYLOAD", CORE_120),
        Fcm("SPORT_FREE_SIGN_LESSONS_PAYLOAD", CORE_120),
    )

    fun route(id: String): Route = routes.single { it.id == id }

    /** `index.json`: one entry per fixture, sorted by `id`; `method` and `path` are null outside HTTP. */
    fun index(): JsonNode {
        data class Entry(val id: String, val kind: String, val method: String?, val path: String?, val file: String, val minCore: String)
        val entries = routes.map { Entry(it.id, "http", it.method.name(), it.path, it.file, it.minCore) } +
            requests.map { Entry(it.id, "request", null, null, it.file, it.minCore) } +
            fcm.map { Entry(it.id, "fcm", null, null, it.file, it.minCore) }
        val array = JsonNodeFactory.instance.arrayNode()
        entries.sortedBy { it.id }.forEach { entry ->
            array.addObject().apply {
                put("id", entry.id)
                put("kind", entry.kind)
                put("method", entry.method)
                put("path", entry.path)
                put("file", entry.file)
                put("minCore", entry.minCore)
            }
        }
        return array
    }
}
