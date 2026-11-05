import io
import unittest

import openpyxl

from sheet_analyzer import workbook
from sheet_analyzer.errors import Failure
from tests import fixtures

ORIGINALS = ["Тестов", "Тест Тестович", "Пробная", "Анна Сергеевна", "Образцов", "100001", "100002", "100003", "teacher@example.com", "https://example.com"]


def packed(data=None, tabs=fixtures.TABS):
    return workbook.pack(workbook.load(data or fixtures.roster_xlsx()), tabs, "k1")


def fingerprint(data):
    return workbook.fingerprint(workbook.load(data))


class PseudonymisationTests(unittest.TestCase):
    def test_no_name_isu_email_or_link_survives_in_the_packed_text(self):
        text = packed().text
        for original in ORIGINALS:
            with self.subTest(original=original):
                self.assertNotIn(original, text)
        self.assertIn("student_0001", text)
        self.assertIn("[email]", text)

    def test_formula_literal_with_a_name_is_pseudonymised_consistently(self):
        result = packed()
        roster = result.book["sheets"][0]
        name_token = next(v for r, values in roster["rows"] if r == 2 for c, v, _ in values if c == "B")
        formula = next(g["formula"] for g in roster["formula_groups"] if g["anchor"] == "I1")
        self.assertEqual(formula, f'=IF(B2="{name_token}",1,0)')

    def test_identity_columns_and_gids(self):
        result = packed()
        self.assertEqual(result.identity_columns[fixtures.ROSTER, "A"], "isu")
        self.assertEqual(result.identity_columns[fixtures.ROSTER, "B"], "name")
        self.assertEqual(result.gids, {fixtures.ROSTER: "1819564669", fixtures.RUBRIC: "42"})

    def test_unknown_tab_has_no_gid(self):
        self.assertEqual(packed(tabs=[]).gids[fixtures.ROSTER], None)

    def test_cached_values_and_formula_groups_are_kept(self):
        roster = packed().book["sheets"][0]
        self.assertIn(dict(anchor="G2", formula="=SUM(C2:F2)", targets=["G2", "G3", "G4"]), roster["formula_groups"])
        row = dict((c, v) for r, values in roster["rows"] if r == 4 for c, v, _ in values)
        self.assertEqual(row["G"], 70)

    def test_native_google_formula_is_grouped_without_its_wrapper(self):
        wb = openpyxl.Workbook()
        ws = wb.active
        ws.append(["ИСУ", "Баллы", "Итог"])
        for r in (2, 3):
            ws.append([100000 + r, r, f'=IFERROR(__xludf.DUMMYFUNCTION("FILTER(B{r}:B{r},B{r}:B{r}>0)"),{r}.0)'])
        buffer = io.BytesIO()
        wb.save(buffer)
        data = fixtures.with_cache(buffer.getvalue(), {1: {"C2": 2, "C3": 3}})
        groups = packed(data, []).book["sheets"][0]["formula_groups"]
        self.assertEqual(len(groups), 1)
        self.assertEqual(groups[0]["formula"], "=FILTER(B2:B2,B2:B2>0)")
        self.assertEqual(groups[0]["targets"], ["C2", "C3"])
        self.assertEqual(groups[0]["excel_wrapper"]["fallback_overrides"], {})

    def test_not_a_workbook_is_rejected(self):
        with self.assertRaises(Failure) as error:
            workbook.load(b"PK\x03\x04 broken")
        self.assertEqual((error.exception.status, error.exception.code), ("rejected", "bad_xlsx"))


class FingerprintTests(unittest.TestCase):
    def test_stable_when_marks_change(self):
        changed = [(isu, name, hw1 + 1, hw2, 5, 30) for isu, name, hw1, hw2, _, _ in fixtures.STUDENTS]
        self.assertEqual(fingerprint(fixtures.roster_xlsx()), fingerprint(fixtures.roster_xlsx(changed)))

    def test_stable_when_a_student_joins(self):
        more = fixtures.STUDENTS + [(100004, "Новикова Нина Ивановна", 1, 2, None, None)]
        self.assertEqual(fingerprint(fixtures.roster_xlsx()), fingerprint(fixtures.roster_xlsx(more)))

    def test_changes_with_a_header(self):
        self.assertNotEqual(fingerprint(fixtures.roster_xlsx()), fingerprint(fixtures.roster_xlsx(header="Сумма")))

    def test_changes_with_a_formula(self):
        self.assertNotEqual(fingerprint(fixtures.roster_xlsx()), fingerprint(fixtures.roster_xlsx(total="=SUM(C{r}:D{r})")))

    def test_changes_with_a_new_tab(self):
        self.assertNotEqual(fingerprint(fixtures.roster_xlsx()), fingerprint(fixtures.roster_xlsx(extra_tab="Архив")))

    def test_is_sha256_hex(self):
        self.assertRegex(fingerprint(fixtures.roster_xlsx()), r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
