package dev.alllexey.itmowidgets.backend.feature.credentials.service

import java.time.LocalDate

/**
 * The MyITMO calls Backend makes, in Backend types, under the technical credential. Every call is network I/O:
 * never call it inside a database transaction. A failure the call can attribute to MyITMO comes back as a
 * [MyItmoResult.Failure]; anything else (a failed credential write, a bug) is thrown.
 *
 * Lists keep the null elements MyITMO may send, so callers count and reject them as rows.
 */
interface MyItmoGateway {
    fun sportTimeSlots(): MyItmoResult<List<MyItmoTimeSlot?>>

    fun sportFilters(): MyItmoResult<MyItmoSportFilters>

    /** The lessons of every day from [from] to [to], in MyITMO's order. */
    fun sportSchedule(from: LocalDate, to: LocalDate): MyItmoResult<List<MyItmoSportLesson?>>

    /** Free places by lesson ID. */
    fun sportSignLimits(): MyItmoResult<Map<Long, MyItmoSportSignLimit>>

    /** The directory entry of [isu]: the name and education only, within a three-second deadline. */
    fun personality(isu: Int): MyItmoResult<MyItmoPersonality>
}
