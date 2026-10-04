package dev.alllexey.itmowidgets.backend

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The package rules of `docs/architecture.md`, checked on `src/main`. A file depends on every class it imports and
 * on every class it names by its full name in code or in a JPQL string.
 *
 * Each rule compares today's violations with its allowlist in both directions: a new violation fails, and so does an
 * entry that no longer occurs. The lists only shrink; remove an entry in the change that fixes it.
 */
class ArchitectureTest {

    private val sources: List<SourceFile> = mainSources

    @Test
    fun `the rules see every layer of every feature`() {
        assertTrue(sources.size > MIN_SOURCE_FILES) { "Only ${sources.size} main files in scope" }
        val layers = sources.filter { it.feature != null }.mapNotNull { it.layer }.toSet()
        assertTrue(layers.containsAll(listOf("web", "service", "persistence", "model"))) { "Layers in scope: $layers" }
        assertTrue(sources.any { it.packageName.startsWith("platform.") }) { "No platform package in scope" }
    }

    @Test
    fun `web never depends on persistence`() = assertBoundary(WEB_TO_PERSISTENCE) { source, target ->
        source.feature != null && source.layer == "web" && target.layer == "persistence"
    }

    @Test
    fun `no feature depends on another feature's web`() = assertBoundary(CROSS_FEATURE_WEB) { source, target ->
        source.feature != null && target.feature != null && target.feature != source.feature && target.layer == "web"
    }

    @Test
    fun `platform depends on no feature`() = assertBoundary(PLATFORM_TO_FEATURE) { source, target ->
        source.packageName.startsWith("platform.") && target.feature != null
    }

    @Test
    fun `main code reads time from a Clock`() {
        val violations = sources.filter { NOW_WITHOUT_CLOCK.containsMatchIn(it.code) }.map { it.path }.toSet()
        assertAllowlist("now() without a Clock", violations, NOW_WITHOUT_CLOCK_ALLOWLIST)
    }

    private fun assertBoundary(rule: Rule, forbidden: (SourceFile, Target) -> Boolean) {
        val violations = sources.flatMap { source ->
            source.dependencies.filter { forbidden(source, it) }.map { "${source.path} -> ${it.name}" }
        }.toSet()
        assertAllowlist(rule.title, violations, rule.allowlist)
    }

    private fun assertAllowlist(title: String, violations: Set<String>, allowlist: List<String>) {
        val added = violations - allowlist.toSet()
        val fixed = allowlist.toSet() - violations
        if (added.isEmpty() && fixed.isEmpty()) return
        fail<Unit>(
            buildString {
                append("Rule \"$title\" changed.")
                if (added.isNotEmpty()) append("\nNew violations (fix them; the allowlist never grows):\n  ")
                append(added.sorted().joinToString("\n  "))
                if (fixed.isNotEmpty()) append("\nNo longer violated (remove them from the allowlist):\n  ")
                append(fixed.sorted().joinToString("\n  "))
            },
        )
    }

    private class Rule(val title: String, val allowlist: List<String>)

    /** [name] is relative to [ROOT_PACKAGE], e.g. `feature.sport.web.SportLessonDto`. */
    private class Target(val name: String) {
        val feature: String? = featureOf(name)
        val layer: String? = layerOf(name)
    }

    /** Keeps only strings: the Konsist declarations are dropped once parsed. */
    private class SourceFile(file: KoFileDeclaration, rootPackageDir: File) {
        /** Relative to the root package directory, e.g. `feature/sport/web/SportController.kt`. */
        val path: String = File(file.path).relativeTo(rootPackageDir).invariantSeparatorsPath
        val packageName: String = file.packagee!!.name.removePrefix("$ROOT_PACKAGE.")
        val feature: String? = featureOf(packageName)
        val layer: String? = layerOf(packageName)

        /** The text without the package and import lines. */
        val code: String = file.text.lineSequence()
            .filterNot { it.startsWith("package ") || it.startsWith("import ") }
            .joinToString("\n")

        val dependencies: List<Target> = (file.imports.map { it.name } + FULL_NAME.findAll(code).map { it.value })
            .mapNotNull { CLASS_NAME.find(it)?.groupValues?.get(1) }
            .distinct()
            .map(::Target)
    }

