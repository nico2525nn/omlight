#!/usr/bin/env python3
"""OMLight camera probe — Termux-friendly, stdlib only.

Connect the phone to the camera Wi-Fi, then run:

    python3 camprobe.py

Dumps handshake, capabilities, listings, and sample binaries into
./camprobe-<model>-<date>/ for protocol analysis. Read-only EXCEPT
--shoot (takes one photo) and --live N (captures N liveview frames).

    python3 camprobe.py --host 192.168.0.10 --ua "OI.Share v2"
    python3 camprobe.py --shoot          # take 1 photo (tries shutter+take)
    python3 camprobe.py --live 30        # save 30 liveview JPEGs
    python3 camprobe.py --deep           # + camprop desclist, resize matrix

Termux setup: pkg install python ; no pip packages needed.
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

UA_DEFAULT = "OI.Share v2"
UA_SPEC = "OlympusCameraKit"
HOST_DEFAULT = "192.168.0.10"


class Cam:
    def __init__(self, host: str, ua: str, outdir: str, timeout: float = 12.0):
        self.base = f"http://{host}"
        self.ua = ua
        self.out = outdir
        self.timeout = timeout
        os.makedirs(outdir, exist_ok=True)
        self.logf = open(os.path.join(outdir, "probe.log"), "w")

    def log(self, *a):
        line = " ".join(str(x) for x in a)
        print(line, flush=True)
        self.logf.write(line + "\n")
        self.logf.flush()

    def save(self, name: str, data: bytes):
        with open(os.path.join(self.out, name), "wb") as f:
            f.write(data)

    def get(self, path: str, params: dict | None = None,
            headers: dict | None = None) -> tuple[int, dict, bytes]:
        url = self.base + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={
            "User-Agent": self.ua, **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.status, dict(r.headers.items()), r.read()
        except urllib.error.HTTPError as e:
            try:
                body = e.read()
            except Exception:
                body = b""
            return e.code, dict((e.headers or {}).items()), body

    def probe_ua(self) -> str:
        """Try both UAs; return the working one. Cameras may go silent
        (timeout) instead of answering 4xx on the wrong UA."""
        for ua in (self.ua, UA_SPEC if self.ua == UA_DEFAULT else UA_DEFAULT):
            self.log(f"--- UA try: {ua} ---")
            old, self.ua = self.ua, ua
            try:
                st, _, body = self.get("/get_connectmode.cgi")
                if st == 200 and body.strip():
                    self.log(f"UA OK: {ua} ({len(body)}B)")
                    self.save(f"connectmode-{_slug(ua)}.xml", body)
                    return ua
                self.log(f"UA {ua}: HTTP {st} {len(body)}B")
            except Exception as e:
                self.log(f"UA {ua}: {type(e).__name__}: {e}")
                self.ua = old
        raise SystemExit("no UA answered; is the camera Wi-Fi joined?")

    # -- handshake ------------------------------------------------------

    def handshake(self):
        st, _, body = self.get("/get_connectmode.cgi")
        self.log(f"connectmode: HTTP {st}: {body[:120]!r}")
        self.save("connectmode.xml", body)
        st, _, body = self.get("/get_caminfo.cgi")
        self.log(f"caminfo: HTTP {st} ({len(body)}B)")
        self.save("caminfo.xml", body)
        st, _, body = self.get("/get_commandlist.cgi")
        self.log(f"commandlist: HTTP {st} ({len(body)}B)")
        self.save("commandlist.xml", body)
        # Optional extras, best-effort.
        for cgi in ("get_state.cgi", "get_commpath.cgi", "get_activate.cgi",
                    "get_unusedcapacity.cgi", "get_dcffilenum.cgi",
                    "get_gpsdivunit.cgi", "get_agpsinfo.cgi"):
            try:
                st, _, body = self.get(f"/{cgi}")
                self.log(f"{cgi}: HTTP {st} ({len(body)}B)")
                self.save(cgi.replace(".cgi", ".xml"), body)
            except Exception as e:
                self.log(f"{cgi}: {type(e).__name__}: {e}")

    # -- playback --------------------------------------------------------

    def playback(self):
        st, _, body = self.get("/switch_cammode.cgi", {"mode": "play"})
        self.log(f"switch play: HTTP {st} ({len(body)}B) {body[:80]!r}")
        # Walk /DCIM one level, then list each folder (cap output).
        st, _, body = self.get("/get_imglist.cgi", {"DIR": "/DCIM"})
        self.save("imglist-DCIM.txt", body)
        text = body.decode("utf-8", "replace")
        self.log("imglist /DCIM:", text.splitlines()[:4])
        dirs = []
        for line in text.splitlines()[1:6]:
            parts = line.split(",")
            if len(parts) >= 2 and parts[0].strip() == "/DCIM":
                dirs.append("/DCIM/" + parts[1].strip())
        for d in dirs[:4]:
            st, _, body = self.get("/get_imglist.cgi", {"DIR": d})
            name = "imglist-" + d.strip("/").replace("/", "_") + ".txt"
            self.save(name, body)
            lines = body.decode("utf-8", "replace").splitlines()
            self.log(f"imglist {d}: HTTP {st} ({len(lines)-1} entries)")
        # First real file: thumb, screen, resize, exif, head bytes.
        first = self._first_file()
        if first:
            self.log("first file:", first)
            for cgi, nm in (("get_thumbnail.cgi", "thumb.jpg"),
                            ("get_screennail.cgi", "screen.jpg")):
                st, _, body = self.get(f"/{cgi}", {"DIR": first})
                self.log(f"{cgi}: HTTP {st} ({len(body)}B)")
                self.save(nm if st == 200 and len(body) > 500 else nm + ".err",
                          body)
            st, _, body = self.get("/get_resizeimg.cgi",
                                   {"DIR": first, "size": "1920"})
            self.log(f"resize1920: HTTP {st} ({len(body)}B)")
            self.save("resize1920.jpg" if len(body) > 500 else "resize1920.err",
                      body)
            st, _, body = self.get("/get_exif.cgi", {"DIR": first})
            self.log(f"get_exif: HTTP {st} ({len(body)}B) {body[:80]!r}")
            self.save("exif.bin", body)
            # Original head: Range probe (206 vs 200 tells support).
            req = urllib.request.Request(
                self.base + urllib.parse.quote(first),
                headers={"User-Agent": self.ua, "Range": "bytes=0-1023"})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    head = r.read()
                    self.log(f"original Range 0-1023: HTTP {r.status} "
                             f"({len(head)}B) CR={r.headers.get('Content-Range')}")
                    self.save("original-head.bin", head)
            except urllib.error.HTTPError as e:
                self.log(f"original Range: HTTP {e.code} (no resume)")
            except Exception as e:
                self.log(f"original Range: {type(e).__name__}: {e}")

    def _first_file(self) -> str | None:
        for f in sorted(os.listdir(self.out)):
            if f.startswith("imglist-") and f != "imglist-DCIM.txt":
                for line in open(os.path.join(self.out, f),
                                 errors="replace").read().splitlines()[1:]:
                    p = line.split(",")
                    if len(p) >= 2 and "." in p[1]:
                        d = f.removeprefix("imglist-").removesuffix(".txt")
                        d = "/" + d.replace("_", "/", 1)
                        return f"{d}/{p[1].strip()}"
        return None

    # -- record / shoot ----------------------------------------------------

    def record(self, deep: bool):
        st, _, body = self.get("/switch_cammode.cgi",
                               {"mode": "rec", "lvqty": "0640x0480"})
        self.log(f"switch rec: HTTP {st} ({len(body)}B) {body[:80]!r}")
        for prop in ("desclist",):
            st, _, body = self.get("/get_camprop.cgi",
                                   {"com": "desc", "propname": prop})
            self.log(f"camprop desc {prop}: HTTP {st} ({len(body)}B)")
            self.save(f"camprop-{prop}.xml", body)
        if deep:
            for prop in ("expcomp", "isospeedvalue", "wbvalue", "takemode",
                         "shutspeedvalue", "focalvalue"):
                try:
                    st, _, body = self.get("/get_camprop.cgi",
                                           {"com": "get", "propname": prop})
                    self.log(f"camprop get {prop}: HTTP {st} {body[:60]!r}")
                except Exception as e:
                    self.log(f"camprop get {prop}: {type(e).__name__}")
        st, _, body = self.get("/switch_cammode.cgi", {"mode": "play"})
        self.log(f"switch back play: HTTP {st}")

    def shoot(self):
        self.log("=== SHOOT (shutter dialect) ===")
        self.get("/switch_cammode.cgi", {"mode": "shutter"})
        for com in ("1stpush", "2ndpush", "2ndrelease", "1strelease"):
            st, _, body = self.get("/exec_shutter.cgi", {"com": com})
            self.log(f"shutter {com}: HTTP {st} {body[:100]!r}")
            time.sleep(0.6 if com == "1stpush" else 0.3)
        self.get("/switch_cammode.cgi", {"mode": "play"})
        self.log("=== SHOOT (takemotion dialect) ===")
        self.get("/switch_cammode.cgi", {"mode": "rec", "lvqty": "0640x0480"})
        for com in ("starttake", "newstarttake"):
            st, _, body = self.get("/exec_takemotion.cgi", {"com": com})
            self.log(f"takemotion {com}: HTTP {st} {body[:120]!r}")
            if st == 200 and b"errorcode" not in body:
                break
        self.get("/switch_cammode.cgi", {"mode": "play"})

    def live(self, nframes: int):
        self.log(f"=== LIVEVIEW ({nframes} frames) ===")
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx.bind(("0.0.0.0", 0))
        rx.settimeout(6.0)
        port = rx.getsockname()[1]
        self.get("/switch_cammode.cgi", {"mode": "rec", "lvqty": "0640x0480"})
        st, _, body = self.get("/exec_takemisc.cgi",
                               {"com": "startliveview", "port": str(port)})
        self.log(f"startliveview:{port}: HTTP {st} {body[:200]!r}")
        got, buf, expect = 0, bytearray(), -1
        t0 = time.time()
        try:
            while got < nframes and time.time() - t0 < 30:
                try:
                    data, _ = rx.recvfrom(65535)
                except socket.timeout:
                    self.log("liveview: UDP timeout (no packets?)")
                    break
                if len(data) < 12 or (data[0] >> 6) & 3 != 2:
                    continue
                seq = struct.unpack(">H", data[2:4])[0]
                marker = bool(data[1] & 0x80)
                off = 12 + 4 * (data[0] & 0x0F)
                if data[0] & 0x10:
                    words = struct.unpack(">H", data[off + 2:off + 4])[0]
                    off += 4 + words * 4
                if off >= len(data):
                    continue
                if data[off:off + 2] == b"\xff\xd8" and buf:
                    buf = bytearray()
                if expect >= 0 and seq != expect:
                    buf = bytearray()
                    if data[off:off + 2] != b"\xff\xd8":
                        expect = -1
                        continue
                expect = (seq + 1) & 0xFFFF
                buf += data[off:]
                if marker and bytes(buf[-2:]) == b"\xff\xd9":
                    got += 1
                    self.save(f"live-{got:03d}.jpg", bytes(buf))
                    buf = bytearray()
                    expect = -1
        finally:
            try:
                self.get("/exec_takemisc.cgi", {"com": "stopliveview"})
                self.get("/switch_cammode.cgi", {"mode": "play"})
            except Exception:
                pass
            rx.close()
        self.log(f"liveview: saved {got}/{nframes} frames")

    def movie(self):
        self.log("=== MOVIE toggle (5s) ===")
        self.get("/switch_cammode.cgi", {"mode": "rec", "lvqty": "0640x0480"})
        st, _, body = self.get("/exec_takemotion.cgi", {"com": "startmovietake"})
        self.log(f"startmovietake: HTTP {st} {body[:100]!r}")
        time.sleep(5)
        st, _, body = self.get("/exec_takemotion.cgi", {"com": "stopmovietake"})
        self.log(f"stopmovietake: HTTP {st} {body[:100]!r}")
        self.get("/switch_cammode.cgi", {"mode": "play"})


def _slug(s: str) -> str:
    return "".join(c if c.isalnum() else "_" for c in s)


def main():
    ap = argparse.ArgumentParser(description="OMLight camera probe (Termux OK)")
    ap.add_argument("--host", default=HOST_DEFAULT)
    ap.add_argument("--ua", default=UA_DEFAULT)
    ap.add_argument("--shoot", action="store_true", help="take 2 test photos")
    ap.add_argument("--live", type=int, default=0, metavar="N",
                    help="capture N liveview frames")
    ap.add_argument("--movie", action="store_true", help="5s test recording")
    ap.add_argument("--deep", action="store_true", help="extra camprop reads")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    outdir = args.out or f"camprobe-{stamp}"
    cam = Cam(args.host, args.ua, outdir)
    cam.log(f"OMLight probe -> {outdir} host={args.host}")
    try:
        cam.probe_ua()
        cam.handshake()
        cam.playback()
        cam.record(args.deep)
        if args.movie:
            cam.movie()
        if args.shoot:
            cam.shoot()
        if args.live > 0:
            cam.live(args.live)
        cam.log("DONE. Zip the folder to share (strip private shots first):")
        cam.log(f"  tar czf {outdir}.tgz {outdir}")
    finally:
        cam.logf.close()


if __name__ == "__main__":
    main()
