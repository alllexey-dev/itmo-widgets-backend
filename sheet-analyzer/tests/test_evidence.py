"""Port of the experiment's test_validate.py."""
import unittest

from sheet_analyzer.evidence import BookEvidence, SheetError, Unsupported, close, model_ast


def book(formula="=SUM(C2:D2)"):
    return dict(workbook_key="synthetic", sheets=[
        dict(name="Marks", rows=[
            [1, [["A", "ISU", 0], ["C", "Lab", 0], ["D", "Exam", 0], ["E", "Total", 0]]],
            [2, [["A", "student_0001", 0], ["C", 10, 0], ["D", 20, 0], ["E", 30, 0]]],
            [3, [["A", "student_0002", 0], ["C", 7, 0], ["D", 3, 0], ["E", 10, 0]]],
        ], formula_groups=[dict(anchor="E2", formula=formula, targets=["E2", "E3"])], merged_ranges=[]),
        dict(name="Other", rows=[[9, [["B", "student_0001", 0], ["F", 4, 0]]]], formula_groups=[], merged_ranges=[]),
    ])


class FormulaTests(unittest.TestCase):
    def test_supported_arithmetic(self):
        cases = {
            "=SUM(C2:D2)": 30, "=C2+D2*2": 50, "=(C2+D2)*2": 60, "=MIN(C2,D2)": 10, "=MAX(C2,D2)": 20,
            "=ROUND(2.5,0)": 3, "=ROUND(-2.5,0)": -3, "=IF(C2>0,D2,0)": 20, "=IF(FALSE,UNKNOWN(),C2)": 10,
            "=IFERROR(1/0,C2)": 10, "=SUM(C2:D2,5)": 35, "=COUNT(C2:D2)": 2, "=AND(C2>0,D2>0)": True,
            "=ROUNDUP(1.01,1)": 1.1, "=ROUNDDOWN(-1.19,1)": -1.1, "=SUM(Other!F9,C2)": 14, "=SUM(C2,D2,TRUE)": 31,
        }
        for formula, expected in cases.items():
            with self.subTest(formula=formula):
                self.assertTrue(close(BookEvidence(book(formula)).evaluate("Marks", "E2"), expected))

    def test_a_value_quoted_as_text_matches_its_number_either_way(self):
        self.assertTrue(close(40, "40"))
        self.assertTrue(close("40", 40))
        self.assertTrue(close(66.3, "66,3"))
        self.assertFalse(close(40, "41"))
        self.assertFalse(close(40, "сорок"))

    def test_relative_formula_translation(self):
        self.assertEqual(BookEvidence(book()).evaluate("Marks", "E3"), 10)

    def test_unsupported_is_not_swallowed_by_iferror(self):
        with self.assertRaises(Unsupported):
            BookEvidence(book("=IFERROR(UNKNOWN(),0)")).evaluate("Marks", "E2")

    def test_unknown_google_placeholder_is_not_recalculation(self):
        with self.assertRaises(Unsupported):
            BookEvidence(book('=IFERROR(__xludf.DUMMYFUNCTION("""COMPUTED_VALUE"""),30)')).evaluate("Marks", "E2")

    def test_missing_sheet_is_unverifiable(self):
        with self.assertRaises(Unsupported):
            BookEvidence(book("=Missing!A2")).evaluate("Marks", "E2")

    def test_sum_ignores_text_and_blanks(self):
        b = book()
        b["sheets"][0]["rows"][1][1][1][1] = "н"
        self.assertEqual(BookEvidence(b).evaluate("Marks", "E2"), 20)

    def test_scalar_text_does_not_silently_turn_into_zero(self):
        b = book("=C2+D2")
        b["sheets"][0]["rows"][1][1][1][1] = "н"
        with self.assertRaises(SheetError):
            BookEvidence(b).evaluate("Marks", "E2")

    def test_cached_input_arithmetic_does_not_claim_upstream_recalculation(self):
        b = book()
        b["sheets"][0]["formula_groups"].append(dict(anchor="C2", formula="=UNKNOWN()", targets=["C2"]))
        evidence = BookEvidence(b)
        self.assertEqual(evidence.evaluate_from_cached_inputs("Marks", "E2"), 30)
        with self.assertRaises(Unsupported):
            evidence.evaluate("Marks", "E2")


