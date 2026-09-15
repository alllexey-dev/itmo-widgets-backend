package dev.alllexey.itmowidgets.backend.dto

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException

@JsonDeserialize(using = UserLookupRequestDeserializer::class)
data class UserLookupRequest(val isus: List<Int>) {
    fun validate() {
        if (isus.size > MAX_ISUS || isus.any { it <= 0 }) {
            throw InvalidRequestDataException("Lookup requires at most 50 positive ISUs")
        }
    }

    companion object { const val MAX_ISUS = 50 }
}

class UserLookupRequestDeserializer : JsonDeserializer<UserLookupRequest>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): UserLookupRequest {
        val node = parser.codec.readTree<JsonNode>(parser).get("isus")
        if (node == null || !node.isArray || node.size() > UserLookupRequest.MAX_ISUS ||
            node.any { !it.isIntegralNumber || !it.canConvertToInt() || it.intValue() <= 0 }) {
            throw InvalidRequestDataException("Lookup requires at most 50 positive ISUs")
        }
        return UserLookupRequest(node.map { it.intValue() })
    }
}