    private companion object {
        const val ROOT_PACKAGE = "dev.alllexey.itmowidgets.backend"
        const val MAIN_SOURCES = "src/main/kotlin"
        const val MIN_SOURCE_FILES = 200

        /** Parsed once per run; `scopeFromExternalDirectory` reads only `src/main/kotlin`, not the whole repository. */
        val mainSources: List<SourceFile> by lazy {
            val mainDir = File(MAIN_SOURCES).absoluteFile
            val rootPackageDir = File(mainDir, ROOT_PACKAGE.replace('.', '/'))
            Konsist.scopeFromExternalDirectory(mainDir.path).files
                .filter { it.packagee?.name?.startsWith(ROOT_PACKAGE) == true }
                .map { SourceFile(it, rootPackageDir) }
        }

        /** A full name up to its first class segment; members and nested names count as their class. */
        val CLASS_NAME = Regex("""^dev\.alllexey\.itmowidgets\.backend\.((?:[a-z]\w*\.)*[A-Z]\w*)""")
        val FULL_NAME = Regex("""dev\.alllexey\.itmowidgets\.backend\.(?:[a-z]\w*\.)*[A-Z]\w*""")
        val NOW_WITHOUT_CLOCK = Regex("""\.now\(\s*\)""")

        /** `feature.<feature>.<layer>…` names the feature and the layer; `platform.…` names neither. */
        fun featureOf(name: String): String? = name.split('.').takeIf { it[0] == "feature" }?.getOrNull(1)

        fun layerOf(name: String): String? = name.split('.').takeIf { it[0] == "feature" }?.getOrNull(2)

        // Allowlists: one entry per line, `<file under the root package> -> <class it depends on>`.

        val WEB_TO_PERSISTENCE = Rule("web never depends on persistence", emptyList())

        /** Mostly wire types composed across features (`UserData`, `GroupData`, `FcmPayload`, admin rows). */
        val CROSS_FEATURE_WEB = Rule(
            "no feature depends on another feature's web",
            listOf(
                // admin
                "feature/admin/service/AdminDashboardService.kt -> feature.links.web.SubjectLinkStatus",
                "feature/admin/service/AdminModerationService.kt -> feature.moderation.web.ModerationCase",
                "feature/admin/service/AdminModerationService.kt -> feature.moderation.web.ModerationDecisionRequest",
                "feature/admin/service/AdminModerationService.kt -> feature.moderation.web.ModerationSettings",
                "feature/admin/service/AdminModerationService.kt -> feature.moderation.web.SubjectLinkTarget",
                "feature/admin/service/AdminModerationService.kt -> feature.moderation.web.TeacherReviewTarget",
                "feature/admin/service/AdminUserSummaries.kt -> feature.users.web.GroupData",
                "feature/admin/service/AdminUsersService.kt -> feature.users.web.UserCapabilities",
                "feature/admin/service/AdminUsersService.kt -> feature.users.web.UserData",
                "feature/admin/web/AdminDashboardModels.kt -> feature.links.web.SubjectLinkStatus",
                "feature/admin/web/AdminModerationController.kt -> feature.moderation.web.ModerationCase",
                "feature/admin/web/AdminModerationController.kt -> feature.moderation.web.ModerationDecisionRequest",
                "feature/admin/web/AdminModerationController.kt -> feature.moderation.web.ModerationSettings",
                "feature/admin/web/AdminModerationModels.kt -> feature.links.web.SubjectLinkRevision",
                "feature/admin/web/AdminReviewsModels.kt -> feature.reviews.web.StrictBooleanDeserializer",
                "feature/admin/web/AdminReviewsModels.kt -> feature.reviews.web.TeacherSummary",
                "feature/admin/web/AdminUsersModels.kt -> feature.users.web.GroupData",
                // app
                "feature/app/service/AppVersionSettings.kt -> feature.admin.web.AdminAppVersion",
                // links
                "feature/links/service/SubjectLinkService.kt -> feature.admin.web.AdminLinkSummary",
                "feature/links/service/SubjectLinkService.kt -> feature.moderation.web.ModerationCaseTarget",
                "feature/links/service/SubjectLinkService.kt -> feature.moderation.web.ModerationReportRequest",
                "feature/links/service/SubjectLinkService.kt -> feature.moderation.web.SubjectLinkTarget",
                "feature/links/service/SubjectLinkService.kt -> feature.moderation.web.SubmitterHistory",
                "feature/links/service/SubjectLinkViews.kt -> feature.users.web.UserData",
                "feature/links/web/SubjectLinkController.kt -> feature.moderation.web.ModerationReportRequest",
                "feature/links/web/SubjectLinkModels.kt -> feature.users.web.UserData",
                // moderation
                "feature/moderation/service/ModerationTarget.kt -> feature.admin.web.AdminLinkSummary",
                "feature/moderation/service/ModerationTarget.kt -> feature.admin.web.AdminReviewSummary",
                "feature/moderation/service/ModerationTarget.kt -> feature.links.web.SubjectLinkRevision",
                "feature/moderation/web/ModerationCaseTarget.kt -> feature.links.web.SubjectLink",
                "feature/moderation/web/ModerationCaseTarget.kt -> feature.links.web.SubjectLinkRevision",
                "feature/moderation/web/ModerationCaseTarget.kt -> feature.reviews.web.ModeratedTeacherReview",
                "feature/moderation/web/ModerationCaseTarget.kt -> feature.reviews.web.TeacherReviewRevision",
                "feature/moderation/web/ModerationCaseTarget.kt -> feature.users.web.UserData",
                // push
                "feature/push/persistence/DeviceRepository.kt -> feature.admin.web.AdminDevice",
                "feature/push/web/FcmModels.kt -> feature.sport.web.SportLessonDto",
                // reviews
                "feature/reviews/service/TeacherReviewService.kt -> feature.admin.web.AdminReviewSummary",
                "feature/reviews/service/TeacherReviewService.kt -> feature.moderation.web.ModerationCaseTarget",
                "feature/reviews/service/TeacherReviewService.kt -> feature.moderation.web.ModerationReportRequest",
                "feature/reviews/service/TeacherReviewService.kt -> feature.moderation.web.SubmitterHistory",
                "feature/reviews/service/TeacherReviewService.kt -> feature.moderation.web.TeacherReviewTarget",
                "feature/reviews/service/TeacherReviewViews.kt -> feature.users.web.UserData",
                "feature/reviews/web/TeacherReviewController.kt -> feature.links.web.ResourceVoteRequest",
                "feature/reviews/web/TeacherReviewController.kt -> feature.moderation.web.ModerationReportRequest",
                "feature/reviews/web/TeacherReviewModels.kt -> feature.users.web.UserData",
                // schedule
                "feature/schedule/service/LessonContextService.kt -> feature.users.web.RelationshipState",
                "feature/schedule/service/LessonContextService.kt -> feature.users.web.UserProfile",
                "feature/schedule/web/ScheduleController.kt -> feature.users.web.UserProfile",
                // social
                "feature/social/service/FriendService.kt -> feature.users.web.RelationshipState",
                "feature/social/service/FriendshipNotificationService.kt -> feature.users.web.RelationshipState",
                "feature/social/web/FriendController.kt -> feature.users.web.UserProfile",
                "feature/social/web/FriendshipEventPayload.kt -> feature.push.web.FcmPayload",
                "feature/social/web/FriendshipEventPayload.kt -> feature.users.web.UserData",
                // sport
                "feature/sport/service/SportNotificationIntent.kt -> feature.push.web.FcmPayload",
                "feature/sport/service/SportQueueTransitionService.kt -> feature.push.web.SportAutoSignLessonsPayload",
                "feature/sport/service/SportQueueTransitionService.kt -> feature.push.web.SportFreeSignLessonsPayload",
                // users
                "feature/users/web/UserController.kt -> feature.moderation.web.UserRestriction",
                "feature/users/web/UserController.kt -> feature.weblogin.web.WebLoginPreview",
                // weblogin
                "feature/weblogin/web/WebModels.kt -> feature.users.web.GroupData",
            ),
        )

        /** Authentication resolves users and web sessions; `RestrictedException` names the capability. */
        val PLATFORM_TO_FEATURE = Rule(
            "platform depends on no feature",
            listOf(
                "platform/error/ServiceExceptions.kt -> feature.moderation.model.RestrictionCapability",
                "platform/security/JwtAuthFilter.kt -> feature.users.service.UserService",
                "platform/security/UserDetailsServiceImpl.kt -> feature.users.persistence.UserRepository",
                "platform/security/WebSessionFilter.kt -> feature.weblogin.service.WebSessionService",
            ),
        )

        /** Files that call `now()` without a `Clock`: BK-12 fixes the entity defaults, BK-16b the device sites. */
        val NOW_WITHOUT_CLOCK_ALLOWLIST = listOf(
            // BK-12
            "feature/sport/model/SportAutoSignEntity.kt",
            "feature/sport/model/SportFreeSignEntity.kt",
            "feature/sport/model/UserSportLesson.kt",
            "feature/users/model/User.kt",
            // BK-16b
            "feature/push/model/Device.kt",
            "feature/push/service/DeviceService.kt",
        )
    }
}
