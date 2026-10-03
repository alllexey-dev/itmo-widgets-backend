package dev.alllexey.itmowidgets.backend.contract

import dev.alllexey.itmowidgets.backend.feature.links.model.LinkCategory
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.links.model.LinkVisibility
import dev.alllexey.itmowidgets.backend.feature.links.web.LinkAudience
import dev.alllexey.itmowidgets.backend.feature.links.web.PinSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.feature.links.web.ResourceVoteRequest
import dev.alllexey.itmowidgets.backend.feature.links.web.SaveSubjectLinkRequest
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLink
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkRevision
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinkStatus
import dev.alllexey.itmowidgets.backend.feature.links.web.SubjectLinksResponse
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationAction
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationActor
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationCaseStatus
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationPolicy
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ReportReason
import dev.alllexey.itmowidgets.backend.feature.moderation.model.RestrictionCapability
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCase
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecision
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationDecisionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReport
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationReportRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationSettings
import dev.alllexey.itmowidgets.backend.feature.moderation.web.RestrictionRequest
import dev.alllexey.itmowidgets.backend.feature.moderation.web.SubjectLinkTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.SubmitterHistory
import dev.alllexey.itmowidgets.backend.feature.moderation.web.TeacherReviewTarget
import dev.alllexey.itmowidgets.backend.feature.moderation.web.UserRestriction
import dev.alllexey.itmowidgets.backend.feature.push.web.RegisterDeviceRequest
import dev.alllexey.itmowidgets.backend.feature.push.web.UnregisterDeviceRequest
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewRevisionStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.model.ReviewVerification
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryConfidence
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleKind
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryScaleValue
import dev.alllexey.itmowidgets.backend.feature.reviews.model.SummaryTag
import dev.alllexey.itmowidgets.backend.feature.reviews.web.ModeratedTeacherReview
import dev.alllexey.itmowidgets.backend.feature.reviews.web.OwnTeacherReview
import dev.alllexey.itmowidgets.backend.feature.reviews.web.SaveTeacherReviewRequest
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReview
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewKind
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewRevision
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewStatus
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherReviewsResponse
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummary
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryLevel
import dev.alllexey.itmowidgets.backend.feature.reviews.web.TeacherSummaryScale
import dev.alllexey.itmowidgets.backend.feature.schedule.model.LessonEntity
import dev.alllexey.itmowidgets.backend.feature.schedule.web.LessonDto
import dev.alllexey.itmowidgets.backend.feature.schedule.web.LessonSyncRequest
import dev.alllexey.itmowidgets.backend.feature.sport.web.FriendSportBooking
import dev.alllexey.itmowidgets.backend.feature.sport.web.FriendsSportBookingsResponse
import dev.alllexey.itmowidgets.backend.feature.sport.web.QueueEntryStatus
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportAutoSignEntry
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportAutoSignLimits
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportAutoSignQueue
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportAutoSignRequest
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportFreeSignEntry
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportFreeSignQueue
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportFreeSignRequest
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportLessonDto
import dev.alllexey.itmowidgets.backend.feature.sport.web.UserSportBookingsResponse
import dev.alllexey.itmowidgets.backend.feature.users.model.SharingVisibility
import dev.alllexey.itmowidgets.backend.feature.users.model.User
import dev.alllexey.itmowidgets.backend.feature.users.web.GroupData
import dev.alllexey.itmowidgets.backend.feature.users.web.IdTokenRequest
import dev.alllexey.itmowidgets.backend.feature.users.web.RelationshipState
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import dev.alllexey.itmowidgets.backend.feature.users.web.UserLookupRequest
import dev.alllexey.itmowidgets.backend.feature.users.web.UserLookupResponse
import dev.alllexey.itmowidgets.backend.feature.users.web.UserPrivacySettings
import dev.alllexey.itmowidgets.backend.feature.users.web.UserProfile
import dev.alllexey.itmowidgets.backend.feature.weblogin.web.WebLoginPreview
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Synthetic, fixed values behind every fixture: no real people, tokens or links. Together the fixtures set every
 * optional field, show every nullable field as `null` at least once, carry both sport entry subtypes and every
 * value of the enums the routes return (`RelationshipState.BLOCKED` is reserved and never returned).
 */
object ContractSamples {
    val NOW: Instant = Instant.parse("2026-10-05T09:00:00Z")

