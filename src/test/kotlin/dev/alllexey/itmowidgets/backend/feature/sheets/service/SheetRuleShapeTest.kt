package dev.alllexey.itmowidgets.backend.feature.sheets.service

import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SheetRuleShapeTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun `the documented rule passes`() {
        assertNull(reject(rule()))
    }

    @Test
    fun `an empty summary or explanation passes`() {
        assertNull(reject(rule().apply { put("summary", "") }))
        assertNull(reject(rule().apply { route(0).put("explanation", "") }))
    }

    @Test
    fun `an unknown or missing key rejects the whole rule`() {
        assertEquals("rule", reject(rule().apply { put("examples", "B4") }))
        assertEquals("rule", reject(rule().apply { remove("summary") }))
        assertEquals("route", reject(rule().apply { route(0).put("student", "Иванов") }))
        assertEquals("identity", reject(rule().apply { (route(0)["identity"][0] as ObjectNode).put("isu", "123456") }))
        assertEquals("calculation.number", reject(rule().apply { (calculation()["args"][1] as ObjectNode).put("cached", 7) }))
    }

    @Test
    fun `enums nodes and references must be the documented ones`() {
        for ((change, code) in listOf(
            case("schema_version") { it.put("schema_version", 2) },
            case("decision") { it.put("decision", "insufficient") },
            case("tab.role") { (it["tabs"][0] as ObjectNode).put("role", "students") },
            case("gid") { (it["tabs"][0] as ObjectNode).put("gid", "12a") },
            case("route.purpose") { it.route(0).put("purpose", "final") },
            case("route.kind") { it.route(0).put("kind", "insufficient") },
            case("route.review_required") { it.route(0).put("review_required", false) },
            case("route.blank_policy") { it.route(0).put("blank_policy", "zero") },
            case("route.id") { it.route(0).put("id", "r 1") },
            case("route.id") { it.route(1).put("id", "r1") },
            case("route.kind") { it.route(1).set("calculation", it.calculation().deepCopy()) },
            case("source.column") { (it.route(1)["source"] as ObjectNode).put("column", "R1") },
            case("identity.columns") { (it.route(0)["identity"][0] as ObjectNode).putNull("isu_column").putNull("name_column") },
            case("calculation.op") { (it.calculation()["args"][0] as ObjectNode).put("op", "formula") },
            case("calculation.column") { (it.calculation()["args"][0] as ObjectNode).put("column", "\$D\$4") },
            case("calculation.args") { it.calculation().put("op", "divide") },
            case("route.warning") { it.route(0).set("warnings", json.createArrayNode().add(" ")) },
        )) {
            assertEquals(code, reject(rule().apply(change)), code)
        }
    }

    @Test
    fun `size and depth are bounded`() {
        assertEquals("size", SheetRuleShape.reject(rule(), SheetRuleShape.MAX_JSON_CHARS + 1))
        var node: JsonNode = cell()
        repeat(20) { node = json.createObjectNode().put("op", "round").put("digits", 0).set("arg", node) }
        assertEquals("calculation.size", reject(rule().apply { route(0).set("calculation", node) }))
        val wide = json.createObjectNode().put("op", "sum").apply { putArray("args").apply { repeat(201) { add(cell()) } } }
        assertEquals("calculation.args", reject(rule().apply { route(0).set("calculation", wide) }))
    }

    private fun case(code: String, change: (ObjectNode) -> Unit) = change to code

    private fun reject(rule: JsonNode): String? = SheetRuleShape.reject(rule, json.writeValueAsString(rule).length)

    private fun ObjectNode.route(index: Int) = (this["routes"] as ArrayNode)[index] as ObjectNode

    private fun ObjectNode.calculation() = route(0)["calculation"] as ObjectNode

    private fun cell(): ObjectNode = json.createObjectNode().put("op", "cell").put("sheet", "Группа 1").put("gid", "0").put("column", "D")

    private fun rule(): ObjectNode = json.readTree(
        """
        {"schema_version":1,"decision":"mixed","summary":"Сумма баллов",
         "tabs":[{"sheet":"Группа 1","gid":"0","role":"active_roster"},{"sheet":"Шаблон","gid":null,"role":"template"}],
         "routes":[
          {"id":"r1","purpose":"current_accumulated","kind":"compute","sheet":"Группа 1","gid":"0",
           "identity":[{"sheet":"Группа 1","gid":"0","isu_column":"B","name_column":"C","first_student_row":4}],
           "source":null,
           "calculation":{"op":"sum","args":[{"op":"cell","sheet":"Группа 1","gid":"0","column":"D"},{"op":"number","value":0.5},
             {"op":"max","args":[{"op":"cell","sheet":"Группа 1","gid":null,"column":"AA"},{"op":"number","value":0}]},
             {"op":"round","arg":{"op":"subtract","args":[{"op":"number","value":10},{"op":"cell","sheet":"Группа 1","gid":"0","column":"E"}]},"digits":1}]},
           "blank_policy":"ignore_blank_inputs","explanation":"Сумма работ","review_required":true,"warnings":[]},
          {"id":"r2","purpose":"official_total","kind":"direct","sheet":"Группа 1","gid":"0",
           "identity":[{"sheet":"Группа 1","gid":"0","isu_column":null,"name_column":"C","first_student_row":4}],
           "source":{"sheet":"Группа 1","gid":"0","column":"R"},"calculation":null,
           "blank_policy":"preserve","explanation":"Итог преподавателя","review_required":true,"warnings":["Итог может быть 0"]}
         ]}
        """,
    ) as ObjectNode
}
