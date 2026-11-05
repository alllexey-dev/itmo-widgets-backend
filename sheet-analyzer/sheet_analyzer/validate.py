"""Checks the model's answer against the packed workbook and sanitises it into the protocol's rule.

Invalid routes are dropped. Nothing from the model is executed: calculations are rebuilt from an allowlist of
node types and evaluated only by evidence.model_ast. The rule carries no examples, cell values, names or ISU.
"""
import collections
import math
import re

from .errors import Failure
from .evidence import BookEvidence, SheetError, Unsupported, close, is_number, model_ast, split_address, student_row

PURPOSES = ("current_accumulated", "official_total")
BLANK_POLICIES = ("preserve", "ignore_blank_inputs")
ROLES = ("active_roster", "source", "rubric", "template", "archive", "empty", "auxiliary")
COLUMN = re.compile(r"[A-Z]{1,3}")
ROUTE_ID = re.compile(r"[A-Za-z0-9_-]{1,32}")  # Backend SheetRuleShape accepts exactly this
BINARY_OPS = ("multiply", "divide", "subtract", "min", "max")
MAX_NODES = 500
MAX_DEPTH = 20
MAX_ROW = 1048576
COUNTEREXAMPLE_STUDENTS = 5
# Independent point components that must move the current total by +1 when they grow by 1.
BONUS_OR_EXAM = re.compile(r"бонус|допбалл|доп\.?\s*балл|дополнительн|экзамен|bonus|extra|exam", re.I)
NOT_A_COMPONENT = re.compile(r"допуск|admission|дата|date|итог|total|сумм|sum|оценк|grade|макс|max", re.I)
PSEUDONYM = re.compile(r"student_\d+")
CONTROL = re.compile(r"[\x00-\x1f\x7f]+")
SOURCE_MISMATCH_WARNING = "Итог в таблице не везде сходится с его же формулой"
IDENTITY_ERRORS = ("missing or ambiguous student", "no identity mapping")


class Invalid(Exception):
    pass


def clean_text(value, limit):
    """Short single-line text without pseudonyms; the model never saw real names, so none can appear here."""
    if not isinstance(value, str):
        return ""
    text = CONTROL.sub(" ", PSEUDONYM.sub("студент", value))
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 3].rstrip() + "..."


def _require(condition):
    if not condition:
        raise Invalid()


def parse_node(node, ev, depth=0, count=None):
    """Rebuilds a calculation node from the allowlist; anything else is invalid."""
    count = count if count is not None else [0]
    count[0] += 1
    _require(isinstance(node, dict) and depth <= MAX_DEPTH and count[0] <= MAX_NODES)
    op = node.get("op")
    if op == "cell":
        _require(node.get("sheet") in ev.sheets and isinstance(node.get("column"), str) and COLUMN.fullmatch(node["column"]))
        return dict(op="cell", sheet=node["sheet"], column=node["column"])
    if op == "number":
        value = node.get("value")
        _require(is_number(value) and math.isfinite(value))
        return dict(op="number", value=value)
    if op == "round":
        digits = node.get("digits", 0)
        _require(isinstance(digits, int) and not isinstance(digits, bool) and 0 <= digits <= 6)
        return dict(op="round", arg=parse_node(node.get("arg"), ev, depth + 1, count), digits=digits)
    args = node.get("args")
    _require(isinstance(args, list))
    if op == "sum":
        _require(1 <= len(args) <= 200)
    elif op in BINARY_OPS:
        _require(len(args) == 2)
    else:
        raise Invalid()
    return dict(op=op, args=[parse_node(a, ev, depth + 1, count) for a in args])


def cell_nodes(node):
    if node["op"] == "cell":
        yield node
    elif node["op"] == "round":
        yield from cell_nodes(node["arg"])
    else:
        for arg in node.get("args", []):
            yield from cell_nodes(arg)


def parse_identity(raw, ev):
    _require(isinstance(raw, list) and raw)
    identity = []
    for item in raw:
        _require(isinstance(item, dict) and item.get("sheet") in ev.sheets)
        columns = item.get("columns")
        _require(isinstance(columns, list) and columns and all(isinstance(c, str) and COLUMN.fullmatch(c) for c in columns))
        first = item.get("first_student_row")
        _require(isinstance(first, int) and not isinstance(first, bool) and 1 <= first <= MAX_ROW)
        identity.append(dict(sheet=item["sheet"], columns=list(dict.fromkeys(columns)), first_student_row=first))
    return identity


