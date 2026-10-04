package dev.alllexey.itmowidgets.backend.platform

import com.fasterxml.jackson.databind.JsonNode
import dev.alllexey.itmowidgets.backend.contract.ContractJson
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAccess
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAiSummariesService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminAuditService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminDashboardService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminModerationService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminReviewsService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminSystemService
import dev.alllexey.itmowidgets.backend.feature.admin.service.AdminUsersService
import dev.alllexey.itmowidgets.backend.feature.app.service.AppVersionSettings
import dev.alllexey.itmowidgets.backend.feature.links.service.SubjectLinkService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.ModerationSettingsService
import dev.alllexey.itmowidgets.backend.feature.moderation.service.RestrictionService
import dev.alllexey.itmowidgets.backend.feature.push.service.DeviceService
import dev.alllexey.itmowidgets.backend.feature.reviews.service.TeacherReviewService
import dev.alllexey.itmowidgets.backend.feature.schedule.persistence.LessonRepository
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonContextService
import dev.alllexey.itmowidgets.backend.feature.schedule.service.LessonService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportAutoSignService
import dev.alllexey.itmowidgets.backend.feature.sport.service.SportFreeSignService
import dev.alllexey.itmowidgets.backend.feature.sport.service.UserSportLessonService
import dev.alllexey.itmowidgets.backend.feature.users.service.CurrentStudyGroupsService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserPrivacyService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserProfileService
import dev.alllexey.itmowidgets.backend.feature.users.service.UserService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebLoginService
import dev.alllexey.itmowidgets.backend.feature.weblogin.service.WebSessionService
import dev.alllexey.itmowidgets.backend.platform.security.JwtAuthFilter
import dev.alllexey.itmowidgets.backend.platform.security.WebSessionFilter
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.servers.Server
import io.swagger.v3.oas.models.tags.Tag
import org.junit.jupiter.api.Test
import org.springdoc.core.configuration.SpringDocConfiguration
import org.springdoc.core.configuration.SpringDocJacksonKotlinModuleConfiguration
import org.springdoc.core.configuration.SpringDocKotlinConfiguration
import org.springdoc.core.configuration.SpringDocSecurityConfiguration
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Generates the OpenAPI document of every Backend route with springdoc (test classpath only) and compares it
 * semantically with `docs/openapi.json`. `-Popenapi.record=true` (`scripts/verify.sh openapi`) rewrites the file.
 *
 * All controllers load with mocked services: springdoc reads signatures, nothing is called. The security filter
 * chain is off, because `SecurityConfig` denies `/v3/api-docs`; production has neither springdoc nor the route.
 * Each operation is tagged with the feature package of its controller (`feature/<f>/web`) and named after the
 * controller and method.
 */
