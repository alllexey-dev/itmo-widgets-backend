package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmoapi.core.IdValuePair
import dev.alllexey.itmoapi.core.MyItmoException
import dev.alllexey.itmoapi.core.ResultResponse
import dev.alllexey.itmoapi.myitmo.MyItmoClient
import dev.alllexey.itmoapi.myitmo.personalities.Personality
import dev.alllexey.itmoapi.myitmo.sport.SportApi
import dev.alllexey.itmoapi.myitmo.sport.SportFilters
import dev.alllexey.itmoapi.myitmo.sport.SportLesson
import dev.alllexey.itmoapi.myitmo.sport.SportSchedule
import dev.alllexey.itmoapi.myitmo.sport.SportSignLimit
import dev.alllexey.itmoapi.myitmo.sport.TimeSlot
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.toKotlinLocalDate
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * [MyItmoGateway] on MyItmoApi 2.x; with [MyItmoService] and [ServiceCredentialStore], the only main code on it.
 * Its `suspend` calls are bridged with `runBlocking` here and nowhere else.
 */
@Service
class MyItmoApiGateway(private val myItmoService: MyItmoService) : MyItmoGateway {

    override fun sportTimeSlots(): MyItmoResult<List<MyItmoTimeSlot?>> =
        request({ getSportTimeSlots() }) { slots: List<TimeSlot> -> slots.map(::timeSlot) }

    override fun sportFilters(): MyItmoResult<MyItmoSportFilters> = request({ getSportFilters() }) { filters: SportFilters ->
        MyItmoSportFilters(
            buildings = entries(filters.buildingId),
            sections = entries(filters.sectionId),
            teachers = entries(filters.teacherIsu),
        )
    }

    override fun sportSchedule(from: LocalDate, to: LocalDate): MyItmoResult<List<MyItmoSportLesson?>> =
        request({ getSportSchedule(from.toKotlinLocalDate(), to.toKotlinLocalDate()) }) { days: List<SportSchedule> ->
            days.flatMap { day -> day.lessons.orEmpty().map(::lesson) }
        }

    override fun sportSignLimits(): MyItmoResult<Map<Long, MyItmoSportSignLimit>> =
        request({ getSportSignLimits() }) { limits: Map<Long, Map<Long, SportSignLimit>> ->
            limits.flatMap { it.value.entries }.associate { it.key to MyItmoSportSignLimit(it.value.limit, it.value.available) }
        }

    override fun personality(isu: Int): MyItmoResult<MyItmoPersonality> =
        // Directory lookups serve a user's request; the deadline covers a token refresh too.
        call(PERSONALITY_TIMEOUT, { personalities.getPersonality(isu.toLong()) }) { profile: Personality ->
            MyItmoPersonality(
                isu = profile.isu,
                fio = profile.fio,
                education = profile.education.map { MyItmoEducation(it.group, it.course, it.facultyName) },
            )
        }

    private fun <W : Any, T> request(sportCall: suspend SportApi.() -> ResultResponse<W>, map: (W) -> T): MyItmoResult<T> =
        call(Duration.INFINITE, { sport.sportCall() }, map)

    private fun <W : Any, T> call(deadline: Duration, api: suspend MyItmoClient.() -> ResultResponse<W>, map: (W) -> T): MyItmoResult<T> {
        val client = myItmoService.myItmo
        val body = try {
            runBlocking {
                withTimeout(deadline) {
                    // A rejected refresh is the credential's; a rejection inside the call stays its HTTP status.
                    try {
                        client.tokens.validAccessToken()
                    } catch (error: MyItmoException.Auth) {
                        throw RefreshRejected(error)
                    }
                    client.api()
                }
            }
        } catch (error: RefreshRejected) {
            return MyItmoResult.CredentialRefreshFailed(error.auth)
        } catch (error: MyItmoException) {
            return failure(error)
        } catch (error: TimeoutCancellationException) {
            return MyItmoResult.TransportFailed(error)
        }
        val result = body.result ?: return MyItmoResult.InvalidEnvelope
        return MyItmoResult.Success(map(result))
    }

    /** Anything that is not a [MyItmoException] (a failed credential write, a bug) reaches the caller as it is. */
    private fun failure(error: MyItmoException): MyItmoResult.Failure = when (error) {
        is MyItmoException.Network -> MyItmoResult.TransportFailed(error)

        is MyItmoException.Http -> MyItmoResult.HttpStatus(error.status)

        is MyItmoException.Auth -> MyItmoResult.HttpStatus(error.status)

        // MyItmoApi reads the envelope before the status: a non-2xx answer stays a status, as it was on 1.x.
        is MyItmoException.Api -> if (error.status in 200..299) MyItmoResult.InvalidEnvelope else MyItmoResult.HttpStatus(error.status)

        is MyItmoException.Decode -> MyItmoResult.MalformedBody(error)
    }

    private fun timeSlot(slot: TimeSlot) = MyItmoTimeSlot(slot.id, slot.timeStart, slot.timeEnd)

    private fun entries(pairs: List<IdValuePair>): List<MyItmoCatalogEntry?> = pairs.map { MyItmoCatalogEntry(it.id, it.value) }

    private fun lesson(row: SportLesson) = MyItmoSportLesson(
        id = row.id,
        date = dateTime(row.date),
        dateEnd = dateTime(row.dateEnd),
        sectionId = row.sectionId,
        sectionName = row.sectionName,
        sectionLevel = row.sectionLevel,
        lessonLevel = row.lessonLevel,
        typeId = row.typeId,
        buildingId = row.buildingId,
        roomId = row.roomId,
        roomName = row.roomName,
        available = row.available,
        timeSlotId = row.timeSlotId,
        timeSlotStart = row.timeSlotStart,
        timeSlotEnd = row.timeSlotEnd,
        teacherIsu = row.teacherIsu,
        teacherFio = row.teacherFio,
    )

    /** MyItmoApi puts the epoch in place of a missing date; the catalog must see it missing, not as a 1970 lesson. */
    private fun dateTime(value: Instant): OffsetDateTime? =
        value.takeIf { it != Instant.fromEpochMilliseconds(0) }?.let { OffsetDateTime.ofInstant(it.toJavaInstant(), ZoneOffset.UTC) }

    private class RefreshRejected(val auth: MyItmoException.Auth) : RuntimeException(null, auth, false, false)

    private companion object {
        val PERSONALITY_TIMEOUT = 3.seconds
    }
}
