package dev.alllexey.itmowidgets.backend.feature.push.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * A kind of push the user can switch off on a device (the app's switches, Android's channel ids in upper case). The
 * sport queue pushes have no category: the device books on them, so they are always sent and the app hides them.
 */
enum class PushCategory {
    /** The self-hosted agent's booking (`SPORT_AGENT_BOOKED_PAYLOAD`). */
    SPORT,

    /** `FRIENDSHIP_EVENT_PAYLOAD`. */
    FRIENDS,

    /** `DEVICE_CONNECTED_PAYLOAD`. */
    CONNECTIONS,
    ;

    companion object {
        /** The known categories among [names]; an unknown name (a newer app) is ignored, never an error. */
        fun known(names: Collection<String>): Set<PushCategory> =
            names.mapNotNullTo(mutableSetOf()) { name -> entries.firstOrNull { it.name == name } }
    }
}

/** `muted_push_categories`: the names comma-separated, empty for none; a name this image does not know is dropped. */
@Converter
class PushCategorySetConverter : AttributeConverter<Set<PushCategory>, String> {
    override fun convertToDatabaseColumn(attribute: Set<PushCategory>?): String =
        attribute.orEmpty().map { it.name }.sorted().joinToString(",")

    override fun convertToEntityAttribute(dbData: String?): Set<PushCategory> =
        PushCategory.known(dbData.orEmpty().split(',').filter { it.isNotBlank() })
}
