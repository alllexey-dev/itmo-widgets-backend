package dev.alllexey.itmowidgets.backend.dto

data class SportFreeSignRequest(
    val lessonId: Long,
    val forceSign: Boolean,
)

data class SportAutoSignRequest(
    val prototypeLessonId: Long,
)