    val VIEWER_ID: UUID = uuid(1)
    const val VIEWER_ISU = 100001
    const val FRIEND_ISU = 100002
    const val TEACHER_ISU = 200001
    const val SUBJECT_ID = 501L
    const val PERIOD = "2026-1"
    const val PAIR_ID = 3_000_001L
    const val FLOW_ID = 7001L
    val LESSON_DATE: LocalDate = LocalDate.parse("2026-10-06")
    val WEEK_START: LocalDate = LocalDate.parse("2026-10-05")
    val WEEK_END: LocalDate = LocalDate.parse("2026-10-11")
    val LINK_ID: UUID = uuid(101)
    val REVIEW_ID: UUID = uuid(201)
    val CASE_ID: UUID = uuid(301)
    val RESTRICTION_ID: UUID = uuid(401)
    val CHALLENGE_ID: UUID = uuid(501)
    const val WEB_LOGIN_CODE = "K7Q2M9"
    const val FREE_ENTRY_ID = 11L
    const val AUTO_ENTRY_ID = 21L
    const val SPORT_LESSON_ID = 9001L
    const val PROTOTYPE_LESSON_ID = 9101L

    fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-%012d".format(n))

    private fun at(text: String): Instant = Instant.parse(text)
    private fun odt(text: String): OffsetDateTime = OffsetDateTime.parse(text)

    // region users

    val viewer = User(id = VIEWER_ID, isu = VIEWER_ISU, pictureUrl = null, name = "Студент Тестовый", createdAt = NOW)
    val friend = User(id = uuid(2), isu = FRIEND_ISU, pictureUrl = null, name = "Друг Первый", createdAt = NOW)

    private val groups = listOf(GroupData("К3240", 2, "ФИТИП"), GroupData("К3240c", 2, "ФИТИП"))

    fun userData(isu: Int, name: String, caps: UserCapabilities, picture: Boolean = true) = UserData(
        isu = isu,
        name = name,
        pictureUrl = if (picture) "https://example.org/avatars/$isu.jpg" else null,
        groups = if (picture) groups else emptyList(),
        capabilities = caps,
    )

    private val all = UserCapabilities(canViewSchedule = true, canViewSport = true, canViewFriends = true)
    private val some = UserCapabilities(canViewSchedule = true, canViewSport = false, canViewFriends = true)
    private val none = UserCapabilities(canViewSchedule = false, canViewSport = false, canViewFriends = false)

    val me = userData(VIEWER_ISU, "Студент Тестовый", all)
    val friendData = userData(FRIEND_ISU, "Друг Первый", all)
    private val second = userData(100003, "Друг Второй", some, picture = false)
    private val third = userData(100004, "Однокурсник Третий", none)
    private val fourth = userData(100005, "Однокурсник Четвёртый", some)

    fun profile(user: UserData, state: RelationshipState) = UserProfile(user, state)

    val friends = listOf(profile(friendData, RelationshipState.FRIENDS), profile(second, RelationshipState.FRIENDS))
    val incoming = listOf(profile(third, RelationshipState.INCOMING))
    val outgoing = listOf(profile(fourth, RelationshipState.OUTGOING))

    /** Another user's friends as the viewer sees them: every relationship a route returns. */
    val mixed = listOf(
        profile(friendData, RelationshipState.FRIENDS),
        profile(third, RelationshipState.INCOMING),
        profile(fourth, RelationshipState.OUTGOING),
        profile(second, RelationshipState.NONE),
    )

    val lookupRequest = UserLookupRequest(listOf(FRIEND_ISU, 100004, 999999))
    val lookup = UserLookupResponse(
        listOf(
            profile(friendData, RelationshipState.FRIENDS),
            profile(third, RelationshipState.INCOMING),
        ),
    )

    val privacy = UserPrivacySettings(SharingVisibility.FRIENDS, SharingVisibility.ALL, SharingVisibility.NOBODY)
    val privacyRequest = UserPrivacySettings(SharingVisibility.ALL, SharingVisibility.NOBODY, SharingVisibility.FRIENDS)

    val restrictions = listOf(
        UserRestriction(
            RESTRICTION_ID,
            RestrictionCapability.SUBMIT_RESOURCES,
            "Спам в ссылках",
            at("2026-10-01T09:00:00Z"),
            at("2026-10-08T09:00:00Z"),
        ),
        UserRestriction(uuid(402), RestrictionCapability.VOTE, "Накрутка голосов", at("2026-10-02T09:00:00Z"), null),
        UserRestriction(
            uuid(403),
            RestrictionCapability.REPORT,
            "Ложные жалобы",
            at("2026-10-03T09:00:00Z"),
            at("2026-11-03T09:00:00Z"),
        ),
        UserRestriction(uuid(404), RestrictionCapability.WRITE_REVIEWS, "Оскорбления", at("2026-10-04T09:00:00Z"), null),
        UserRestriction(
            uuid(405),
            RestrictionCapability.ALL,
            "Повторные нарушения",
            at("2026-10-04T12:00:00Z"),
            at("2027-10-04T12:00:00Z"),
        ),
    )

