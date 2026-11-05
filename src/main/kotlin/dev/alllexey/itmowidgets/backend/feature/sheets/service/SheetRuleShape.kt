package dev.alllexey.itmowidgets.backend.feature.sheets.service

import tools.jackson.databind.JsonNode

/**
 * The shape check of an analyzer rule before it is stored (protocol version 1, docs/contracts/sheet-rules.md#rule).
 * Exactly the documented keys, enums and nodes, bounded sizes; anything else rejects the whole rule, so no example,
 * cell value, name or ISU can ride along. It checks shape only: the analyzer has checked the rule against the
 * workbook.
 */
object SheetRuleShape {
    const val MAX_JSON_CHARS = 64 * 1024

    private val TOP = setOf("schema_version", "decision", "summary", "tabs", "routes")
    private val TAB = setOf("sheet", "gid", "role")
    private val ROUTE = setOf(
        "id", "purpose", "kind", "sheet", "gid", "identity", "source", "calculation", "blank_policy", "explanation",
        "review_required", "warnings",
    )
    private val IDENTITY = setOf("sheet", "gid", "isu_column", "name_column", "first_student_row")
    private val SOURCE = setOf("sheet", "gid", "column")
    private val DECISIONS = setOf("direct", "compute", "mixed")
    private val ROLES = setOf("active_roster", "source", "rubric", "template", "archive", "empty", "auxiliary")
    private val PURPOSES = setOf("current_accumulated", "official_total")
    private val KINDS = setOf("direct", "compute")
    private val BLANK_POLICIES = setOf("preserve", "ignore_blank_inputs")
    private val LIST_OPS = setOf("sum", "multiply", "divide", "subtract", "min", "max")
    private val COLUMN = Regex("^[A-Z]{1,3}$")
    private val GID = Regex("^[0-9]{1,12}$")
    private val ROUTE_ID = Regex("^[A-Za-z0-9_-]{1,32}$")
    private const val MAX_TABS = 100
    private const val MAX_ROUTES = 100
    private const val MAX_IDENTITIES = 10
    private const val MAX_WARNINGS = 20
    private const val MAX_ARGS = 200
    private const val MAX_DEPTH = 20
    private const val MAX_NODES = 2000

    /** Null when [rule] has the documented shape, otherwise a short rejection code for logs. */
    fun reject(rule: JsonNode, jsonChars: Int): String? {
        if (jsonChars > MAX_JSON_CHARS) return "size"
        return try {
            top(rule)
            null
        } catch (rejection: Rejection) {
            rejection.code
        }
    }

    private fun top(rule: JsonNode) {
        keys(rule, TOP, "rule")
        require(rule["schema_version"].let { it.isIntegralNumber && it.intValue() == 1 }, "schema_version")
        oneOf(rule["decision"], DECISIONS, "decision")
        text(rule["summary"], 500, "summary")
        list(rule["tabs"], 1, MAX_TABS, "tabs").forEach { tab ->
            keys(tab, TAB, "tab")
            text(tab["sheet"], 200, "tab.sheet")
            gid(tab["gid"])
            oneOf(tab["role"], ROLES, "tab.role")
        }
        val routes = list(rule["routes"], 1, MAX_ROUTES, "routes")
        routes.forEach(::route)
        require(routes.map { it["id"].stringValue() }.toSet().size == routes.size, "route.id")
    }

