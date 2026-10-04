package dev.alllexey.itmowidgets.backend.feature.users.web

import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize

@JsonDeserialize(using = UserLookupRequestDeserializer::class)
data class UserLookupRequest(val isus: List<Int>) {
    fun validate() {
        if (isus.size > MAX_ISUS || isus.any { it <= 0 }) {
            throw InvalidRequestDataException("Lookup requires at most 50 positive ISUs")
        }
    }

    companion object {
        const val MAX_ISUS = 50
    }
}

class UserLookupRequestDeserializer : ValueDeserializer<UserLookupRequest>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): UserLookupRequest {
        val node = context.readTree(parser).get("isus")
        if (node == null || !node.isArray || node.size() > UserLookupRequest.MAX_ISUS ||
            node.any { !it.isIntegralNumber || !it.canConvertToInt() || it.intValue() <= 0 }
        ) {
            throw InvalidRequestDataException("Lookup requires at most 50 positive ISUs")
        }
        return UserLookupRequest(node.values().map { it.intValue() })
    }
}
