package dev.alllexey.itmowidgets.backend.feature.sport.web

/** Expected transfer outcomes must not mark the enclosing transaction rollback-only. */
enum class SportFreeSignTransferResult {
    CREATED,
    EXISTING,
    ALREADY_SATISFIED,
    LESSON_ENDED,
}
