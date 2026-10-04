package dev.alllexey.itmowidgets.backend.feature.credentials.service

import api.myitmo.MyItmo
import api.myitmo.MyItmoApi
import api.myitmo.model.IdValuePair
import api.myitmo.model.ResultResponse
import api.myitmo.model.personality.Education
import api.myitmo.model.personality.Personality
import api.myitmo.model.sport.SportFilters
import api.myitmo.model.sport.SportSchedule
import api.myitmo.model.sport.SportSignLimit
import api.myitmo.model.sport.TimeSlot
import api.myitmo.utils.TokenRefreshException
import com.google.gson.JsonSyntaxException
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito.RETURNS_DEFAULTS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import retrofit2.Call
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import api.myitmo.model.sport.SportLesson as ApiSportLesson

class MyItmoApiGatewayTest {
    private val api = mock(MyItmoApi::class.java)
    private val service = MyItmoService(mock(ServiceCredentialStore::class.java), MyItmoConfig()).apply {
        myItmo = MyItmo().apply { api = this@MyItmoApiGatewayTest.api }
    }
    private val gateway = MyItmoApiGateway(service)
    private val from = LocalDate.parse("2026-09-09")
    private val to = from.plusDays(21)

    @Test
    fun `schedule flattens the days in order and keeps null rows for the caller to count`() {
        val start = OffsetDateTime.parse("2026-09-10T10:00:00+03:00")
        val first = ApiSportLesson().apply {
            id = 101
            date = start
            dateEnd = start.plusHours(1)
            sectionId = 1
            sectionName = " Section "
            sectionLevel = 2
            lessonLevel = 3
            typeId = 4
            buildingId = 5
            roomId = 6
            roomName = "Room"
            available = 7
            timeSlotId = 8
            timeSlotStart = "10:00"
            timeSlotEnd = "11:30"
            teacherIsu = 9
            teacherFio = "Teacher"
        }
        val days = listOf(
            SportSchedule().apply { lessons = rows(first, null) },
            SportSchedule(),
            SportSchedule().apply { lessons = listOf(ApiSportLesson().apply { id = 102 }) },
        )
        schedule(Response.success(envelope(days)))

        assertEquals(
            MyItmoResult.Success(
                listOf(
                    MyItmoSportLesson(
                        id = 101, date = start, dateEnd = start.plusHours(1), sectionId = 1, sectionName = " Section ",
                        sectionLevel = 2, lessonLevel = 3, typeId = 4, buildingId = 5, roomId = 6, roomName = "Room",
                        available = 7, timeSlotId = 8, timeSlotStart = "10:00", timeSlotEnd = "11:30", teacherIsu = 9,
                        teacherFio = "Teacher",
                    ),
                    null,
                    MyItmoSportLesson(id = 102),
                ),
            ),
            gateway.sportSchedule(from, to),
        )
    }