    val webLogin = WebLoginPreview(CHALLENGE_ID, null, at("2026-10-05T08:58:00Z"), at("2026-10-05T09:03:00Z"))

    // endregion users

    // region devices and schedule

    val registerDevice = RegisterDeviceRequest(fcmToken = "synthetic-fcm-token", deviceName = "Pixel 8 (synthetic)")
    val unregisterDevice = UnregisterDeviceRequest(fcmToken = "synthetic-fcm-token")
    val idToken = IdTokenRequest(idToken = "synthetic.id.token")

    private val lecture = LessonDto(
        pairId = PAIR_ID, date = LESSON_DATE, start = LocalTime.parse("08:20"), end = LocalTime.parse("09:50"),
        type = "Лекции", typeId = 1, note = "Принести ноутбук", subjectName = "Математический анализ",
        subjectId = SUBJECT_ID, groupName = "ЛЕК МАТАН 3.1", flowId = FLOW_ID, flowTypeId = 2,
        teacherIsu = TEACHER_ISU.toLong(), teacherFio = "Преподаватель Тестовый", room = "1404", building = "Кронверкский пр., д.49",
        buildingId = 13, mainBuildingId = 13, format = "Очно", formatId = 1,
    )
    private val online = LessonDto(
        pairId = PAIR_ID + 1, date = LESSON_DATE, start = LocalTime.parse("10:00"), end = LocalTime.parse("11:30"),
        type = "Практические занятия", typeId = 3, note = null, subjectName = "Программирование", subjectId = 502,
        groupName = "ПРАК ПРОГ 3.1.2", flowId = 7002, flowTypeId = 2, teacherIsu = null, teacherFio = null, room = null,
        building = null, buildingId = null, mainBuildingId = null, format = "Дистанционно", formatId = 3,
    )

    val lessonSync = LessonSyncRequest(listOf(lecture, online), WEEK_START, WEEK_END)

    val lessons: List<LessonEntity> = lessonSync.lessons.map { lesson ->
        LessonEntity(
            userIsu = FRIEND_ISU, date = lesson.date, pairId = lesson.pairId, subjectId = lesson.subjectId,
            subjectName = lesson.subjectName, teacherIsu = lesson.teacherIsu, teacherFio = lesson.teacherFio,
            start = lesson.start, end = lesson.end, type = lesson.type, typeId = lesson.typeId, groupName = lesson.groupName,
            flowId = lesson.flowId, flowTypeId = lesson.flowTypeId, note = lesson.note, room = lesson.room,
            building = lesson.building, buildingId = lesson.buildingId, mainBuildingId = lesson.mainBuildingId,
            format = lesson.format, formatId = lesson.formatId,
        )
    }

    // endregion devices and schedule

    // region links

    private fun link(
        n: Int,
        category: LinkCategory,
        visibility: LinkVisibility,
        status: SubjectLinkStatus,
        mine: Boolean,
        period: String = PERIOD,
        title: String? = "Материалы",
        reviewNote: String? = null,
        myVote: Int = 0,
        reportedByMe: Boolean = false,
    ) = SubjectLink(
        id = uuid(100 + n), subjectId = SUBJECT_ID, subjectName = "Математический анализ", periodKey = period,
        category = category, url = "https://example.org/links/$n", title = title, visibility = visibility,
        flowId = if (visibility == LinkVisibility.FLOW) FLOW_ID else null,
        audienceLabel = if (visibility == LinkVisibility.FLOW) "ЛЕК МАТАН 3.1" else null,
        status = status, reviewNote = reviewNote, score = 3 - n, myVote = myVote, isMine = mine,
        reportedByMe = reportedByMe, author = if (mine) null else friendData,
        updatedAt = at("2026-10-0${1 + n % 4}T10:00:00Z"),
    )

