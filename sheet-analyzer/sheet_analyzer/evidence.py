"""Data-only evaluation over a packed workbook. Never executes model-generated code or formulas.

Port of the experiment's validate.py: a small spreadsheet formula interpreter for the supported functions and
the interpreter of the model's bounded calculation AST.
"""
import collections
import math
import re
from decimal import ROUND_HALF_UP, Decimal

from openpyxl.formula import Tokenizer
from openpyxl.formula.translate import Translator
from openpyxl.utils.cell import column_index_from_string, get_column_letter, range_boundaries

ERROR_VALUES = ("#REF!", "#DIV/0!", "#VALUE!", "#N/A", "#NAME?", "#NUM!")
PSEUDONYM = re.compile(r"student_\d{4,}")
PRECEDENCE = {"=": 1, "<>": 1, ">": 1, "<": 1, ">=": 1, "<=": 1, "&": 2, "+": 3, "-": 3, "*": 4, "/": 4, "^": 5}


class Unsupported(Exception):
    pass


class SheetError(Exception):
    pass


def split_address(address):
    match = re.fullmatch(r"([A-Z]+)([0-9]+)", address)
    if not match:
        raise Unsupported("bad address")
    return match[1], int(match[2])


class FormulaParser:
    def __init__(self, formula):
        self.tokens = [t for t in Tokenizer(formula).items if t.type != "WHITE-SPACE"]
        self.pos = 0

    def parse(self):
        value = self.expr(0)
        if self.pos != len(self.tokens):
            raise Unsupported("trailing tokens")
        return value

    def _peek(self):
        return self.tokens[self.pos] if self.pos < len(self.tokens) else None

    def expr(self, minimum):
        token = self._peek()
        if token is None:
            raise Unsupported("missing operand")
        self.pos += 1
        if token.type == "OPERATOR-PREFIX":
            left = ("unary", token.value, self.expr(6))
        elif token.type in ("FUNC", "PAREN") and token.subtype == "OPEN":
            if token.type == "FUNC":
                args = []
                if not (self._peek() and self._peek().subtype == "CLOSE"):
                    while True:
                        args.append(self.expr(0))
                        if self._peek() and self._peek().type == "SEP":
                            self.pos += 1
                            continue
                        break
                left = ("func", token.value[:-1].upper(), args)
            else:
                left = self.expr(0)
            if not (self._peek() and self._peek().subtype == "CLOSE"):
                raise Unsupported("unclosed expression")
            self.pos += 1
        elif token.type == "OPERAND":
            if token.subtype == "NUMBER":
                left = ("literal", float(token.value))
            elif token.subtype == "TEXT":
                left = ("literal", token.value[1:-1].replace('""', '"'))
            elif token.subtype == "LOGICAL":
                left = ("literal", token.value.upper() == "TRUE")
            elif token.subtype == "RANGE":
                left = ("reference", token.value)
            elif token.subtype == "ERROR":
                left = ("error", token.value)
            else:
                raise Unsupported("operand " + token.subtype)
        else:
            raise Unsupported("token " + token.type)
        while self._peek():
            token = self._peek()
            if token.type == "OPERATOR-POSTFIX" and token.value == "%":
                self.pos += 1
                left = ("binary", "/", left, ("literal", 100))
                continue
            if token.type != "OPERATOR-INFIX" or token.value not in PRECEDENCE or PRECEDENCE[token.value] < minimum:
                break
            self.pos += 1
            right = self.expr(PRECEDENCE[token.value] + (0 if token.value == "^" else 1))
            left = ("binary", token.value, left, right)
        return left


def flatten(value):
    if isinstance(value, list):
        for row in value:
            yield from flatten(row)
    else:
        yield value


def number(value):
    if value is None:
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        if value == "":
            raise SheetError("empty string is not a numeric scalar")
        try:
            return float(value.replace(",", "."))
        except ValueError:
            pass
    raise SheetError("non-numeric scalar")


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def round_excel(value, digits):
    return float(Decimal(str(number(value))).quantize(Decimal("1").scaleb(-int(digits)), rounding=ROUND_HALF_UP))


