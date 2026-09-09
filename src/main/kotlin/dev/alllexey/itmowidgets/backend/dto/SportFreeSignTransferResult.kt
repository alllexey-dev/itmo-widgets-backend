package dev.alllexey.itmowidgets.backend.dto

/** Expected transfer outcomes must not mark the enclosing transaction rollback-only. */
enum class SportFreeSignTransferResult {
    CREATED,
    EXISTING,
    ALREADY_SATISFIED,
    LESSON_ENDED,
}
