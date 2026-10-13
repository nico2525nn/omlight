#!/usr/bin/env python3
"""Mock OPC camera for emulator testing.

Serves the OPC HTTP dialect on 127.0.0.1:8080 (emulator reaches the host
via 10.0.2.2). Serves generated JPEGs + a synthetic RTP liveview stream.

Usage:
    python3 mockcam.py [--port 8080] [--shots DIR]

Endpoints:
    GET /get_connectmode.cgi        -> <connectmode>OPC</connectmode>
    GET /get_commandlist.cgi        -> shipping-dialect command list
    GET /get_caminfo.cgi            -> model E-Mock1
    GET /switch_cammode.cgi?mode=.. -> mode switch (tracks current mode)
    GET /get_imglist.cgi?DIR=..     -> VER_100 text list
    GET /get_thumbnail.cgi?DIR=..   -> 160x120 JPEG
    GET /get_screennail.cgi?DIR=..  -> 640x480 JPEG
    GET /get_resizeimg.cgi          -> resized JPEG
    GET /DCIM/...                   -> original file
    GET /exec_takemotion.cgi        -> take / movie record (creates a file)
    GET /exec_takemisc.cgi          -> start/stop liveview, getrecview
    GET /get_state.cgi              -> card status
"""
from __future__ import annotations

import argparse
import io
import os
import socket
import struct
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

try:
    from PIL import Image, ImageDraw
except ImportError:
    sys.exit("need Pillow: pip install Pillow")

HOST = "127.0.0.1"
ARGS: argparse.Namespace

# -- synthetic photo roll ------------------------------------------------------

ROLL: list[dict] = []  # {name, dir, bytes, thumb, screen, w, h}


