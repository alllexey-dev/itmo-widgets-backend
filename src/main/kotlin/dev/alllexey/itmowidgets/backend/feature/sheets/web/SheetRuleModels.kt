package dev.alllexey.itmowidgets.backend.feature.sheets.web

import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.JsonNode
import java.time.Instant

/** The state of a sheet's AI rule as clients see it; `NONE` means nobody has asked for it yet. */
enum class SheetRuleState { NONE, PENDING, RUNNING, READY, INSUFFICIENT, FAILED }

/**
 * The AI rule of the Google sheet behind a link (docs/contracts/sheet-rules.md). [rule] is the stored rule: set with
 * `READY`, and while a re-check is `PENDING` or `RUNNING` the previous rule, still valid until the check ends.
 * [analyzedAt] is when the stored verdict was made. [reason] is a code of an open set, null with `NONE` and `READY`
 * and with a plain `PENDING` or `RUNNING`.
 */
data class SheetRuleResponse(
    val status: SheetRuleState,
    @get:Schema(implementation = Map::class, description = "The analyzer's sanitised rule, schema_version 1")
    val rule: JsonNode?,
    val analyzedAt: Instant?,
    val reason: String?,
)