def _check_examples(route, kind, node, identity, blank_policy, ev):
    formula_examples = route.get("formula_examples") or []
    examples = route.get("examples") or []
    _require(isinstance(formula_examples, list) and isinstance(examples, list))
    for example in formula_examples:
        _require(isinstance(example, dict))
        _require(ev.formulas.get((example.get("sheet"), example.get("cell"))) == example.get("formula"))
    for example in examples:
        _require(isinstance(example, dict) and isinstance(example.get("student"), str))
        cells = example.get("cells") or []
        _require(isinstance(cells, list))
        for cell in cells:
            _require(isinstance(cell, dict) and isinstance(cell.get("cell"), str))
            _require(close(ev.cached(cell.get("sheet"), cell["cell"]), cell.get("value")))
        result = example.get("result")
        if isinstance(result, str):
            try:
                result = float(result.replace(",", "."))
            except ValueError:
                raise Invalid() from None
        try:
            actual = model_ast(node, ev, example["student"], identity, blank_policy)
        except (SheetError, Unsupported) as error:
            # A direct read of a still empty total legitimately has no result.
            _require(kind == "direct" and result is None and str(error) in ("blank input", "missing formula cache"))
            continue
        _require(close(actual, result))


def _resolves_anyone(node, identity, blank_policy, ev, sheet, columns):
    failures = collections.Counter()
    tokens = ev.tokens(sheet, columns)
    for token in tokens:
        try:
            model_ast(node, ev, token, identity, blank_policy)
            return True
        except Exception as error:
            failures[str(error)] += 1
    # Errors other than identity (blank inputs, no cache yet) still prove the route finds people.
    return any(reason not in IDENTITY_ERRORS for reason in failures)


def _source_mismatch(ev, sheet, column):
    for (name, address), _ in list(ev.formulas.items()):
        if name != sheet or split_address(address)[0] != column:
            continue
        cached = ev.cached(name, address)
        if cached is None:
            continue
        try:
            if not close(ev.evaluate(name, address), cached):
                return True
        except (Unsupported, SheetError, RecursionError, ArithmeticError, TypeError, ValueError):
            continue
    return False


def _component_columns(ev, sheet, first_row, exclude):
    """Literal numeric roster columns whose header names a bonus or an exam."""
    headers = collections.defaultdict(list)
    student_values = collections.defaultdict(list)
    for (name, address), value in ev.cells.items():
        if name != sheet:
            continue
        column, row = split_address(address)
        if row < first_row:
            if isinstance(value, str):
                headers[column].append(value)
        else:
            student_values[column].append(value)
    formula_columns = {split_address(a)[0] for (n, a) in ev.formulas if n == sheet and split_address(a)[1] >= first_row}
    result = []
    for column, texts in headers.items():
        header = " ".join(texts)
        if column in exclude or column in formula_columns or not BONUS_OR_EXAM.search(header) or NOT_A_COMPONENT.search(header):
            continue
        if all(v is None or v == "" or is_number(v) for v in student_values[column]):
            result.append(column)
    return sorted(result)


def _counterexamples_pass(node, identity, blank_policy, ev, sheet, columns, first_row, exclude):
    """+1 in an (even empty) bonus or exam cell must raise the current total by exactly 1."""
    components = _component_columns(ev, sheet, first_row, exclude)
    if not components:
        return True
    tested = 0
    for token in ev.tokens(sheet, columns):
        if tested >= COUNTEREXAMPLE_STUDENTS:
            break
        try:
            row = student_row(ev, sheet, token, identity)
            ev.memo.clear()
            before = model_ast(node, ev, token, identity, blank_policy, live=True)
        except Exception:
            continue
        tested += 1
        for column in components:
            key = (sheet, column + str(row))
            old = ev.cells.get(key)
            if old is not None and not is_number(old) and old != "":
                continue
            ev.cells[key] = (old if is_number(old) else 0) + 1
            ev.memo.clear()
            try:
                after = model_ast(node, ev, token, identity, blank_policy, live=True)
            except Exception:
                continue
            finally:
                if old is None:
                    ev.cells.pop(key, None)
                else:
                    ev.cells[key] = old
                ev.memo.clear()
            if not close(after, (before or 0) + 1):
                return False
    # Bonus or exam columns exist but no student could be checked: the route is not proven to keep them.
    return tested > 0