    @Test
    fun `a valid empty result is a success`() {
        schedule(Response.success(envelope(emptyList())))

        assertEquals(MyItmoResult.Success(emptyList()), gateway.sportSchedule(from, to))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 401, 503])
    fun `an error envelope is never accepted even when its result is an empty list`(errorCode: Int) {
        schedule(
            Response.success(
                envelope(emptyList<SportSchedule>()).apply {
                    this.errorCode = errorCode
                    errorMessage = SECRET
                },
            ),
        )

        assertSame(MyItmoResult.InvalidEnvelope, gateway.sportSchedule(from, to))
    }

    @Test
    fun `a missing body or result is an invalid envelope, not an empty catalog`() {
        schedule(Response.success(null))
        assertSame(MyItmoResult.InvalidEnvelope, gateway.sportSchedule(from, to))

        schedule(Response.success(ResultResponse()))
        assertSame(MyItmoResult.InvalidEnvelope, gateway.sportSchedule(from, to))
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 404, 429, 503])
    fun `an HTTP status keeps only its code and tells credential rejections and outages apart`(status: Int) {
        schedule(Response.error(status, SECRET.toResponseBody()))

        val failure = assertIs<MyItmoResult.HttpStatus>(gateway.sportSchedule(from, to))

        assertEquals(status, failure.status)
        assertEquals(if (status == 401 || status == 403) MyItmoFailureKind.AUTH else MyItmoFailureKind.HTTP, failure.kind)
        assertEquals(status == 429 || status == 503, failure.temporary)
        assertNull(failure.cause)
    }

    @Test
    fun `thrown upstream failures are classified with their cause`() {
        val refresh = TokenRefreshException(SECRET)
        val wrappedRefresh = IOException(SECRET, TokenRefreshException(SECRET))
        val transport = IOException(SECRET)
        val mapping = JsonSyntaxException(SECRET)

        assertClassified<MyItmoResult.CredentialRefreshFailed>(refresh, MyItmoFailureKind.AUTH)
        assertClassified<MyItmoResult.CredentialRefreshFailed>(wrappedRefresh, MyItmoFailureKind.AUTH)
        assertClassified<MyItmoResult.TransportFailed>(transport, MyItmoFailureKind.NETWORK)
        assertClassified<MyItmoResult.MalformedBody>(mapping, MyItmoFailureKind.MAPPING)
    }

    @Test
    fun `Backend's own failures are thrown as they are, even inside a token refresh`() {
        val persistence = DataIntegrityViolationException(SECRET)
        val wrappedPersistence = TokenRefreshException(SECRET, DataIntegrityViolationException(SECRET))
        val internal = IllegalStateException(SECRET)

        for (error in listOf(persistence, wrappedPersistence, internal)) {
            failingSchedule(error)
            assertSame(error, assertFailsWith<Exception> { gateway.sportSchedule(from, to) })
        }
    }

    @Test
    fun `a failure while building the request is classified like one while executing it`() {
        `when`(api.getSportSchedule(from, to, null, null, null)).thenThrow(TokenRefreshException(SECRET))

        assertIs<MyItmoResult.CredentialRefreshFailed>(gateway.sportSchedule(from, to))
    }

    @Test
    fun `dictionaries keep null rows and turn missing filter lists into empty ones`() {
        val slot = TimeSlot().apply {
            id = 1
            timeStart = "08:20"
            timeEnd = null
        }
        `when`(api.sportTimeSlots).thenReturn(call(Response.success(envelope(rows(slot, null)))))
        val building = IdValuePair().apply {
            id = 2
            value = " Building "
        }
        val filters = SportFilters().apply { buildingId = rows(null, building) }
        `when`(api.sportFilters).thenReturn(call(Response.success(envelope(filters))))

        assertEquals(MyItmoResult.Success(listOf(MyItmoTimeSlot(1, "08:20", null), null)), gateway.sportTimeSlots())
        assertEquals(
            MyItmoResult.Success(MyItmoSportFilters(buildings = listOf(null, MyItmoCatalogEntry(2, " Building ")))),
            gateway.sportFilters(),
        )
    }

    @Test
    fun `sign limits are keyed by lesson ID across the groups`() {
        val limits = hashMapOf(
            1L to hashMapOf(101L to limit(20, 0)),
            2L to hashMapOf(102L to limit(10, 3)),
        )
        `when`(api.sportSignLimits).thenReturn(call(Response.success(envelope(limits))))

        assertEquals(
            MyItmoResult.Success(mapOf(101L to MyItmoSportSignLimit(20, 0), 102L to MyItmoSportSignLimit(10, 3))),
            gateway.sportSignLimits(),
        )
    }

    @Test
    fun `personality reads the name and education within three seconds`() {
        val timeout = Timeout()
        val profile = Personality().apply {
            isu = 100001
            fio = " Synthetic Person "
            education = rows(
                Education().apply {
                    group = "NEW"
                    course = "2"
                    facultyName = "Faculty"
                },
                null,
            )
        }
        val call = call(Response.success(envelope(profile)), timeout)
        `when`(api.getPersonality(100001)).thenReturn(call)

        assertEquals(
            MyItmoResult.Success(MyItmoPersonality(100001, " Synthetic Person ", listOf(MyItmoEducation("NEW", "2", "Faculty"), null))),
            gateway.personality(100001),
        )
        assertEquals(TimeUnit.SECONDS.toNanos(3), timeout.timeoutNanos())
    }

    @Test
    fun `a personality without education keeps it missing`() {
        val profile = Personality().apply { isu = 100001 }
        `when`(api.getPersonality(100001)).thenReturn(call(Response.success(envelope(profile))))

        assertEquals(MyItmoResult.Success(MyItmoPersonality(100001, null, null)), gateway.personality(100001))
    }

    private inline fun <reified F : MyItmoResult.Failure> assertClassified(error: Exception, kind: MyItmoFailureKind) {
        failingSchedule(error)
        val failure = assertIs<F>(gateway.sportSchedule(from, to))
        assertEquals(kind, failure.kind)
        assertTrue(failure.temporary)
        assertSame(error, failure.cause)
    }

    private fun failingSchedule(error: Exception) {
        val call = call(Response.success(envelope(emptyList<SportSchedule>())))
        `when`(call.execute()).thenThrow(error)
        `when`(api.getSportSchedule(from, to, null, null, null)).thenReturn(call)
    }

    private fun schedule(response: Response<ResultResponse<List<SportSchedule>>>) {
        `when`(api.getSportSchedule(from, to, null, null, null)).thenReturn(call(response))
    }

    private fun limit(limit: Int, available: Int) = SportSignLimit().apply {
        this.limit = limit
        this.available = available
    }

    private fun <T> envelope(result: T): ResultResponse<T> = ResultResponse<T>().apply { this.result = result }

    /** Models the null elements Gson may put into a Java list, which Kotlin's list builders do not allow. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> rows(vararg rows: T?): List<T> = rows.toList() as List<T>

    @Suppress("UNCHECKED_CAST")
    private fun <T> call(response: Response<T>, timeout: Timeout = Timeout()): Call<T> = mock(Call::class.java) { invocation ->
        when (invocation.method.name) {
            "execute" -> response
            "timeout" -> timeout
            else -> RETURNS_DEFAULTS.answer(invocation)
        }
    } as Call<T>

    private companion object {
        const val SECRET = "synthetic-upstream-private-response"
    }
}
