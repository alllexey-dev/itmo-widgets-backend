package dev.alllexey.itmowidgets.backend.feature.credentials.service

import dev.alllexey.itmoapi.itmoid.TokenSet
import dev.alllexey.itmowidgets.backend.feature.credentials.model.MyItmoTokenSnapshot
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationContext
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.dao.DataIntegrityViolationException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaInstant

class MyItmoApiGatewayTest {
    private val store = mock(ServiceCredentialStore::class.java)
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private var myItmo: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("No MyITMO answer") }
    private var tokenEndpoint: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json(ROTATED_TOKENS) }
    private val engine = MockEngine { request ->
        requests.add(request)
        if (request.url.host == "id.itmo.ru") tokenEndpoint(request) else myItmo(request)
    }
    private val gateway = MyItmoApiGateway(
        MyItmoService(store, MyItmoConfig(), engine, CLOCK).apply {
            onApplicationEvent(ContextRefreshedEvent(mock(ApplicationContext::class.java)))
        },
    )
    private val from = LocalDate.parse("2026-09-09")
    private val to = from.plusDays(21)

    /** What [store] holds: a refresh writes it, every read returns it, as the real store does. */
    private var stored = session(accessExpiresAt = NOW.plus(Duration.ofHours(1)))

    init {
        `when`(store.myItmoSnapshot()).thenAnswer { stored }
        doAnswer { invocation ->
            val tokens = invocation.getArgument<TokenSet>(0)
            stored = MyItmoTokenSnapshot(
                tokens.accessToken,
                tokens.accessExpiresAt.toEpochMilliseconds(),
                tokens.refreshToken,
                tokens.refreshExpiresAt.toEpochMilliseconds(),
                tokens.idToken,
            )
        }.`when`(store).rotateMyItmo(any(TokenSet::class.java) ?: ANY_TOKENS)
    }

    @Test
    fun `schedule flattens the days in order and asks for the requested range with the stored token`() {
        myItmo = {
            json(
                """{"error_code":0,"result":[
                  {"date":"2026-09-10","lessons":[$FIRST_LESSON]},
                  {"date":"2026-09-11","lessons":null},
                  {"date":"2026-09-12","lessons":[{"id":102,"date":"2026-09-12T10:00:00+03:00"}]}
                ]}""",
            )
        }

        val lessons = assertIs<MyItmoResult.Success<List<MyItmoSportLesson?>>>(gateway.sportSchedule(from, to)).value

        // The same instants as the wire's +03:00, in UTC; the catalog compares instants.
        val start = OffsetDateTime.parse("2026-09-10T07:00:00Z")
        assertEquals(
            MyItmoSportLesson(
                id = 101, date = start, dateEnd = start.plusHours(1), sectionId = 1, sectionName = " Section ",
                sectionLevel = 2, lessonLevel = 3, typeId = 4, buildingId = 5, roomId = 6, roomName = "Room",
                available = 7, timeSlotId = 8, timeSlotStart = "10:00", timeSlotEnd = "11:30", teacherIsu = 9,
                teacherFio = "Teacher",
            ),
            lessons[0],
        )
        assertEquals(listOf(101L, 102L), lessons.map { it!!.id })
        val request = requests.single()
        assertEquals("/api/sport/sign/schedule", request.url.encodedPath)
        assertEquals("2026-09-09", request.url.parameters["date_start"])
        assertEquals("2026-09-30", request.url.parameters["date_end"])
        assertEquals("Bearer synthetic-access", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a lesson without its dates keeps them missing instead of the epoch`() {
        myItmo = { json("""{"error_code":0,"result":[{"date":"2026-09-10","lessons":[{"id":103}]}]}""") }

        val lesson = assertIs<MyItmoResult.Success<List<MyItmoSportLesson?>>>(gateway.sportSchedule(from, to)).value.single()!!

        assertNull(lesson.date)
        assertNull(lesson.dateEnd)
    }

    @Test
    fun `a valid empty result is a success`() {
        myItmo = { json("""{"error_code":0,"result":[]}""") }

        assertEquals(MyItmoResult.Success(emptyList()), gateway.sportSchedule(from, to))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 401, 503])
    fun `an error envelope is never accepted even when its result is an empty list`(errorCode: Int) {
        myItmo = { json("""{"error_code":$errorCode,"error_message":"$SECRET","result":[]}""") }

        assertSame(MyItmoResult.InvalidEnvelope, gateway.sportSchedule(from, to))
    }

    @Test
    fun `a missing result is an invalid envelope, not an empty catalog`() {
        myItmo = { json("""{"error_code":0}""") }

        assertSame(MyItmoResult.InvalidEnvelope, gateway.sportSchedule(from, to))
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 404, 429, 503])
    fun `an HTTP status keeps only its code and tells credential rejections and outages apart`(status: Int) {
        myItmo = { respond(SECRET, HttpStatusCode.fromValue(status)) }

        val failure = assertIs<MyItmoResult.HttpStatus>(gateway.sportSchedule(from, to))

        assertEquals(status, failure.status)
        assertEquals(if (status == 401 || status == 403) MyItmoFailureKind.AUTH else MyItmoFailureKind.HTTP, failure.kind)
        assertEquals(status == 429 || status == 503, failure.temporary)
        assertNull(failure.cause)
    }

    @Test
    fun `an error envelope with an error status stays that status`() {
        myItmo = { json("""{"error_code":100,"error_message":"$SECRET","result":null}""", HttpStatusCode.BadRequest) }

        assertEquals(400, assertIs<MyItmoResult.HttpStatus>(gateway.personality(100001)).status)
    }

    @Test
    fun `transport and mapping failures are temporary and keep a cause without the answer`() {
        myItmo = { throw IOException(SECRET) }
        assertClassified<MyItmoResult.TransportFailed>(MyItmoFailureKind.NETWORK)

        myItmo = { json("""{"error_code":0,"result":[{"date":"$SECRET"}]}""") }
        assertClassified<MyItmoResult.MalformedBody>(MyItmoFailureKind.MAPPING)
    }

    @Test
    fun `a seeded refresh token is refreshed before the first request and the whole rotation is stored`() {
        stored = MyItmoTokenSnapshot(null, 0, "synthetic-seed", inDays(30), null)
        myItmo = { json("""{"error_code":0,"result":[]}""") }

        assertEquals(MyItmoResult.Success(emptyList()), gateway.sportSchedule(from, to))

        val refresh = requests.first()
        assertEquals("id.itmo.ru", refresh.url.host)
        assertEquals("synthetic-seed", (refresh.body as FormDataContent).formData["refresh_token"])
        assertEquals("Bearer synthetic-rotated-access", requests.last().headers[HttpHeaders.Authorization])
        val stored = ArgumentCaptor.forClass(TokenSet::class.java)
        verify(store).rotateMyItmo(stored.capture() ?: ANY_TOKENS)
        assertEquals("synthetic-rotated-refresh", stored.value.refreshToken)
        assertEquals(NOW.plusSeconds(3600), stored.value.accessExpiresAt.toJavaInstant())
        assertEquals(NOW.plusSeconds(86400), stored.value.refreshExpiresAt.toJavaInstant())
    }

    @Test
    fun `a rejected refresh is a credential failure and never touches the stored refresh token`() {
        stored = session(accessExpiresAt = NOW.minusSeconds(1))
        tokenEndpoint = { json("""{"error":"invalid_grant","error_description":"$SECRET"}""", HttpStatusCode.BadRequest) }

        val failure = assertIs<MyItmoResult.CredentialRefreshFailed>(gateway.sportSchedule(from, to))

        assertEquals(MyItmoFailureKind.AUTH, failure.kind)
        assertTrue(failure.temporary)
        assertTrue(requests.none { it.url.host == "my.itmo.ru" })
        verify(store, never()).rotateMyItmo(any(TokenSet::class.java) ?: ANY_TOKENS)
    }

    @Test
    fun `an expired refresh token is a credential failure without a refresh request`() {
        stored = MyItmoTokenSnapshot(null, 0, "synthetic-refresh", 0, null)

        assertIs<MyItmoResult.CredentialRefreshFailed>(gateway.sportSchedule(from, to))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Backend's own failures are thrown as they are, even inside a token refresh`() {
        stored = session(accessExpiresAt = NOW.minusSeconds(1))
        // Coroutines may rethrow a copy of the exception with a recovered stack trace, so the type and text identify it.
        doThrow(DataIntegrityViolationException(SECRET)).`when`(store).rotateMyItmo(any(TokenSet::class.java) ?: ANY_TOKENS)
        assertEquals(SECRET, assertFailsWith<DataIntegrityViolationException> { gateway.sportSchedule(from, to) }.message)

        `when`(store.myItmoSnapshot()).thenThrow(IllegalStateException(SECRET))
        assertEquals(SECRET, assertFailsWith<IllegalStateException> { gateway.sportSchedule(from, to) }.message)
    }

    @Test
    fun `dictionaries map every row and every filter list`() {
        myItmo = { request ->
            when (request.url.encodedPath) {
                "/api/sport/time_slots" -> json("""{"error_code":0,"result":[{"id":1,"time_start":"08:20","time_end":"09:50"}]}""")

                else -> json(
                    """{"error_code":0,"result":{"building_id":[{"id":2,"value":" Building "}],"section_id":[],
                    "teacher_isu":[{"id":3,"value":"Teacher"}]}}""",
                )
            }
        }

        assertEquals(MyItmoResult.Success(listOf(MyItmoTimeSlot(1, "08:20", "09:50"))), gateway.sportTimeSlots())
        assertEquals(
            MyItmoResult.Success(
                MyItmoSportFilters(
                    buildings = listOf(MyItmoCatalogEntry(2, " Building ")),
                    teachers = listOf(MyItmoCatalogEntry(3, "Teacher")),
                ),
            ),
            gateway.sportFilters(),
        )
    }

    @Test
    fun `sign limits are keyed by lesson ID across the groups`() {
        myItmo = {
            json("""{"error_code":0,"result":{"1":{"101":{"limit":20,"available":0}},"2":{"102":{"limit":10,"available":3}}}}""")
        }

        assertEquals(
            MyItmoResult.Success(mapOf(101L to MyItmoSportSignLimit(20, 0), 102L to MyItmoSportSignLimit(10, 3))),
            gateway.sportSignLimits(),
        )
    }

    @Test
    fun `personality reads the name and education`() {
        myItmo = {
            json(
                """{"error_code":0,"result":{"isu":100001,"fio":" Synthetic Person ","contacts":[],
                "education":[{"group":"NEW","course":"2","faculty_name":"Faculty"}]}}""",
            )
        }

        assertEquals(
            MyItmoResult.Success(MyItmoPersonality(100001, " Synthetic Person ", listOf(MyItmoEducation("NEW", "2", "Faculty")))),
            gateway.personality(100001),
        )
        assertEquals("/api/personalities/persons/100001", requests.single().url.encodedPath)
    }

    @Test
    fun `personality gives up after three seconds`() {
        myItmo = {
            delay(30.seconds)
            json("""{"error_code":0,"result":{"isu":100001}}""")
        }

        val started = System.nanoTime()
        val failure = assertIs<MyItmoResult.TransportFailed>(gateway.personality(100001))

        assertEquals(MyItmoFailureKind.NETWORK, failure.kind)
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(10).toNanos())
    }

    private inline fun <reified F : MyItmoResult.Failure> assertClassified(kind: MyItmoFailureKind) {
        val failure = assertIs<F>(gateway.sportSchedule(from, to))
        assertEquals(kind, failure.kind)
        assertTrue(failure.temporary)
        assertFalse(assertNotNull(failure.cause).toString().contains(SECRET))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun session(accessExpiresAt: Instant) =
        MyItmoTokenSnapshot("synthetic-access", accessExpiresAt.toEpochMilli(), "synthetic-refresh", inDays(30), "synthetic-id")

    private fun inDays(days: Long) = NOW.plus(Duration.ofDays(days)).toEpochMilli()

    private companion object {
        const val SECRET = "synthetic-upstream-private-response"
        val NOW: Instant = Instant.parse("2026-09-08T21:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneId.of("Europe/Moscow"))
        val ANY_TOKENS = TokenSet(
            "synthetic-any",
            kotlin.time.Instant.fromEpochMilliseconds(0),
            "synthetic-any",
            kotlin.time.Instant.fromEpochMilliseconds(0),
            "synthetic-any",
        )
        const val ROTATED_TOKENS = """{"access_token":"synthetic-rotated-access","expires_in":3600,
            "refresh_token":"synthetic-rotated-refresh","refresh_expires_in":86400,"id_token":"synthetic-rotated-id"}"""
        const val FIRST_LESSON = """{"id":101,"date":"2026-09-10T10:00:00+03:00","date_end":"2026-09-10T11:00:00+03:00",
            "section_id":1,"section_name":" Section ","section_level":2,"lesson_level":3,"type_id":4,"building_id":5,
            "room_id":6,"room_name":"Room","available":7,"time_slot_id":8,"time_slot_start":"10:00","time_slot_end":"11:30",
            "teacher_isu":9,"teacher_fio":"Teacher"}"""
    }
}
