package dev.alllexey.itmowidgets.backend.platform

import dev.alllexey.itmowidgets.backend.feature.moderation.web.ModerationCaseTarget
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportQueue
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportQueueEntry
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.JsonSchema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import kotlin.reflect.full.primaryConstructor
import io.swagger.v3.oas.models.media.Schema as SchemaModel

/**
 * Makes springdoc's document say what the wire does (SP-18). Plain springdoc gets five things wrong:
 *
 * 1. A sealed parent with `@Schema(oneOf, discriminatorProperty)` turns every subtype into
 *    `allOf: [$ref parent, {...}]`, so parent and subtypes reference each other and generated client unions collapse.
 *    Subtypes are flattened (parent properties copied in); the parent keeps only `oneOf` and the discriminator.
 * 2. A nullable polymorphic property comes out as `{"type": "null", "oneOf": [...]}`, which means "null and one of";
 *    it becomes `oneOf: [..., {"type": "null"}]`.
 * 3. `required` follows Kotlin nullability only. Backend's ObjectMapper writes every property, nulls included, so a
 *    schema reached from a response requires all its properties. A schema only sent also requires the constructor
 *    parameters without a default, nullable or not: `required` may ask a client for more than Backend needs, never
 *    promise less than it writes.
 * 4. `RestrictionRequest` is the one schema that is both sent (`days` optional, defaulted) and read
 *    (`ModerationDecision.restriction`, `days` always written). The response side points at a copy,
 *    `DecisionRestriction`, with the same JSON. Another schema shared with an optional property fails the build until
 *    it is split the same way.
 * 5. A sealed parent no route names is left out: each current-queue route returns the list of one `SportQueue`
 *    subtype. Such a parent is added from its `@Schema`, so clients can share one discriminated type.
 */
object OpenApiFidelity {
    fun customizer(): OpenApiCustomizer = OpenApiCustomizer { openApi -> apply(openApi) }

    /** The sealed wire types; each carries `@Schema(oneOf, discriminatorProperty, discriminatorMapping)`. */
    val SEALED_TYPES: List<Class<*>> = listOf(SportQueueEntry::class.java, SportQueue::class.java, ModerationCaseTarget::class.java)

    private const val SCHEMAS = "#/components/schemas/"
    private const val ROOT_PACKAGE = "dev.alllexey.itmowidgets.backend"

    private fun apply(openApi: OpenAPI) {
        val schemas = openApi.components?.schemas ?: return
        SEALED_TYPES.forEach { addUnreferencedSealedType(schemas, it) }
        flattenSealedSubtypes(schemas)
        schemas.values.forEach(::nullableOneOf)
        splitResponseCopy(
            schemas,
            owner = "ModerationDecision",
            property = "restriction",
            source = "RestrictionRequest",
            copy = "DecisionRestriction",
        )
        requireWrittenProperties(openApi, schemas)
    }

    private fun addUnreferencedSealedType(schemas: MutableMap<String, SchemaModel<*>>, type: Class<*>) {
        if (type.simpleName in schemas) return
        val annotation = checkNotNull(type.getAnnotation(Schema::class.java)) { "${type.simpleName} has no @Schema" }
        val subtypes = annotation.oneOf.map { it.java.simpleName }
        check(subtypes.all { it in schemas }) { "${type.simpleName}: no route reaches its subtypes $subtypes either" }
        schemas[type.simpleName] = JsonSchema().apply {
            oneOf = subtypes.map { JsonSchema().`$ref`(SCHEMAS + it) }
            discriminator = Discriminator().propertyName(annotation.discriminatorProperty).apply {
                annotation.discriminatorMapping.forEach { mapping(it.value, SCHEMAS + it.schema.java.simpleName) }
            }
        }
    }

    private fun flattenSealedSubtypes(schemas: Map<String, SchemaModel<*>>) {
        for ((parentName, parent) in schemas) {
            if (parent.discriminator == null || parent.oneOf.isNullOrEmpty()) continue
            val parentRef = SCHEMAS + parentName
            for (option in parent.oneOf) {
                val child = option.`$ref`?.let { schemas[it.removePrefix(SCHEMAS)] } ?: continue
                val allOf = child.allOf ?: continue
                if (allOf.none { it.`$ref` == parentRef }) continue
                val properties = LinkedHashMap<String, SchemaModel<*>>(parent.properties.orEmpty())
                allOf.filter { it.`$ref` != parentRef }.forEach { properties.putAll(it.properties.orEmpty()) }
                child.allOf = null
                child.addType("object")
                child.properties = properties
                child.required = (child.required.orEmpty() + parent.required.orEmpty()).distinct().sorted()
            }
            parent.properties = null
            parent.required = null
            parent.types = null
            parent.type = null
        }
    }

