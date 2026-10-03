package dev.alllexey.itmowidgets.backend.feature.credentials.service

/** The part of a MyITMO directory entry Backend reads; contacts, rooms and positions never leave the gateway. */
data class MyItmoPersonality(val isu: Long, val fio: String?, val education: List<MyItmoEducation?>?)

data class MyItmoEducation(val group: String?, val course: String?, val facultyName: String?)
