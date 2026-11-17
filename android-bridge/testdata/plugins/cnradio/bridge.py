#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""HLS -> ADTS bridge for the MyGo Music "云听" radio plugin.

MyGo Music decodes AAC only as ADTS frames (the first byte of the stream must
be 0xFF 0xF1/0xF9). Cloud Radio (radio.cn) serves its live streams as HLS
playlists of MPEG-TS segments, which the player cannot open. This tiny bridge
pulls those segments, extracts the ADTS AAC frames from the TS packets and
serves them as one endless HTTP stream at http://127.0.0.1:<port>/live.aac.

Standard library only, no third-party packages. Cross-platform: any machine
with Python 3. Started by the plugin through host.spawn(); the plugin owns the
process and kills it when the listener switches stations.

Usage:
    python3 bridge.py --url "<signed m3u8 url>" --port 21345
"""

import argparse
import http.server
import socket
import sys
import time
import urllib.parse
import urllib.request

REFERER = "https://www.radio.cn/"
USER_AGENT = "Mozilla/5.0 (compatible; MyGoMusic/1.0)"
HEALTH = b"cnradio-bridge-ok"


def http_get(url, timeout=12):
    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Referer": REFERER,
        "Accept": "*/*",
    })
    with urllib.request.urlopen(req, timeout=timeout) as res:
        return res.read()


def read_playlist(url):
    """Return (media_sequence, [absolute segment urls], target_duration)."""
    text = http_get(url, 10).decode("utf-8", "ignore")
    sequence, segments, target = 0, [], 10.0
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            try:
                sequence = int(line.split(":", 1)[1].strip())
            except ValueError:
                pass
        elif line.startswith("#EXT-X-TARGETDURATION:"):
            try:
                target = float(line.split(":", 1)[1].strip())
            except ValueError:
                pass
        elif not line.startswith("#"):
            segments.append(urllib.parse.urljoin(url, line))
    return sequence, segments, max(1.0, target)


class Demuxer:
    """Strip 188-byte MPEG-TS headers and emit complete ADTS AAC frames.

    ADTS frames can straddle a segment boundary, so the unfinished tail is
    carried into the next segment. PES headers and PCR/private data are skipped
    by scanning for the 0xFFF sync word rather than parsing PMT/PES.
    """

    def __init__(self):
        self.carry = bytearray()

    def feed(self, ts):
        payload = bytearray()
        pos, size = 0, len(ts)
        while pos + 188 <= size:
            if ts[pos] != 0x47:
                pos += 1
                continue
            packet = ts[pos:pos + 188]
            pos += 188
            adaptation = (packet[3] >> 4) & 0x03
            offset = 4
            if adaptation & 0x02:  # adaptation field present
                offset = 5 + packet[4]
            if (adaptation & 0x01) and offset < 188:  # payload present
                payload += packet[offset:188]

        data = self.carry + payload
        self.carry = bytearray()
        out = bytearray()
        i, n = 0, len(data)
        while i + 7 <= n:
            if data[i] == 0xFF and (data[i + 1] & 0xF6) == 0xF0:
                length = ((data[i + 3] & 0x03) << 11) | (data[i + 4] << 3) | (data[i + 5] >> 5)
                if length < 7:
                    i += 1
                    continue
                if i + length > n:
                    break  # incomplete frame, keep for the next segment
                out += data[i:i + length]
                i += length
            else:
                i += 1
        self.carry += data[i:]
        if len(self.carry) > 65536:  # guard against a non-AAC / encrypted stream
            self.carry = self.carry[-8192:]
        return bytes(out)


class Handler(http.server.BaseHTTPRequestHandler):
    source_url = ""
    server_version = "CNRadioBridge/1.0"

    def log_message(self, fmt, *args):
        pass  # keep the plugin's stderr quiet

    def do_GET(self):
        if self.path.startswith("/health"):
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(HEALTH)))
            self.end_headers()
            self.wfile.write(HEALTH)
            return
        if not self.path.startswith("/live"):
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "audio/aac")
        self.send_header("Cache-Control", "no-store, no-cache")
        self.send_header("Connection", "close")
        # No Content-Length: HTTP/1.0 clients read until the connection closes,
        # which is exactly what an endless radio stream wants.
        self.end_headers()
        try:
            self.pump()
        except (BrokenPipeError, ConnectionResetError, socket.timeout):
            pass  # listener stopped or switched stations
        except Exception as exc:
            print("bridge: %s" % exc, file=sys.stderr, flush=True)

    def pump(self):
        demux = Demuxer()
        played = set()
        backoff = 0
        while True:
            try:
                sequence, segments, target = read_playlist(self.source_url)
                backoff = 0
            except Exception as exc:
                backoff = min(backoff + 1, 6)
                print("bridge: playlist %s" % exc, file=sys.stderr, flush=True)
                time.sleep(backoff)
                continue

            fresh = [(sequence + i, url) for i, url in enumerate(segments)
                     if (sequence + i) not in played]
            if not played:  # first pass: start near live, don't dump the backlog
                fresh = fresh[-1:]
            for number, segment in fresh:
                try:
                    raw = http_get(segment, 15)
                except Exception:
                    continue
                frames = demux.feed(raw)
                if frames:
                    self.wfile.write(frames)
                    self.wfile.flush()
                played.add(number)

            if len(played) > 400:
                played = set(sorted(played)[-200:])
            time.sleep(max(1.0, target / 2.0))


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    parser = argparse.ArgumentParser(description="Cloud Radio HLS to ADTS bridge")
    parser.add_argument("--url", required=True, help="signed HLS playlist URL")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    Handler.source_url = args.url
    server = Server((args.host, args.port), Handler)
    print("bridge: listening on %s:%d" % (args.host, args.port), file=sys.stderr, flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