    private val ownPrivate = link(1, LinkCategory.SCORES, LinkVisibility.PRIVATE, SubjectLinkStatus.PRIVATE, true, title = null)
    private val ownPending = link(2, LinkCategory.QUEUE, LinkVisibility.FLOW, SubjectLinkStatus.PENDING, true)
    private val ownRejected = link(
        3,
        LinkCategory.CHAT,
        LinkVisibility.ALL,
        SubjectLinkStatus.REJECTED,
        true,
        reviewNote = "Ссылка ведёт не на тот предмет",
    )
    private val ownHidden = link(4, LinkCategory.OTHER, LinkVisibility.ALL, SubjectLinkStatus.HIDDEN, true)
    val sharedLink = link(
        0,
        LinkCategory.MATERIALS,
        LinkVisibility.ALL,
        SubjectLinkStatus.PUBLISHED,
        false,
        myVote = 1,
        reportedByMe = true,
    )
    private val sharedFlow = link(5, LinkCategory.TASKS, LinkVisibility.FLOW, SubjectLinkStatus.PUBLISHED, false, myVote = -1)
    private val sharedNotes = link(6, LinkCategory.NOTES, LinkVisibility.ALL, SubjectLinkStatus.PUBLISHED, false, title = null)
    private val previousRecordings = link(
        7,
        LinkCategory.RECORDINGS,
        LinkVisibility.ALL,
        SubjectLinkStatus.PUBLISHED,
        false,
        period = "2025-1",
    )
    private val previousExam = link(
        8,
        LinkCategory.EXAM,
        LinkVisibility.ALL,
        SubjectLinkStatus.PUBLISHED,
        false,
        period = "2025-1",
        myVote = 1,
    )

    val subjectLinks = SubjectLinksResponse(
        mine = listOf(ownPrivate, ownPending, ownRejected, ownHidden),
        shared = listOf(sharedLink, sharedFlow, sharedNotes),
        previous = listOf(previousRecordings, previousExam),
        pinnedId = sharedLink.id,
        audiences = listOf(LinkAudience(FLOW_ID, "ЛЕК МАТАН 3.1", 1, 1), LinkAudience(7003, "ПРАК МАТАН 3.1.2", 3, 2)),
        premoderation = true,
    )

    val pinnedLinks = SubjectLinksResponse(
        mine = listOf(ownPending),
        shared = emptyList(),
        previous = emptyList(),
        pinnedId = null,
        audiences = emptyList(),
        premoderation = false,
    )

    val savedLink = ownPending.copy(id = LINK_ID)

    val saveLink = SaveSubjectLinkRequest(
        subjectId = SUBJECT_ID,
        subjectName = "Математический анализ",
        periodKey = PERIOD,
        category = LinkCategory.MATERIALS,
        url = "https://example.org/links/new",
        title = "Конспекты лекций",
        visibility = LinkVisibility.FLOW,
        flowId = FLOW_ID,
    )
    val pinLink = PinSubjectLinkRequest(periodKey = PERIOD, linkId = LINK_ID)
    val vote = ResourceVoteRequest(1)
    val report = ModerationReportRequest(ReportReason.OTHER, "Синтетическая жалоба")

    // endregion links

    // region reviews

    private fun scales(vararg values: SummaryScaleValue) = SummaryScaleKind.entries.zip(values.toList()) { kind, value ->
        TeacherSummaryScale(kind, value, if (value == SummaryScaleValue.NOT_ENOUGH_DATA) null else "Синтетическое пояснение")
    }

    private fun summary(confidence: SummaryConfidence, level: SummaryLevel, tags: List<SummaryTag>, vararg values: SummaryScaleValue) =
        TeacherSummary(
            reviewCount = 7, description = "Синтетическое описание отзывов.", pros = listOf("Понятно объясняет"),
            cons = listOf("Строгие сроки"), tags = tags, scales = scales(*values), level = level, confidence = confidence,
            generatedAt = at("2026-10-04T03:00:00Z"),
        )

    private val namedReview = TeacherReview(
        id = REVIEW_ID, kind = TeacherReviewKind.COMMUNITY, subjectTitle = "Математический анализ", writtenOn = LESSON_DATE,
        writtenBeforeYear = null, text = "Синтетический отзыв под именем.", score = 4, myVote = 1, verified = true,
        reportedByMe = false, author = friendData, sourceTitle = null, sourceLink = null,
    )
    private val anonymousReview = TeacherReview(
        id = uuid(202), kind = TeacherReviewKind.COMMUNITY, subjectTitle = null, writtenOn = LESSON_DATE.minusDays(30),
        writtenBeforeYear = null, text = "Синтетический анонимный отзыв.", score = -1, myVote = -1, verified = false,
        reportedByMe = true, author = null, sourceTitle = null, sourceLink = null,
    )
    private val copiedReview = TeacherReview(
        id = uuid(203), kind = TeacherReviewKind.REVIEWS, subjectTitle = "Программирование", writtenOn = null,
        writtenBeforeYear = 2024, text = "Синтетическая копия отзыва.", score = 0, myVote = 0, verified = false,
        reportedByMe = false, author = null, sourceTitle = "Reviews", sourceLink = "https://example.org/reviews/1",
    )

