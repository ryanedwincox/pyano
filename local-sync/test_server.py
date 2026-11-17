# Concern: verifies local sync boundaries and playback | Non-concern: Android encoding | IO: (HTTP and ZIP fixtures) -> assertions
import io
import json
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from zipfile import ZipFile

from server import make_server

MIDI = b'MThd' + struct.pack('>IHHH', 6, 0, 1, 500) + b'MTrk\x00\x00\x00\x04\x00\xff\x2f\x00'
AUDIO = b'\x00\x00\x00\x18ftypM4A ' + b'\x00' * 32
ID = 'a' * 64


def bundle(audio=AUDIO, midi=MIDI, title='A quiet take', extra=None):
    output = io.BytesIO()
    with ZipFile(output, 'w') as archive:
        archive.writestr('audio.m4a', audio)
        archive.writestr('metadata.json', json.dumps({'version': 1, 'title': title}))
        if midi is not None:
            archive.writestr('performance.mid', midi)
        if extra:
            archive.writestr(extra, b'escape')
    return output.getvalue()


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.server = make_server(self.root, port=0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, path, data=None, token=None, headers=None):
        headers = dict(headers or {})
        if token is not None:
            headers['Authorization'] = 'Bearer ' + token
        request = Request(self.url + path, data=data, headers=headers, method='PUT' if data is not None else 'GET')
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()

    def upload(self, body=None, recording_id=ID):
        return self.request('/api/recordings/' + recording_id, bundle() if body is None else body, self.server.token)

    def test_authenticated_atomic_retry_and_no_wav_storage(self):
        self.assertEqual(self.request('/api/recordings/' + ID, bundle(), 'wrong')[0], 401)
        self.assertEqual(self.request('/api/recordings')[2], b'[]')
        self.assertEqual(self.upload()[0], 200)
        self.assertEqual(self.upload()[0], 200)
        record = json.loads(self.request('/api/recordings/' + ID, token=self.server.token)[2])
        self.assertEqual(record['id'], ID)
        self.assertEqual(self.request('/api/recordings/' + ID, token='wrong')[0], 401)
        self.assertEqual(len(json.loads(self.request('/api/recordings')[2])), 1)
        self.assertEqual({p.name for p in (self.root / ID).iterdir()}, {'audio.m4a', 'performance.mid', 'metadata.json'})
        self.assertEqual(self.upload(bundle(audio=AUDIO + b'changed'))[0], 400)
        self.assertEqual((self.root / ID / 'audio.m4a').read_bytes(), AUDIO)

    def test_invalid_and_traversing_bundles_leave_no_partial_take(self):
        for payload in [b'not a zip', bundle(extra='../escape'), bundle(audio=b'RIFF WAV'), bundle(midi=b'broken')]:
            with self.subTest(payload=payload[:16]):
                self.assertEqual(self.upload(payload)[0], 400)
                self.assertFalse((self.root / ID).exists())
        self.assertFalse((self.root.parent / 'escape').exists())
        self.assertEqual(list(self.root.glob('.incoming-*')), [])
        self.assertEqual(self.upload(bundle(title=' '))[0], 400)
        self.assertEqual(self.upload(recording_id='../escape')[0], 404)

    def test_legacy_audio_and_byte_ranges(self):
        self.assertEqual(self.upload(bundle(midi=None))[0], 200)
        path = '/recordings/' + ID + '/audio.m4a'
        status, headers, body = self.request(path, headers={'Range': 'bytes=4-11'})
        self.assertEqual((status, body), (206, AUDIO[4:12]))
        self.assertEqual(headers['Content-Range'], f'bytes 4-11/{len(AUDIO)}')
        self.assertEqual(self.request(path, headers={'Range': 'bytes=-4'})[2], AUDIO[-4:])
        self.assertEqual(self.request(path, headers={'Range': 'bytes=9999-'})[0], 416)
        self.assertEqual(self.request('/recordings/' + ID + '/performance.mid')[0], 404)
        self.assertEqual(self.request('/')[0], 200)
        self.assertEqual(self.request('/../server.py')[0], 404)

    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'Requires ffmpeg/ffprobe')
    def test_real_compressed_audio_round_trip(self):
        audio = self.root / 'fixture.m4a'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1',
                        '-c:a', 'aac', '-b:a', '192k', str(audio)], check=True)
        self.assertEqual(self.upload(bundle(audio=audio.read_bytes()))[0], 200)
        received = self.request('/recordings/' + ID + '/audio.m4a')[2]
        self.assertEqual(received, audio.read_bytes())
        probe = subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of',
                                'default=noprint_wrappers=1:nokey=1', str(self.root / ID / 'audio.m4a')],
                               check=True, capture_output=True, text=True)
        self.assertAlmostEqual(float(probe.stdout), 1, delta=.1)


if __name__ == '__main__':
    unittest.main()
