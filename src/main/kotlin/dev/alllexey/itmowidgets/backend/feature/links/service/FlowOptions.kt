package dev.alllexey.itmowidgets.backend.feature.links.service

import dev.alllexey.itmowidgets.backend.feature.links.web.LinkAudience
import dev.alllexey.itmowidgets.backend.feature.schedule.service.FlowMembership
import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.util.UUID

/**
 * The flows of a subject a link's author could pick from, stored with a revision (`flow_options`) so that a moderator
 * can move a FLOW link to another of them. The client sends the list; Backend keeps only flows of the author and its
 * own label, type and depth of each.
 */
@Component
class FlowOptions(private val flows: FlowMembership) {

    /** Rejects a malformed list before anything is written; with FLOW the list must contain [flowId]. */
    fun requireWellFormed(options: List<LinkAudience>?, flowId: Long?) {
        if (options == null) return
        // Jackson fills a List<LinkAudience> with nulls from `[null]`.
        @Suppress("SENSELESS_COMPARISON")
        val malformed = options.size > MAX || options.any { it == null } ||
            options.map { it.flowId }.toSet().size != options.size ||
            options.any { it.flowId <= 0 || it.label.trim().length !in 1..MAX_LABEL || it.typeId < 0 || it.depth !in 1..MAX_DEPTH }
        if (malformed) throw InvalidRequestDataException("Invalid flow options")
        if (flowId != null &&
            options.none { it.flowId == flowId }
        ) {
            throw InvalidRequestDataException("The flow must be one of the flow options")
        }
    }

    /**
     * The stored form of [options] for a revision of [userId]'s link: every option must be one of the user's flows of
     * the subject and period (otherwise `audience_unavailable`, as for the chosen flow), in the client's order.
     */
    fun encode(userId: UUID, subjectId: Long, periodKey: String, options: List<LinkAudience>?): String? {
        if (options == null) return null
        val own = flows.flowsOf(userId, subjectId, periodKey).associateBy { it.flowId }
        val stored = options.map { option ->
            val flow = own[option.flowId] ?: throw InvalidRequestDataException("audience_unavailable")
            LinkAudience(flow.flowId, flow.groupName, flow.typeId, flow.depth)
        }
        return jsonMapper.writeValueAsString(stored)
    }

    fun decode(stored: String?): List<LinkAudience>? = stored?.let { jsonMapper.readValue(it, LIST) }

    private companion object {
        const val MAX = 32
        const val MAX_LABEL = 200
        const val MAX_DEPTH = 10
        val LIST = object : TypeReference<List<LinkAudience>>() {}

        /** The stored column only: plain field names, independent of the web mapper's settings. */
        val jsonMapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    }
}