    private fun nullableOneOf(schema: SchemaModel<*>) {
        for (property in schema.properties.orEmpty().values) {
            if (property.types == setOf("null") && !property.oneOf.isNullOrEmpty()) {
                property.types = null
                property.oneOf = property.oneOf + JsonSchema().apply { addType("null") }
            }
        }
    }

    private fun splitResponseCopy(
        schemas: MutableMap<String, SchemaModel<*>>,
        owner: String,
        property: String,
        source: String,
        copy: String,
    ) {
        val original = checkNotNull(schemas[source]) { "$source is gone: remove its split from OpenApiFidelity" }
        val slot = checkNotNull(schemas[owner]?.properties?.get(property)) { "$owner.$property is gone: remove its split" }
        val target = (listOf(slot) + slot.oneOf.orEmpty()).firstOrNull { it.`$ref` == SCHEMAS + source } ?: return
        schemas[copy] = JsonSchema().apply {
            addType("object")
            properties = LinkedHashMap(original.properties.orEmpty())
        }
        target.`$ref` = SCHEMAS + copy
    }

    private fun requireWrittenProperties(openApi: OpenAPI, schemas: Map<String, SchemaModel<*>>) {
        val requests = mutableSetOf<String>()
        val responses = mutableSetOf<String>()
        for (operation in openApi.paths.orEmpty().values.flatMap { it.readOperations() }) {
            operation.requestBody?.content?.values?.forEach { collect(it.schema, schemas, requests) }
            operation.parameters?.forEach { collect(it.schema, schemas, requests) }
            operation.responses?.values?.forEach { response ->
                response.content?.values?.forEach { collect(it.schema, schemas, responses) }
            }
        }
        val classes = kotlinClassesBySimpleName()
        val conflicts = mutableListOf<String>()
        for ((name, schema) in schemas) {
            val properties = schema.properties?.keys ?: continue
            val required = sortedSetOf<String>().apply { addAll(schema.required.orEmpty()) }
            val candidates = classes[name].orEmpty()
            check(candidates.size <= 1) { "Schema $name matches several Backend classes: $candidates" }
            candidates.singleOrNull()?.kotlin?.primaryConstructor?.parameters
                ?.filter { !it.isOptional }
                ?.mapNotNull { it.name }
                ?.filterTo(required) { it in properties }
            if (name in responses) {
                if (name in requests && !required.containsAll(properties)) conflicts += "$name: ${properties - required}"
                required += properties
            }
            schema.required = required.toList().ifEmpty { null }
        }
        check(conflicts.isEmpty()) {
            "Schemas read and sent with optional request properties; split the response side like DecisionRestriction: $conflicts"
        }
    }

    private fun collect(schema: SchemaModel<*>?, schemas: Map<String, SchemaModel<*>>, into: MutableSet<String>) {
        if (schema == null) return
        schema.`$ref`?.removePrefix(SCHEMAS)?.let { name ->
            if (into.add(name)) collect(schemas[name], schemas, into)
            return
        }
        schema.properties?.values?.forEach { collect(it, schemas, into) }
        collect(schema.items, schemas, into)
        collect(schema.additionalProperties as? SchemaModel<*>, schemas, into)
        listOfNotNull(schema.allOf, schema.oneOf, schema.anyOf).flatten().forEach { collect(it, schemas, into) }
    }

    /** springdoc names a schema after the simple class name; only Backend's main classes count. */
    private fun kotlinClassesBySimpleName(): Map<String, List<Class<*>>> {
        val testOutput = OpenApiFidelity::class.java.protectionDomain.codeSource.location
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Metadata::class.java))
        return scanner.findCandidateComponents(ROOT_PACKAGE)
            .map { Class.forName(it.beanClassName) }
            .filter { it.protectionDomain.codeSource.location != testOutput }
            .groupBy { it.simpleName }
    }
}
