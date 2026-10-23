package dev.alllexey.itmowidgets.backend.feature.sport.web

/**
 * The push to the owner's devices when their self-hosted agent reports a booking (docs/contracts/connections.md); sent as
 * `{type: TYPE, payload}` through `DeviceService`.
 */
data class SportAgentBookedPayload(val sportLessons: List<SportLessonDto>, val agentName: String) {
    companion object {
        const val TYPE = "SPORT_AGENT_BOOKED_PAYLOAD"
    }
}