def close(a, b):
    if a is None or b is None:
        return a is b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return math.isclose(a, b, rel_tol=1e-9, abs_tol=1e-7)
    if isinstance(a, str) and isinstance(b, (int, float)):
        try:
            return close(float(a.replace(",", ".")), b)
        except ValueError:
            return False
    return a == b


class BookEvidence:
    def __init__(self, book):
        self.book = book
        self.sheets = {s["name"]: s for s in book["sheets"]}
        self.cells = {}
        self.formulas = {}
        self.memo = {}
        self.stack = set()
        self.parsed = {}
        self.cached_precedents = False
        self.max_row = collections.Counter()
        self._by_column = collections.defaultdict(lambda: collections.defaultdict(set))
        for s in book["sheets"]:
            name = s["name"]
            for r, values in s["rows"]:
                self.max_row[name] = max(self.max_row[name], r)
                for col, value, *_ in values:
                    self.cells[name, col + str(r)] = value
                    if isinstance(value, str):
                        self._by_column[name, col][value].add(r)
            for group in s["formula_groups"]:
                for target in group["targets"]:
                    self.formulas[name, target] = Translator(group["formula"], origin=group["anchor"]).translate_formula(target)

    def rows_with(self, sheet, column, value):
        """Rows whose literal cached text in this column equals value (identity columns hold pseudonyms)."""
        return self._by_column.get((sheet, column), {}).get(value, set())

    def tokens(self, sheet, columns):
        return sorted({v for c in columns for v in self._by_column.get((sheet, c), {}) if PSEUDONYM.fullmatch(v)})

    def cached(self, sheet, cell):
        return self.cells.get((sheet, cell.replace("$", "")))

    def resolve(self, reference, sheet):
        reference = reference.replace("$", "")
        if "!" in reference:
            sheet, reference = reference.rsplit("!", 1)
            sheet = sheet.strip("'").replace("''", "'")
        if sheet not in self.sheets:
            raise Unsupported("external or missing sheet")
        if re.fullmatch("[A-Za-z]+:[A-Za-z]+", reference):
            a, b = reference.split(":")
            reference = f"{a}1:{b}{self.max_row[sheet] or 1}"
        try:
            min_col, min_row, max_col, max_row = range_boundaries(reference)
        except ValueError:
            raise Unsupported("named/indirect reference") from None
        if min_row is None or max_row is None:
            raise Unsupported("unbounded reference")
        if (max_row - min_row + 1) * (max_col - min_col + 1) > 200000:
            raise Unsupported("range too large")
        reader = self.cached if self.cached_precedents else self.evaluate
        if ":" not in reference:
            return reader(sheet, reference)
        return [[reader(sheet, get_column_letter(c) + str(r)) for c in range(min_col, max_col + 1)] for r in range(min_row, max_row + 1)]

    def evaluate_from_cached_inputs(self, sheet, cell):
        formula = self.formulas.get((sheet, cell))
        if formula is None:
            raise Unsupported("no source formula")
        parsed = FormulaParser(formula).parse()
        self.cached_precedents = True
        try:
            return self.node(parsed, sheet, cell)
        finally:
            self.cached_precedents = False

    def evaluate(self, sheet, cell):
        key = (sheet, cell)
        if key in self.memo:
            return self.memo[key]
        if key in self.stack:
            raise SheetError("cycle")
        formula = self.formulas.get(key)
        if formula is None:
            value = self.cached(sheet, cell)
            if isinstance(value, str) and value.startswith(ERROR_VALUES):
                raise SheetError(value)
            return value
        if len(self.stack) > 100:
            raise Unsupported("formula chain too deep")
        self.stack.add(key)
        try:
            if formula not in self.parsed:
                self.parsed[formula] = FormulaParser(formula).parse()
            value = self.node(self.parsed[formula], sheet, cell)
            self.memo[key] = value
            return value
        finally:
            self.stack.remove(key)

    def node(self, node, sheet, cell):
        kind = node[0]
        if kind == "literal":
            return node[1]
        if kind == "error":
            raise SheetError(node[1])
        if kind == "reference":
            return self.resolve(node[1], sheet)
        if kind == "unary":
            v = number(self.node(node[2], sheet, cell))
            return -v if node[1] == "-" else v
        if kind == "binary":
            return self._binary(node[1], self.node(node[2], sheet, cell), self.node(node[3], sheet, cell))
        if kind != "func":
            raise Unsupported(kind)
        return self._function(node[1].removeprefix("_XLFN."), node[2], sheet, cell)

    @staticmethod
    def _binary(op, a, b):
        if op == "&":
            return ("" if a is None else str(a)) + ("" if b is None else str(b))
        if op in ("=", "<>", ">", "<", ">=", "<="):
            a = 0 if a is None else a
            b = 0 if b is None else b
            if isinstance(a, str) and isinstance(b, str):
                a, b = a.casefold(), b.casefold()
            try:
                return {"=": lambda: a == b, "<>": lambda: a != b, ">": lambda: a > b, "<": lambda: a < b,
                        ">=": lambda: a >= b, "<=": lambda: a <= b}[op]()
            except TypeError:
                raise Unsupported("mixed-type comparison") from None
        a, b = number(a), number(b)
        if op == "+":
            return a + b
        if op == "-":
            return a - b
        if op == "*":
            return a * b
        if op == "/":
            if b == 0:
                raise SheetError("#DIV/0!")
            return a / b
        if op == "^":
            return a ** b
        raise Unsupported("operator " + op)

    def _function(self, name, args, sheet, cell):
        def get(i):
            return self.node(args[i], sheet, cell)

        if name == "IF":
            return get(1) if bool(get(0)) else get(2) if len(args) > 2 else False
        if name == "IFERROR":
            try:
                return get(0)
            except SheetError:
                return get(1)
        if name == "__XLUDF.DUMMYFUNCTION":
            # Google stores formulas not representable in Excel inside this wrapper.
            original = get(0)
            if "COMPUTED_VALUE" in str(original):
                raise Unsupported("Google COMPUTED_VALUE placeholder")
            if not isinstance(original, str):
                raise Unsupported("dummy formula")
            formula = original if original.startswith("=") else "=" + original
            if formula not in self.parsed:
                self.parsed[formula] = FormulaParser(formula).parse()
            return self.node(self.parsed[formula], sheet, cell)
        if name == "ROW":
            return int(re.search(r"\d+$", cell)[0]) if not args else int(re.search(r"\d+", args[0][1])[0])
        if name == "COLUMN":
            return column_index_from_string(re.match("[A-Z]+", cell)[0])
        if name in ("TRUE", "FALSE"):
            return name == "TRUE"
        values = [get(i) for i in range(len(args))]
        flat = list(flatten(values))
        numeric = [float(v) for v in flat if is_number(v)]
        if name == "SUM":
            total = 0.0
            for ast, value in zip(args, values):
                if isinstance(value, list):
                    total += sum(float(v) for v in flatten(value) if is_number(v))
                elif isinstance(value, (int, float)):
                    if not isinstance(value, bool) or ast[0] != "reference":
                        total += float(value)
                elif isinstance(value, str) and ast[0] == "literal":
                    total += number(value)
            return total
        if name == "MIN":
            return min(numeric, default=0)
        if name == "MAX":
            return max(numeric, default=0)
        if name == "COUNT":
            return len(numeric)
        if name == "COUNTA":
            return sum(v is not None for v in flat)
        if name == "AVERAGE":
            if not numeric:
                raise SheetError("#DIV/0!")
            return sum(numeric) / len(numeric)
        if name == "ROUND":
            return round_excel(values[0], values[1] if len(values) > 1 else 0)
        if name == "ROUNDDOWN":
            scale = 10 ** int(values[1])
            return math.trunc(number(values[0]) * scale) / scale
        if name == "ROUNDUP":
            value = number(values[0])
            scale = 10 ** int(values[1])
            return math.copysign(math.ceil(abs(value) * scale) / scale, value)
        if name == "ABS":
            return abs(number(values[0]))
        if name == "AND":
            return all(bool(v) for v in flat)
        if name == "OR":
            return any(bool(v) for v in flat)
        if name == "ISBLANK":
            return values[0] is None
        if name == "LOWER":
            return str(values[0]).lower()
        if name == "LEN":
            return len(str(values[0] or ""))
        if name == "VALUE":
            return number(values[0])
        if name == "VLOOKUP":
            needle, rows, index = values[:3]
            index = int(index) - 1
            if len(values) < 4 or values[3] not in (False, 0):
                raise Unsupported("approximate VLOOKUP")
            matches = [r for r in rows if r[0] == needle]
            if not matches:
                raise SheetError("#N/A")
            if index < 0 or index >= len(matches[0]):
                raise SheetError("#REF!")
            return matches[0][index]
        if name == "MATCH":
            needle, rows = values[:2]
            if len(values) < 3 or values[2] != 0:
                raise Unsupported("approximate MATCH")
            for i, value in enumerate(flatten(rows), 1):
                if value == needle:
                    return i
            raise SheetError("#N/A")
        if name == "INDEX":
            rows = values[0]
            r = int(number(values[1])) - 1
            c = int(number(values[2])) - 1 if len(values) > 2 else 0
            try:
                return rows[r][c]
            except (TypeError, IndexError):
                raise SheetError("#REF!") from None
        raise Unsupported("function " + name)


