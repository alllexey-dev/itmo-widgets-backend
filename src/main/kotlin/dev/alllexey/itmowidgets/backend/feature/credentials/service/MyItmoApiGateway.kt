package dev.alllexey.itmowidgets.backend.feature.credentials.service

import api.myitmo.MyItmoApi
import api.myitmo.model.IdValuePair
import api.myitmo.model.ResultResponse
import api.myitmo.model.personality.Personality
import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.SportLesson
import api.myitmo.model.sport.SportSchedule
import api.myitmo.model.sport.SportSignLimit
import api.myitmo.model.sport.TimeSlot
import api.myitmo.utils.TokenRefreshException
import com.google.gson.JsonParseException
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import retrofit2.Call
import java.io.IOException
import java.sql.SQLException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** [MyItmoGateway] on MyItmoApi 1.x; with [MyItmoService] and [ServiceCredentialStore], the only main code on it. */
@Service
class MyItmoApiGateway(private val myItmoService: MyItmoService) : MyItmoGateway {

    override fun sportTimeSlots(): MyItmoResult<List<MyItmoTimeSlot?>> =
        request({ api().sportTimeSlots }) { slots: List<TimeSlot?> -> slots.map { it?.let(::timeSlot) } }

    override fun sportFilters(): MyItmoResult<MyItmoSportFilters> = request({ api().sportFilters }) { filters: SportFilters ->
        MyItmoSportFilters(
            buildings = entries(filters.buildingId),
            sections = entries(filters.sectionId),
            teachers = entries(filters.teacherIsu),
        )
    }

    override fun sportSchedule(from: LocalDate, to: LocalDate): MyItmoResult<List<MyItmoSportLesson?>> =
        request({ api().getSportSchedule(from, to, null, null, null) }) { days: List<SportSchedule> ->
            days.flatMap { day -> day.lessons.orEmpty().map { lesson: SportLesson? -> lesson?.let(::lesson) } }
        }

    override fun sportSignLimits(): MyItmoResult<Map<Long, MyItmoSportSignLimit>> =
        request({ api().sportSignLimits }) { limits: Map<Long, Map<Long, SportSignLimit>> ->
            limits.flatMap { it.value.entries }.associate { it.key to MyItmoSportSignLimit(it.value.limit, it.value.available) }
        }

    override fun personality(isu: Int): MyItmoResult<MyItmoPersonality> = request({
        api().getPersonality(isu).also { it.timeout().timeout(PERSONALITY_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
    }) { profile: Personality ->
        MyItmoPersonality(
            isu = profile.isu,
            fio = profile.fio,
            education = profile.education?.map { entry -> entry?.let { MyItmoEducation(it.group, it.course, it.facultyName) } },
        )
    }

    private fun api(): MyItmoApi = myItmoService.myItmo.api

    private fun <W : Any, T> request(call: () -> Call<out ResultResponse<out W>>, map: (W) -> T): MyItmoResult<T> {
        val response = try {
            call().execute()
        } catch (error: Exception) {
            return failure(error)
        }
        if (!response.isSuccessful) return MyItmoResult.HttpStatus(response.code())
        val body = response.body() ?: return MyItmoResult.InvalidEnvelope
        if (body.errorCode != 0) return MyItmoResult.InvalidEnvelope
        val result = body.result ?: return MyItmoResult.InvalidEnvelope
        return MyItmoResult.Success(map(result))
    }

    /** Walks the cause chain; a failure that is Backend's own, or unknown, is thrown to the caller as it is. */
    private fun failure(error: Exception): MyItmoResult.Failure {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES).toList()
        return when {
            // A token refresh that could not store the rotated tokens failed in Backend, not in MyITMO.
            causes.any { it is DataAccessException || it is SQLException || it is PersistenceException || it is TransactionException } ->
                throw error

            causes.any { it is TokenRefreshException } -> MyItmoResult.CredentialRefreshFailed(error)

            causes.any { it is IOException } -> MyItmoResult.TransportFailed(error)

            causes.any { it is JsonParseException } -> MyItmoResult.MalformedBody(error)

            else -> throw error
        }
    }

    private fun timeSlot(slot: TimeSlot) = MyItmoTimeSlot(slot.id, slot.timeStart, slot.timeEnd)

    private fun entries(pairs: List<IdValuePair?>?): List<MyItmoCatalogEntry?> =
        pairs.orEmpty().map { pair -> pair?.let { MyItmoCatalogEntry(it.id, it.value) } }

    private fun lesson(row: SportLesson) = MyItmoSportLesson(
        id = row.id,
        date = row.date,
        dateEnd = row.dateEnd,
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

    private companion object {
        const val MAX_CAUSES = 10

        /** Directory lookups serve a user's request. */
        const val PERSONALITY_TIMEOUT_SECONDS = 3L
    }
}
