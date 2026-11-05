"""Protocol operations: fingerprint and analyze. Logs carry only statuses, codes, sizes and durations."""
import hashlib
import logging
import threading
import time

from . import download, model, validate, workbook
from .errors import Failure

VERSION = 1
log = logging.getLogger("sheet_analyzer")


def failure(status, code):
    return dict(version=VERSION, status=status, code=code)


class Service:
    def __init__(self, config, export_xlsx=download.export_xlsx, tab_list=download.tab_list, run_model=model.run):
        self.config = config
        self.export_xlsx = export_xlsx
        self.tab_list = tab_list
        self.run_model = run_model
        # One run per operation at a time; a concurrent one gets busy instead of queueing.
        self.locks = dict(fingerprint=threading.Lock(), analyze=threading.Lock())

    def handle(self, request):
        started = time.monotonic()
        op = request.get("op") if isinstance(request, dict) else None
        try:
            response = self._dispatch(request)
        except Failure as error:
            response = failure(error.status, error.code)
        except Exception as error:  # never a stack trace or sheet data on the wire or in logs
            log.error("op=%s internal_error=%s", op if op in self.locks else "-", type(error).__name__)
            response = failure("rejected", "internal")
        log.info("op=%s status=%s code=%s ms=%d", op if op in self.locks else "-", response["status"],
                 response.get("code", "-"), (time.monotonic() - started) * 1000)
        return response

    def _dispatch(self, request):
        if not isinstance(request, dict) or request.get("version") != VERSION:
            raise Failure("rejected", "bad_version")
        op = request.get("op")
        if op not in self.locks:
            raise Failure("rejected", "bad_op")
        sheet_id = download.check_sheet_id(request.get("sheet_id"))
        lock = self.locks[op]
        if not lock.acquire(blocking=False):
            raise Failure("busy", "busy")
        try:
            return self._fingerprint(sheet_id) if op == "fingerprint" else self._analyze(sheet_id)
        finally:
            lock.release()

    def _load(self, sheet_id):
        data = self.export_xlsx(sheet_id)
        log.info("xlsx_bytes=%d", len(data))
        return workbook.load(data)

    def _fingerprint(self, sheet_id):
        return dict(version=VERSION, status="ok", fingerprint=workbook.fingerprint(self._load(sheet_id)))

    def _analyze(self, sheet_id):
        wb = self._load(sheet_id)
        tabs = self.tab_list(sheet_id)
        fingerprint = workbook.fingerprint(wb)
        packed = workbook.pack(wb, tabs, hashlib.sha256(sheet_id.encode()).hexdigest()[:12])
        del wb
        size = len(packed.text.encode("utf-8"))
        log.info("tabs=%d sheets=%d input_bytes=%d", len(tabs), len(packed.book["sheets"]), size)
        if size > self.config.max_input_bytes:
            raise Failure("too_large", "input_too_large")
        started = time.monotonic()
        answer = self.run_model(self.config, packed.text)
        log.info("model_ms=%d answer_routes=%d", (time.monotonic() - started) * 1000,
                 len(answer.get("routes") or []) if isinstance(answer.get("routes"), list) else 0)
        rule = validate.build_rule(answer, packed)
        log.info("valid_routes=%d", len(rule["routes"]))
        return dict(version=VERSION, status="ok", fingerprint=fingerprint, rule=rule)
