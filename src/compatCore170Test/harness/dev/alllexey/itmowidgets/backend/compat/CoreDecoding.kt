package dev.alllexey.itmowidgets.backend.compat

import com.google.gson.JsonElement
import dev.alllexey.itmowidgets.core.model.fcm.FcmJsonWrapper

/**
 * Reads one fixture with the released Core and lists what an installed app would trip over; an
 * empty list means the release reads it. Exceptions from Core's strict adapters become problems.
 */
object CoreDecoding {
    fun check(entry: ContractEntry, fixture: JsonElement): List<String> = try {
        when (entry.kind) {
            "http" -> response(entry, fixture)
            "request" -> request(entry, fixture)
            "fcm" -> fcm(entry, fixture)
            else -> listOf("unknown fixture kind ${entry.kind}")
        }
    } catch (error: RuntimeException) {
        listOf("Core rejects it: ${describe(error)}")
    }

    /** The whole `ApiResponse` body decoded into the method's declared result type. */
    private fun response(entry: ContractEntry, fixture: JsonElement): List<String> {
        val route = ReleasedCore.routes[entry.id] ?: return listOf("Core has no method ${entry.id}")
        val problems = mutableListOf<String>()
        if (route.method != entry.method || route.path != entry.path) {
            problems += "Core calls ${route.method} ${route.path}, the fixture is ${entry.method} ${entry.path}"
        }
        val decoded: Any? = ReleasedCore.gson.fromJson(fixture, route.response)
        return problems + (if (decoded == null) listOf("decoded as null") else NonNullCheck.violations(decoded))
    }

    /** A body as the release writes it: decoded, then re-encoded by the same Gson to the same JSON. */
    private fun request(entry: ContractEntry, fixture: JsonElement): List<String> {
        val type = ReleasedCore.requests[entry.id] ?: return listOf("Core sends no ${entry.id}")
        val decoded: Any = ReleasedCore.gson.fromJson(fixture, type) ?: return listOf("decoded as null")
        return NonNullCheck.violations(decoded) +
            JsonDifferences.of(fixture, ReleasedCore.gson.toJsonTree(decoded, type)).map { "re-encoded $it" }
    }

    /**
     * The FCM data map: `recipient_isu` as the app's messaging service parses it, `data` (a JSON
     * string on the wire) through `FcmJsonWrapper` and the payload class of its `type`.
     */
    private fun fcm(entry: ContractEntry, fixture: JsonElement): List<String> {
        val message = fixture.asJsonObject
        val problems = mutableListOf<String>()
        val recipient = message["recipient_isu"]
        if (recipient == null || !recipient.isJsonPrimitive || !recipient.asJsonPrimitive.isString ||
            (recipient.asString.toIntOrNull() ?: 0) <= 0
        ) {
            problems += "recipient_isu must be a string with a positive ISU, found $recipient"
        }
        val data = message["data"] ?: return problems + "no data"
        val wrapper = ReleasedCore.gson.fromJson(data.toString(), FcmJsonWrapper::class.java)
            ?: return problems + "data decoded as null"
        problems += NonNullCheck.violations(wrapper, "$.data")
        if (wrapper.type != entry.id) problems += "data.type is ${wrapper.type}, the fixture id is ${entry.id}"
        val payloadClass = ReleasedCore.fcmPayloads[wrapper.type]
            ?: return problems + "Core has no payload for ${wrapper.type}"
        @Suppress("SENSELESS_COMPARISON")
        if (wrapper.payload == null || !wrapper.payload.isJsonObject) return problems + "data.payload must be an object"
        val payload: Any = ReleasedCore.gson.fromJson(wrapper.payload, payloadClass)
            ?: return problems + "payload decoded as null"
        return problems + NonNullCheck.violations(payload, "$.data.payload")
    }

    private fun describe(error: Throwable): String =
        generateSequence(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }
}
