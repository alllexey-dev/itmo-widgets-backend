package dev.alllexey.itmowidgets.backend.feature.push.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.google.firebase.messaging.FirebaseMessaging
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmTypedWrapper
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@Service
class FcmService(
    jsonMapper: JsonMapper,
    private val firebaseMessaging: FirebaseMessaging,
    private val messageFactory: PushMessageFactory,
) {
    // A private copy: released clients expect absent keys, not nulls, inside `data`, while HTTP responses keep
    // writing nulls through the shared mapper.
    // Value and content NON_NULL, as Jackson 2's setSerializationInclusion set them.
    private val envelopeMapper: JsonMapper = jsonMapper.rebuild()
        .changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
        .build()

    /** [expiresAt] is when the message stops being actionable; FCM drops it if the device stays offline past it. */
    fun <T> sendDataMessage(token: String?, data: FcmTypedWrapper<T?>?, recipientIsu: Int, expiresAt: Instant) {
        val serializedData = serialize(data, recipientIsu)
        firebaseMessaging.send(messageFactory.dataMessage(token, serializedData, recipientIsu, expiresAt))
    }

    /** The iOS form of [sendDataMessage]: the same `data` under an APNs alert. */
    fun <T> sendAlertMessage(token: String?, data: FcmTypedWrapper<T?>?, recipientIsu: Int, expiresAt: Instant, alert: PushAlert) {
        val serializedData = serialize(data, recipientIsu)
        firebaseMessaging.send(messageFactory.alertMessage(token, serializedData, recipientIsu, expiresAt, alert))
    }

    private fun serialize(data: FcmTypedWrapper<*>?, recipientIsu: Int): String {
        require(recipientIsu > 0) { "FCM recipient must have a positive ISU" }
        return envelopeMapper.writeValueAsString(data)
    }
}