    private fun own(status: TeacherReviewStatus, note: String? = null) = OwnTeacherReview(
        id = uuid(210), subjectTitle = "Математический анализ", text = "Мой синтетический отзыв.", anonymous = true,
        status = status, reviewNote = note, score = 2, verified = status == TeacherReviewStatus.PUBLISHED,
        writtenOn = LESSON_DATE,
    )

    private fun reviews(reviews: List<TeacherReview>, mine: OwnTeacherReview?, summary: TeacherSummary?, canWrite: Boolean = true) =
        TeacherReviewsResponse(
            teacherIsu = TEACHER_ISU, providerUrl = "https://example.org/reviews", reviews = reviews, mine = mine,
            canWrite = canWrite, canVote = true, canReport = true, knownTeacher = true, summary = summary,
        )

    private val tagsA = listOf(
        SummaryTag.STRICT_DEFENSE, SummaryTag.HARD_EXAM, SummaryTag.STRICT_DEADLINES,
        SummaryTag.ATTENDANCE_REQUIRED, SummaryTag.CLEAR_REQUIREMENTS, SummaryTag.QUICK_REPLIES, SummaryTag.MANY_LABS,
        SummaryTag.HEAVY_HOMEWORK, SummaryTag.FREQUENT_TESTS, SummaryTag.ASKS_THEORY,
    )
    private val tagsB = listOf(
        SummaryTag.SOFT_DEFENSE, SummaryTag.EASY_EXAM, SummaryTag.FLEXIBLE_DEADLINES,
        SummaryTag.ATTENDANCE_OPTIONAL, SummaryTag.UNCLEAR_REQUIREMENTS, SummaryTag.HARD_TO_REACH, SummaryTag.AUTOMAT,
        SummaryTag.BONUS_POINTS, SummaryTag.INTERESTING_CLASSES, SummaryTag.READS_SLIDES,
    )

    val teacherReviews = reviews(
        listOf(namedReview, anonymousReview, copiedReview),
        own(TeacherReviewStatus.PUBLISHED),
        summary(
            SummaryConfidence.HIGH,
            SummaryLevel.POSITIVE,
            tagsA,
            SummaryScaleValue.HIGH,
            SummaryScaleValue.MEDIUM,
            SummaryScaleValue.LOW,
            SummaryScaleValue.NOT_ENOUGH_DATA,
            SummaryScaleValue.HIGH,
        ),
    )
    val savedReview = reviews(
        emptyList(),
        own(TeacherReviewStatus.PENDING),
        summary(
            SummaryConfidence.MEDIUM,
            SummaryLevel.MIXED,
            tagsB,
            SummaryScaleValue.LOW,
            SummaryScaleValue.LOW,
            SummaryScaleValue.MEDIUM,
            SummaryScaleValue.MEDIUM,
            SummaryScaleValue.NOT_ENOUGH_DATA,
        ),
    )
    val deletedReview = reviews(listOf(copiedReview), null, null, canWrite = false)
    val votedReview = reviews(
        listOf(namedReview),
        own(TeacherReviewStatus.REJECTED, "Отзыв не о преподавателе"),
        summary(
            SummaryConfidence.LOW,
            SummaryLevel.NEGATIVE,
            emptyList(),
            SummaryScaleValue.NOT_ENOUGH_DATA,
            SummaryScaleValue.NOT_ENOUGH_DATA,
            SummaryScaleValue.NOT_ENOUGH_DATA,
            SummaryScaleValue.NOT_ENOUGH_DATA,
            SummaryScaleValue.NOT_ENOUGH_DATA,
        ),
    )
    val reportedReview = reviews(listOf(anonymousReview), own(TeacherReviewStatus.HIDDEN), null)

    val summaryLevels = SummaryLevel.entries.mapIndexed { index, level -> TeacherSummaryLevel(TEACHER_ISU + index, level) }

    val saveReview = SaveTeacherReviewRequest(
        subjectTitle = "Математический анализ",
        text = "Синтетический текст отзыва.",
        anonymous = false,
        flowIds = listOf(FLOW_ID, 7002),
    )

    // endregion reviews

    // region moderation

    private val history = SubmitterHistory(
        approved = 5,
        rejected = 1,
        dismissedReports = 2,
        activeRestrictions = restrictions.take(1),
    )

