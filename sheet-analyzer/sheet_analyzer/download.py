"""Fetches the XLSX export and the tab list of a public Google Sheet, directly and only from docs.google.com."""
import http.client
import json
import re
import socket
import urllib.error
import urllib.parse
import urllib.request

from .errors import Failure

HOST = "docs.google.com"
SHEET_ID = re.compile(r"[A-Za-z0-9_-]{20,100}")
XLSX_LIMIT = 30 * 1024 * 1024
HTML_LIMIT = 10 * 1024 * 1024
TIMEOUT_SECONDS = 90
USER_AGENT = "itmo-widgets-sheet-analyzer/1"
SIGN_IN_HOSTS = ("accounts.google.com",)
# Google serves the XLSX export through a 307 to its per-shard content host, e.g. doc-10-88-sheets.googleusercontent.com.
CONTENT_HOST = re.compile(r"doc-[a-z0-9-]+-sheets\.googleusercontent\.com")
TAB_ITEM = re.compile(
    r'items\.push\(\{\s*name:\s*"((?:[^"\\]|\\.)*)",\s*pageUrl:\s*"((?:[^"\\]|\\.)*)",\s*gid:\s*"((?:[^"\\]|\\.)*)"'
)


def check_sheet_id(sheet_id):
    if not isinstance(sheet_id, str) or not SHEET_ID.fullmatch(sheet_id):
        raise Failure("rejected", "bad_sheet_id")
    return sheet_id


def _allowed(url):
    host = (url.hostname or "").lower()
    return url.scheme == "https" and (host == HOST or CONTENT_HOST.fullmatch(host) is not None)


def _is_sign_in(url):
    host = (url.hostname or "").lower()
    return host in SIGN_IN_HOSTS or host.endswith(".accounts.google.com") or "/ServiceLogin" in url.path


class Redirects(urllib.request.HTTPRedirectHandler):
    """Follows redirects only to https docs.google.com or Google's sheet content host; a sign-in page means closed."""

    max_redirections = 5

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        url = urllib.parse.urlsplit(newurl)
        if _is_sign_in(url):
            raise Failure("closed", "signin_redirect")
        if not _allowed(url):
            raise Failure("network", "offsite_redirect")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def opener():
    # ProxyHandler({}) ignores *_PROXY variables: docs.google.com is reached directly.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), Redirects())


def _http_failure(code):
    if code in (401, 403):
        return Failure("closed", f"http_{code}")
    if code in (404, 410):
        return Failure("not_found", f"http_{code}")
    if code == 413:
        return Failure("too_large", "http_413")
    return Failure("network", f"http_{code}")


def fetch(url, limit, http_opener=None):
    if not url.startswith(f"https://{HOST}/"):
        raise Failure("rejected", "bad_url")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with (http_opener or opener()).open(request, timeout=TIMEOUT_SECONDS) as response:
            final = urllib.parse.urlsplit(response.geturl())
            if _is_sign_in(final):
                raise Failure("closed", "signin_redirect")
            if not _allowed(final):
                raise Failure("network", "offsite_redirect")
            length = response.headers.get("Content-Length", "")
            if length.isdigit() and int(length) > limit:
                raise Failure("too_large", "content_length")
            data = response.read(limit + 1)
    except Failure:
        raise
    except urllib.error.HTTPError as error:
        error.close()
        raise _http_failure(error.code) from None
    except (TimeoutError, socket.timeout):
        raise Failure("network", "timeout") from None
    except urllib.error.URLError as error:
        timed_out = isinstance(error.reason, (TimeoutError, socket.timeout))
        raise Failure("network", "timeout" if timed_out else "unreachable") from None
    except (OSError, http.client.HTTPException):
        raise Failure("network", "io_error") from None
    if len(data) > limit:
        raise Failure("too_large", "body_limit")
    return data


def export_xlsx(sheet_id, http_opener=None):
    check_sheet_id(sheet_id)
    data = fetch(f"https://{HOST}/spreadsheets/d/{sheet_id}/export?format=xlsx", XLSX_LIMIT, http_opener)
    if not data.startswith(b"PK"):
        # Google answers a closed sheet with an HTML page instead of the export.
        raise Failure("closed", "not_xlsx")
    return data


def _unescape(text):
    text = re.sub(r"\\x([0-9a-fA-F]{2})", r"\\u00\1", text)
    return json.loads('"' + text.replace("\\'", "'") + '"')


def tab_list(sheet_id, http_opener=None):
    """Returns [{"name": tab name, "gid": "123"}] in workbook order; hidden tabs are not listed by Google."""
    check_sheet_id(sheet_id)
    body = fetch(f"https://{HOST}/spreadsheets/d/{sheet_id}/htmlview", HTML_LIMIT, http_opener)
    html = body.decode("utf-8", "replace")
    tabs = []
    for match in TAB_ITEM.finditer(html):
        try:
            tabs.append(dict(name=_unescape(match[1]), gid=_unescape(match[3])))
        except ValueError:
            continue
    if not tabs and "ServiceLogin" in html:
        raise Failure("closed", "signin_page")
    return tabs