def check_route(route, ev):
    """Returns the internal checked route or raises Invalid."""
    _require(isinstance(route, dict))
    kind, purpose = route.get("kind"), route.get("purpose")
    _require(kind in ("direct", "compute") and purpose in PURPOSES)
    applies_to = route.get("applies_to")
    _require(isinstance(applies_to, list) and len(applies_to) == 1 and applies_to[0] in ev.sheets)
    sheet = applies_to[0]
    blank_policy = route.get("blank_policy")
    _require(blank_policy in BLANK_POLICIES)
    identity = parse_identity(route.get("identity"), ev)
    if kind == "direct":
        source = route.get("source")
        _require(route.get("calculation") is None and isinstance(source, dict))
        node = parse_node(dict(op="cell", sheet=source.get("sheet"), column=source.get("column")), ev)
        source = dict(sheet=node["sheet"], column=node["column"])
        calculation = None
    else:
        _require(route.get("source") is None)
        calculation = parse_node(route.get("calculation"), ev)
        node = calculation
        source = None
    mapped = {i["sheet"] for i in identity}
    _require(sheet in mapped and all(c["sheet"] in mapped for c in cell_nodes(node)))
    roster = next(i for i in identity if i["sheet"] == sheet)
    student_rows = [row for token in ev.tokens(sheet, roster["columns"]) for column in roster["columns"]
                    for row in ev.rows_with(sheet, column, token)]
    _require(any(row >= roster["first_student_row"] for row in student_rows))
    _check_examples(route, kind, node, identity, blank_policy, ev)
    _require(_resolves_anyone(node, identity, blank_policy, ev, sheet, roster["columns"]))
    warnings = [clean_text(w, 200) for w in route.get("warnings") or [] if isinstance(w, str)][:5]
    if source and _source_mismatch(ev, source["sheet"], source["column"]):
        warnings.append(SOURCE_MISMATCH_WARNING)
    if purpose == "current_accumulated":
        exclude = {source["column"]} if source and source["sheet"] == sheet else set()
        # Headers are what lies above the first real student, whatever row the model declared.
        _require(_counterexamples_pass(node, identity, blank_policy, ev, sheet, roster["columns"], min(student_rows), exclude))
    return dict(
        id=route.get("id"), purpose=purpose, kind=kind, sheet=sheet, identity=identity, source=source,
        calculation=calculation, blank_policy=blank_policy,
        explanation=clean_text(route.get("expression") or route.get("explanation"), 500),
        warnings=[w for w in warnings if w],
    )


def _with_gid(node, gids):
    if node["op"] == "cell":
        return dict(node, gid=gids.get(node["sheet"]))
    if node["op"] == "round":
        return dict(node, arg=_with_gid(node["arg"], gids))
    return dict(node, args=[_with_gid(a, gids) for a in node["args"]])


def _public_route(route, index, used_ids, packed):
    gids = packed.gids
    route_id = route["id"] if isinstance(route["id"], str) and ROUTE_ID.fullmatch(route["id"]) else f"r{index}"
    if route_id in used_ids:
        route_id = f"r{index}"
    used_ids.add(route_id)
    identity = []
    for item in route["identity"]:
        kinds = {c: packed.identity_columns.get((item["sheet"], c)) for c in item["columns"]}
        isu = next((c for c, k in kinds.items() if k == "isu"), None)
        name = next((c for c, k in kinds.items() if k == "name"), None)
        if isu is None and name is None:
            raise Invalid()
        identity.append(dict(sheet=item["sheet"], gid=gids.get(item["sheet"]), isu_column=isu, name_column=name,
                             first_student_row=item["first_student_row"]))
    source = route["source"]
    return dict(
        id=route_id, purpose=route["purpose"], kind=route["kind"], sheet=route["sheet"], gid=gids.get(route["sheet"]),
        identity=identity,
        source=dict(source, gid=gids.get(source["sheet"])) if source else None,
        calculation=_with_gid(route["calculation"], gids) if route["calculation"] else None,
        blank_policy=route["blank_policy"], explanation=route["explanation"], review_required=True,
        warnings=route["warnings"],
    )


def build_rule(answer, packed):
    """Validated, sanitised rule; Failure("rejected") for a malformed answer, ("insufficient") when no route survives."""
    if not isinstance(answer, dict) or not isinstance(answer.get("routes"), list):
        raise Failure("rejected", "schema")
    if answer.get("workbook_key") != packed.book["workbook_key"]:
        raise Failure("rejected", "wrong_workbook_key")
    ev = BookEvidence(packed.book)
    routes = []
    used_ids = set()
    for index, raw in enumerate(answer["routes"], 1):
        try:
            routes.append(_public_route(check_route(raw, ev), index, used_ids, packed))
        except (Invalid, Unsupported, SheetError, RecursionError, ArithmeticError, LookupError, TypeError, ValueError, AttributeError):
            continue
        finally:
            ev.memo.clear()
    if not routes:
        raise Failure("insufficient", "no_valid_route")
    roles = {}
    for tab in answer.get("tabs") or []:
        if isinstance(tab, dict) and tab.get("role") in ROLES:
            roles.setdefault(tab.get("sheet"), tab["role"])
    kinds = {r["kind"] for r in routes}
    return dict(
        schema_version=1,
        decision=kinds.pop() if len(kinds) == 1 else "mixed",
        summary=clean_text(answer.get("summary"), 500),
        tabs=[dict(sheet=name, gid=packed.gids.get(name), role=roles.get(name, "auxiliary")) for name in ev.sheets],
        routes=routes,
    )
