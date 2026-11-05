"""Synthetic workbooks with invented people. Never real student data."""
import io
import re
import zipfile
from xml.sax.saxutils import escape

import openpyxl

STUDENTS = [
    (100001, "Тестов Тест Тестович", 10, 20, None, None),
    (100002, "Пробная Анна Сергеевна", 7, 3, 2, None),
    (100003, "Образцов Олег Петрович", 15, 15, None, 40),
]
ROSTER = "Ведомость"
RUBRIC = "Правила"
TABS = [dict(name=ROSTER, gid="1819564669"), dict(name=RUBRIC, gid="42")]


def with_cache(data, caches):
    """openpyxl cannot write cached formula values; patch them into the sheet XML like a Google export has them."""
    source = zipfile.ZipFile(io.BytesIO(data))
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as target:
        for info in source.infolist():
            content = source.read(info.filename)
            match = re.fullmatch(r"xl/worksheets/sheet(\d+)\.xml", info.filename)
            if match and int(match[1]) in caches:
                xml = content.decode()
                for coordinate, value in caches[int(match[1])].items():
                    kind = ' t="str"' if isinstance(value, str) else ""
                    xml = re.sub(
                        rf'<c r="{coordinate}"([^>]*)><f>(.*?)</f><v ?/>',
                        lambda m: f'<c r="{coordinate}"{m[1]}{kind}><f>{m[2]}</f><v>{escape(str(value))}</v>',
                        xml,
                    )
                content = xml.encode()
            target.writestr(info, content)
    return out.getvalue()


def roster_xlsx(students=STUDENTS, total="=SUM(C{r}:F{r})", header="Итого", extra_tab=None, email_note=True, homework=None):
    """Roster with ISU, name, two homeworks, bonus, exam and a teacher total; a rubric tab.

    homework, e.g. "=SUMPRODUCT({v})", makes ДЗ 1 a cached formula the interpreter may not run.
    """
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = ROSTER
    ws.append(["ИСУ", "ФИО", "ДЗ 1", "ДЗ 2", "Бонус", "Экзамен", header])
    caches = {}
    for index, (isu, name, hw1, hw2, bonus, exam) in enumerate(students, start=2):
        ws.append([isu, name, homework.format(v=hw1) if homework else hw1, hw2, bonus, exam, total.format(r=index)])
        if homework:
            caches[f"C{index}"] = hw1
        caches[f"G{index}"] = sum(v for v in (hw1, hw2, bonus, exam) if v is not None) if "C{r}:F{r}" in total else hw1 + hw2
    ws["I1"] = '=IF(B2="Тестов Тест Тестович",1,0)'
    caches["I1"] = 1
    rubric = wb.create_sheet(RUBRIC)
    rubric["A1"] = "Максимум 100 баллов, экзамен 40"
    if email_note:
        rubric["A2"] = "Вопросы: teacher@example.com, https://example.com/rules"
    if extra_tab:
        wb.create_sheet(extra_tab)["A1"] = "Архив"
    buffer = io.BytesIO()
    wb.save(buffer)
    return with_cache(buffer.getvalue(), {1: caches})
