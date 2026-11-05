import copy
import json
import unittest

from sheet_analyzer import validate, workbook
from sheet_analyzer.errors import Failure
from tests import fixtures

ROSTER = fixtures.ROSTER
IDENTITY = [{"sheet": ROSTER, "columns": ["A", "B"], "first_student_row": 2}]


def cell(column, sheet=ROSTER):
    return {"op": "cell", "sheet": sheet, "column": column}


def packed(**kwargs):
    return workbook.pack(workbook.load(fixtures.roster_xlsx(**kwargs)), fixtures.TABS, "k1")


def token(result, row, column="A"):
    roster = result.book["sheets"][0]
    return next(v for r, values in roster["rows"] if r == row for c, v, _ in values if c == column)


def current_route(result, columns="CDEF"):
    return {
        "id": "r1", "purpose": "current_accumulated", "kind": "compute", "applies_to": [ROSTER],
        "score_stage": "current_accumulated", "identity": copy.deepcopy(IDENTITY), "source": None,
        "calculation": {"op": "sum", "args": [cell(c) for c in columns]},
        "expression": "ДЗ 1 + ДЗ 2 + Бонус + Экзамен, например у " + token(result, 4),
        "blank_policy": "ignore_blank_inputs",
        "evidence": [{"sheet": ROSTER, "cell": "E1", "reason": "заголовок бонуса"}],
        "formula_examples": [{"sheet": ROSTER, "cell": "G2", "formula": "=SUM(C2:F2)"}],
        "examples": [{"student": token(result, 4), "cells": [{"sheet": ROSTER, "cell": "C4", "value": 15}], "result": 70 if columns == "CDEF" else 30}],
        "review_required": False, "warnings": ["Проверьте " + token(result, 3, "B")],
    }


def official_route(result):
    return {
        "id": "r2", "purpose": "official_total", "kind": "direct", "applies_to": [ROSTER],
        "score_stage": "semester_total", "identity": copy.deepcopy(IDENTITY), "source": {"sheet": ROSTER, "column": "G"},
        "calculation": None, "expression": "Итого преподавателя", "blank_policy": "preserve", "evidence": [],
        "formula_examples": [], "examples": [{"student": token(result, 2, "B"), "cells": [], "result": 30}],
        "review_required": True, "warnings": [],
    }


def answer(result, *routes):
    return {
        "workbook_key": "k1", "decision": "mixed", "summary": "Сумма работ у " + token(result, 2),
        "tabs": [{"sheet": ROSTER, "role": "active_roster", "reason": "-"}, {"sheet": fixtures.RUBRIC, "role": "rubric", "reason": "-"}],
        "routes": list(routes), "warnings": [],
    }


