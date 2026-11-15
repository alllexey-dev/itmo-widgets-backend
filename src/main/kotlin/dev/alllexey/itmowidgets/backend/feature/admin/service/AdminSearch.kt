package dev.alllexey.itmowidgets.backend.feature.admin.service

import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException

/**
 * The text of an admin search box, trimmed and ignoring case. [pattern] is a `%...%` LIKE pattern escaped with `!`, so
 * `%` and `_` are literal; [isuPrefix] matches an ISU as text and is empty, matching nothing, unless the text is digits.
 */
class AdminSearch private constructor(val pattern: String, val isuPrefix: String) {
    companion object {
        const val MAX_LENGTH = 100

        /** Null for an absent or blank text; 400 `invalid_request_data` for one over [MAX_LENGTH] characters. */
        fun of(text: String?): AdminSearch? {
            val trimmed = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (trimmed.length > MAX_LENGTH) throw InvalidRequestDataException("Search query is too long")
            val escaped = trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_")
            return AdminSearch("%$escaped%", if (trimmed.all(Char::isDigit)) "$trimmed%" else "")
        }
    }
}
