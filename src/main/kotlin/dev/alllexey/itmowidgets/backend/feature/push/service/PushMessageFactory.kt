package dev.alllexey.itmowidgets.backend.feature.push.service

import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.Message
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Builds the firebase-admin message for one device. Every registered device is Android today.
 *
 * Android messages stay data-only: 2.0.1 would display a `notification` block itself, and 2.1/2.2 would not get
 * `onMessageReceived` for it in the background. HIGH priority wakes a dozing device; the TTL drops a message that
 * would arrive after it stopped being actionable.
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

    /** Zero past the deadline (FCM then tries once, without storing), never above FCM's maximum. */
    private fun ttlUntil(expiresAt: Instant): Duration {
        val remaining = Duration.between(Instant.now(clock), expiresAt)
        return remaining.coerceIn(Duration.ZERO, MAX_TTL)
    }

    companion object {
        /** FCM rejects a longer Android TTL. */
        val MAX_TTL: Duration = Duration.ofDays(28)
    }
}
