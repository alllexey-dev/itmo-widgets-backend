package dev.alllexey.itmowidgets.backend.feature.push.service

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import dev.alllexey.itmowidgets.backend.feature.push.web.FcmTypedWrapper
import org.springframework.stereotype.Service

@Service
class FcmService(objectMapper: ObjectMapper, private val firebaseMessaging: FirebaseMessaging) {
    // A private copy: released clients expect absent keys, not nulls, inside `data`, while HTTP responses keep
    // writing nulls through the shared mapper.
    private val envelopeMapper: ObjectMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL)

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
