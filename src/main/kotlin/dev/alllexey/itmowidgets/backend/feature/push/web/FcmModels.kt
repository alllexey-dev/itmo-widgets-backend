package dev.alllexey.itmowidgets.backend.feature.push.web

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import dev.alllexey.itmowidgets.backend.feature.sport.web.SportLessonDto

/** A payload of an FCM data message. [getType] goes into the envelope and never into the payload. */
interface FcmPayload {
    @JsonIgnore
    fun getType(): String
}

/** The `data` envelope `{type, payload}` released clients decode. */
data class FcmTypedWrapper<T>(val type: String, val payload: T)

/** The same envelope read back with the payload left undecoded. */
data class FcmJsonWrapper(val type: String, val payload: JsonNode)

data class SportFreeSignLessonsPayload(val sportLessons: List<SportLessonDto>) : FcmPayload {
    override fun getType() = TYPE

    companion object {
        const val TYPE = "SPORT_FREE_SIGN_LESSONS_PAYLOAD"
    }
}

data class SportAutoSignLessonsPayload(val sportLessons: List<SportLessonDto>) : FcmPayload {
    override fun getType() = TYPE

    companion object {
        const val TYPE = "SPORT_AUTO_SIGN_LESSONS_PAYLOAD"
    }
}
