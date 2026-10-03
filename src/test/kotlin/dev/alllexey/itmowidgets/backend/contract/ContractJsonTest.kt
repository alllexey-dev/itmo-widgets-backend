package dev.alllexey.itmowidgets.backend.contract

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ContractJsonTest {
    private fun differences(expected: String, actual: String) =
        ContractJson.differences(ContractJson.parse(expected), ContractJson.parse(actual))

    @Test
    fun `key order is ignored`() {
        assertEquals(emptyList(), differences("""{"a":1,"b":{"c":2,"d":3}}""", """{"b":{"d":3,"c":2},"a":1}"""))
    }

    @Test
    fun `date-times are equal by instant and offset`() {
        assertEquals(emptyList(), differences("""{"t":"2026-10-05T10:00:00+03:00"}""", """{"t":"2026-10-05T10:00+03:00"}"""))
        assertEquals(emptyList(), differences("""{"t":"2026-10-05T09:00:00Z"}""", """{"t":"2026-10-05T09:00:00.000Z"}"""))
        assertEquals(
            listOf("$.t: 2026-10-05T10:00:00+03:00 expected, 2026-10-05T07:00:00Z found"),
            differences("""{"t":"2026-10-05T10:00:00+03:00"}""", """{"t":"2026-10-05T07:00:00Z"}"""),
        )
    }

    @Test
    fun `everything else is exact`() {
        assertEquals(listOf("$.a: missing", "$.b: unexpected"), differences("""{"a":null}""", """{"b":null}"""))
        assertEquals(listOf("$.n: 1 expected, 1.0 found"), differences("""{"n":1}""", """{"n":1.0}"""))
        assertEquals(listOf("$.s: 10:00 expected, 10:00:00 found"), differences("""{"s":"10:00"}""", """{"s":"10:00:00"}"""))
        assertEquals(listOf("$.v: null expected, \"x\" found"), differences("""{"v":null}""", """{"v":"x"}"""))
        assertEquals(listOf("$[1]: true expected, false found"), differences("""[1,true]""", """[1,false]"""))
        assertEquals(listOf("$: 1 elements expected, 2 found"), differences("""[1]""", """[1,1]"""))
    }

    @Test
    fun `rendering is stable`() {
        val text = "{\n  \"a\": [\n    1,\n    {}\n  ],\n  \"b\": []\n}\n"
        assertEquals(text, ContractJson.render(ContractJson.parse(text)))
    }
}