    private fun route(route: JsonNode) {
        keys(route, ROUTE, "route")
        require(route["id"].let { it.isString && ROUTE_ID.matches(it.stringValue()) }, "route.id")
        oneOf(route["purpose"], PURPOSES, "route.purpose")
        val kind = oneOf(route["kind"], KINDS, "route.kind")
        text(route["sheet"], 200, "route.sheet")
        gid(route["gid"])
        list(route["identity"], 1, MAX_IDENTITIES, "identity").forEach { identity ->
            keys(identity, IDENTITY, "identity")
            text(identity["sheet"], 200, "identity.sheet")
            gid(identity["gid"])
            val isu = optionalColumn(identity["isu_column"], "identity.isu_column")
            val name = optionalColumn(identity["name_column"], "identity.name_column")
            require(isu || name, "identity.columns")
            require(identity["first_student_row"].let { it.isIntegralNumber && it.longValue() in 1..1_000_000 }, "identity.row")
        }
        val source = route["source"]
        val calculation = route["calculation"]
        if (kind == "direct") {
            require(source.isObject && calculation.isNull, "route.kind")
            keys(source, SOURCE, "source")
            text(source["sheet"], 200, "source.sheet")
            gid(source["gid"])
            column(source["column"], "source.column")
        } else {
            require(source.isNull && !calculation.isNull, "route.kind")
            node(calculation, depth = 1, Counter())
        }
        oneOf(route["blank_policy"], BLANK_POLICIES, "route.blank_policy")
        text(route["explanation"], 1000, "route.explanation")
        require(route["review_required"].let { it.isBoolean && it.booleanValue() }, "route.review_required")
        list(route["warnings"], 0, MAX_WARNINGS, "route.warnings").forEach { text(it, 500, "route.warning") }
    }

    private class Counter(var nodes: Int = 0)

    private fun node(node: JsonNode, depth: Int, counter: Counter) {
        require(depth <= MAX_DEPTH && ++counter.nodes <= MAX_NODES, "calculation.size")
        require(node.isObject, "calculation")
        when (val op = node["op"]?.takeIf { it.isString }?.stringValue()) {
            "cell" -> {
                keys(node, setOf("op", "sheet", "gid", "column"), "calculation.cell")
                text(node["sheet"], 200, "calculation.sheet")
                gid(node["gid"])
                column(node["column"], "calculation.column")
            }

            "number" -> {
                keys(node, setOf("op", "value"), "calculation.number")
                require(node["value"].let { it.isNumber && it.doubleValue().isFinite() }, "calculation.value")
            }

            "round" -> {
                keys(node, setOf("op", "arg", "digits"), "calculation.round")
                require(node["digits"].let { it.isIntegralNumber && it.intValue() in 0..6 }, "calculation.digits")
                node(node["arg"], depth + 1, counter)
            }

            in LIST_OPS -> {
                keys(node, setOf("op", "args"), "calculation.$op")
                val min = if (op == "divide" || op == "subtract") 2 else 1
                val max = if (op == "divide" || op == "subtract") 2 else MAX_ARGS
                list(node["args"], min, max, "calculation.args").forEach { node(it, depth + 1, counter) }
            }

            else -> throw Rejection("calculation.op")
        }
    }

    private fun keys(node: JsonNode?, allowed: Set<String>, code: String) {
        require(node != null && node.isObject && node.propertyNames().toSet() == allowed, code)
    }

    private fun text(node: JsonNode?, maxChars: Int, code: String) {
        require(node != null && node.isString && node.stringValue().isNotBlank() && node.stringValue().length <= maxChars, code)
    }

    private fun oneOf(node: JsonNode?, values: Set<String>, code: String): String {
        val value = node?.takeIf { it.isString }?.stringValue()
        require(value in values, code)
        return value!!
    }

    private fun gid(node: JsonNode?) = require(node != null && (node.isNull || (node.isString && GID.matches(node.stringValue()))), "gid")

    private fun column(node: JsonNode?, code: String) = require(node != null && node.isString && COLUMN.matches(node.stringValue()), code)

    /** True when [node] names a column, false when it is null. */
    private fun optionalColumn(node: JsonNode?, code: String): Boolean {
        require(node != null, code)
        if (node!!.isNull) return false
        column(node, code)
        return true
    }

    private fun list(node: JsonNode?, min: Int, max: Int, code: String): List<JsonNode> {
        require(node != null && node.isArray && node.size() in min..max, code)
        return node!!.values().toList()
    }

    private fun require(condition: Boolean, code: String) {
        if (!condition) throw Rejection(code)
    }

    private class Rejection(val code: String) : RuntimeException(code, null, false, false)
}
