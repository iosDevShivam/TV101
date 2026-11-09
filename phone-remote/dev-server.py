#!/usr/bin/env python3
"""
Serve the phone remote's page without a TV, for working on its design.

It serves the real page from the launcher (launcher/app/src/main/assets/remote/index.html) and answers its API
with sample data: six apps on Home, a few more in All apps, two HDMI inputs. Key presses, links and typing are
printed instead of sent anywhere.

    python3 phone-remote/dev-server.py                 # http://localhost:8099
    python3 phone-remote/dev-server.py --art DIR       # app art from DIR/<package>.png (e.g. saved from a TV)

Open it in a phone-sized window (Chrome DevTools device mode, 390 x 844). The page pairs with any code here.
/__shot?tab=remote|apps|send&mode=pad|stick&paired=1|0 shows the page in a 390 x 844 frame for screenshots.
"""
import argparse
import json
import os
import struct
import sys
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "launcher", "app", "src", "main", "assets", "remote")
FONT = os.path.join(ROOT, "launcher", "app", "src", "main", "res", "font", "outfit.ttf")
TOKEN = "dev"

HOME = [("com.google.android.youtube.tv", "YouTube"), ("com.netflix.ninja", "Netflix"),
        ("in.startv.hotstar", "JioHotstar"), ("com.amazon.amazonvideo.livingroom", "Prime Video"),
        ("com.sonyliv", "Sony LIV"), ("com.google.android.youtube.tvmusic", "YouTube Music")]
MORE = [
        ("com.google.android.videos", "Play Movies & TV"), ("com.android.vending", "Play Store")]
INPUTS = [{"id": "hdmi1", "label": "HDMI 1", "connected": True}, {"id": "hdmi2", "label": "HDMI 2", "connected": False}]

def _png(r, g, b):
    """A 1 x 1 PNG of one colour, for apps without saved art."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes([0, r, g, b]))) + chunk(b"IEND", b""))


BLANK_PNG = _png(0x1C, 0x1F, 0x27)

SHOT = """<!doctype html><meta charset="utf-8"><body style="margin:0;background:#000">
<script>
  localStorage.setItem('tvToken', %(token)s);
  localStorage.setItem('tvTab', %(tab)s);
  localStorage.setItem('tvPadMode', %(mode)s);
</script>
<iframe src="/" style="border:0;width:390px;height:844px;display:block"></iframe>"""


class Handler(BaseHTTPRequestHandler):
    art_dir = None

    def log_message(self, fmt, *args):
        pass

    def send(self, status, ctype, body):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def json(self, obj, status=200):
        self.send(status, "application/json", json.dumps(obj).encode())

    def file(self, path, ctype):
        with open(path, "rb") as f:
            self.send(200, ctype, f.read())

    def paired(self, query):
        return self.headers.get("X-Token") == TOKEN or query.get("t", [""])[0] == TOKEN

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        p = url.path
        if p in ("/", "/index.html"):
            return self.file(os.path.join(ASSETS, "index.html"), "text/html; charset=utf-8")
        if p == "/manifest.webmanifest":
            return self.file(os.path.join(ASSETS, "manifest.webmanifest"), "application/manifest+json")
        if p == "/icon.png":
            return self.file(os.path.join(ASSETS, "icon.png"), "image/png")
        if p == "/outfit.ttf":
            return self.file(FONT, "font/ttf")
        if p == "/__shot":
            page = SHOT % {"token": json.dumps(TOKEN if q.get("paired", ["1"])[0] == "1" else ""),
                           "tab": json.dumps(q.get("tab", ["remote"])[0]),
                           "mode": json.dumps(q.get("mode", ["pad"])[0])}
            return self.send(200, "text/html; charset=utf-8", page.encode())
        if p == "/api/hello":
            return self.json({"name": "Living Room TV", "paired": self.paired(q)})
        if not self.paired(q):
            return self.json({"error": "pair first"}, 401)
        if p.startswith("/art/"):
            pkg = p[5:]
            path = os.path.join(self.art_dir, pkg + ".png") if self.art_dir else None
            if path and os.path.isfile(path):
                return self.file(path, "image/png")
            return self.send(200, "image/png", BLANK_PNG)
        if p == "/api/state":
            return self.json({"control": "ok", "sleepAt": 0,
                              "now": {"pkg": "com.netflix.ninja", "home": False, "label": "Netflix"}})
        if p == "/api/apps":
            apps = lambda items: [{"pkg": k, "label": v} for k, v in items]
            return self.json({"home": apps(HOME), "all": sorted(apps(HOME + MORE), key=lambda a: a["label"].lower()),
                              "inputs": INPUTS})
        self.send(404, "text/plain", b"")

    def do_POST(self):
        url = urlparse(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode() if length else ""
        if url.path == "/api/pair":
            print("pair: the TV would show a code now (any 4 digits work here)")
            return self.json({"ok": True})
        if url.path == "/api/pair/confirm":
            return self.json({"token": TOKEN})
        if not self.paired({}):
            return self.json({"error": "pair first"}, 401)
        print(f"{url.path} {body}")
        if url.path == "/api/action" and '"clearram"' in body:
            return self.json({"freed": 118})
        self.json({"ok": True})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8099)
    ap.add_argument("--art", help="folder of <package>.png app art")
    args = ap.parse_args()
    Handler.art_dir = args.art
    print(f"Phone remote preview: http://localhost:{args.port}  (Ctrl+C to stop)")
    try:
        ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