def is_proven_blank_reference(ev, sheet, address, seen=None):
    key = (sheet, address)
    seen = set() if seen is None else seen
    if key in seen:
        return False
    seen.add(key)
    value = ev.cached(sheet, address)
    if value is not None:
        return value == ""
    formula = ev.formulas.get(key)
    if formula is None:
        return True
    tokens = [t for t in Tokenizer(formula).items if t.type != "WHITE-SPACE"]
    if len(tokens) != 1 or tokens[0].type != "OPERAND" or tokens[0].subtype != "RANGE":
        return False
    reference = tokens[0].value.replace("$", "")
    target_sheet = sheet
    if "!" in reference:
        target_sheet, reference = reference.rsplit("!", 1)
        target_sheet = target_sheet.strip("'").replace("''", "'")
    if target_sheet not in ev.sheets or not re.fullmatch(r"[A-Z]+[0-9]+", reference):
        return False
    return is_proven_blank_reference(ev, target_sheet, reference, seen)


def student_row(ev, sheet, student, identity):
    mapping = next((i for i in identity if i["sheet"] == sheet), None)
    if mapping is None:
        raise SheetError("no identity mapping")
    rows = set()
    for column in mapping["columns"]:
        rows |= ev.rows_with(sheet, column, student)
    if len(rows) != 1:
        raise SheetError("missing or ambiguous student")
    return next(iter(rows))


