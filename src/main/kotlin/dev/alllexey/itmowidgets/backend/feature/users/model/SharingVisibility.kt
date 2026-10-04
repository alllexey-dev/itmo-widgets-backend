package dev.alllexey.itmowidgets.backend.feature.users.model

import com.fasterxml.jackson.annotation.JsonCreator
import tools.jackson.databind.JsonNode

/** Owner-selected audience. ALL still requires an authenticated API caller. */
enum class SharingVisibility {
    ALL,
    FRIENDS,
    NOBODY,
    ;

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun fromJson(value: JsonNode): SharingVisibility {
            // Jackson normally accepts enum ordinals: numeric 0 must never open an ALL audience.
            require(value.isString) { "Sharing visibility must be an enum name string" }
            return entries.firstOrNull { it.name == value.stringValue() }
                ?: throw IllegalArgumentException("Unknown sharing visibility")
        }
    }
}
