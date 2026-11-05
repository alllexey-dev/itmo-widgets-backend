import email.message
import io
import os
import socket
import unittest
from unittest import mock
import urllib.error
import urllib.request

from sheet_analyzer import download
from sheet_analyzer.errors import Failure

SHEET_ID = "1AbCdEfGhIjKlMnOpQrStUvWxYz_-0123456789"


class Response(io.BytesIO):
    def __init__(self, body, url, headers=None):
        super().__init__(body)
        self.url = url
        self.headers = email.message.Message()
        for key, value in (headers or {}).items():
            self.headers[key] = value

    def geturl(self):
        return self.url


class FakeOpener:
    """Fake HTTP layer: returns a body or raises, and records requested URLs."""

    def __init__(self, body=b"PK\x03\x04data", error=None, final_url=None, headers=None):
        self.body, self.error, self.final_url, self.headers = body, error, final_url, headers
        self.urls = []

    def open(self, request, timeout):
        self.urls.append(request.full_url)
        if self.error:
            raise self.error
        return Response(self.body, self.final_url or request.full_url, self.headers)


def http_error(code):
    return urllib.error.HTTPError("https://docs.google.com/x", code, "error", email.message.Message(), io.BytesIO())


def status(call):
    try:
        call()
    except Failure as failure:
        return failure.status, failure.code
    return "ok", None


class SheetIdTests(unittest.TestCase):
    def test_valid_ids(self):
        for sheet_id in (SHEET_ID, "a" * 20, "Z" * 100):
            self.assertEqual(download.check_sheet_id(sheet_id), sheet_id)

    def test_invalid_ids_are_rejected(self):
        for sheet_id in ("short", "a" * 101, SHEET_ID + "/../x", SHEET_ID + "?a=b", "%2e" * 10, None, 12345678901234567890123):
            with self.subTest(sheet_id=sheet_id):
                self.assertEqual(status(lambda: download.check_sheet_id(sheet_id)), ("rejected", "bad_sheet_id"))

    def test_invalid_id_is_never_fetched(self):
        opener = FakeOpener()
        status(lambda: download.export_xlsx("../../etc/passwd/aaaaaaaaaaaaaaa", opener))
        self.assertEqual(opener.urls, [])


class ExportTests(unittest.TestCase):
    def test_export_url_and_body(self):
        opener = FakeOpener()
        self.assertEqual(download.export_xlsx(SHEET_ID, opener), b"PK\x03\x04data")
        self.assertEqual(opener.urls, [f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/export?format=xlsx"])

    def test_status_mapping(self):
        cases = {
            401: ("closed", "http_401"), 403: ("closed", "http_403"), 404: ("not_found", "http_404"),
            410: ("not_found", "http_410"), 413: ("too_large", "http_413"), 500: ("network", "http_500"),
            429: ("network", "http_429"),
        }
        for code, expected in cases.items():
            with self.subTest(code=code):
                self.assertEqual(status(lambda: download.export_xlsx(SHEET_ID, FakeOpener(error=http_error(code)))), expected)

    def test_network_errors(self):
        cases = {
            "timeout": (urllib.error.URLError(socket.timeout()), ("network", "timeout")),
            "dns": (urllib.error.URLError("no host"), ("network", "unreachable")),
            "reset": (ConnectionResetError(), ("network", "io_error")),
            "raw timeout": (TimeoutError(), ("network", "timeout")),
        }
        for name, (error, expected) in cases.items():
            with self.subTest(name):
                self.assertEqual(status(lambda: download.export_xlsx(SHEET_ID, FakeOpener(error=error))), expected)

    def test_html_instead_of_xlsx_is_closed(self):
        opener = FakeOpener(body=b"<!DOCTYPE html><html>Sign in</html>")
        self.assertEqual(status(lambda: download.export_xlsx(SHEET_ID, opener)), ("closed", "not_xlsx"))

    def test_size_limits(self):
        big = FakeOpener(body=b"PK" + b"0" * 64)
        original = download.XLSX_LIMIT
        download.XLSX_LIMIT = 32
        try:
            self.assertEqual(status(lambda: download.export_xlsx(SHEET_ID, big)), ("too_large", "body_limit"))
            declared = FakeOpener(headers={"Content-Length": "1000"})
            self.assertEqual(status(lambda: download.export_xlsx(SHEET_ID, declared)), ("too_large", "content_length"))
        finally:
            download.XLSX_LIMIT = original

    def test_landing_on_sign_in_is_closed(self):
        opener = FakeOpener(final_url="https://accounts.google.com/ServiceLogin?continue=x")
        self.assertEqual(status(lambda: download.export_xlsx(SHEET_ID, opener)), ("closed", "signin_redirect"))


class RedirectTests(unittest.TestCase):
    def redirect(self, url):
        request = urllib.request.Request(f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/export?format=xlsx")
        return status(lambda: download.Redirects().redirect_request(request, None, 302, "Found", email.message.Message(), url))

    def test_within_docs_google_com_over_https_is_followed(self):
        self.assertEqual(self.redirect(f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/export?format=xlsx&x=1"), ("ok", None))

    def test_google_sheet_content_host_is_followed(self):
        self.assertEqual(self.redirect("https://doc-10-88-sheets.googleusercontent.com/export/abc"), ("ok", None))

    def test_sign_in_redirect_is_closed(self):
        self.assertEqual(self.redirect("https://accounts.google.com/v3/signin/identifier?continue=x"), ("closed", "signin_redirect"))
        self.assertEqual(self.redirect("https://docs.google.com/ServiceLogin?continue=x"), ("closed", "signin_redirect"))

    def test_other_hosts_and_plain_http_are_refused(self):
        for url in ("http://docs.google.com/x", "https://evil.example/x", "https://docs.google.com.evil.example/x",
                    "http://doc-10-88-sheets.googleusercontent.com/x",
                    "https://evil.googleusercontent.com/x", "https://doc-1-sheets.googleusercontent.com.evil.example/x"):
            with self.subTest(url=url):
                self.assertEqual(self.redirect(url), ("network", "offsite_redirect"))

    def test_default_opener_ignores_environment_proxies(self):
        with mock.patch.dict(os.environ, HTTPS_PROXY="http://127.0.0.1:3181", https_proxy="http://127.0.0.1:3181"):
            handlers = download.opener().handlers
        self.assertFalse(any(isinstance(h, urllib.request.ProxyHandler) and h.proxies for h in handlers))
        self.assertTrue(any(isinstance(h, download.Redirects) for h in handlers))


class TabListTests(unittest.TestCase):
    def test_parses_names_and_gids(self):
        body = ('<script>items.push({name: "\\u0412\\u0435\\u0434\\u043e\\u043c\\u043e\\u0441\\u0442\\u044c", pageUrl: "u", gid: "1819564669"});'
                'items.push({name: "Tab \\x22two\\x22", pageUrl: "u2", gid: "42"});</script>').encode()
        opener = FakeOpener(body=body)
        self.assertEqual(download.tab_list(SHEET_ID, opener), [dict(name="Ведомость", gid="1819564669"), dict(name='Tab "two"', gid="42")])
        self.assertEqual(opener.urls, [f"https://docs.google.com/spreadsheets/d/{SHEET_ID}/htmlview"])

    def test_sign_in_page_is_closed(self):
        opener = FakeOpener(body=b'<a href="https://accounts.google.com/ServiceLogin">Sign in</a>')
        self.assertEqual(status(lambda: download.tab_list(SHEET_ID, opener)), ("closed", "signin_page"))


if __name__ == "__main__":
    unittest.main()
