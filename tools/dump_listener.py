"""Receives screen dumps posted by the phone.

The phone reaches this over the tailnet, which works on mobile data. Wireless
debugging does not: it needs the phone joined to a wifi network, and there is
not always one to join.

    python tools/dump_listener.py

Writes one json and, when the screen was captured, one png per dump into
tools/dumps/. Both are named after the moment they arrived, so a sequence of
screens reads in order.
"""

import base64
import datetime
import json
import os
import re
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8099
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dumps")

# A phone display png runs to a few hundred kilobytes. Anything far past that is
# not a dump and is refused before it is read into memory
MAX_BODY = 32 * 1024 * 1024


def tailnet_address():
    """The tailnet address of this machine, or loopback if tailscale is absent.

    Binding to the tailnet address rather than every interface matters here:
    this machine also sits on a company network, and the listener has no
    business answering anything on it.
    """
    for candidate in (
        r"C:\Program Files\Tailscale\tailscale.exe",
        "tailscale",
    ):
        try:
            out = subprocess.run(
                [candidate, "ip", "-4"],
                capture_output=True,
                text=True,
                timeout=5,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        address = out.stdout.strip().splitlines()
        if out.returncode == 0 and address:
            return address[0].strip()
    return "127.0.0.1"


def safe(name):
    return re.sub(r"[^A-Za-z0-9._-]", "_", name or "unknown")


def summarise(dump):
    elements = dump.get("elements", [])
    capture = dump.get("capture", {})

    parts = [
        dump.get("packageName", "?"),
        "%d elements" % len(elements),
        "text usable" if dump.get("textUsable") else "vision only",
    ]
    if dump.get("truncated"):
        parts.append("truncated")

    if not capture.get("attempted"):
        parts.append("no capture needed")
    elif capture.get("blank"):
        parts.append("CAPTURE BLANK - this app blocks screenshots")
    elif capture.get("bytes"):
        parts.append("capture %d KB" % (capture["bytes"] // 1024))
    else:
        parts.append("CAPTURE FAILED")

    return " | ".join(parts)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        path = self.path.rstrip("/")
        if path not in ("/dump", "/log"):
            self.send_error(404)
            return

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            self.send_error(413)
            return

        raw = self.rfile.read(length)
        try:
            dump = json.loads(raw)
        except ValueError as failure:
            self.send_error(400, str(failure))
            return

        stamp = datetime.datetime.now().strftime("%H%M%S")

        # A run log is what the phone wrote down while it worked, and it is
        # the only account of a run that stalled. Printed in full rather than
        # summarised: there is never much of it and every line matters
        if path == "/log":
            print("%s  RUN LOG  goal: %s" % (stamp, dump.get("goal", "?")))
            for line in dump.get("lines", []):
                print("          " + line)
            sys.stdout.flush()
            self.send_response(204)
            self.end_headers()
            return

        stem = os.path.join(OUT_DIR, "%s-%s" % (stamp, safe(dump.get("packageName"))))

        # The screenshot is stripped from the json and written beside it, or
        # every file is a megabyte of base64 nobody can read
        capture = dump.get("capture", {})
        encoded = capture.pop("pngBase64", None)
        if encoded:
            with open(stem + ".png", "wb") as png:
                png.write(base64.b64decode(encoded))

        with open(stem + ".json", "w", encoding="utf-8") as out:
            json.dump(dump, out, ensure_ascii=False, indent=2)

        print("%s  %s" % (stamp, summarise(dump)))
        print("          %s.json" % stem)
        sys.stdout.flush()

        self.send_response(204)
        self.end_headers()

    def log_message(self, *_):
        """The summary above is the log. The default one repeats it as noise."""


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    host = tailnet_address()

    server = HTTPServer((host, PORT), Handler)
    print("listening on http://%s:%d/dump" % (host, PORT))
    print("set that as the dump url in the app, then press Dump screen")
    sys.stdout.flush()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("stopped")


if __name__ == "__main__":
    main()
