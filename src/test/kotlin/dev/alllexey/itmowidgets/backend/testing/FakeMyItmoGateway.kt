package dev.alllexey.itmowidgets.backend.testing

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoGateway
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoPersonality
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoResult
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoSportFilters
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoSportLesson
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoSportSignLimit
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoTimeSlot
import java.time.LocalDate

/**
 * A [MyItmoGateway] without a network for every test above the adapter. Each call records its arguments and runs
 * the answer the test set, which may return a failure or throw; a call without an answer throws.
 */
class FakeMyItmoGateway : MyItmoGateway {
    var timeSlots: () -> MyItmoResult<List<MyItmoTimeSlot?>> = { unexpected("sportTimeSlots") }
    var filters: () -> MyItmoResult<MyItmoSportFilters> = { unexpected("sportFilters") }
    var schedule: (LocalDate, LocalDate) -> MyItmoResult<List<MyItmoSportLesson?>> = { _, _ -> unexpected("sportSchedule") }
    var signLimits: () -> MyItmoResult<Map<Long, MyItmoSportSignLimit>> = { unexpected("sportSignLimits") }
    var personality: (Int) -> MyItmoResult<MyItmoPersonality> = { unexpected("personality") }

    /** Every call in order, e.g. `sportSchedule 2026-09-09..2026-09-30` or `personality 100001`. */
    val calls: MutableList<String> = mutableListOf()

    override fun sportTimeSlots(): MyItmoResult<List<MyItmoTimeSlot?>> {
        calls += "sportTimeSlots"
        return timeSlots()
    }

    override fun sportFilters(): MyItmoResult<MyItmoSportFilters> {
        calls += "sportFilters"
        return filters()
    }

    override fun sportSchedule(from: LocalDate, to: LocalDate): MyItmoResult<List<MyItmoSportLesson?>> {
        calls += "sportSchedule $from..$to"
        return schedule(from, to)
    }

    override fun sportSignLimits(): MyItmoResult<Map<Long, MyItmoSportSignLimit>> {
        calls += "sportSignLimits"
        return signLimits()
    }

    override fun personality(isu: Int): MyItmoResult<MyItmoPersonality> {
        calls += "personality $isu"
        return personality.invoke(isu)
    }

    /** An exception, not an error: a Spring context that refreshes the sport catalog on start must still start. */
    private fun unexpected(call: String): Nothing = throw IllegalStateException("No answer set for MyITMO call $call")
}
