package dev.alllexey.itmowidgets.backend.contract

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.google.gson.GsonBuilder
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import java.time.LocalDate
import java.time.LocalTime

/**
 * The request bodies of [ContractCatalog.requests] with the Backend type each one decodes into. Fixtures are what
 * released Core writes; a recording run only creates a missing one, rendered the way Core's Gson does.
 */
object ContractRequests {
    class Sample(val value: Any, val type: TypeReference<*>)

    private inline fun <reified T : Any> sample(value: T) = Sample(value, object : TypeReference<T>() {})

    val samples: Map<String, Sample> = with(ContractSamples) {
        mapOf(
            "RegisterDeviceRequest" to sample(registerDevice),
            "UnregisterDeviceRequest" to sample(unregisterDevice),
            "IdTokenRequest" to sample(idToken),
            "LessonSyncRequest" to sample(lessonSync),
            "UserLookupRequest" to sample(lookupRequest),
            "UserPrivacySettings" to sample(privacyRequest),
            "SportLessonIds" to sample(sportLessonIds),
            "SportFreeSignRequest" to sample(freeSign),
            "SportAutoSignRequest" to sample(autoSign),
            "SaveSubjectLinkRequest" to sample(saveLink),
            "PinSubjectLinkRequest" to sample(pinLink),
            "ResourceVoteRequest" to sample(vote),
            "ModerationReportRequest" to sample(report),
            "SaveTeacherReviewRequest" to sample(saveReview),
            "ModerationDecisionRequest" to sample(decision),
            "ModerationSettings" to sample(moderationSettings),
        )
    }

    /** Core's Gson: declaration order, `null` fields omitted, `LocalDate` and `LocalTime` as `toString()`. */
    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { value, _, _ -> JsonPrimitive(value.toString()) })
        .registerTypeAdapter(LocalTime::class.java, JsonSerializer<LocalTime> { value, _, _ -> JsonPrimitive(value.toString()) })
        .create()

    fun asCoreWrites(id: String): JsonNode = ContractJson.parse(gson.toJson(samples.getValue(id).value))

    /** The fixture when it exists, else what Core would send; the latter only happens while recording. */
    fun body(id: String): ByteArray {
        val file = ContractCatalog.requests.single { it.id == id }.file
        return if (ContractFiles.exists(file)) ContractFiles.read(file) else ContractJson.render(asCoreWrites(id)).toByteArray()
    }
}
