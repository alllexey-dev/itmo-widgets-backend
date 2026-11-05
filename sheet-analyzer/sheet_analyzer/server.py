"""Unix-socket server: one JSON request line in, one JSON response line out, per connection."""
import argparse
import dataclasses
import json
import logging
import os
import signal
import socket
import socketserver
import stat
import sys

from .config import DEFAULT_SOCKET, Config
from .service import Service, failure

MAX_REQUEST_BYTES = 4096


class RequestTooLarge(Exception):
    pass


def read_line(sock, limit=MAX_REQUEST_BYTES):
    data = b""
    while b"\n" not in data:
        chunk = sock.recv(1024)
        if not chunk:
            break
        data += chunk
        if len(data) > limit:
            raise RequestTooLarge()
    line = data.split(b"\n", 1)[0]
    if len(line) > limit:
        raise RequestTooLarge()
    return line


def respond(sock, response):
    sock.sendall(json.dumps(response, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(self.server.config.io_timeout)
        try:
            try:
                request = json.loads(read_line(sock).decode("utf-8"))
            except RequestTooLarge:
                return respond(sock, failure("rejected", "request_too_large"))
            except (ValueError, UnicodeDecodeError):
                return respond(sock, failure("rejected", "bad_request"))
            sock.settimeout(None)  # the analysis itself is bounded by the model timeout
            response = self.server.service.handle(request)
            sock.settimeout(self.server.config.io_timeout)
            respond(sock, response)
        except (socket.timeout, TimeoutError):
            logging.getLogger("sheet_analyzer").info("connection timeout")
        except OSError:
            logging.getLogger("sheet_analyzer").info("connection closed")


class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True

    def __init__(self, config, service):
        self.config = config
        self.service = service
        path = config.socket_path
        os.makedirs(os.path.dirname(path) or ".", mode=0o770, exist_ok=True)
        if os.path.lexists(path):
            if not stat.S_ISSOCK(os.lstat(path).st_mode):
                raise SystemExit(f"{path} exists and is not a socket")
            os.unlink(path)
        previous = os.umask(0o117)
        try:
            super().__init__(path, Handler)
        finally:
            os.umask(previous)
        os.chmod(path, 0o660)

    def server_close(self):
        super().server_close()
        try:
            os.unlink(self.config.socket_path)
        except FileNotFoundError:
            pass


def _stop(signum, frame):
    raise SystemExit(0)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="sheet_analyzer")
    parser.add_argument("--socket", help=f"unix socket path (default: $SHEET_ANALYZER_SOCKET or {DEFAULT_SOCKET})")
    args = parser.parse_args(argv)
    logging.basicConfig(stream=sys.stderr, level=logging.INFO, format="%(levelname)s %(message)s")
    config = Config.from_env()
    if args.socket:
        config = dataclasses.replace(config, socket_path=args.socket)
    server = Server(config, Service(config))
    signal.signal(signal.SIGTERM, _stop)
    logging.getLogger("sheet_analyzer").info("listening")
    try:
        server.serve_forever()
    finally:
        server.server_close()
