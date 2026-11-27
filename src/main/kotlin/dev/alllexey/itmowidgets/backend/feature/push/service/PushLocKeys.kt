package dev.alllexey.itmowidgets.backend.feature.push.service

/**
 * `loc-key`s of iOS alerts. Each value is a frozen key of the app's string catalog
 * (`scripts/strings-frozen-keys.txt` in ITMO.Widgets), so iOS resolves it from `Localizable`; Backend sends no copy.
 */
object PushLocKeys {
    const val FRIENDS_TITLE = "notification_channel_friends"

    /** `loc-args`: the actor's name. */
    const val FRIEND_REQUEST = "notification_friend_request"

    /** `loc-args`: the actor's name. */
    const val FRIEND_ACCEPTED = "notification_friend_accepted"

    /** The neutral title the NSE keeps when it cannot book; on an outcome it swaps in `notification_sport_success`/`_failure`. */
    const val SPORT_PLACE_FREE = "notification_sport_place_free"

    /** The title of a booking a self-hosted agent made; the NSE uses it for its own bookings too. */
    const val SPORT_SUCCESS = "notification_sport_success"

    /** `loc-args`: the section name and the lesson start as `dd.MM HH:mm` in Europe/Moscow. */
    const val SPORT_LESSON = "notification_sport_lesson"

    /** A new connection: the title; added to the app's catalog with the pairing UI. */
    const val DEVICE_CONNECTED_TITLE = "notification_device_connected_title"

    /** `loc-args`: the agent's or MCP server's name. */
    const val DEVICE_CONNECTED_AGENT = "notification_device_connected_agent"

    /** A new web connection; no `loc-args`. */
    const val DEVICE_CONNECTED_WEB = "notification_device_connected_web"

    /** `loc-args`: the link's title (else its host) and the subject. */
    const val MODERATION_LINK_BODY = "notification_moderation_link_body"

    /** `loc-args`: the link's title (else its host), the subject and the moderator's note. */
    const val MODERATION_LINK_BODY_NOTE = "notification_moderation_link_body_note"

    /** `loc-args`: the teacher's name, else the review's subject. */
    const val MODERATION_REVIEW_BODY = "notification_moderation_review_body"

    /** `loc-args`: the teacher's name (else the review's subject) and the moderator's note. */
    const val MODERATION_REVIEW_BODY_NOTE = "notification_moderation_review_body_note"

    /** A review with neither a teacher's name nor a subject; `loc-args`: the moderator's note. */
    const val MODERATION_REVIEW_NOTE = "notification_moderation_review_note"

    /** A review with neither a teacher's name nor a subject, and no note; no `loc-args`. */
    const val MODERATION_REVIEW_UNNAMED = "notification_moderation_review_unnamed"

    /**
     * The title of a moderation notice: `notification_moderation_<link|review>_<approved|rejected|hidden|restored>`,
     * eight keys, none with `loc-args`.
     */
    fun moderationTitle(review: Boolean, outcome: String): String =
        "notification_moderation_${if (review) "review" else "link"}_${outcome.lowercase()}"
}