class BuildRuleTests(unittest.TestCase):
    def setUp(self):
        self.packed = packed()

    def build(self, *routes):
        return validate.build_rule(answer(self.packed, *routes), self.packed)

    def test_valid_answer_becomes_the_protocol_rule(self):
        rule = self.build(current_route(self.packed), official_route(self.packed))
        self.assertEqual(rule["schema_version"], 1)
        self.assertEqual(rule["decision"], "mixed")
        self.assertEqual(rule["tabs"], [
            {"sheet": ROSTER, "gid": "1819564669", "role": "active_roster"},
            {"sheet": fixtures.RUBRIC, "gid": "42", "role": "rubric"},
        ])
        current, official = rule["routes"]
        self.assertEqual(current["identity"], [{"sheet": ROSTER, "gid": "1819564669", "isu_column": "A", "name_column": "B", "first_student_row": 2}])
        self.assertEqual(current["calculation"]["args"][2], {"op": "cell", "sheet": ROSTER, "column": "E", "gid": "1819564669"})
        self.assertIsNone(current["source"])
        self.assertTrue(current["review_required"])
        self.assertEqual(official["source"], {"sheet": ROSTER, "column": "G", "gid": "1819564669"})
        self.assertEqual(set(current), {"id", "purpose", "kind", "sheet", "gid", "identity", "source", "calculation",
                                        "blank_policy", "explanation", "review_required", "warnings"})

    def test_rule_carries_no_examples_values_or_pseudonyms(self):
        text = json.dumps(self.build(current_route(self.packed), official_route(self.packed)), ensure_ascii=False)
        self.assertNotRegex(text, r"student_\d")
        for forbidden in ("examples", "evidence", "formula", '"value"', "Тестов", "100001", "70"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, text)
        self.assertIn("например у студент", text)

    def test_route_missing_bonus_and_exam_is_dropped_by_counterexample(self):
        rule = self.build(current_route(self.packed, columns="CD"), official_route(self.packed))
        self.assertEqual([r["id"] for r in rule["routes"]], ["r2"])
        self.assertEqual(rule["decision"], "direct")

    def test_counterexample_still_runs_over_inputs_the_interpreter_cannot_evaluate(self):
        self.packed = packed(homework="=SUMPRODUCT({v})")
        self.assertEqual([r["id"] for r in self.build(current_route(self.packed, columns="CD"), official_route(self.packed))["routes"]], ["r2"])
        self.assertEqual(len(self.build(current_route(self.packed))["routes"]), 1)

    def test_direct_current_total_passes_when_its_formula_includes_bonus_and_exam(self):
        route = dict(official_route(self.packed), purpose="current_accumulated")
        self.assertEqual(len(self.build(route)["routes"]), 1)

    def test_direct_current_total_that_excludes_bonus_and_exam_is_dropped(self):
        self.packed = packed(total="=SUM(C{r}:D{r})")
        route = dict(official_route(self.packed), purpose="current_accumulated")
        with self.assertRaises(Failure) as error:
            self.build(route)
        self.assertEqual(error.exception.status, "insufficient")
        self.assertEqual(len(self.build(official_route(self.packed))["routes"]), 1)

    def test_invalid_routes_are_dropped(self):
        def broken(change):
            route = current_route(self.packed)
            change(route)
            return route

        cases = {
            "eval node": lambda r: r.update(calculation={"op": "eval", "args": ["__import__('os')"]}),
            "unknown sheet": lambda r: r["calculation"]["args"].append(cell("C", "Нет такой")),
            "two tabs": lambda r: r.update(applies_to=[ROSTER, fixtures.RUBRIC]),
            "example value": lambda r: r["examples"][0]["cells"][0].update(value=16),
            "example result": lambda r: r["examples"][0].update(result=71),
            "invented formula": lambda r: r["formula_examples"][0].update(formula="=SUM(C2:G2)"),
            "insufficient kind": lambda r: r.update(kind="insufficient", calculation=None),
            "no identity kind": lambda r: r["identity"][0].update(columns=["C"]),
            "bad purpose": lambda r: r.update(purpose="everything"),
            "unmapped sheet": lambda r: r["calculation"]["args"].append(cell("A", fixtures.RUBRIC)),
        }
        for name, change in cases.items():
            with self.subTest(name):
                rule = self.build(broken(change), official_route(self.packed))
                self.assertEqual([r["id"] for r in rule["routes"]], ["r2"])

    def test_no_surviving_route_is_insufficient(self):
        with self.assertRaises(Failure) as error:
            self.build(current_route(self.packed, columns="CD"))
        self.assertEqual((error.exception.status, error.exception.code), ("insufficient", "no_valid_route"))

    def test_malformed_answer_is_rejected(self):
        for bad in ([], {"workbook_key": "k1"}, dict(answer(self.packed), workbook_key="other")):
            with self.subTest(bad=bad), self.assertRaises(Failure) as error:
                validate.build_rule(bad, self.packed)
            self.assertEqual(error.exception.status, "rejected")

    def test_duplicate_route_ids_are_renumbered(self):
        second = dict(official_route(self.packed), id="r1")
        rule = self.build(current_route(self.packed), second)
        self.assertEqual([r["id"] for r in rule["routes"]], ["r1", "r2"])


if __name__ == "__main__":
    unittest.main()
