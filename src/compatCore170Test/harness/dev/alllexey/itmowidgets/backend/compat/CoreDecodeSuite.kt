package dev.alllexey.itmowidgets.backend.compat

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The golden fixtures read by one released Core, as installed apps read them. A red test is a
 * breaking change for that release (`docs/contracts/compatibility.md`), never a reason to
 * re-record. Fixtures newer than the release (`minCore`) are skipped.
 */
abstract class CoreDecodeSuite(version: String) {
    private val core = CoreVersion.parse(version)
    private val coreJar = "itmo-widgets-core-$version.jar"

    @Test
    fun `the suite runs the Core release it names`() {
        assertEquals(coreJar, ReleasedCore.jarName)
    }

    @Test
    fun `fixtures cover exactly what this release calls, sends and receives`() {
        val covered = ContractFixtures.entries.filter { it.minCore <= core }
        fun ids(kind: String) = covered.filter { it.kind == kind }.map { it.id }.sorted()
        assertEquals(ReleasedCore.routes.keys.sorted(), ids("http"), "Routes with minCore <= $core")
        assertEquals(ReleasedCore.requests.keys.sorted(), ids("request"), "Request bodies with minCore <= $core")
        assertEquals(ReleasedCore.fcmPayloads.keys.sorted(), ids("fcm"), "FCM types with minCore <= $core")
    }

    @TestFactory
    fun responses(): List<DynamicTest> = fixtureTests("http")

    @TestFactory
    fun requests(): List<DynamicTest> = fixtureTests("request")

    @TestFactory
    fun fcm(): List<DynamicTest> = fixtureTests("fcm")

    @Test
    fun `an unknown SharingVisibility is rejected`() = assertRejected("myPrivacySettings", "Unknown sharing visibility") {
        data(it).addProperty("scheduleVisibility", "CONTACTS")
    }

    @Test
    fun `an unknown RelationshipState is rejected`() =
        assertRejected("userProfile", "Unknown relationship state") { data(it).addProperty("relationship", "MUTED") }

    @Test
    fun `an unknown sport entry type is rejected`() = assertRejected("friendsSportBookings", "subtype named group") {
        polymorphicEntry(it).addProperty("type", "group")
    }

    @Test
    fun `an unknown QueueEntryStatus is caught as a null non-null property`() =
        assertRejected("mySportFreeSignEntries", "parameter status") { firstEntry(it).addProperty("status", "PAUSED") }

    @Test
    fun `version-info without note is rejected`() = assertRejected("appVersionInfo", "Missing note") { data(it).remove("note") }

    @Test
    fun `an unknown FriendshipEvent is rejected`() = assertRejected("FRIENDSHIP_EVENT_PAYLOAD", "Unknown friendship event") {
        it.getAsJsonObject("data").getAsJsonObject("payload").addProperty("event", "REQUEST_CANCELLED")
    }

    @Test
    fun `a renamed request field is rejected`() =
        assertRejected("RegisterDeviceRequest", "parameter fcmToken") { it.add("token", it.remove("fcmToken")) }

    private fun fixtureTests(kind: String): List<DynamicTest> = ContractFixtures.entries.filter { it.kind == kind }.map { entry ->
        DynamicTest.dynamicTest(entry.id) {
            assumeTrue(entry.minCore <= core) { "${entry.id} is newer than Core $core (minCore ${entry.minCore})" }
            val problems = CoreDecoding.check(entry, ContractFixtures.json(entry))
            if (problems.isNotEmpty()) {
                fail<Unit>(
                    "Core $core cannot read ${entry.file} as installed apps do:\n" +
                        problems.joinToString("\n") { "  $it" } +
                        "\nThis breaks released clients (docs/contracts/compatibility.md); never re-record to pass.",
                )
            }
        }
    }

    /** Strictness guard: a fixture mutated the way a breaking change would look fails for [reason]. */
    private fun assertRejected(id: String, reason: String, mutate: (JsonObject) -> Unit) {
        val entry = ContractFixtures.entry(id)
        assumeTrue(entry.minCore <= core) { "$id is newer than Core $core" }
        val fixture = ContractFixtures.json(entry).deepCopy().asJsonObject.also(mutate)
        val problems = CoreDecoding.check(entry, fixture)
        assertTrue(problems.any { reason in it }) {
            "Core $core did not reject the change to $id for \"$reason\": $problems"
        }
    }

    private fun data(response: JsonObject): JsonObject = response.getAsJsonObject("data")

    private fun firstEntry(response: JsonObject): JsonObject = response.getAsJsonArray("data")[0].asJsonObject

    /** An entry decoded through the `SportQueueEntry` interface, where the `type` property picks the class. */
    private fun polymorphicEntry(response: JsonObject): JsonObject = data(response).getAsJsonArray("bookings")
        .map { it.asJsonObject }
        .first { it["entry"].isJsonObject }
        .getAsJsonObject("entry")
}
