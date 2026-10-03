package dev.alllexey.itmowidgets.backend.feature.users.persistence

import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRepository
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.links.persistence.SubjectLinkVoteRepository
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkViews
import dev.alllexey.itmowidgets.backend.feature.moderation.persistence.ModerationReportRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.ExternalTeacherReviewVoteRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewRevisionRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.persistence.TeacherReviewVoteRepository
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewViews
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.UserSubjectFlowRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.service.ScheduleFlowMembership
import dev.alllexey.itmowidgets.backend.feature.social.service.FriendService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.web.UserCapabilities
import dev.alllexey.itmowidgets.backend.feature.users.web.UserData
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlRepositoryTest
import dev.alllexey.itmowidgets.backend.platform.PostgreSqlTestDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.Container
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*

/**
 * Runs `docs/ops/account-deletion.sql` with psql inside the test PostgreSQL, exactly as the owner runs it,
 * against an account that has every kind of row the V1–V10 schema can hold.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AccountDeletionRunbookTest @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val transactions: PlatformTransactionManager,
    private val users: UserRepository,
    private val links: SubjectLinkRepository,
    private val linkRevisions: SubjectLinkRevisionRepository,
    private val linkVotes: SubjectLinkVoteRepository,
    private val reports: ModerationReportRepository,
    private val subjectFlows: UserSubjectFlowRepository,
    private val reviews: TeacherReviewRepository,
    private val reviewRevisions: TeacherReviewRevisionRepository,
    private val reviewVotes: TeacherReviewVoteRepository,
    private val externalVotes: ExternalTeacherReviewVoteRepository,
) : PostgreSqlRepositoryTest() {
    private lateinit var f: Fixture

    @BeforeEach
    fun createAccounts() {
        f = Fixture(
            deleted = user(DELETED_ISU, "Synthetic deleted", Instant.parse("2026-09-01T08:00:00Z")),
            other = user(OTHER_ISU, "Synthetic other", NOW),
            third = user(THIRD_ISU, "Synthetic third", NOW),
        )
        f.populate()
    }

    @AfterEach
    fun removeRows() {
        val ids = "SELECT id FROM users WHERE isu IN ($DELETED_ISU, $OTHER_ISU, $THIRD_ISU, ${-DELETED_ISU})"
        val cases = f.cases.joinToString(",") { "'$it'" }
        listOf(
            "DELETE FROM user_restrictions WHERE user_id IN ($ids) OR revoked_by IN ($ids)",
            "DELETE FROM moderation_decisions WHERE case_id IN ($cases)",
            "DELETE FROM moderation_cases WHERE id IN ($cases)",
            "DELETE FROM moderation_reports WHERE reporter_id IN ($ids)",
            "DELETE FROM moderation_settings WHERE key = '$SETTING_KEY'",
            "DELETE FROM admin_audit WHERE actor_id IN ($ids)",
            "DELETE FROM subject_links WHERE owner_id IN ($ids)",
            "DELETE FROM teacher_reviews WHERE author_id IN ($ids)",
            "DELETE FROM external_teacher_reviews WHERE provider = 'REVIEWS_WORK_GD' AND external_id = $SYNTHETIC_ID",
            "DELETE FROM friendships WHERE requester_id IN ($ids) OR addressee_id IN ($ids)",
            "DELETE FROM devices WHERE user_id IN ($ids)",
            "DELETE FROM sport_auto_sign_entries WHERE user_id IN ($ids)",
            "DELETE FROM sport_free_sign_entries WHERE user_id IN ($ids)",
            "DELETE FROM user_sport_lessons WHERE user_id IN ($ids)",
            "DELETE FROM web_login_challenges WHERE approved_by IN ($ids)",
            "DELETE FROM lessons WHERE user_isu IN ($DELETED_ISU, $OTHER_ISU, $THIRD_ISU)",
            "DELETE FROM user_groups WHERE user_id IN ($ids)",
            "DELETE FROM user_roles WHERE user_id IN ($ids)",
            "DELETE FROM subject_link_pins WHERE user_id IN ($ids)",
            "DELETE FROM users WHERE isu IN ($DELETED_ISU, $OTHER_ISU, $THIRD_ISU, ${-DELETED_ISU})",
            "DELETE FROM sport_lessons WHERE id = $SYNTHETIC_ID",
            "DELETE FROM sport_sections WHERE id = $SYNTHETIC_ID",
            "DELETE FROM sport_time_slots WHERE id = $SYNTHETIC_ID",
            "DELETE FROM sport_teachers WHERE isu = $SYNTHETIC_ID",
            "DELETE FROM groups WHERE id = '${f.group}'",
            "DELETE FROM faculties WHERE id = $SYNTHETIC_ID",
            "DELETE FROM qualifications WHERE code = $SYNTHETIC_ID",
        ).forEach(jdbc::update)
    }

    @Test
    fun `every foreign key to users is one the runbook handles`() {
        val references = jdbc.queryForList(
            """
            SELECT c.conrelid::regclass::text || '.' || a.attname
            FROM pg_constraint c JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass
            """,
            String::class.java,
        ).toSet()

        assertEquals(HANDLED_REFERENCES, references)
    }

    @Test
    fun `the account is gone and a placeholder that shares nothing takes its place`() {
        val result = runRunbook(DELETED_ISU)

        assertEquals(0, result.exitCode, result.stderr)
        assertContains(result.stdout, "placeholder created")
        assertNull(users.findIdByIsu(DELETED_ISU))
        val placeholder = placeholderId()
        assertEquals(
            mapOf(
                "name" to "Удалённый пользователь",
                "picture_url" to null,
                "created_at" to Timestamp.from(Instant.parse("2026-09-01T08:00:00Z")),
            ),
            jdbc.queryForMap("SELECT name, picture_url, created_at FROM users WHERE id = ?", placeholder),
        )
        assertEquals(
            mapOf(
                "auto_sign_limit" to 0,
                "sport_visibility" to "NOBODY",
                "schedule_visibility" to "NOBODY",
                "friends_visibility" to "NOBODY",
            ),
            jdbc.queryForMap(
                "SELECT auto_sign_limit, sport_visibility, schedule_visibility, friends_visibility FROM user_settings WHERE user_id = ?",
                placeholder,
            ),
        )
        val deleted = f.deleted
        for (query in listOf(
            "SELECT count(*) FROM user_settings WHERE user_id = ?",
            "SELECT count(*) FROM user_groups WHERE user_id = ?",
            "SELECT count(*) FROM user_roles WHERE user_id = ?",
            "SELECT count(*) FROM devices WHERE user_id = ?",
            "SELECT count(*) FROM friendships WHERE requester_id = ? OR addressee_id = ?",
            "SELECT count(*) FROM sport_auto_sign_entries WHERE user_id = ?",
            "SELECT count(*) FROM sport_free_sign_entries WHERE user_id = ?",
            "SELECT count(*) FROM user_sport_lessons WHERE user_id = ?",
            "SELECT count(*) FROM subject_link_pins WHERE user_id = ?",
            "SELECT count(*) FROM user_subject_flows WHERE user_id = ?",
            "SELECT count(*) FROM web_sessions WHERE user_id = ?",
            "SELECT count(*) FROM web_login_challenges WHERE approved_by = ?",
            "SELECT count(*) FROM user_restrictions WHERE user_id = ?",
        )) {
            val args = Array(query.count { it == '?' }) { deleted }
            assertEquals(0, jdbc.queryForObject(query, Int::class.java, *args), query)
        }
        assertEquals(0, count("SELECT count(*) FROM lessons WHERE user_isu = $DELETED_ISU"))
        for (table in listOf("user_groups", "user_roles", "devices", "user_sport_lessons", "web_sessions", "user_restrictions")) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table WHERE user_id = ?", Int::class.java, placeholder), table)
        }
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM friendships WHERE requester_id = ? OR addressee_id = ?",
                Int::class.java,
                placeholder,
                placeholder,
            ),
        )
        // Only the flow that labels the kept FLOW link moves to the placeholder.
        assertEquals(
            listOf(FLOW_ID),
            jdbc.queryForList(
                "SELECT flow_id FROM user_subject_flows WHERE user_id = ?",
                Long::class.java,
                placeholder,
            ),
        )
    }

    @Test
    fun `published links and reviews stay visible to others under the placeholder`() {
        assertEquals(0, runRunbook(DELETED_ISU).exitCode)
        val placeholder = placeholderId()

        tx().execute {
            val viewer = users.findById(f.other).orElseThrow()
            val linkViews = SubjectLinkViews(linkRevisions, linkVotes, reports, ScheduleFlowMembership(subjectFlows), privacy())
            val shown = linkViews.published(viewer, linkViews.shown(viewer.id, links.findVisibleCandidates(SUBJECT_ID, PERIOD)))
                .associateBy { it.id }
            val published = shown.getValue(f.publishedLink)
            assertEquals("https://example.org/published", published.url)
            assertEquals("Опубликовано", published.title)
            assertEquals(2, published.score)
            assertTrue(published.reportedByMe)
            assertEquals(placeholderData(), published.author)
            val flow = shown.getValue(f.flowLink)
            assertEquals("ФИЗ ПИИКТ 3.2", flow.audienceLabel)
            assertEquals(placeholderData(), flow.author)
            assertEquals(setOf(f.publishedLink, f.flowLink, f.otherLink), shown.keys)

            val reviewViews = TeacherReviewViews(reviewRevisions, reviewVotes, externalVotes, reports, privacy(), CLOCK)
            val review = reviewViews.published(viewer, reviews.findAllByTeacherIsuAndHiddenAtIsNull(TEACHER)).single()
            assertEquals(f.publishedReview, review.id)
            assertNull(review.author)
            assertEquals(PUBLISHED_TEXT, review.text)
            assertEquals(1, review.score)
            assertFalse(review.verified)
        }
        // The rows carry the shown content, not the unpublished drafts.
        assertEquals(
            mapOf("owner_id" to placeholder, "url" to "https://example.org/published", "title" to "Опубликовано"),
            jdbc.queryForMap("SELECT owner_id, url, title FROM subject_links WHERE id = ?", f.publishedLink),
        )
        assertEquals(
            mapOf(
                "author_id" to placeholder,
                "anonymous" to true,
                "text" to PUBLISHED_TEXT,
                "verification" to "UNVERIFIED",
                "verification_due_at" to null,
            ),
            jdbc.queryForMap(
                "SELECT author_id, anonymous, text, verification, verification_due_at FROM teacher_reviews WHERE id = ?",
                f.publishedReview,
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM teacher_review_flows WHERE review_id = ?",
                Int::class.java,
                f.publishedReview,
            ),
        )
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM subject_link_pins WHERE link_id = ?", Int::class.java, f.publishedLink))
    }

    @Test
    fun `unpublished drafts are withdrawn and unpublished content is deleted as the services delete it`() {
        assertEquals(0, runRunbook(DELETED_ISU).exitCode)

        assertEquals("WITHDRAWN", revisionStatus("subject_link_revisions", f.publishedLinkDraft))
        assertEquals("WITHDRAWN", revisionStatus("teacher_review_revisions", f.publishedReviewDraft))
        for (link in listOf(f.privateLink, f.pendingLink, f.hiddenLink)) {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM subject_links WHERE id = ?", Int::class.java, link))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM subject_link_revisions WHERE link_id = ?", Int::class.java, link))
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM teacher_reviews WHERE id = ?", Int::class.java, f.pendingReview))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM teacher_review_revisions WHERE review_id = ?",
                Int::class.java,
                f.pendingReview,
            ),
        )
        // Cases outlive their targets as withdrawn history; reports on removed revisions go.
        for (case in listOf(
            f.publishedLinkDraftCase,
            f.pendingLinkCase,
            f.hiddenLinkCase,
            f.publishedReviewDraftCase,
            f.pendingReviewCase,
        )) {
            assertEquals(
                mapOf("status" to "WITHDRAWN", "resolved" to true),
                jdbc.queryForMap("SELECT status, resolved_at IS NOT NULL AS resolved FROM moderation_cases WHERE id = ?", case),
            )
        }
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM moderation_reports WHERE target_id IN (?, ?)",
                Int::class.java,
                f.pendingLinkRevision,
                f.pendingReviewRevision,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM moderation_reports WHERE target_id = ?",
                Int::class.java,
                f.publishedLinkRevision,
            ),
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM subject_link_pins WHERE link_id = ?", Int::class.java, f.pendingLink))
    }

    @Test
    fun `the account's votes go and the scores they counted in are recalculated`() {
        assertEquals(0, runRunbook(DELETED_ISU).exitCode)

        assertEquals(1, count("SELECT score FROM subject_links WHERE id = '${f.otherLink}'"))
        assertEquals(0, count("SELECT score FROM teacher_reviews WHERE id = '${f.otherReview}'"))
        assertEquals(1, count("SELECT score FROM external_teacher_reviews WHERE id = '${f.externalReview}'"))
        assertEquals(0, count("SELECT count(*) FROM subject_link_votes WHERE link_id = '${f.otherLink}' AND value = -1"))
        assertEquals(0, count("SELECT count(*) FROM teacher_review_votes WHERE review_id = '${f.otherReview}'"))
        assertEquals(1, count("SELECT count(*) FROM external_teacher_review_votes WHERE review_id = '${f.externalReview}'"))
    }

    @Test
    fun `moderation history and the admin audit name the placeholder`() {
        assertEquals(0, runRunbook(DELETED_ISU).exitCode)
        val placeholder = placeholderId()

        assertEquals(placeholder, uuid("SELECT reporter_id FROM moderation_reports WHERE target_id = '${f.otherReviewRevision}'"))
        assertEquals(placeholder, uuid("SELECT moderator_id FROM moderation_decisions WHERE id = '${f.dismissDecision}'"))
        assertEquals(placeholder, uuid("SELECT revoked_by FROM user_restrictions WHERE user_id = '${f.other}'"))
        assertEquals(placeholder, uuid("SELECT updated_by FROM moderation_settings WHERE key = '$SETTING_KEY'"))
        assertEquals(
            listOf("user:$OTHER_ISU"),
            jdbc.queryForList(
                "SELECT target FROM admin_audit WHERE actor_id = ?",
                String::class.java,
                placeholder,
            ),
        )
        assertEquals(
            listOf("user:${-DELETED_ISU}"),
            jdbc.queryForList(
                "SELECT target FROM admin_audit WHERE actor_id = ?",
                String::class.java,
                f.other,
            ),
        )
        assertEquals(1, count("SELECT count(*) FROM moderation_decisions WHERE id = '${f.restrictDecision}'"))
    }

    @Test
    fun `the placeholder is a nameable author without any audience`() {
        assertEquals(0, runRunbook(DELETED_ISU).exitCode)

        tx().execute {
            val viewer = users.findById(f.other).orElseThrow()
            val placeholder = users.findById(placeholderId()).orElseThrow()
            assertEquals(placeholderData(), privacy().userDataFor(viewer, placeholder))
        }
    }

    @Test
    fun `other accounts keep their data`() {
        val before = othersState()

        assertEquals(0, runRunbook(DELETED_ISU).exitCode)

        assertEquals(before, othersState())
    }

    @Test
    fun `a second run changes nothing`() {
        assertEquals(0, runRunbook(DELETED_ISU).exitCode)
        val before = everythingState()

        val again = runRunbook(DELETED_ISU)

        assertEquals(0, again.exitCode, again.stderr)
        assertContains(again.stdout, "not found, nothing changed")
        assertEquals(before, everythingState())
    }

    @Test
    fun `an account recreated after the deletion is deleted into the same placeholder`() {
        assertEquals(0, runRunbook(DELETED_ISU).exitCode)
        val placeholder = placeholderId()
        val recreated = user(DELETED_ISU, null, NOW)

        val result = runRunbook(DELETED_ISU)

        assertEquals(0, result.exitCode, result.stderr)
        assertNull(users.findIdByIsu(DELETED_ISU))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Int::class.java, recreated))
        assertEquals(placeholder, placeholderId())
        assertEquals(1, count("SELECT count(*) FROM users WHERE isu = ${-DELETED_ISU}"))
    }

    @Test
    fun `a non-positive ISU is refused without changes`() {
        val before = everythingState()

        val result = runRunbook(-DELETED_ISU)

        assertNotEquals(0, result.exitCode)
        assertContains(result.stderr, "isu must be a positive ISU number")
        assertEquals(before, everythingState())
    }

    private fun runRunbook(isu: Int): Container.ExecResult {
        val postgres = PostgreSqlTestDatabase.container
        postgres.copyFileToContainer(MountableFile.forHostPath(RUNBOOK), "/tmp/account-deletion.sql")
        return postgres.execInContainer(
            "psql", "-X", "-v", "ON_ERROR_STOP=1", "-v", "isu=$isu",
            "-U", postgres.username, "-d", postgres.databaseName, "-f", "/tmp/account-deletion.sql",
        )
    }

    private fun placeholderId(): UUID = checkNotNull(users.findIdByIsu(-DELETED_ISU))

    private fun placeholderData() = UserData(
        -DELETED_ISU,
        "Удалённый пользователь",
        null,
        emptyList(),
        UserCapabilities(canViewSchedule = false, canViewSport = false, canViewFriends = false),
    )

    private fun privacy() = UserPrivacyService(mock(FriendService::class.java))

    private fun tx() = TransactionTemplate(transactions).apply { isReadOnly = true }

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    private fun uuid(sql: String): UUID = jdbc.queryForObject(sql, UUID::class.java)!!

    private fun revisionStatus(table: String, id: UUID): String =
        jdbc.queryForObject("SELECT status FROM $table WHERE id = ?", String::class.java, id)!!

    /**
     * The rows of the other two accounts, without the scores the deleted account's votes counted in and the rows
     * the runbook changes on purpose: a pin and reports on removed content and the audit target naming the account.
     */
    private fun othersState(): List<String> {
        val ids = "'${f.other}', '${f.third}'"
        return listOf(
            "SELECT * FROM users WHERE id IN ($ids) ORDER BY isu",
            "SELECT * FROM user_settings WHERE user_id IN ($ids) ORDER BY user_id",
            "SELECT * FROM user_groups WHERE user_id IN ($ids) ORDER BY user_id",
            "SELECT * FROM devices WHERE user_id IN ($ids) ORDER BY id",
            "SELECT * FROM friendships WHERE requester_id IN ($ids) AND addressee_id IN ($ids) ORDER BY id",
            "SELECT * FROM user_sport_lessons WHERE user_id IN ($ids) ORDER BY id",
            "SELECT * FROM lessons WHERE user_isu IN ($OTHER_ISU, $THIRD_ISU) ORDER BY id",
            "SELECT * FROM user_subject_flows WHERE user_id IN ($ids) ORDER BY user_id, flow_id",
            "SELECT id, owner_id, url, title, visibility, hidden_at FROM subject_links WHERE owner_id IN ($ids) ORDER BY id",
            "SELECT * FROM subject_link_revisions WHERE link_id IN (SELECT id FROM subject_links WHERE owner_id IN ($ids)) ORDER BY id",
            "SELECT * FROM subject_link_votes WHERE user_id IN ($ids) ORDER BY link_id, user_id",
            "SELECT * FROM subject_link_pins WHERE user_id IN ($ids) AND link_id <> '${f.pendingLink}' ORDER BY user_id",
            "SELECT id, author_id, text, anonymous, verification FROM teacher_reviews WHERE author_id IN ($ids) ORDER BY id",
            "SELECT * FROM teacher_review_votes WHERE user_id IN ($ids) ORDER BY review_id, user_id",
            "SELECT * FROM external_teacher_review_votes WHERE user_id IN ($ids) ORDER BY review_id, user_id",
            "SELECT * FROM moderation_reports WHERE reporter_id IN ($ids) AND target_id NOT IN ('${f.pendingLinkRevision}', " +
                "'${f.pendingReviewRevision}') ORDER BY id",
            "SELECT * FROM moderation_decisions WHERE moderator_id IN ($ids) ORDER BY id",
            "SELECT id, user_id, capability, decision_id FROM user_restrictions WHERE user_id IN ($ids) ORDER BY id",
            "SELECT * FROM admin_audit WHERE actor_id IN ($ids) AND target NOT IN ('user:$DELETED_ISU', 'user:${-DELETED_ISU}') ORDER BY id",
        ).map { jdbc.queryForList(it).toString() }
    }

    /** Every table the runbook may touch, row for row. */
    private fun everythingState(): List<String> = listOf(
        "users", "user_settings", "user_groups", "user_roles", "devices", "friendships", "sport_auto_sign_entries",
        "sport_free_sign_entries", "user_sport_lessons", "lessons", "user_subject_flows", "subject_links",
        "subject_link_revisions", "subject_link_votes", "subject_link_pins", "teacher_reviews", "teacher_review_revisions",
        "teacher_review_votes", "teacher_review_flows", "external_teacher_reviews", "external_teacher_review_votes",
        "moderation_cases", "moderation_reports", "moderation_decisions", "moderation_settings", "user_restrictions",
        "admin_audit", "web_sessions", "web_login_challenges",
    ).map { table -> jdbc.queryForList("SELECT * FROM $table ORDER BY 1, 2").toString() }

    private fun user(isu: Int, name: String?, createdAt: Instant): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO users (id, isu, name, created_at) VALUES (?, ?, ?, ?)", id, isu, name, Timestamp.from(createdAt))
        jdbc.update("INSERT INTO user_settings (user_id) VALUES (?)", id)
        return id
    }

    /** Every kind of row the deleted account can have, and the same kinds for the others around it. */
    private inner class Fixture(val deleted: UUID, val other: UUID, val third: UUID) {
        val group: UUID = UUID.randomUUID()
        val cases = mutableListOf<UUID>()
        val privateLink: UUID = UUID.randomUUID()
        val publishedLink: UUID = UUID.randomUUID()
        val publishedLinkRevision: UUID = UUID.randomUUID()
        val publishedLinkDraft: UUID = UUID.randomUUID()
        lateinit var publishedLinkDraftCase: UUID
        val flowLink: UUID = UUID.randomUUID()
        val pendingLink: UUID = UUID.randomUUID()
        val pendingLinkRevision: UUID = UUID.randomUUID()
        lateinit var pendingLinkCase: UUID
        val hiddenLink: UUID = UUID.randomUUID()
        lateinit var hiddenLinkCase: UUID
        val otherLink: UUID = UUID.randomUUID()
        val otherLinkRevision: UUID = UUID.randomUUID()
        val publishedReview: UUID = UUID.randomUUID()
        val publishedReviewDraft: UUID = UUID.randomUUID()
        lateinit var publishedReviewDraftCase: UUID
        val pendingReview: UUID = UUID.randomUUID()
        val pendingReviewRevision: UUID = UUID.randomUUID()
        lateinit var pendingReviewCase: UUID
        val otherReview: UUID = UUID.randomUUID()
        val otherReviewRevision: UUID = UUID.randomUUID()
        val externalReview: UUID = UUID.randomUUID()
        val dismissDecision: UUID = UUID.randomUUID()
        val restrictDecision: UUID = UUID.randomUUID()

        fun populate() {
            profile()
            sport()
            subjectLinks()
            teacherReviews()
            moderation()
        }

        private fun profile() {
            jdbc.update("INSERT INTO qualifications (code, name) VALUES ($SYNTHETIC_ID, 'Synthetic')")
            jdbc.update("INSERT INTO faculties (id, name, short_name) VALUES ($SYNTHETIC_ID, 'Synthetic', 'SYN')")
            jdbc.update(
                "INSERT INTO groups (id, name, course, qualification_id, faculty_id) VALUES (?, 'K3221', 3, $SYNTHETIC_ID, $SYNTHETIC_ID)",
                group,
            )
            for (user in listOf(deleted, other)) {
                jdbc.update("INSERT INTO user_groups (user_id, group_id) VALUES (?, ?)", user, group)
                jdbc.update(
                    "INSERT INTO devices (id, user_id, fcm_token, device_name, last_login) VALUES (?, ?, ?, 'Pixel', ?)",
                    UUID.randomUUID(),
                    user,
                    "synthetic-fcm-$user",
                    ts,
                )
            }
            jdbc.update("INSERT INTO user_roles (user_id, role, granted_at) VALUES (?, 'MODERATOR', ?)", deleted, ts)
            friendship(deleted, other, accepted = true)
            friendship(third, deleted, accepted = false)
            friendship(other, third, accepted = true)
            jdbc.update(
                """
                INSERT INTO web_sessions (id, user_id, token_hash, user_agent, created_at, last_seen_at, expires_at)
                VALUES (?, ?, ?, 'Synthetic browser', ?, ?, ?)
                """,
                UUID.randomUUID(),
                deleted,
                "a".repeat(64),
                ts,
                ts,
                Timestamp.from(NOW.plusSeconds(86_400)),
            )
            jdbc.update(
                """
                INSERT INTO web_login_challenges (id, code, poll_secret_hash, status, client_ip, created_at, expires_at, approved_by, approved_at)
                VALUES (?, 'SYN12345', ?, 'APPROVED', '203.0.113.1', ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                "b".repeat(64),
                ts,
                Timestamp.from(NOW.plusSeconds(300)),
                deleted,
                ts,
            )
            for ((isu, pair) in listOf(DELETED_ISU to 1L, OTHER_ISU to 2L)) {
                jdbc.update(
                    """
                    INSERT INTO lessons (id, user_isu, date, pair_id, subject_id, subject_name, teacher_isu, start_time, end_time,
                        type, type_id, group_name, flow_id, flow_type_id, format, format_id)
                    VALUES (?, ?, DATE '2026-09-21', ?, ?, 'Физика', ?, TIME '10:00', TIME '11:30', 'Практика', 2,
                        'ФИЗ ПИИКТ 3.2', ?, 2, 'Очно', 1)
                    """,
                    UUID.randomUUID(),
                    isu,
                    SYNTHETIC_ID * 10 + pair,
                    SUBJECT_ID,
                    TEACHER.toLong(),
                    FLOW_ID,
                )
            }
            for (user in listOf(deleted, other)) {
                jdbc.update(
                    """
                    INSERT INTO user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
                    VALUES (?, ?, ?, ?, 'ФИЗ ПИИКТ 3.2', 2, DATE '2026-09-21')
                    """,
                    user,
                    SUBJECT_ID,
                    PERIOD,
                    FLOW_ID,
                )
            }
            // A second flow of the deleted account labels nothing that stays.
            jdbc.update(
                """
                INSERT INTO user_subject_flows (user_id, subject_id, period_key, flow_id, group_name, type_id, last_seen)
                VALUES (?, ?, ?, ?, 'ФИЗ ПИИКТ 3', 1, DATE '2026-09-21')
                """,
                deleted,
                SUBJECT_ID,
                PERIOD,
                FLOW_ID + 1,
            )
        }

        private fun sport() {
            jdbc.update("INSERT INTO sport_sections (id, name) VALUES ($SYNTHETIC_ID, 'Synthetic section')")
            jdbc.update("INSERT INTO sport_time_slots (id, time_start, time_end) VALUES ($SYNTHETIC_ID, '10:00', '11:30')")
            jdbc.update("INSERT INTO sport_teachers (isu, name) VALUES ($SYNTHETIC_ID, 'Synthetic teacher')")
            jdbc.update(
                """
                INSERT INTO sport_lessons (id, section_id, section_level, lesson_level, type_id, section_name, time_slot_id,
                    teacher_isu, room_id, room_name, starts_at, ends_at, last_seen_at)
                VALUES ($SYNTHETIC_ID, $SYNTHETIC_ID, 1, 1, 1, 'Synthetic section', $SYNTHETIC_ID, $SYNTHETIC_ID, 1, 'Room', ?, ?, ?)
                """,
                ts,
                Timestamp.from(NOW.plusSeconds(5_400)),
                ts,
            )
            jdbc.update(
                """
                INSERT INTO sport_auto_sign_entries (user_id, prototype_lesson_id, target_section_id, target_section_name,
                    target_section_level, target_lesson_level, target_type_id, target_time_slot_id, target_teacher_isu,
                    target_teacher_name, target_room_id, target_room_name, target_starts_at, target_ends_at,
                    predicted_starts_at, predicted_ends_at)
                VALUES (?, $SYNTHETIC_ID, $SYNTHETIC_ID, 'Synthetic section', 1, 1, 1, $SYNTHETIC_ID, $SYNTHETIC_ID,
                    'Synthetic teacher', 1, 'Room', ?, ?, ?, ?)
                """,
                deleted,
                ts,
                Timestamp.from(NOW.plusSeconds(5_400)),
                Timestamp.from(NOW.plusSeconds(604_800)),
                Timestamp.from(NOW.plusSeconds(610_200)),
            )
            jdbc.update("INSERT INTO sport_free_sign_entries (user_id, lesson_id, force_sign) VALUES (?, $SYNTHETIC_ID, false)", deleted)
            for (user in listOf(deleted, other)) {
                jdbc.update("INSERT INTO user_sport_lessons (user_id, lesson_id) VALUES (?, $SYNTHETIC_ID)", user)
            }
        }

        private fun subjectLinks() {
            link(privateLink, deleted, "https://example.org/private", "Личное", "PRIVATE")
            // The row holds the pending edit, others see revision 1.
            link(publishedLink, deleted, "https://example.org/draft", "Черновик", "ALL")
            linkRevision(publishedLinkRevision, publishedLink, 1, "https://example.org/published", "Опубликовано", "ALL", "APPROVED")
            linkRevision(publishedLinkDraft, publishedLink, 2, "https://example.org/draft", "Черновик", "ALL", "PENDING")
            publishedLinkDraftCase = case("SUBJECT_RESOURCE", publishedLinkDraft, "OPEN", "SUBMISSION")
            link(flowLink, deleted, "https://example.org/flow", "Поток", "FLOW", flowId = FLOW_ID)
            linkRevision(UUID.randomUUID(), flowLink, 1, "https://example.org/flow", "Поток", "FLOW", "APPROVED", flowId = FLOW_ID)
            link(pendingLink, deleted, "https://example.org/pending", "На проверке", "ALL")
            linkRevision(pendingLinkRevision, pendingLink, 1, "https://example.org/pending", "На проверке", "ALL", "PENDING")
            pendingLinkCase = case("SUBJECT_RESOURCE", pendingLinkRevision, "OPEN", "SUBMISSION")
            link(hiddenLink, deleted, "https://example.org/hidden", "Скрыто", "ALL", hidden = true)
            val hiddenRevision = UUID.randomUUID()
            linkRevision(hiddenRevision, hiddenLink, 1, "https://example.org/hidden", "Скрыто", "ALL", "APPROVED")
            hiddenLinkCase = case("SUBJECT_RESOURCE", hiddenRevision, "OPEN", "REPORTS")
            link(otherLink, other, "https://example.org/other", "Чужая", "ALL", score = 0)
            linkRevision(otherLinkRevision, otherLink, 1, "https://example.org/other", "Чужая", "ALL", "APPROVED")

            linkVote(publishedLink, other, 1)
            linkVote(publishedLink, third, 1)
            jdbc.update("UPDATE subject_links SET score = 2 WHERE id = ?", publishedLink)
            linkVote(otherLink, deleted, -1)
            linkVote(otherLink, third, 1)
            report("SUBJECT_RESOURCE", publishedLinkRevision, other)
            report("SUBJECT_RESOURCE", pendingLinkRevision, other)
            pin(other, publishedLink)
            pin(third, pendingLink)
            pin(deleted, otherLink)
        }

        private fun teacherReviews() {
            review(publishedReview, deleted, TEACHER, DRAFT_TEXT, anonymous = false, verification = "PENDING", score = 1)
            reviewRevision(UUID.randomUUID(), publishedReview, 1, PUBLISHED_TEXT, "APPROVED")
            reviewRevision(publishedReviewDraft, publishedReview, 2, DRAFT_TEXT, "PENDING")
            publishedReviewDraftCase = case("TEACHER_REVIEW", publishedReviewDraft, "OPEN", "SUBMISSION")
            jdbc.update("INSERT INTO teacher_review_flows (review_id, flow_id) VALUES (?, ?)", publishedReview, FLOW_ID)
            jdbc.update(
                "INSERT INTO teacher_review_votes (review_id, user_id, value, created_at) VALUES (?, ?, 1, ?)",
                publishedReview,
                other,
                ts,
            )
            review(pendingReview, deleted, TEACHER + 1, PUBLISHED_TEXT, anonymous = true, verification = "PENDING", score = 0)
            reviewRevision(pendingReviewRevision, pendingReview, 1, PUBLISHED_TEXT, "PENDING")
            pendingReviewCase = case("TEACHER_REVIEW", pendingReviewRevision, "OPEN", "SUBMISSION")
            report("TEACHER_REVIEW", pendingReviewRevision, other)
            review(otherReview, other, TEACHER + 2, PUBLISHED_TEXT, anonymous = false, verification = "VERIFIED", score = 1)
            reviewRevision(otherReviewRevision, otherReview, 1, PUBLISHED_TEXT, "APPROVED")
            jdbc.update(
                "INSERT INTO teacher_review_votes (review_id, user_id, value, created_at) VALUES (?, ?, 1, ?)",
                otherReview,
                deleted,
                ts,
            )
            report("TEACHER_REVIEW", otherReviewRevision, deleted)

            jdbc.update(
                """
                INSERT INTO external_teacher_reviews (id, provider, external_id, teacher_isu, teacher_name, date_raw, text,
                    first_seen_at, last_seen_at, score)
                VALUES (?, 'REVIEWS_WORK_GD', $SYNTHETIC_ID, ?, 'Synthetic teacher', '2025', ?, ?, ?, 0)
                """,
                externalReview,
                TEACHER + 2,
                PUBLISHED_TEXT,
                ts,
                ts,
            )
            jdbc.update(
                "INSERT INTO external_teacher_review_votes (review_id, user_id, value, created_at) VALUES (?, ?, -1, ?)",
                externalReview,
                deleted,
                ts,
            )
            jdbc.update(
                "INSERT INTO external_teacher_review_votes (review_id, user_id, value, created_at) VALUES (?, ?, 1, ?)",
                externalReview,
                other,
                ts,
            )
        }

        private fun moderation() {
            val reviewCase = case("TEACHER_REVIEW", otherReviewRevision, "RESOLVED", "REPORTS")
            jdbc.update(
                """
                INSERT INTO moderation_decisions (id, case_id, moderator_id, action, created_at)
                VALUES (?, ?, ?, 'DISMISS', ?)
                """,
                dismissDecision,
                reviewCase,
                deleted,
                ts,
            )
            val linkCase = case("SUBJECT_RESOURCE", otherLinkRevision, "RESOLVED", "REPORTS")
            jdbc.update(
                """
                INSERT INTO moderation_decisions (id, case_id, moderator_id, action, restriction_capability, restriction_days, created_at)
                VALUES (?, ?, ?, 'RESTRICT_USER', 'VOTE', 7, ?)
                """,
                restrictDecision,
                linkCase,
                other,
                ts,
            )
            jdbc.update(
                """
                INSERT INTO user_restrictions (id, user_id, capability, decision_id, reason, starts_at)
                VALUES (?, ?, 'VOTE', ?, 'Synthetic', ?)
                """,
                UUID.randomUUID(),
                deleted,
                restrictDecision,
                ts,
            )
            jdbc.update(
                """
                INSERT INTO user_restrictions (id, user_id, capability, decision_id, reason, starts_at, revoked_at, revoked_by)
                VALUES (?, ?, 'REPORT', ?, 'Synthetic', ?, ?, ?)
                """,
                UUID.randomUUID(),
                other,
                restrictDecision,
                ts,
                Timestamp.from(NOW.plusSeconds(60)),
                deleted,
            )
            jdbc.update(
                "INSERT INTO moderation_settings (key, value, updated_at, updated_by) VALUES ('$SETTING_KEY', '1', ?, ?)",
                ts,
                deleted,
            )
            jdbc.update(
                """
                INSERT INTO admin_audit (id, actor_id, action, target, details, created_at)
                VALUES (?, ?, 'ROLE_GRANTED', 'user:$OTHER_ISU', 'role MODERATOR', ?)
                """,
                UUID.randomUUID(),
                deleted,
                ts,
            )
            jdbc.update(
                """
                INSERT INTO admin_audit (id, actor_id, action, target, details, created_at)
                VALUES (?, ?, 'ROLE_REVOKED', 'user:$DELETED_ISU', 'role MODERATOR', ?)
                """,
                UUID.randomUUID(),
                other,
                ts,
            )
        }

        private fun friendship(requester: UUID, addressee: UUID, accepted: Boolean) {
            jdbc.update(
                "INSERT INTO friendships (id, requester_id, addressee_id, status, created_at, responded_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                requester,
                addressee,
                if (accepted) "ACCEPTED" else "PENDING",
                ts,
                if (accepted) ts else null,
            )
        }

        private fun link(
            id: UUID,
            owner: UUID,
            url: String,
            title: String,
            visibility: String,
            flowId: Long? = null,
            hidden: Boolean = false,
            score: Int = 0,
        ) {
            jdbc.update(
                """
                INSERT INTO subject_links (id, owner_id, subject_id, subject_name, period_key, category, url, normalized_url,
                    title, visibility, flow_id, score, hidden_at, created_at, updated_at)
                VALUES (?, ?, ?, 'Физика', ?, 'MATERIALS', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, owner, SUBJECT_ID, PERIOD, url, url, title, visibility, flowId, score, if (hidden) ts else null, ts, ts,
            )
        }

        private fun linkRevision(
            id: UUID,
            link: UUID,
            number: Int,
            url: String,
            title: String,
            visibility: String,
            status: String,
            flowId: Long? = null,
        ) {
            jdbc.update(
                """
                INSERT INTO subject_link_revisions (id, link_id, number, category, url, normalized_url, title, visibility,
                    flow_id, status, submitted_at, decided_at)
                VALUES (?, ?, ?, 'MATERIALS', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, link, number, url, url, title, visibility, flowId, status, ts, if (status == "PENDING") null else ts,
            )
        }

        private fun linkVote(link: UUID, user: UUID, value: Int) {
            jdbc.update("INSERT INTO subject_link_votes (link_id, user_id, value, created_at) VALUES (?, ?, ?, ?)", link, user, value, ts)
        }

        private fun pin(user: UUID, link: UUID) {
            jdbc.update(
                "INSERT INTO subject_link_pins (user_id, subject_id, period_key, link_id) VALUES (?, ?, ?, ?)",
                user,
                SUBJECT_ID,
                PERIOD,
                link,
            )
        }

        private fun review(id: UUID, author: UUID, teacher: Int, text: String, anonymous: Boolean, verification: String, score: Int) {
            jdbc.update(
                """
                INSERT INTO teacher_reviews (id, author_id, teacher_isu, subject_title, text, anonymous, score, verification,
                    verified_flow_id, verification_due_at, created_at, updated_at)
                VALUES (?, ?, ?, 'Физика', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, author, teacher, text, anonymous, score, verification,
                if (verification == "VERIFIED") FLOW_ID else null, if (verification == "PENDING") ts else null, ts, ts,
            )
        }

        private fun reviewRevision(id: UUID, review: UUID, number: Int, text: String, status: String) {
            jdbc.update(
                """
                INSERT INTO teacher_review_revisions (id, review_id, number, subject_title, text, status, submitted_at, decided_at)
                VALUES (?, ?, ?, 'Физика', ?, ?, ?, ?)
                """,
                id,
                review,
                number,
                text,
                status,
                ts,
                if (status == "PENDING") null else ts,
            )
        }

        private fun case(type: String, target: UUID, status: String, reason: String): UUID {
            val id = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO moderation_cases (id, target_type, target_id, status, reason, opened_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                type,
                target,
                status,
                reason,
                ts,
                if (status == "OPEN") null else ts,
            )
            cases += id
            return id
        }

        private fun report(type: String, target: UUID, reporter: UUID) {
            jdbc.update(
                """
                INSERT INTO moderation_reports (id, target_type, target_id, reporter_id, reason, created_at)
                VALUES (?, ?, ?, ?, 'OTHER', ?)
                """,
                UUID.randomUUID(),
                type,
                target,
                reporter,
                ts,
            )
        }
    }

    private companion object {
        val RUNBOOK: Path = Path.of("docs/ops/account-deletion.sql")
        val NOW: Instant = Instant.parse("2026-10-02T09:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val ts: Timestamp = Timestamp.from(NOW)
        const val DELETED_ISU = 975001
        const val OTHER_ISU = 975002
        const val THIRD_ISU = 975003
        const val SYNTHETIC_ID = 975001L
        const val SUBJECT_ID = 975001L
        const val PERIOD = "2026-1"
        const val FLOW_ID = 975100L
        const val TEACHER = 9750001
        const val SETTING_KEY = "ACCOUNT_DELETION.test_marker"
        const val PUBLISHED_TEXT = "Опубликованный отзыв о преподавателе, достаточно длинный."
        const val DRAFT_TEXT = "Неопубликованная правка отзыва, она не должна остаться."

        /** Foreign keys to users as of V10; a new one needs a matching step in docs/ops/account-deletion.sql. */
        val HANDLED_REFERENCES = setOf(
            "user_settings.user_id", "user_groups.user_id", "devices.user_id", "sport_auto_sign_entries.user_id",
            "sport_free_sign_entries.user_id", "user_sport_lessons.user_id", "friendships.requester_id",
            "friendships.addressee_id", "user_roles.user_id", "moderation_decisions.moderator_id", "user_restrictions.user_id",
            "user_restrictions.revoked_by", "moderation_settings.updated_by", "moderation_reports.reporter_id",
            "subject_links.owner_id", "subject_link_votes.user_id", "subject_link_pins.user_id", "user_subject_flows.user_id",
            "web_login_challenges.approved_by", "web_sessions.user_id", "app_settings.updated_by", "admin_audit.actor_id",
            "service_credentials.updated_by", "teacher_reviews.author_id", "teacher_review_votes.user_id",
            "external_teacher_review_votes.user_id", "teacher_summaries.hidden_by",
        )
    }
}
