#!/usr/bin/env python3
"""Host-side RTP check: drive mockcam liveview, reassemble one JPEG like
LiveviewReceiver does, verify FFD8..FFD9. Run: python3 rtpcheck.py"""
import socket
import struct
import subprocess
import sys
import time
import urllib.request

MOCK_PORT = 8081

srv = subprocess.Popen(
    [sys.executable, "mockcam.py", "--port", str(MOCK_PORT), "--seed", "2"],
    stdout=subprocess.DEVNULL,
)
time.sleep(1.5)
try:
    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rx.bind(("127.0.0.1", 0))
    rx.settimeout(8)
    lv_port = rx.getsockname()[1]
    urllib.request.urlopen(
        f"http://127.0.0.1:{MOCK_PORT}/exec_takemisc.cgi?com=startliveview&port={lv_port}"
    ).read()

    frame = bytearray()
    expect = -1
    got = None
    deadline = time.time() + 8
    while time.time() < deadline:
        data, _ = rx.recvfrom(65535)
        if len(data) < 12 or (data[0] >> 6) & 3 != 2:
            continue
        seq = struct.unpack(">H", data[2:4])[0]
        marker = bool(data[1] & 0x80)
        off = 12 + 4 * (data[0] & 0x0F)
        if data[0] & 0x10:
            words = struct.unpack(">H", data[off + 2:off + 4])[0]
            off += 4 + words * 4
        is_start = data[off:off + 2] == b"\xff\xd8"
        if is_start and frame:
            frame = bytearray()
        if expect >= 0 and seq != expect:
            frame = bytearray()
            if not is_start:
                expect = -1
                continue
        expect = (seq + 1) & 0xFFFF
        frame += data[off:]
        if marker:
            if bytes(frame[-2:]) == b"\xff\xd9":
                got = bytes(frame)
                break
            frame = bytearray()
            expect = -1

    urllib.request.urlopen(
        f"http://127.0.0.1:{MOCK_PORT}/exec_takemisc.cgi?com=stopliveview"
    ).read()
    if got is None:
        print("RTP FAIL: no complete frame")
        sys.exit(1)
    print(f"RTP OK: {len(got)}B JPEG FFD8..FFD9, {frame[:0]!r}")
finally:
    srv.terminate()
