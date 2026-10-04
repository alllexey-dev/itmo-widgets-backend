package dev.alllexey.itmowidgets.backend.feature.push.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmTypedWrapper
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper

@Service
class FcmService(jsonMapper: JsonMapper, private val firebaseMessaging: FirebaseMessaging) {
    // A private copy: released clients expect absent keys, not nulls, inside `data`, while HTTP responses keep
    // writing nulls through the shared mapper.
    // Value and content NON_NULL, as Jackson 2's setSerializationInclusion set them.
    private val envelopeMapper: JsonMapper = jsonMapper.rebuild()
        .changeDefaultPropertyInclusion { JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL) }
        .build()

    fun <T> sendDataMessage(token: String?, data: FcmTypedWrapper<T?>?, recipientIsu: Int) {
        require(recipientIsu > 0) { "FCM recipient must have a positive ISU" }
        val serializedData = envelopeMapper.writeValueAsString(data)

        val message: Message? = Message.builder()
            .setToken(token)
            .putData("data", serializedData)
            // Outside the stable {type, payload} envelope; older clients ignore this extra key.
            .putData("recipient_isu", recipientIsu.toString())
            .build()

        firebaseMessaging.send(message)
    }
}