    private fun linkRevision(number: Int, status: LinkRevisionStatus, note: String? = null) = SubjectLinkRevision(
        id = uuid(110 + number), linkId = LINK_ID, number = number, category = LinkCategory.MATERIALS,
        url = "https://example.org/links/new", title = if (number == 1) null else "Конспекты лекций",
        visibility = LinkVisibility.ALL, flowId = null, status = status, submittedAt = at("2026-10-0${number}T07:00:00Z"),
        decidedAt = if (status == LinkRevisionStatus.PENDING) null else at("2026-10-0${number}T08:00:00Z"), note = note,
    )

    private fun reviewRevision(number: Int, status: ReviewRevisionStatus, note: String? = null) = TeacherReviewRevision(
        id = uuid(220 + number), reviewId = REVIEW_ID, number = number,
        subjectTitle = if (number == 1) null else "Математический анализ", text = "Синтетическая редакция $number.",
        status = status, submittedAt = at("2026-09-2${number}T07:00:00Z"),
        decidedAt = if (status == ReviewRevisionStatus.PENDING) null else at("2026-09-2${number}T08:00:00Z"), note = note,
    )

    private val moderatedReview = ModeratedTeacherReview(
        id = REVIEW_ID, teacherIsu = TEACHER_ISU, teacherName = "Преподаватель Тестовый", anonymous = true,
        status = TeacherReviewStatus.PUBLISHED, reviewNote = null, shown = reviewRevision(1, ReviewRevisionStatus.APPROVED),
        score = -4, hidden = false, verification = ReviewVerification.VERIFIED, verifiedFlowId = FLOW_ID,
    )

    private fun decision(n: Int, action: ModerationAction, actor: ModerationActor = ModerationActor.MODERATOR, days: Int? = 30) =
        ModerationDecision(
            id = uuid(310 + n),
            moderatorId = if (actor == ModerationActor.POLICY) null else uuid(3),
            action = action,
            note = if (n % 2 == 0) null else "Синтетическое решение $n",
            restriction = if (action == ModerationAction.RESTRICT_USER) RestrictionRequest(RestrictionCapability.REPORT, days) else null,
            createdAt = at("2026-10-0${1 + n % 4}T1$n:00:00Z"),
            actor = actor,
        )

    private fun case(
        n: Int,
        type: ModerationTargetType,
        status: ModerationCaseStatus,
        reason: ModerationCaseReason,
        target: ModerationCaseTarget?,
        vararg decisions: ModerationDecision,
    ) = ModerationCase(uuid(300 + n), type, status, reason, at("2026-10-0${n}T06:00:00Z"), target, decisions.toList())

    private fun linkTarget(revision: SubjectLinkRevision, reports: List<ModerationReport> = emptyList()) =
        SubjectLinkTarget(revision, savedLink, friendData, reports, history)

    private fun reviewTarget(
        revision: TeacherReviewRevision,
        review: ModeratedTeacherReview = moderatedReview,
        reports: List<ModerationReport> = emptyList(),
    ) = TeacherReviewTarget(revision, review, friendData, reports, history)

    private val linkReports = listOf(
        ModerationReport(ReportReason.BROKEN, "Не открывается", at("2026-10-05T07:10:00Z")),
        ModerationReport(ReportReason.WRONG_SUBJECT, null, at("2026-10-05T07:20:00Z")),
        ModerationReport(ReportReason.SPAM, null, at("2026-10-05T07:30:00Z")),
        ModerationReport(ReportReason.OTHER, "Другое", at("2026-10-05T07:40:00Z")),
    )
    private val reviewReports = listOf(
        ModerationReport(ReportReason.OFFENSIVE, null, at("2026-09-28T07:10:00Z")),
        ModerationReport(ReportReason.WRONG_TEACHER, "Другой преподаватель", at("2026-09-28T07:20:00Z")),
    )

