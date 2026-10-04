package dev.alllexey.itmowidgets.backend.contract

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `index.json` is generated from [ContractCatalog]; a rebase conflict in it is solved by re-recording. */
class ContractIndexTest {
    @Test
    fun `catalog covers the released clients`() {
        val core = ContractCatalog.routes.filter { it.minCore != ContractCatalog.NOT_IN_CORE }
        assertEquals(62, core.size, "Core 1.7.0: 56 ItmoWidgetsApi + 6 ItmoWidgetsModerationApi methods")
        assertEquals(listOf("appVersionInfoIos"), (ContractCatalog.routes - core.toSet()).map { it.id })
        assertEquals(40, ContractCatalog.routes.count { it.minCore == ContractCatalog.CORE_120 }, "Core 1.2.0 calls 40 routes")
        assertEquals(3, ContractCatalog.fcm.size)
        val ids = ContractCatalog.routes.map { it.id } + ContractCatalog.requests.map { it.id } + ContractCatalog.fcm.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "Fixture ids are unique")
        assertEquals(
            ContractCatalog.requests.map { it.id }.toSet(),
            ContractCatalog.routes.mapNotNull { it.request }.toSet(),
            "Every request type is sent by a route and every route body has a request fixture",
        )
        assertEquals(ContractCatalog.requests.map { it.id }.toSet(), ContractRequests.samples.keys)
        for (route in ContractCatalog.routes.filter { it.request != null }) {
            val request = ContractCatalog.requests.single { it.id == route.request }
            assertTrue(request.minCore <= route.minCore, "${route.id} sends ${request.id} since ${request.minCore}")
        }
    }

    @Test
    fun `index lists every fixture and nothing else`() {
        val index = ContractCatalog.index()
        ContractFiles.check("index.json", index)
        val indexed = index.map { it["file"].textValue() }.sorted()
        val present = (ContractFiles.list("http") + ContractFiles.list("requests") + ContractFiles.list("fcm")).sorted()
        assertEquals(emptyList(), present - indexed.toSet(), "Fixtures of a removed route or type; delete them")
        // A recording run may write the other fixtures after this test.
        if (!ContractFiles.recording) assertEquals(indexed, present)
    }
}
