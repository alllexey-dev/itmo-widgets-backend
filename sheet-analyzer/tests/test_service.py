import json
import os
import socket
import stat
import tempfile
import threading
import unittest
from unittest import mock

from sheet_analyzer import server
from sheet_analyzer import service as service_module
from sheet_analyzer.config import Config
from sheet_analyzer.errors import Failure
from sheet_analyzer.service import Service
from tests import fixtures
from tests.test_validate import answer, current_route, official_route

SHEET_ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz_-0123456789"


def model_answer(config, text):
    """Fake model: answers from what it was given, like the real one only sees the packed text."""
    from sheet_analyzer import workbook

    packed = workbook.Packed(book=json.loads(text), text=text, gids={}, identity_columns={})
    result = answer(packed, current_route(packed), official_route(packed))
    result["workbook_key"] = packed.book["workbook_key"]
    return result


def service(**overrides):
    deps = dict(export_xlsx=lambda sheet_id: fixtures.roster_xlsx(), tab_list=lambda sheet_id: fixtures.TABS, run_model=model_answer)
    deps.update(overrides)
    return Service(Config(), **deps)


def request(op="analyze", **extra):
    return dict(version=1, op=op, sheet_id=SHEET_ID, **extra)


class ServiceTests(unittest.TestCase):
    def test_analyze_returns_fingerprint_and_rule(self):
        response = service().handle(request())
        self.assertEqual(response["status"], "ok")
        self.assertEqual(response["version"], 1)
        self.assertRegex(response["fingerprint"], "^[0-9a-f]{64}$")
        self.assertEqual([r["purpose"] for r in response["rule"]["routes"]], ["current_accumulated", "official_total"])

    def test_fingerprint_changes_with_the_analyzer_revision(self):
        before = service().handle(request("fingerprint"))["fingerprint"]
        with mock.patch.object(service_module, "RULES_REVISION", service_module.RULES_REVISION + 1):
            after = service().handle(request("fingerprint"))["fingerprint"]
        self.assertNotEqual(before, after)

    def test_fingerprint_matches_analyze_and_has_no_rule(self):
        analyzed = service().handle(request())
        fingerprinted = service().handle(request("fingerprint"))
        self.assertEqual(fingerprinted, dict(version=1, status="ok", fingerprint=analyzed["fingerprint"]))

    def test_model_sees_no_names_or_isu(self):
        seen = []
        service(run_model=lambda config, text: seen.append(text) or model_answer(config, text)).handle(request())
        for original in ("Тестов", "Пробная", "Образцов", "100001", "100003"):
            self.assertNotIn(original, seen[0])

    def test_download_failure_is_a_status(self):
        def closed(sheet_id):
            raise Failure("closed", "http_403")
        self.assertEqual(service(export_xlsx=closed).handle(request()), dict(version=1, status="closed", code="http_403"))

    def test_bad_requests(self):
        cases = {
            "bad_version": dict(version=2, op="analyze", sheet_id=SHEET_ID),
            "bad_op": dict(version=1, op="delete", sheet_id=SHEET_ID),
            "bad_sheet_id": dict(version=1, op="analyze", sheet_id="../../x"),
        }
        for code, body in cases.items():
            with self.subTest(code):
                self.assertEqual(service().handle(body), dict(version=1, status="rejected", code=code))
        self.assertEqual(service().handle([1])["code"], "bad_version")

    def test_input_over_the_limit_is_too_large(self):
        svc = service()
        svc.config = Config(max_input_bytes=100)
        self.assertEqual(svc.handle(request()), dict(version=1, status="too_large", code="input_too_large"))

    def test_unexpected_error_is_internal_without_details_in_response_or_logs(self):
        def boom(config, text):
            raise RuntimeError("Тестов Тест Тестович 100001")
        with self.assertLogs("sheet_analyzer", "INFO") as logs:
            response = service(run_model=boom).handle(request())
        self.assertEqual(response, dict(version=1, status="rejected", code="internal"))
        self.assertNotIn("Тестов", "\n".join(logs.output))
        self.assertNotIn("100001", "\n".join(logs.output))

    def test_logs_carry_no_sheet_data(self):
        with self.assertLogs("sheet_analyzer", "INFO") as logs:
            service().handle(request())
        text = "\n".join(logs.output)
        for forbidden in ("Тестов", "100001", "student_", "ДЗ", "Ведомость", SHEET_ID):
            self.assertNotIn(forbidden, text)
        self.assertIn("status=ok", text)


class SocketTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="sa-")
        self.path = os.path.join(self.dir, "a.sock")
        self.release = threading.Event()
        self.started = threading.Event()

        def slow_model(config, text):
            self.started.set()
            self.release.wait(10)
            return model_answer(config, text)

        config = Config(socket_path=self.path, io_timeout=2)
        self.server = server.Server(config, service(run_model=slow_model))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.release.set()
        self.server.shutdown()
        self.server.server_close()
        os.rmdir(self.dir)

    def call(self, payload):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(10)
            client.connect(self.path)
            client.sendall(payload)
            data = b""
            while not data.endswith(b"\n"):
                chunk = client.recv(65536)
                if not chunk:
                    break
                data += chunk
        self.assertTrue(data.endswith(b"\n"))
        self.assertEqual(data.count(b"\n"), 1)
        return json.loads(data)

    def test_socket_mode_is_0660(self):
        self.assertTrue(stat.S_ISSOCK(os.stat(self.path).st_mode))
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o660)

    def test_one_line_each_way(self):
        self.release.set()
        response = self.call(json.dumps(request("fingerprint")).encode() + b"\n")
        self.assertEqual(response["status"], "ok")
        self.assertNotIn("rule", response)

    def test_framing_errors(self):
        self.assertEqual(self.call(b"{not json\n"), dict(version=1, status="rejected", code="bad_request"))
        self.assertEqual(self.call(b"\xff\xfe\n"), dict(version=1, status="rejected", code="bad_request"))
        self.assertEqual(self.call(b"x" * 5000 + b"\n"), dict(version=1, status="rejected", code="request_too_large"))

    def test_silent_client_times_out_without_a_crash(self):
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(10)
            client.connect(self.path)
            self.assertEqual(client.recv(10), b"")  # server closes after io_timeout
        self.release.set()
        self.assertEqual(self.call(json.dumps(request("fingerprint")).encode() + b"\n")["status"], "ok")

    def test_second_concurrent_analyze_is_busy(self):
        results = []
        first = threading.Thread(target=lambda: results.append(self.call(json.dumps(request()).encode() + b"\n")))
        first.start()
        self.assertTrue(self.started.wait(10))
        self.assertEqual(self.call(json.dumps(request()).encode() + b"\n"), dict(version=1, status="busy", code="busy"))
        self.release.set()
        first.join(10)
        self.assertEqual(results[0]["status"], "ok")

    def test_stale_socket_file_is_replaced(self):
        self.server.server_close()
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as stale:
            stale.bind(self.path)
        replacement = server.Server(Config(socket_path=self.path), service())
        replacement.server_close()


if __name__ == "__main__":
    unittest.main()
