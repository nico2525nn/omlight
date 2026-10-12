#!/usr/bin/env python3
"""Host-side event check: start mockcam, enable push events, fire a capture,
verify the TCP event frames. Run: python3 evtcheck.py"""
import socket
import struct
import subprocess
import sys
import time
import urllib.request

PORT = 8083
EVT = 65001

srv = subprocess.Popen(
    [sys.executable, "mockcam.py", "--port", str(PORT), "--seed", "2"],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
)
time.sleep(1.5)
try:
    base = f"http://127.0.0.1:{PORT}"
    urllib.request.urlopen(f"{base}/start_pushevent.cgi?port={EVT}").read()
    s = socket.create_connection(("127.0.0.1", EVT), timeout=5)
    s.settimeout(5)
    # Trigger a capture; expect 103,106,107,108 frames (may coalesce).
    urllib.request.urlopen(f"{base}/exec_takemotion.cgi?com=newstarttake").read()
    got = []
    s.settimeout(3)
    try:
        while len(got) < 4:
            hdr = s.recv(6, socket.MSG_WAITALL)
            if len(hdr) < 6:
                break
            app, ev, ln = struct.unpack(">HHH", hdr)
            if ln:
                s.recv(ln, socket.MSG_WAITALL)
            got.append((app, ev))
    except socket.timeout:
        pass
    print("events:", got)
    ids = [e for _, e in got]
    assert 103 in ids and 106 in ids and 107 in ids, f"missing capture events: {ids}"
    # Zoom stop event.
    urllib.request.urlopen(
        f"{base}/exec_takemisc.cgi?com=newctrlzoom&ctrl=start&dir=tele&method=contnormal"
    ).read()
    urllib.request.urlopen(
        f"{base}/exec_takemisc.cgi?com=newctrlzoom&ctrl=stop&dir=tele"
    ).read()
    # Admin: delete + protect.
    lst = urllib.request.urlopen(f"{base}/get_imglist.cgi?DIR=/DCIM/100OLYMP").read().decode()
    names = [ln.split(",")[1] for ln in lst.splitlines()[1:] if ln]
    d = urllib.request.urlopen(f"{base}/exec_erase.cgi?DIR=/DCIM/100OLYMP/{names[0]}").read()
    assert b"OK" in d, d
    p = urllib.request.urlopen(
        f"{base}/exec_protect.cgi?com=set&DIR=/DCIM/100OLYMP/{names[1]}").read()
    assert b"OK" in p, p
    urllib.request.urlopen(f"{base}/stop_pushevent.cgi").read()
    print("EVT OK")
finally:
    srv.terminate()
