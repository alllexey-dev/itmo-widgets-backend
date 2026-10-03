package dev.alllexey.itmowidgets.backend.feature.sport.service

/** Expected transfer outcomes must not mark the enclosing transaction rollback-only. */
enum class SportFreeSignTransferResult {
    CREATED,
    EXISTING,
    ALREADY_SATISFIED,
    LESSON_ENDED,
}
