package dev.alllexey.itmowidgets.backend.feature.credentials.service

import java.time.LocalDate

/**
 * The MyITMO calls Backend makes, in Backend types, under the technical credential. Every call is network I/O:
 * never call it inside a database transaction. A failure the call can attribute to MyITMO comes back as a
 * [MyItmoResult.Failure]; anything else (a failed credential write, a bug) is thrown.
 *
 * List elements are nullable for callers that count and reject rows; MyItmoApi 2.x fails a whole answer that has a
 * null row as [MyItmoResult.MalformedBody].
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