def model_ast(node, ev, student, identity, blank_policy, live=False):
    """Evaluates the model's calculation for one pseudonymised student.

    live=True re-evaluates formula cells instead of reading their cache (used for +1 counterexamples).
    """
    op = node.get("op")
    if op == "number":
        return number(node["value"])
    if op == "cell":
        sheet = node["sheet"]
        address = node["column"] + str(student_row(ev, sheet, student, identity))
        if live and (sheet, address) in ev.formulas:
            value = ev.evaluate(sheet, address)
        else:
            value = ev.cached(sheet, address)
            if value is None and (sheet, address) in ev.formulas and not is_proven_blank_reference(ev, sheet, address):
                raise SheetError("missing formula cache")
        if (value is None or isinstance(value, str) and not value.strip()) and blank_policy == "ignore_blank_inputs":
            return None
        if value is None:
            raise SheetError("blank input")
        if isinstance(value, str) and value.startswith("#"):
            raise SheetError(value)
        return number(value)
    if op == "round":
        return round_excel(model_ast(node["arg"], ev, student, identity, blank_policy, live), node.get("digits", 0))
    args = [model_ast(n, ev, student, identity, blank_policy, live) for n in node.get("args", [])]
    if op == "sum":
        return sum(number(v) for v in args if v is not None)
    if len(args) != 2 or any(v is None for v in args):
        raise SheetError("invalid calculation operands")
    a, b = args
    if op == "multiply":
        return a * b
    if op == "divide":
        if b == 0:
            raise SheetError("division by zero")
        return a / b
    if op == "subtract":
        return a - b
    if op == "min":
        return min(a, b)
    if op == "max":
        return max(a, b)
    raise SheetError("unsupported AST op")
