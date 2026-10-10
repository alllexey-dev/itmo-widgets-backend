package dev.alllexey.itmowidgets.backend.feature.push.service

/** What an iOS device shows for a push. Android ignores it and keeps rendering from `data`. */
data class PushAlert(
    val titleLocKey: String,
    val locKey: String,
    val locArgs: List<String>,
    /** `apns-collapse-id`: a newer alert with the same id replaces the shown one. At most 64 bytes. */
    val collapseId: String,
    val threadId: String,
    val interruptionLevel: InterruptionLevel,
) {
    enum class InterruptionLevel(val wire: String) {
        ACTIVE("active"),

        /** Breaks through Focus only with the app's time-sensitive entitlement; without it iOS treats it as [ACTIVE]. */
        TIME_SENSITIVE("time-sensitive"),
    }
}