@WebMvcTest(properties = ["springdoc.writer-with-order-by-keys=true"])
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(
    SpringDocConfiguration::class,
    SpringDocConfigProperties::class,
    SpringDocSpecPropertiesConfiguration::class,
    SpringDocSecurityConfiguration::class,
    SpringDocKotlinConfiguration::class,
    SpringDocJacksonKotlinModuleConfiguration::class,
    SpringDocWebMvcConfiguration::class,
    MultipleOpenApiSupportConfiguration::class,
)
@Import(OpenApiSnapshotTest.Customization::class)
class OpenApiSnapshotTest @Autowired constructor(
    private val mvc: MockMvc,
    @param:Qualifier("requestMappingHandlerMapping") private val mappings: RequestMappingHandlerMapping,
) {
    @MockitoBean private lateinit var jwtAuthFilter: JwtAuthFilter

    @MockitoBean private lateinit var webSessionFilter: WebSessionFilter

    @MockitoBean private lateinit var adminAccess: AdminAccess

    @MockitoBean private lateinit var adminAiSummaries: AdminAiSummariesService

    @MockitoBean private lateinit var adminAudit: AdminAuditService

    @MockitoBean private lateinit var adminDashboard: AdminDashboardService

    @MockitoBean private lateinit var adminModeration: AdminModerationService

    @MockitoBean private lateinit var adminReviews: AdminReviewsService

    @MockitoBean private lateinit var adminSystem: AdminSystemService

    @MockitoBean private lateinit var adminUsers: AdminUsersService

    @MockitoBean private lateinit var appVersions: AppVersionSettings

    @MockitoBean private lateinit var links: SubjectLinkService

    @MockitoBean private lateinit var moderation: ModerationService

    @MockitoBean private lateinit var moderationSettings: ModerationSettingsService

    @MockitoBean private lateinit var restrictions: RestrictionService

    @MockitoBean private lateinit var devices: DeviceService

    @MockitoBean private lateinit var reviews: TeacherReviewService

    @MockitoBean private lateinit var lessonRepository: LessonRepository

    @MockitoBean private lateinit var lessonContext: LessonContextService

    @MockitoBean private lateinit var lessons: LessonService

    @MockitoBean private lateinit var autoSign: SportAutoSignService

    @MockitoBean private lateinit var freeSign: SportFreeSignService

    @MockitoBean private lateinit var sportLessons: UserSportLessonService

    @MockitoBean private lateinit var currentGroups: CurrentStudyGroupsService

    @MockitoBean private lateinit var privacy: UserPrivacyService

    @MockitoBean private lateinit var profiles: UserProfileService

    @MockitoBean private lateinit var users: UserService

    @MockitoBean private lateinit var webLogins: WebLoginService

    @MockitoBean private lateinit var webSessions: WebSessionService

    @TestConfiguration(proxyBeanMethods = false)
    class Customization {
        /** Fixed metadata and a relative server, so the file changes only when a route or a type does. */
        @Bean
        fun document(): OpenAPI = OpenAPI()
            .info(Info().title("ITMO.Widgets Backend").version("generated").description(DESCRIPTION))
            .servers(listOf(Server().url("/").description("The Backend the client talks to")))

        /**
         * Tags each operation with its feature and names it `<controller>_<method>` (`adminUsers_revoke`), so an
         * operation keeps its id when another controller adds a method of the same name; springdoc's own ids get
         * numeric suffixes in route order.
         */
        @Bean
        fun featureOperations(): OperationCustomizer = OperationCustomizer { operation, handler ->
            operation
                .tags(listOf(feature(handler.beanType)))
                .operationId("${controllerStem(handler.beanType)}_${handler.method.name}")
        }

        @Bean
        fun featureTags(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
            openApi.tags = openApi.paths.orEmpty().values
                .flatMap { path -> path.readOperations().flatMap { it.tags.orEmpty() } }
                .toSortedSet()
                .map { Tag().name(it).description("feature/$it") }
        }
    }

    @Test
    fun `docs openapi json matches the generated document`() {
        val actual = generated()
        if (recording) {
            if (!SNAPSHOT.exists() || ContractJson.differences(ContractJson.parse(SNAPSHOT.readBytes()), actual).isNotEmpty()) {
                Files.createDirectories(SNAPSHOT.parent)
                SNAPSHOT.writeText(ContractJson.render(actual))
            }
            return
        }
        if (!SNAPSHOT.exists()) fail("Missing docs/openapi.json; generate it with $RECORD_HINT")
        val differences = ContractJson.differences(ContractJson.parse(SNAPSHOT.readBytes()), actual)
        if (differences.isNotEmpty()) {
            fail(
                "docs/openapi.json differs from the generated document:\n" +
                    differences.take(MAX_REPORTED).joinToString("\n") { "  $it" } +
                    (if (differences.size > MAX_REPORTED) "\n  … ${differences.size - MAX_REPORTED} more" else "") +
                    "\nThe file is generated, never edited or merged by hand: regenerate it with $RECORD_HINT" +
                    " and follow docs/contracts/compatibility.md for the wire change it shows.",
            )
        }
    }

    @Test
    fun `every Backend handler method is an operation of the document`() {
        val spec = generated()
        val documented = spec.path("paths").properties().flatMap { (path, item) ->
            item.properties().map { (method, _) -> "${method.uppercase()} $path" }
        }.toSortedSet()
        val handled = mappings.handlerMethods
            .filter { (_, handler) -> handler.beanType.name.startsWith(BACKEND_PACKAGE) }
            .flatMap { (info, _) ->
                info.methodsCondition.methods.flatMap { method ->
                    info.pathPatternsCondition!!.patternValues.map { "$method $it" }
                }
            }
            .toSortedSet()
        assertTrue(handled.isNotEmpty(), "no Backend handler methods found")
        assertEquals(handled, documented)
        val untagged = spec.path("paths").properties().flatMap { (path, item) ->
            item.properties().filter { (_, op) -> op.tagNames().size != 1 }.map { (method, _) -> "$method $path" }
        }
        assertEquals(emptyList(), untagged, "operations without exactly one feature tag")
        val ids = spec.path("paths").properties().flatMap { (_, item) -> item.map { it.path("operationId").asText() } }
        assertEquals(ids.groupingBy { it }.eachCount().filterValues { it > 1 }, emptyMap(), "duplicate operationIds")
    }

    private fun generated(): JsonNode =
        ContractJson.parse(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn().response.contentAsByteArray)

    private fun JsonNode.tagNames(): List<String> = path("tags").map { it.asText() }

    private companion object {
        const val BACKEND_PACKAGE = "dev.alllexey.itmowidgets.backend."
        const val FEATURE_PREFIX = "${BACKEND_PACKAGE}feature."
        const val MAX_REPORTED = 40
        const val RECORD_HINT = "scripts/verify.sh openapi"
        const val DESCRIPTION =
            "Generated by OpenApiSnapshotTest from the controllers; regenerate with scripts/verify.sh openapi. " +
                "Every response body is the ApiResponse envelope {success, data, error{message, code}}."

        val SNAPSHOT: Path = Path.of("docs", "openapi.json").toAbsolutePath()
        val recording: Boolean = System.getProperty("openapi.record") == "true"

        fun controllerStem(controller: Class<*>): String =
            controller.simpleName.removeSuffix("Controller").replaceFirstChar { it.lowercaseChar() }

        fun feature(controller: Class<*>): String {
            val name = controller.name
            check(name.startsWith(FEATURE_PREFIX)) { "$name is not in a feature/<f>/web package" }
            return name.removePrefix(FEATURE_PREFIX).substringBefore('.')
        }
    }
}
