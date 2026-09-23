package dev.alllexey.itmowidgets.backend.model

import dev.alllexey.itmowidgets.backend.exceptions.InvalidRequestDataException
import org.junit.jupiter.api.Test
import kotlin.test.*

class ModerationPolicyTest {
    private val target = ModerationTargetType.SUBJECT_RESOURCE
    @Test
    fun `missing and unknown keys preserve defaults and round trip uses valid database keys`() {
        assertEquals(ModerationPolicy(), ModerationPolicy.fromKeys(target, emptyMap()))
        assertEquals(ModerationPolicy(), ModerationPolicy.fromKeys(target, mapOf("SUBJECT_RESOURCE.future" to "99")))
        val policy = ModerationPolicy(false, 7, -5, 9, 20)
        assertTrue(policy.toKeys(target).keys.all { it.matches(Regex("^[A-Z_]+\\.[a-z_]+$")) })
        assertEquals(policy, ModerationPolicy.fromKeys(target, policy.toKeys(target)))
    }
    @Test
    fun `invalid thresholds and limits are rejected`() {
        for (policy in listOf(ModerationPolicy(reportThreshold = 0), ModerationPolicy(voteThreshold = 0),
            ModerationPolicy(dailySubmissionLimit = 0), ModerationPolicy(dailyReportLimit = 0))) {
            assertFailsWith<InvalidRequestDataException> { policy.validate() }
        }
    }
}