    /** Fixture data, not the route's filtering: the list shows every case status and target side by side. */
    val cases = listOf(
        case(
            1,
            ModerationTargetType.SUBJECT_RESOURCE,
            ModerationCaseStatus.OPEN,
            ModerationCaseReason.SUBMISSION,
            linkTarget(linkRevision(2, LinkRevisionStatus.PENDING), linkReports),
        ),
        case(
            2,
            ModerationTargetType.TEACHER_REVIEW,
            ModerationCaseStatus.RESOLVED,
            ModerationCaseReason.REPORTS,
            reviewTarget(
                reviewRevision(2, ReviewRevisionStatus.REJECTED, "Оскорбления"),
                moderatedReview.copy(
                    status = TeacherReviewStatus.HIDDEN,
                    reviewNote = "Скрыт по жалобам",
                    hidden = true,
                    verification = ReviewVerification.PENDING,
                    verifiedFlowId = null,
                    teacherName = null,
                ),
                reviewReports,
            ),
            decision(1, ModerationAction.HIDE),
            decision(2, ModerationAction.RESTRICT_USER),
            decision(3, ModerationAction.HIDE_ALL_BY_USER),
        ),
        case(
            3,
            ModerationTargetType.SUBJECT_RESOURCE,
            ModerationCaseStatus.WITHDRAWN,
            ModerationCaseReason.VOTES,
            null,
            decision(4, ModerationAction.APPROVE, ModerationActor.POLICY),
        ),
        case(
            4,
            ModerationTargetType.TEACHER_REVIEW,
            ModerationCaseStatus.OPEN,
            ModerationCaseReason.SUBMISSION,
            reviewTarget(
                reviewRevision(3, ReviewRevisionStatus.PENDING),
                moderatedReview.copy(
                    status = TeacherReviewStatus.PENDING,
                    shown = null,
                    score = 0,
                    verification = ReviewVerification.UNVERIFIED,
                    verifiedFlowId = null,
                ),
            ),
        ),
        case(
            5,
            ModerationTargetType.SUBJECT_RESOURCE,
            ModerationCaseStatus.RESOLVED,
            ModerationCaseReason.SUBMISSION,
            linkTarget(linkRevision(3, LinkRevisionStatus.REJECTED, "Ссылка ведёт не на тот предмет")),
            decision(5, ModerationAction.REJECT),
        ),
        case(
            6,
            ModerationTargetType.SUBJECT_RESOURCE,
            ModerationCaseStatus.WITHDRAWN,
            ModerationCaseReason.SUBMISSION,
            linkTarget(linkRevision(1, LinkRevisionStatus.WITHDRAWN)),
        ),
        case(
            7,
            ModerationTargetType.TEACHER_REVIEW,
            ModerationCaseStatus.WITHDRAWN,
            ModerationCaseReason.SUBMISSION,
            reviewTarget(reviewRevision(4, ReviewRevisionStatus.WITHDRAWN)),
        ),
    )

    val decided = case(
        1, ModerationTargetType.SUBJECT_RESOURCE, ModerationCaseStatus.RESOLVED, ModerationCaseReason.SUBMISSION,
        linkTarget(linkRevision(2, LinkRevisionStatus.APPROVED, "Одобрено"), linkReports),
        decision(6, ModerationAction.APPROVE), decision(7, ModerationAction.RESTORE), decision(8, ModerationAction.DISMISS),
        decision(9, ModerationAction.RESTRICT_USER, days = null),
    )

    val decision = ModerationDecisionRequest(
        ModerationAction.RESTRICT_USER,
        "Синтетическое ограничение",
        RestrictionRequest(RestrictionCapability.SUBMIT_RESOURCES, 7),
    )

    val moderationSettings = ModerationSettings(
        mapOf(
            ModerationTargetType.SUBJECT_RESOURCE to ModerationPolicy(),
            ModerationTargetType.TEACHER_REVIEW to ModerationPolicy(
                premoderation = false,
                reportThreshold = 2,
                voteThreshold = -5,
                dailySubmissionLimit = 3,
                dailyReportLimit = 5,
            ),
        ),
    )

    // endregion moderation

    // region sport

    private fun sportLesson(id: Long, start: String, buildingId: Long? = 13) = SportLessonDto(
        id = id, sectionId = 41, sectionName = "Плавание", sectionLevel = 1, level = 1, typeId = 2,
        buildingId = buildingId, roomName = if (buildingId == null) "Онлайн" else "Бассейн",
        start = odt("$start+03:00"), end = odt("$start+03:00").plusMinutes(90), timeSlotId = 3,
        teacherIsu = TEACHER_ISU.toLong(), teacherFio = "Тренер Тестовый",
    )

    private val lesson = sportLesson(SPORT_LESSON_ID, "2026-10-07T10:00:00")
    private val onlineLesson = sportLesson(SPORT_LESSON_ID + 1, "2026-10-08T12:00:00", buildingId = null)
    private val prototype = sportLesson(PROTOTYPE_LESSON_ID, "2026-09-30T10:00:00")

    val fcmLessons = listOf(lesson, onlineLesson)