def make_jpeg(w: int, h: int, label: str, hue: int, exif: bool = False) -> bytes:
    img = Image.new("RGB", (w, h), (hue % 256, (hue * 2) % 256, (hue * 3) % 256))
    d = ImageDraw.Draw(img)
    d.text((w // 4, h // 2 - 10), label, fill=(255, 255, 255))
    buf = io.BytesIO()
    if exif:
        try:
            import piexif
            zeroth = {
                piexif.ImageIFD.Make: "OLYMPUS IMAGING CORP.",
                piexif.ImageIFD.Model: "E-Mock1",
                piexif.ImageIFD.Software: "OMLightMock",
            }
            exif_ifd = {
                piexif.ExifIFD.DateTimeOriginal: "2026:10:12 12:00:00",
                piexif.ExifIFD.FNumber: (28, 10),
                piexif.ExifIFD.ExposureTime: (1, 250),
                piexif.ExifIFD.ISOSpeedRatings: 200,
                piexif.ExifIFD.ExposureBiasValue: (0, 3),
                piexif.ExifIFD.FocalLength: (2500, 100),
                piexif.ExifIFD.WhiteBalance: 0,
                piexif.ExifIFD.Flash: 16,
                piexif.ExifIFD.MeteringMode: 5,
                piexif.ExifIFD.ExposureProgram: 3,
                piexif.ExifIFD.ColorSpace: 1,
            }
            img.save(buf, "JPEG", quality=85,
                     exif=piexif.dump({"0th": zeroth, "Exif": exif_ifd}))
            return buf.getvalue()
        except ImportError:
            pass
    img.save(buf, "JPEG", quality=85)
    return buf.getvalue()


def add_photo(tag: str, folder: str = "/DCIM/100OLYMP", raw: bool = False) -> dict:
    n = len(ROLL) + 1
    ext = "ORF" if raw else "JPG"
    name = f"P{n:06d}.{ext}"
    if raw:
        # Synthetic ORF: real Olympus header + padding (NOT a valid raw,
        # but byte-identical in the magic the app verifies).
        full = b"IIRO" + bytes(44000)
    else:
        full = make_jpeg(1920, 1440, f"{tag} {name}", 40 + n * 37, exif=True)
    ROLL.append({
        "name": name, "dir": folder,
        "bytes": full,
        "thumb": make_jpeg(160, 120, name, 40 + n * 37),
        "screen": make_jpeg(640, 480, name, 40 + n * 37, exif=True),
    })
    return ROLL[-1]


def seed_roll(count: int) -> None:
    for i in range(count):
        # Mix: every 4th is ORF, second half lives in 101OLYMP
        # (same basenames across folders exercise temp-file collisions).
        folder = "/DCIM/101OLYMP" if i >= count // 2 else "/DCIM/100OLYMP"
        add_photo("seed", folder, raw=(i % 4 == 3))


# DCF date/time pack (spec section 6)
def pack_dt() -> tuple[int, int]:
    t = time.localtime()
    date = ((t.tm_year - 1980) << 9) | (t.tm_mon << 5) | t.tm_mday
    tm = (t.tm_hour << 11) | (t.tm_min << 5) | (t.tm_sec // 2)
    return date, tm


# -- RTP liveview sender -------------------------------------------------------

class LiveSender(threading.Thread):
    """Send one synthetic JPEG frame over RTP/UDP, ~4fps, until stopped."""

    def __init__(self, client_ip: str, port: int):
        super().__init__(daemon=True)
        self.dest = (client_ip, port)
        self.stop_ev = threading.Event()
        self.seq = 0
        self.ts = 0

    def run(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        frame_no = 0
        while not self.stop_ev.is_set():
            frame_no += 1
            jpeg = make_jpeg(640, 480, f"LIVE {frame_no}", frame_no * 11)
            # Split into ~8KB sub-frames like a real camera.
            chunks = [jpeg[i:i + 8192] for i in range(0, len(jpeg), 8192)]
            for i, ch in enumerate(chunks):
                last = i == len(chunks) - 1
                first = i == 0
                # RTP header: V=2, X=first, M=last, PT=96.
                b0 = 0x80 | (0x10 if first else 0)
                b1 = (0x80 if last else 0) | 96
                hdr = struct.pack(">BBHII", b0, b1, self.seq & 0xFFFF,
                                  self.ts & 0xFFFFFFFF, 0x12345678)
                self.seq += 1
                if first:
                    # Extension header: version=1, len=1 word, funcid=1 framesize.
                    hdr += struct.pack(">HHHHI", 1, 1, 1, 1, len(jpeg))
                try:
                    sock.sendto(hdr + ch, self.dest)
                except OSError:
                    pass
            self.ts += 1
            time.sleep(0.25)
        sock.close()


LIVE: LiveSender | None = None
MODE = {"cur": "play"}
REC = {"on": False}


# -- event notification server -------------------------------------------------
# Spec section 5: app calls start_pushevent.cgi?port=P, camera listens on P,
# app connects, camera pushes appID/event/len/XML frames.

class EventServer(threading.Thread):
    def __init__(self, port: int):
        super().__init__(daemon=True)
        self.port = port
        self.stop_ev = threading.Event()
        self.conn: socket.socket | None = None
        self.lock = threading.Lock()

    def run(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((HOST, self.port))
        srv.listen(1)
        srv.settimeout(0.5)
        while not self.stop_ev.is_set():
            try:
                c, _ = srv.accept()
            except socket.timeout:
                continue
            with self.lock:
                if self.conn is not None:
                    try:
                        self.conn.close()
                    except OSError:
                        pass
                self.conn = c
        srv.close()
        with self.lock:
            if self.conn is not None:
                try:
                    self.conn.close()
                except OSError:
                    pass
                self.conn = None

    def push(self, app_id: int, event_id: int, xml: str = "") -> None:
        body = xml.encode() if xml else b""
        frame = struct.pack(">HHH", app_id, event_id, len(body)) + body
        with self.lock:
            if self.conn is not None:
                try:
                    self.conn.sendall(frame)
                except OSError:
                    pass


EVT: EventServer | None = None
FOCAL = {"cur": 25, "min": 12, "max": 100}


def evt(app_id: int, event_id: int, xml: str = "") -> None:
    if EVT is not None:
        EVT.push(app_id, event_id, xml)


# -- HTTP ----------------------------------------------------------------------

XML = '<?xml version="1.0"?>\n<response>\n%s\n</response>\n'


class H(BaseHTTPRequestHandler):
    server_version = "MockCam/1.0"
    protocol_version = "HTTP/1.1"  # keep-alive like a real camera

    def log_message(self, *a):
        pass

    def _send(self, code: int, ctype: str, body: bytes, extra: dict | None = None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _xml(self, inner: str):
        self._send(200, "text/xml", (XML % inner).encode())

    def do_GET(self):
        global LIVE, EVT
        u = urlparse(self.path)
        q = parse_qs(u.query)
        p = u.path
        get = lambda k, d="": q.get(k, [d])[0]

        if p == "/get_connectmode.cgi":
            self._send(200, "text/xml",
                        b'<?xml version="1.0"?>\n<connectmode>private</connectmode>\n')
        elif p == "/get_commandlist.cgi":
            self._xml(COMMANDLIST)
        elif p == "/get_caminfo.cgi":
            self._xml("<model>E-Mock1</model><maker>MockCam</maker>")
        elif p == "/switch_cammode.cgi":
            MODE["cur"] = get("mode", "play")
            evt(2, 201)  # operation mode changed
            self._send(200, "text/xml", b"")
        elif p == "/switch_cameramode.cgi":
            MODE["cur"] = {"rec": "rec", "play": "play"}.get(get("mode"), "play")
            evt(2, 201)
            self._send(200, "text/xml", b"")
        elif p == "/start_pushevent.cgi":
            port = int(get("port", "65000"))
            if EVT is not None:
                EVT.stop_ev.set()
            EVT = EventServer(port)
            EVT.start()
            self._xml("<result>OK</result>")
        elif p == "/stop_pushevent.cgi":
            if EVT is not None:
                EVT.stop_ev.set()
                EVT = None
            self._xml("<result>OK</result>")
        elif p == "/get_state.cgi":
            self._xml("<cardstatus>ok</cardstatus><cardremainnum>999</cardremainnum>"
                       "<cardremainbyte>32000000</cardremainbyte>")
        elif p == "/get_imglist.cgi":
            d = get("DIR", "/DCIM")
            if d == "/DCIM":
                dt = pack_dt()
                body = ("VER_100\r\n/DCIM,100OLYMP,0,16,%d,%d\r\n"
                        "/DCIM,101OLYMP,0,16,%d,%d\r\n" % (dt + dt))
            elif d in ("/DCIM/100OLYMP", "/DCIM/101OLYMP"):
                lines = ["VER_100"]
                date, tm = pack_dt()
                for r in ROLL:
                    if r["dir"] != d:
                        continue
                    lines.append(f"{d},{r['name']},{len(r['bytes'])},32,{date},{tm}")
                body = "\r\n".join(lines) + "\r\n"
            else:
                self._send(404, "text/plain", b"no such dir")
                return
            self._send(200, "text/plain", body.encode())
        elif p == "/get_thumbnail.cgi":
            r = find(get("DIR"))
            if r is None:
                self._send(404, "text/plain", b"no such file")
                return
            self._send(200, "image/jpeg", r["thumb"])
        elif p == "/get_screennail.cgi":
            r = find(get("DIR"))
            if r is None:
                self._send(404, "text/plain", b"no such file")
                return
            self._send(200, "image/jpeg", r["screen"])
        elif p == "/get_resizeimg.cgi":
            r = find(get("DIR"))
            if r is None:
                self._send(404, "text/plain", b"no such file")
                return
            self._send(200, "image/jpeg", r["screen"])
        elif p == "/get_exif.cgi":
            r = find(get("DIR"))
            if r is None:
                self._send(404, "text/plain", b"no such file")
                return
            # Camera color/shooting info as flat XML (E-M5 II shape TBD;
            # merged with preview EXIF on the app side).
            self._xml("<shutspeedvalue>250</shutspeedvalue>"
                      "<focalvalue>2.8</focalvalue>"
                      "<expcomp>0.0</expcomp>"
                      "<isospeedvalue>200</isospeedvalue>"
                      "<wbvalue>auto</wbvalue>"
                      "<colortone>natural</colortone>"
                      "<takemode>A</takemode>")
        elif p == "/exec_takemotion.cgi":
            com = get("com")
            if com in ("starttake", "newstarttake"):
                evt(2, 103)  # capture started
                r = add_photo("shot")
                save_shot(r)
                evt(2, 106)  # capture finished
                evt(2, 107)  # capture process finished
                evt(2, 108)  # preview generated
                # Real shape: <take>ok</take>.
                self._send(200, "text/xml",
                            b'<?xml version="1.0"?><response><take>ok</take></response>')
            elif com in ("startmovietake", "newstartmovietake",
                         "stopmovietake", "newstopmovietake"):
                REC["on"] = com in ("startmovietake", "newstartmovietake")
                evt(2, 135 if REC["on"] else 110)
                self._send(200, "text/xml", b"")
            else:
                self._send(200, "text/xml", b"")
        elif p == "/exec_takemisc.cgi":
            com = get("com")
            if com == "startliveview":
                port = int(get("port", "5555"))
                if LIVE is not None:
                    LIVE.stop_ev.set()
                LIVE = LiveSender(self.client_address[0], port)
                LIVE.start()
                self._xml("<result>OK</result>")
            elif com == "stopliveview":
                if LIVE is not None:
                    LIVE.stop_ev.set()
                    LIVE = None
                self._xml("<result>OK</result>")
            elif com == "getrecview":
                r = ROLL[-1] if ROLL else add_photo("recview")
                self._send(200, "image/jpeg", r["screen"])
            elif com == "getlastjpg":
                r = ROLL[-1] if ROLL else add_photo("recview")
                self._send(200, "image/jpeg", r["bytes"])
            elif com == "changelvqty":
                self._send(200, "text/xml", b"")
            elif com == "ctrlzoom":
                move = get("move")
                if move == "telemove":
                    FOCAL["cur"] = min(FOCAL["max"], FOCAL["cur"] + 5)
                elif move == "widemove":
                    FOCAL["cur"] = max(FOCAL["min"], FOCAL["cur"] - 5)
                elif move == "off":
                    evt(2, 122)  # lens drive stopped
                self._send(200, "text/xml", b"")
            elif com == "newctrlzoom":
                ctrl, direction = get("ctrl"), get("dir")
                if direction == "fix":
                    FOCAL["cur"] = max(FOCAL["min"], min(FOCAL["max"], int(get("focallen", "25"))))
                    evt(2, 122)
                elif ctrl == "start":
                    step = 5 if direction == "tele" else -5
                    FOCAL["cur"] = max(FOCAL["min"], min(FOCAL["max"], FOCAL["cur"] + step))
                elif ctrl == "stop":
                    evt(2, 122)  # lens drive stopped
                self._send(200, "text/xml", b"")
            else:
                self._send(200, "text/xml", b"")
        elif p == "/exec_shutter.cgi":
            com = get("com")
            if com == "1st2ndpush":
                evt(2, 103)
            elif com == "2nd1strelease":
                # Full press completed: capture.
                r = add_photo("shot")
                save_shot(r)
                evt(2, 106)
                evt(2, 107)
                evt(2, 108)
            self._send(200, "text/xml", b"")
        elif p == "/exec_erase.cgi":
            r = find(get("DIR"))
            if r is None:
                self._xml("<result>NG</result>")
            else:
                ROLL.remove(r)
                self._xml("<result>OK</result>")
        elif p == "/exec_protect.cgi":
            ok = find(get("DIR")) is not None
            self._xml("<result>OK</result>" if ok else "<result>NG</result>")
        elif p == "/release_allprotect.cgi":
            # Async-ish: progress events then done.
            evt(2, 111, '<?xml version="1.0"?><root><processing>50/100</processing></root>')
            evt(2, 134)
            self._xml("<result>OK</result>")
        elif p == "/get_camprop.cgi":
            com = get("com")
            if com == "desc" and get("propname") == "desclist":
                self._xml("<desc><propname>EXP_COMP</propname><attribute>getset</attribute>"
                           "<value>0.0</value><enum>-1.0 0.0 +1.0</enum></desc>")
            else:
                self._xml("<value>0.0</value>")
        elif p.startswith("/DCIM/"):
            r = find(p)
            if r is None:
                self._send(404, "text/plain", b"no such file")
                return
            data = r["bytes"]
            ctype = "application/octet-stream" if r["name"].endswith(".ORF") else "image/jpeg"
            # HTTP Range (resume): "bytes=N-".
            rng = self.headers.get("Range", "")
            if rng.startswith("bytes="):
                try:
                    start = int(rng[6:].split("-")[0])
                except ValueError:
                    start = 0
                if start >= len(data):
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{len(data)}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                part = data[start:]
                self.send_response(206)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Range",
                                 f"bytes {start}-{len(data)-1}/{len(data)}")
                self.send_header("Content-Length", str(len(part)))
                self.end_headers()
                self.wfile.write(part)
                return
            self._send(200, ctype, data)
        else:
            self._send(404, "text/plain", b"unknown: " + p.encode())

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/set_camprop.cgi":
            length = int(self.headers.get("Content-Length", 0))
            self.rfile.read(length)
            self._xml("<result>OK</result>")
        else:
            self._send(404, "text/plain", b"unknown post")


def find(path: str) -> dict | None:
    for r in ROLL:
        if f"{r['dir']}/{r['name']}" == path or f"{r['dir']}/{r['name']}" == path.replace("%2F", "/"):
            return r
    return None


def save_shot(r: dict) -> None:
    if ARGS.shots:
        os.makedirs(ARGS.shots, exist_ok=True)
        with open(os.path.join(ARGS.shots, r["name"]), "wb") as f:
            f.write(r["bytes"])


COMMANDLIST = """<cgi name="switch_cammode"><http_method type="get">
<cmd1 name="mode"><param1 name="rec"><cmd2 name="lvqty"><param2 name="0320x0240"/><param2 name="0640x0480"/><param2 name="0800x0600"/><param2 name="1024x0768"/><param2 name="1280x0960"/></cmd2></param1><param1 name="play"/><param1 name="shutter"/></cmd1>
</http_method></cgi>
<cgi name="get_imglist"><http_method type="get">
<cmd1 name="DIR"/>
</http_method></cgi>
<cgi name="get_exif"><http_method type="get">
<cmd1 name="DIR"/>
</http_method></cgi>
<cgi name="get_thumbnail"><http_method type="get">
<cmd1 name="DIR"/>
</http_method></cgi>
<cgi name="get_screennail"><http_method type="get">
<cmd1 name="DIR"/>
</http_method></cgi>
<cgi name="get_resizeimg"><http_method type="get">
<cmd1 name="DIR"><param1><cmd2 name="size"><param2 name="1024"/><param2 name="1600"/><param2 name="1920"/><param2 name="2048"/></cmd2></param1></cmd1>
</http_method></cgi>
<cgi name="exec_takemotion"><http_method type="get">
<cmd1 name="com"><param1 name="starttake"><cmd2 name="point"/></param1><param1 name="stoptake"/><param1 name="startmovietake"/><param1 name="stopmovietake"/></cmd1>
</http_method></cgi>
<cgi name="exec_takemisc"><http_method type="get">
<cmd1 name="com"><param1 name="startliveview"><cmd2 name="port"/></param1><param1 name="stopliveview"/><param1 name="getrecview"/><param1 name="getlastjpg"/><param1 name="ctrlzoom"><cmd2 name="move"><param2 name="widemove"/><param2 name="telemove"/><param2 name="off"/></cmd2></param1></cmd1>
</http_method></cgi>
<cgi name="exec_shutter"><http_method type="get">
<cmd1 name="com"><param1 name="1st2ndpush"/><param1 name="2nd1strelease"/></cmd1>
</http_method></cgi>
<cgi name="get_camprop"><http_method type="get">
<cmd1 name="com"><param1 name="desc"/><param1 name="get"/></cmd1>
<cmd1 name="propname"><param1 name="*"/></cmd1>
</http_method></cgi>
<cgi name="set_camprop"><http_method type="post">
<cmd1 name="com"><param1 name="set"/></cmd1>
<cmd1 name="propname"><param1 name="*"/></cmd1>
</http_method></cgi>
<cgi name="exec_erase"><http_method type="get">
<cmd1 name="DIR"/>
</http_method></cgi>
<cgi name="exec_protect"><http_method type="get">
<cmd1 name="com"><param1 name="set"/><param1 name="release"/></cmd1>
</http_method></cgi>"""


def main() -> None:
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--shots", default="")
    ap.add_argument("--seed", type=int, default=12)
    ARGS = ap.parse_args()
    seed_roll(ARGS.seed)
    srv = ThreadingHTTPServer((HOST, ARGS.port), H)
    print(f"mockcam on http://{HOST}:{ARGS.port} ({len(ROLL)} seeded photos)")
    print("emulator: use host 10.0.2.2:%d" % ARGS.port)
    srv.serve_forever()


if __name__ == "__main__":
    main()