class RuleTests(unittest.TestCase):
    def setUp(self):
        self.rule = {"op": "sum", "args": [{"op": "cell", "sheet": "Marks", "column": "C"}, {"op": "cell", "sheet": "Other", "column": "F"}]}
        self.identity = [dict(sheet="Marks", columns=["A"]), dict(sheet="Other", columns=["B"])]

    def test_cross_sheet_key_join_not_row_position(self):
        self.assertEqual(model_ast(self.rule, BookEvidence(book()), "student_0001", self.identity, "preserve"), 14)

    def test_reordering_rows_preserves_result(self):
        b = book()
        b["sheets"][0]["rows"].reverse()
        self.assertEqual(model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "preserve"), 14)

    def test_actual_row_coordinates_can_change_independently_between_tabs(self):
        b = book()
        b["sheets"][0]["rows"][1][0] = 8
        b["sheets"][0]["rows"][2][0] = 2
        b["sheets"][1]["rows"][0][0] = 3
        self.assertEqual(model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "preserve"), 14)

    def test_duplicate_student_fails_closed(self):
        b = book()
        b["sheets"][1]["rows"].append([10, [["B", "student_0001", 0], ["F", 99, 0]]])
        with self.assertRaises(SheetError):
            model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "preserve")

    def test_missing_student_fails_closed(self):
        with self.assertRaises(SheetError):
            model_ast(self.rule, BookEvidence(book()), "student_missing", self.identity, "preserve")

    def test_missing_cross_sheet_row_does_not_become_zero(self):
        with self.assertRaises(SheetError):
            model_ast(self.rule, BookEvidence(book()), "student_0002", self.identity, "ignore_blank_inputs")

    def test_blank_cell_policy_is_explicit(self):
        b = book()
        b["sheets"][1]["rows"][0][1][1][1] = None
        with self.assertRaises(SheetError):
            model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "preserve")
        self.assertEqual(model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "ignore_blank_inputs"), 10)

    def test_missing_formula_cache_is_not_a_blank_input(self):
        b = book()
        b["sheets"][1]["rows"][0][1][1][1] = None
        b["sheets"][1]["formula_groups"].append(dict(anchor="F9", formula="=UNKNOWN()", targets=["F9"]))
        with self.assertRaises(SheetError):
            model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "ignore_blank_inputs")

    def test_direct_reference_to_proven_empty_source_is_a_blank_not_unknown_cache(self):
        b = book()
        b["sheets"][1]["rows"][0][1][1][1] = None
        b["sheets"][1]["formula_groups"].append(dict(anchor="F9", formula="=G9", targets=["F9"]))
        self.assertEqual(model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "ignore_blank_inputs"), 10)

    def test_whitespace_only_cell_is_blank_under_explicit_ignore_policy(self):
        b = book()
        b["sheets"][1]["rows"][0][1][1][1] = " "
        self.assertEqual(model_ast(self.rule, BookEvidence(b), "student_0001", self.identity, "ignore_blank_inputs"), 10)

    def test_model_code_is_never_executed(self):
        with self.assertRaises(SheetError):
            model_ast({"op": "eval", "args": []}, BookEvidence(book()), "student_0001", self.identity, "preserve")

    def test_live_mode_recalculates_formula_cells(self):
        evidence = BookEvidence(book())
        evidence.cells["Marks", "D2"] = 21
        node = {"op": "cell", "sheet": "Marks", "column": "E"}
        identity = [dict(sheet="Marks", columns=["A"])]
        self.assertEqual(model_ast(node, evidence, "student_0001", identity, "preserve"), 30)
        self.assertEqual(model_ast(node, evidence, "student_0001", identity, "preserve", live=True), 31)


if __name__ == "__main__":
    unittest.main()
