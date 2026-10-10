package dev.alllexey.itmowidgets.backend.feature.push.service

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.ApnsConfig
import com.google.firebase.messaging.Aps
import com.google.firebase.messaging.ApsAlert
import com.google.firebase.messaging.Message
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Builds the firebase-admin message for one device.
 *
 * Android messages stay data-only: 2.0.1 would display a `notification` block itself, and 2.1/2.2 would not get
 * `onMessageReceived` for it in the background. HIGH priority wakes a dozing device; the TTL drops a message that
 * would arrive after it stopped being actionable.
 *
 * iOS messages are alerts: iOS throttles silent pushes and never delivers them after a force-quit, while an alert with
 * `mutable-content` reaches the Notification Service Extension. FCM passes `data` and `recipient_isu` as custom keys
 * of the APNs payload.
 */
@Component
class PushMessageFactory(private val clock: Clock) {
    fun dataMessage(token: String?, envelope: String, recipientIsu: Int, expiresAt: Instant): Message = Message.builder()
        .setToken(token)
        .putData("data", envelope)
        // Outside the stable {type, payload} envelope; older clients ignore this extra key.
        .putData("recipient_isu", recipientIsu.toString())
        .setAndroidConfig(
            AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setTtl(ttlUntil(expiresAt).toMillis())
                .build(),
        )
        .build()

    fun alertMessage(token: String?, envelope: String, recipientIsu: Int, expiresAt: Instant, alert: PushAlert): Message {
        val size = estimatedApnsBytes(envelope, alert)
        check(size <= MAX_APNS_BYTES) { "APNs payload of about $size bytes exceeds $MAX_APNS_BYTES" }
        val aps = Aps.builder()
            .setAlert(
                ApsAlert.builder()
                    .setTitleLocalizationKey(alert.titleLocKey)
                    .setLocalizationKey(alert.locKey)
                    .addAllLocalizationArgs(alert.locArgs)
                    .build(),
            )
            .setMutableContent(true)
            .setThreadId(alert.threadId)
            .putCustomData("interruption-level", alert.interruptionLevel.wire)
            .build()
        return Message.builder()
            .setToken(token)
            .putData("data", envelope)
            .putData("recipient_isu", recipientIsu.toString())
            .setApnsConfig(
                ApnsConfig.builder()
                    .putHeader("apns-push-type", "alert")
                    .putHeader("apns-priority", "10")
                    .putHeader("apns-collapse-id", alert.collapseId)
                    .putHeader("apns-expiration", apnsExpiration(expiresAt))
                    .setAps(aps)
                    .build(),
            )
            .build()
    }

    /** Zero past the deadline (FCM then tries once, without storing), never above FCM's maximum. */
    private fun ttlUntil(expiresAt: Instant): Duration {
        val remaining = Duration.between(Instant.now(clock), expiresAt)
        return remaining.coerceIn(Duration.ZERO, MAX_TTL)
    }

    /** Epoch seconds; `0` past the deadline, which APNs reads as "try once, do not store", like a zero Android TTL. */
    private fun apnsExpiration(expiresAt: Instant): String =
        if (expiresAt.isAfter(Instant.now(clock))) expiresAt.epochSecond.toString() else "0"

    /** `data` is a JSON string inside the payload, so every quote and backslash in it gains an escape. */
    private fun estimatedApnsBytes(envelope: String, alert: PushAlert): Int =
        envelope.utf8Size() + envelope.count { it == '"' || it == '\\' } +
            alert.locArgs.sumOf { it.utf8Size() + JSON_STRING_OVERHEAD } + APS_RESERVE

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

    companion object {
        /** FCM rejects a longer Android TTL. */
        val MAX_TTL: Duration = Duration.ofDays(28)

        /** APNs rejects a larger alert payload. */
        const val MAX_APNS_BYTES = 4096

        /** Quotes and a comma around one string. */
        private const val JSON_STRING_OVERHEAD = 3

        /** Keys, loc-keys, `recipient_isu` and the keys FCM adds itself (`gcm.message_id`, `google.c.*`). */
        private const val APS_RESERVE = 512
    }
}