    private fun free(id: Long, status: QueueEntryStatus, lesson: SportLessonDto = this.lesson) = SportFreeSignEntry(
        id = id, lessonId = lesson.id, position = id.toInt() % 5 + 1, total = 6, isCancelled = false, status = status,
        createdAt = odt("2026-10-0${id % 4 + 1}T08:00:00Z"),
        firstNotifiedAt = if (status == QueueEntryStatus.WAITING) null else odt("2026-10-05T08:10:00Z"),
        lastNotifiedAt = if (status == QueueEntryStatus.WAITING) null else odt("2026-10-05T08:40:00Z"),
        cancelledAt = null,
        satisfiedAt = if (status == QueueEntryStatus.SATISFIED) odt("2026-10-05T08:45:00Z") else null,
        expiredAt = if (status == QueueEntryStatus.EXPIRED) odt("2026-10-05T08:50:00Z") else null,
        notificationAttempts = if (status == QueueEntryStatus.WAITING) 0 else 3, maxNotificationAttempts = 3,
        targetLesson = lesson, forceSign = id % 2 == 0L,
    )

    private fun auto(id: Long, status: QueueEntryStatus, real: SportLessonDto? = null) = SportAutoSignEntry(
        id = id, prototypeLessonId = PROTOTYPE_LESSON_ID, realLessonId = real?.id, position = 1, total = 2,
        isCancelled = status == QueueEntryStatus.EXPIRED, status = status, createdAt = odt("2026-09-25T08:00:00Z"),
        firstNotifiedAt = real?.let { odt("2026-10-05T08:10:00Z") }, lastNotifiedAt = real?.let { odt("2026-10-05T08:40:00Z") },
        cancelledAt = if (status == QueueEntryStatus.EXPIRED) odt("2026-10-05T08:55:00Z") else null,
        satisfiedAt = if (status == QueueEntryStatus.SATISFIED) odt("2026-10-05T08:45:00Z") else null,
        expiredAt = if (status == QueueEntryStatus.EXPIRED) odt("2026-10-05T08:50:00Z") else null,
        notificationAttempts = if (real == null) 0 else 2, maxNotificationAttempts = 3, targetLesson = prototype, realLesson = real,
    )

    val freeEntries = listOf(
        free(FREE_ENTRY_ID, QueueEntryStatus.WAITING),
        free(12, QueueEntryStatus.NOTIFIED, onlineLesson),
        free(13, QueueEntryStatus.GAVE_UP_NOTIFYING),
        free(14, QueueEntryStatus.SATISFIED),
        free(15, QueueEntryStatus.EXPIRED).copy(isCancelled = true, cancelledAt = odt("2026-10-05T08:55:00Z")),
    )
    val createdFree = free(16, QueueEntryStatus.WAITING)

    val autoEntries = listOf(
        auto(AUTO_ENTRY_ID, QueueEntryStatus.WAITING),
        auto(22, QueueEntryStatus.NOTIFIED, lesson),
        auto(23, QueueEntryStatus.GAVE_UP_NOTIFYING, lesson),
        auto(24, QueueEntryStatus.SATISFIED, onlineLesson),
        auto(25, QueueEntryStatus.EXPIRED, lesson),
    )
    val createdAuto = auto(26, QueueEntryStatus.WAITING)

    val freeQueues = listOf(SportFreeSignQueue(SPORT_LESSON_ID, 6), SportFreeSignQueue(SPORT_LESSON_ID + 1, 1))
    val autoQueues = listOf(SportAutoSignQueue(PROTOTYPE_LESSON_ID, 2, null), SportAutoSignQueue(9102, 1, SPORT_LESSON_ID))
    val autoLimits = SportAutoSignLimits(limit = 3, available = 0, nextAvailableAt = odt("2026-10-25T08:10:00Z"))

    val friendBookings = FriendsSportBookingsResponse(
        listOf(
            FriendSportBooking(FRIEND_ISU, SPORT_LESSON_ID, null),
            FriendSportBooking(FRIEND_ISU, SPORT_LESSON_ID + 1, freeEntries[1]),
            FriendSportBooking(100003, 9102, autoEntries[1]),
        ),
    )

    val userBookings = UserSportBookingsResponse(listOf(SPORT_LESSON_ID, 9103), listOf(freeEntries[0], autoEntries[1]))

    val sportLessonIds = listOf(SPORT_LESSON_ID, SPORT_LESSON_ID + 1)
    val freeSign = SportFreeSignRequest(lessonId = SPORT_LESSON_ID, forceSign = true)
    val autoSign = SportAutoSignRequest(prototypeLessonId = PROTOTYPE_LESSON_ID)

    // endregion sport
}
