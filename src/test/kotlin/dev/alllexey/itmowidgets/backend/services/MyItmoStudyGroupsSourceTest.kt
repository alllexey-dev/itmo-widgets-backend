package dev.alllexey.itmowidgets.backend.services

import api.myitmo.MyItmo
import api.myitmo.MyItmoApi
import api.myitmo.model.ResultResponse
import api.myitmo.model.personality.Education
import api.myitmo.model.personality.Personality
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.transaction.support.TransactionSynchronizationManager
import retrofit2.Call
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MyItmoStudyGroupsSourceTest {
    private val api = mock(MyItmoApi::class.java)
    private val myItmo = MyItmo().apply { this.api = this@MyItmoStudyGroupsSourceTest.api }
    private val service = mock(MyItmoService::class.java)
    @Suppress("UNCHECKED_CAST")
    private val call = mock(Call::class.java) as Call<ResultResponse<Personality>>
    private val timeout = Timeout()
    private val source = MyItmoStudyGroupsSource(service)

    @BeforeEach fun fixture() {
        `when`(service.myItmo).thenReturn(myItmo)
        `when`(api.getPersonality(100001)).thenReturn(call)
        `when`(call.timeout()).thenReturn(timeout)
    }

    @Test fun `typed personality education is normalized without selecting the maximum course`() {
        val first = education(" NEW ", "2", " Synthetic faculty ")
        val second = education("PARALLEL", "1", "Other faculty")
        `when`(call.execute()).thenAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            Response.success(body(listOf(first, second, first)))
        }
        assertEquals(listOf(OfficialStudyGroup("NEW", 2, "Synthetic faculty"),
            OfficialStudyGroup("PARALLEL", 1, "Other faculty")), source.load(100001))
        assertEquals(TimeUnit.SECONDS.toNanos(3), timeout.timeoutNanos())
        verify(api).getPersonality(100001)
    }

    @Test fun `empty education succeeds but missing malformed or another persons education fails`() {
        `when`(call.execute()).thenReturn(Response.success(body(emptyList())))
        assertEquals(emptyList(), source.load(100001))
        val cases = listOf(
            body(null), body(listOf(education("", "2", "Faculty"))),
            body(listOf(education("NEW", "unknown", "Faculty"))),
            body(listOf(education("NEW", "0", "Faculty"))),
            body(listOf(education("NEW", "2", ""))),
            body(listOf(education("NEW", "2", "Faculty"))).apply { result.isu = 100002 },
            body(emptyList()).apply { errorCode = 27 },
            ResultResponse<Personality>(),
        )
        for (body in cases) {
            `when`(call.execute()).thenReturn(Response.success(body))
            assertFailsWith<StudyGroupsUnavailable> { source.load(100001) }
        }
    }

    @Test fun `HTTP and transport failures have safe messages and only outages pause other users`() {
        for (code in listOf(404, 429, 503)) {
            `when`(call.execute()).thenReturn(Response.error(code, "synthetic upstream content".toResponseBody()))
            val error = assertFailsWith<StudyGroupsUnavailable> { source.load(100001) }
            assertEquals(code != 404, error.temporary)
            assertEquals("Study groups unavailable", error.message)
            assertNull(error.cause)
        }
        `when`(call.execute()).thenThrow(IOException("synthetic private response"))
        assertTrue(assertFailsWith<StudyGroupsUnavailable> { source.load(100001) }.temporary)
    }

    @Test fun `network calls cannot run inside an active transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try { assertFailsWith<IllegalStateException> { source.load(100001) } }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false) }
        verifyNoInteractions(api, call)
    }

    private fun body(groups: List<Education>?): ResultResponse<Personality> = ResultResponse<Personality>().apply {
        result = Personality().apply { isu = 100001; education = groups }
    }
    private fun education(groupName: String, courseNumber: String, faculty: String) = Education().apply {
        group = groupName; course = courseNumber; facultyName = faculty
    }
}
