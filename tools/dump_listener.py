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
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8099

# Planner traffic is handed on to the model server on the loopback, so the only
# port the phone has to reach is this one
UPSTREAM_PORT = 18080

# A vision model on a small card runs to the better part of a minute
PROXY_TIMEOUT = 300
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

        # The phone could post a megabyte here and could not get a request into
        # llama.cpp on its own port at all, so planner traffic is forwarded
        # through the one path that is known to work. It also prints what the
        # phone actually sent, which no amount of guessing had established
        if path.startswith("/v1/"):
            self.proxy(path)
            return

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

    def proxy(self, path):
        stamp = datetime.datetime.now().strftime("%H%M%S")
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""

        print("%s  -> %s  %d bytes from %s" % (stamp, path, len(raw), self.client_address[0]))
        sys.stdout.flush()

        request = urllib.request.Request(
            UPSTREAM + path,
            data=raw,
            headers={"Content-Type": "application/json"},
        )
        started = time.time()
        try:
            with urllib.request.urlopen(request, timeout=PROXY_TIMEOUT) as answer:
                body = answer.read()
                status = answer.status
        except urllib.error.HTTPError as failure:
            body = failure.read()
            status = failure.code
        except Exception as failure:
            # send_error puts the reason on the status line, which is latin-1
            # only, and an OS error here arrives translated into the system
            # language. The reason goes in the body instead
            print("          upstream failed: %s" % failure)
            sys.stdout.flush()
            body = json.dumps({"error": str(failure)}).encode("utf-8")
            status = 502

        # The decision itself, and the screen it was made on. Byte counts said
        # the exchange was healthy while the agent went nowhere at all
        try:
            sent = json.loads(raw)
            text = sent["messages"][0]["content"][0]["text"]
            screen = text.split("Current screen:", 1)[-1].strip().splitlines()
            print("          screen: %s" % " | ".join(x.strip() for x in screen[:6]))
            answer = json.loads(body)["choices"][0]["message"]
            chose = answer.get("content") or answer.get("reasoning_content") or ""
            print("          chose : %s" % " ".join(chose.split()))
            with open(os.path.join(OUT_DIR, "last-planner.txt"), "w", encoding="utf-8") as f:
                f.write(text + "\n\n=== ANSWER ===\n" + chose)
        except Exception as oops:
            print("          (could not read exchange: %s)" % oops)

        print("          <- %d in %.1fs, %d bytes" % (status, time.time() - started, len(body)))
        sys.stdout.flush()

        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        """The summary above is the log. The default one repeats it as noise."""


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    host = tailnet_address()

    # Threaded, because a planner call holds a connection for as long as the
    # model thinks and a dump arriving meanwhile must not wait behind it
    global UPSTREAM
    UPSTREAM = "http://%s:%d" % (host, UPSTREAM_PORT)

    server = ThreadingHTTPServer((host, PORT), Handler)
    print("listening on http://%s:%d" % (host, PORT))
    print("  /dump and /log   from the app")
    print("  /v1/...          forwarded to %s" % UPSTREAM)
    sys.stdout.flush()

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("stopped")


if __name__ == "__main__":
    main()
