# Concern: serves local recording bundles | Non-concern: cloud publishing | IO: (HTTP requests) -> stored audio and responses
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from zipfile import BadZipFile, ZipFile

MAX_UPLOAD = 128 * 1024 * 1024
WEB = Path(__file__).parent / 'web'


def store_bundle(root, source, recording_id):
    with ZipFile(source) as bundle:
        names = bundle.namelist()
        allowed = {'audio.m4a', 'performance.mid', 'metadata.json'}
        if len(names) != len(set(names)) or not set(names) <= allowed:
            raise ValueError('Unexpected or duplicate bundle entries')
        if not {'audio.m4a', 'metadata.json'} <= set(names):
            raise ValueError('Missing audio or metadata')
        if sum(entry.file_size for entry in bundle.infolist()) > MAX_UPLOAD:
            raise ValueError('Expanded bundle is too large')
        if bundle.getinfo('metadata.json').file_size > 16384:
            raise ValueError('Metadata is too large')
        metadata = json.loads(bundle.read('metadata.json'))
        if not isinstance(metadata, dict) or metadata.get('version') != 1:
            raise ValueError('Unsupported metadata version')
        title = metadata.get('title')
        if not isinstance(title, str) or not title.strip() or len(title) > 200:
            raise ValueError('Title must contain 1–200 characters')
        with tempfile.TemporaryDirectory(dir=root, prefix='.incoming-') as staging:
            staging = Path(staging)
            hashes = {}
            for name in sorted(set(names) - {'metadata.json'}):
                target = staging / name
                with bundle.open(name) as src, target.open('wb') as dst:
                    remaining = MAX_UPLOAD
                    while chunk := src.read(min(65536, remaining + 1)):
                        remaining -= len(chunk)
                        if remaining < 0:
                            raise ValueError('Asset is too large')
                        dst.write(chunk)
                with target.open('rb') as stored:
                    hashes[name] = hashlib.file_digest(stored, 'sha256').hexdigest()
            with (staging / 'audio.m4a').open('rb') as source_audio:
                audio = source_audio.read(12)
            if len(audio) < 12 or audio[4:8] != b'ftyp':
                raise ValueError('Audio must be an M4A container')
            if 'performance.mid' in hashes:
                with (staging / 'performance.mid').open('rb') as source_midi:
                    midi = source_midi.read(14)
                if len(midi) < 14 or midi[:8] != b'MThd\x00\x00\x00\x06':
                    raise ValueError('Invalid MIDI header')
            destination = root / recording_id
            record = {'id': recording_id, 'title': title.strip(), 'hashes': hashes}
            (staging / 'metadata.json').write_text(json.dumps(record))
            try:
                os.rename(staging, destination)
            except OSError:
                if not destination.is_dir():
                    raise
                existing = json.loads((destination / 'metadata.json').read_text())
                if existing['hashes'] != hashes:
                    raise ValueError('Recording ID already exists with different assets')
                return existing
            staging.mkdir()
            return record


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, data, mime='application/json'):
        payload = json.dumps(data).encode() if mime == 'application/json' else data
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(payload)

    def do_PUT(self):
        expected = 'Bearer ' + self.server.token
        if not hmac.compare_digest(self.headers.get('Authorization', '').encode(), expected.encode()):
            self.close_connection = True
            return self.reply(401, {'error': 'Device token required'})
        match = re.fullmatch(r'/api/recordings/([a-f0-9]{64})', self.path)
        if not match:
            self.close_connection = True
            return self.reply(404, {'error': 'Unknown upload route'})
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if self.headers.get('Transfer-Encoding') or not 0 < size <= MAX_UPLOAD:
                raise ValueError('A Content-Length of 1–128 MiB is required')
            with tempfile.TemporaryFile() as source:
                remaining = size
                self.connection.settimeout(60)
                while remaining:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        raise ValueError('Incomplete upload')
                    source.write(chunk)
                    remaining -= len(chunk)
                source.seek(0)
                record = store_bundle(self.server.root, source, match[1])
            self.reply(200, record)
        except (ValueError, BadZipFile, KeyError, UnicodeError, EOFError) as exc:
            self.close_connection = True
            self.reply(400, {'error': str(exc)})
        except OSError:
            self.close_connection = True
            self.reply(500, {'error': 'Upload failed; retry when the server is available'})

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/api/recordings':
            records = [json.loads(p.read_text()) for p in self.server.root.glob('*/metadata.json')
                       if re.fullmatch('[a-f0-9]{64}', p.parent.name)]
            return self.reply(200, sorted(records, key=lambda record: record['title'].casefold()))
        record_match = re.fullmatch(r'/api/recordings/([a-f0-9]{64})', path)
        if record_match:
            expected = ('Bearer ' + self.server.token).encode()
            if not hmac.compare_digest(self.headers.get('Authorization', '').encode(), expected):
                return self.reply(401, {'error': 'Device token required'})
            metadata = self.server.root / record_match[1] / 'metadata.json'
            if not metadata.is_file():
                return self.reply(404, {'error': 'Not found'})
            return self.reply(200, json.loads(metadata.read_text()))
        pages = {'/': ('index.html', 'text/html; charset=utf-8'),
                 '/player.js': ('player.js', 'text/javascript; charset=utf-8'),
                 '/keyboard.js': ('keyboard.js', 'text/javascript; charset=utf-8'),
                 '/midi.mjs': ('midi.mjs', 'text/javascript; charset=utf-8'),
                 '/style.css': ('style.css', 'text/css; charset=utf-8')}
        if path in pages:
            name, mime = pages[path]
            return self.reply(200, (WEB / name).read_bytes(), mime)
        match = re.fullmatch(r'/recordings/([a-f0-9]{64})/(audio.m4a|performance.mid)', path)
        if not match or not (file := self.server.root / match[1] / match[2]).is_file():
            return self.reply(404, {'error': 'Not found'})
        self.serve_asset(file)

    def serve_asset(self, file):
        size = file.stat().st_size
        start, end, status = 0, size - 1, 200
        requested = self.headers.get('Range')
        if requested:
            match = re.fullmatch(r'bytes=(\d*)-(\d*)', requested)
            if not match or not any(match.groups()):
                return self.reply(416, {'error': 'Invalid byte range'})
            left, right = match.groups()
            if left:
                start = int(left)
                end = min(int(right), end) if right else end
            else:
                start = max(0, size - int(right))
            if start > end or start >= size:
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{size}')
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            status = 206
        self.send_response(status)
        self.send_header('Content-Type', 'audio/mp4' if file.suffix == '.m4a' else 'audio/midi')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(end - start + 1))
        self.send_header('X-Content-Type-Options', 'nosniff')
        if status == 206:
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
        self.end_headers()
        with file.open('rb') as source:
            source.seek(start)
            remaining = end - start + 1
            while remaining:
                chunk = source.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


def make_server(root, host='127.0.0.1', port=8765):
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    token_file = root / '.device-token'
    if not token_file.exists():
        with token_file.open('x') as target:
            os.chmod(token_file, 0o600)
            target.write(secrets.token_urlsafe(32))
    server = ThreadingHTTPServer((host, port), Handler)
    server.root, server.token = root, token_file.read_text().strip()
    return server


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Pyano local compressed-audio/MIDI sync prototype')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--data', type=Path, default=Path(__file__).parent / 'data')
    args = parser.parse_args()
    with make_server(args.data, args.host, args.port) as server:
        print(f'Player: http://{args.host}:{args.port}/', flush=True)
        print(f'Device token file: {server.root / ".device-token"}', flush=True)
        server.serve_forever()
