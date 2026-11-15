package dev.alllexey.itmowidgets.backend.feature.schedule.web

import dev.alllexey.itmowidgets.backend.feature.users.web.UserData

/** An accepted friend whose schedule audience admits the viewer, with the friend's lessons of one day ordered by start. */
data class FriendDay(val user: UserData, val lessons: List<LessonDto>)
