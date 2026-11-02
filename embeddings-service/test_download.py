"""Exercise actual HTTP transfers using local fixture bytes; no inference is performed."""

from contextlib import contextmanager, redirect_stdout
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from download_model import download_file


@contextmanager
def server(respond):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            respond(self)

        def log_message(self, *_):
            pass

    instance = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{instance.server_port}/fixture"
    finally:
        instance.shutdown()
        instance.server_close()
        thread.join(timeout=5)


class DownloadTest(unittest.TestCase):
    def test_truncated_transfer_retries_and_activates_only_complete_file(self):
        payload = b"HTTP download fixture, never model weights" * 1024
        calls = []

        def respond(request):
            calls.append(True)
            request.send_response(200)
            request.send_header(
                "Content-Length", str(len(payload) + (10 if len(calls) == 1 else 0))
            )
            request.end_headers()
            request.wfile.write(payload)

        with server(
            respond
        ) as url, tempfile.TemporaryDirectory() as directory, redirect_stdout(
            io.StringIO()
        ) as log, patch(
            "download_model.time.sleep"
        ):
            target = Path(directory) / "fixture.bin"
            actual = download_file(url, target, time.monotonic() + 10)
            self.assertEqual(actual, hashlib.sha256(payload).hexdigest())
            self.assertEqual(target.read_bytes(), payload)
            self.assertEqual(len(calls), 2)
            self.assertEqual(list(Path(directory).glob("*.part")), [])
            self.assertIn("attempt 2/3", log.getvalue())
            self.assertIn("Incomplete download", log.getvalue())

    def test_forbidden_fails_without_retries_and_preserves_existing_file(self):
        calls = []

        def respond(request):
            calls.append(True)
            request.send_error(403)

        with server(
            respond
        ) as url, tempfile.TemporaryDirectory() as directory, redirect_stdout(
            io.StringIO()
        ) as log:
            target = Path(directory) / "fixture.bin"
            target.write_bytes(b"existing")
            with self.assertRaises(HTTPError):
                download_file(url, target, time.monotonic() + 10)
            self.assertEqual(target.read_bytes(), b"existing")
            self.assertEqual(len(calls), 1)
            self.assertIn("HTTP 403", log.getvalue())
            self.assertEqual(list(Path(directory).glob("*.part")), [])

    def test_same_length_wrong_content_is_rejected_before_replacing_cached_file(self):
        def respond(request):
            request.send_response(200)
            request.send_header("Content-Length", "3")
            request.end_headers()
            request.wfile.write(b"bad")

        with server(
            respond
        ) as url, tempfile.TemporaryDirectory() as directory, redirect_stdout(
            io.StringIO()
        ), patch(
            "download_model.DOWNLOAD_ATTEMPTS", 1
        ):
            target = Path(directory) / "fixture.bin"
            target.write_bytes(b"existing")
            expected = {"size": 3, "sha256": hashlib.sha256(b"yes").hexdigest()}
            with self.assertRaisesRegex(OSError, "upstream checksum"):
                download_file(url, target, time.monotonic() + 10, expected=expected)
            self.assertEqual(target.read_bytes(), b"existing")
            self.assertEqual(list(Path(directory).glob("*.part")), [])

    def test_elapsed_deadline_prevents_any_network_request(self):
        with tempfile.TemporaryDirectory() as directory, patch(
            "download_model.urlopen"
        ) as network:
            with self.assertRaises(TimeoutError):
                download_file(
                    "http://unused.test",
                    Path(directory) / "fixture.bin",
                    time.monotonic() - 1,
                )
            network.assert_not_called()

    def test_stalled_http_response_times_out_and_leaves_no_active_file(self):
        release = threading.Event()

        def respond(request):
            request.send_response(200)
            request.send_header("Content-Length", "3")
            request.end_headers()
            release.wait(timeout=2)

        try:
            with server(
                respond
            ) as url, tempfile.TemporaryDirectory() as directory, redirect_stdout(
                io.StringIO()
            ), patch(
                "download_model.NETWORK_TIMEOUT", 0.1
            ), patch(
                "download_model.DOWNLOAD_ATTEMPTS", 1
            ):
                target = Path(directory) / "fixture.bin"
                with self.assertRaises(TimeoutError):
                    download_file(url, target, time.monotonic() + 10)
                self.assertFalse(target.exists())
                self.assertEqual(list(Path(directory).glob("*.part")), [])
        finally:
            release.set()

    def test_progress_is_reported_during_transfer(self):
        def respond(request):
            request.send_response(200)
            request.send_header("Content-Length", "3")
            request.end_headers()
            request.wfile.write(b"abc")

        with server(
            respond
        ) as url, tempfile.TemporaryDirectory() as directory, redirect_stdout(
            io.StringIO()
        ) as log, patch(
            "download_model.time.monotonic", side_effect=[0, 0, 0, 0, 11, 12]
        ):
            download_file(url, Path(directory) / "fixture.bin", 100)
            self.assertIn("100.0%", log.getvalue())
            self.assertIn("elapsed 11s", log.getvalue())


if __name__ == "__main__":
    unittest.main()
