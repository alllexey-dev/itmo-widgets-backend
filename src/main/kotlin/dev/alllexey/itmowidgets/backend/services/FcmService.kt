package dev.alllexey.itmowidgets.backend.services

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import dev.alllexey.itmowidgets.core.model.fcm.FcmTypedWrapper
import org.springframework.stereotype.Service

@Service
class FcmService(private val myItmoService: MyItmoService, private val firebaseMessaging: FirebaseMessaging) {

    fun <T> sendDataMessage(token: String?, data: FcmTypedWrapper<T?>?, recipientIsu: Int) {
        require(recipientIsu > 0) { "FCM recipient must have a positive ISU" }
        val serializedData = myItmoService.myItmo.gson.toJson(data)

        val message: Message? = Message.builder()
            .setToken(token)
            .putData("data", serializedData)
            // Outside the stable {type, payload} envelope; older clients ignore this extra key.
            .putData("recipient_isu", recipientIsu.toString())
            .build()

        firebaseMessaging.send(message)
    }
}
