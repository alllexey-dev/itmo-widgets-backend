package dev.alllexey.itmowidgets.backend.feature.users.service

import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoEducation
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoPersonality
import dev.alllexey.itmowidgets.backend.feature.credentials.service.MyItmoResult
import dev.alllexey.itmowidgets.backend.testing.FakeMyItmoGateway
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.io.IOException
import kotlin.test.*

class MyItmoStudyGroupsSourceTest {
    private val gateway = FakeMyItmoGateway()
    private val source = MyItmoStudyGroupsSource(gateway)

    @Test fun `typed personality education is normalized without selecting the maximum course`() {
        val first = education(" NEW ", "2", " Synthetic faculty ")
        val second = education("PARALLEL", "1", "Other faculty")
        gateway.personality = {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            MyItmoResult.Success(profile(listOf(first, second, first)))
        }
        assertEquals(
            listOf(
                OfficialStudyGroup("NEW", 2, "Synthetic faculty"),
                OfficialStudyGroup("PARALLEL", 1, "Other faculty"),
            ),
            source.load(100001),
        )
        assertEquals(listOf("personality 100001"), gateway.calls)
    }

    @Test fun `empty education succeeds but missing malformed or another persons education fails`() {
        gateway.personality = { MyItmoResult.Success(profile(emptyList())) }
        assertEquals(emptyList(), source.load(100001))
        val cases = listOf(
            MyItmoResult.Success(profile(null)),
            MyItmoResult.Success(profile(listOf(null))),
            MyItmoResult.Success(profile(listOf(education("", "2", "Faculty")))),
            MyItmoResult.Success(profile(listOf(education("NEW", "unknown", "Faculty")))),
            MyItmoResult.Success(profile(listOf(education("NEW", "0", "Faculty")))),
            MyItmoResult.Success(profile(listOf(education("NEW", "2", "")))),
            MyItmoResult.Success(profile(listOf(education("NEW", "2", "Faculty")), isu = 100002)),
            MyItmoResult.InvalidEnvelope,
            MyItmoResult.HttpStatus(404),
            MyItmoResult.HttpStatus(401),
        )
        for (answer in cases) {
            gateway.personality = { answer }
            assertFalse(assertFailsWith<StudyGroupsUnavailable> { source.load(100001) }.temporary)
        }
    }

    @Test fun `outages and thrown failures have safe messages and only they pause other users`() {
        val cases = listOf<() -> MyItmoResult<MyItmoPersonality>>(
            { MyItmoResult.HttpStatus(429) },
            { MyItmoResult.HttpStatus(503) },
            { MyItmoResult.TransportFailed(IOException("synthetic private response")) },
            { MyItmoResult.CredentialRefreshFailed(IllegalStateException("synthetic private response")) },
            { MyItmoResult.MalformedBody(IllegalStateException("synthetic private response")) },
            { throw IllegalStateException("synthetic private response") },
        )
        for (answer in cases) {
            gateway.personality = { answer() }
            val error = assertFailsWith<StudyGroupsUnavailable> { source.load(100001) }
            assertTrue(error.temporary)
            assertEquals("Study groups unavailable", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun `network calls cannot run inside an active transaction`() {
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            assertFailsWith<IllegalStateException> { source.load(100001) }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        assertEquals(emptyList(), gateway.calls)
    }

    private fun profile(groups: List<MyItmoEducation?>?, isu: Long = 100001) = MyItmoPersonality(isu, "Synthetic Person", groups)

    private fun education(groupName: String, courseNumber: String, faculty: String) = MyItmoEducation(groupName, courseNumber, faculty)
}
