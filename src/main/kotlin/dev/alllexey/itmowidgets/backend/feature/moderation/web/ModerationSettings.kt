package dev.alllexey.itmowidgets.backend.feature.moderation.web

import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationPolicy
import dev.alllexey.itmowidgets.backend.feature.moderation.model.ModerationTargetType
import java.time.Instant
import java.util.UUID

data class ModerationSettings(val policies: Map<ModerationTargetType, ModerationPolicy>)
