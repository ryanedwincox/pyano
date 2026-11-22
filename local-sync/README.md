<!-- Concern: documents local sync operation | Non-concern: cloud deployment | IO: none -->
# Local recording sync prototype

Pyano records WAV + MIDI locally. Select a take and tap Upload to encode AAC-LC
at 192 kbps into `.m4a` on the tablet and upload that file with MIDI. WAV originals
never leave the tablet. Older WAV-only takes upload without MIDI.

## Run on your computer

Requires Python 3.11+. No Python packages, database, or cloud account are needed.

```bash
cd /home/ryan/Work/pyano
python local-sync/server.py
```

Open **http://127.0.0.1:8765/**. The server prints the location of its device-token
file, not the secret itself. Standalone prototype clients can read
`local-sync/data/.device-token` locally. The current tablet UI supports Cloud only
and no longer configures this prototype endpoint; existing local data is retained.

Archived Local prototype tablet clients can forward their loopback port over USB:

```bash
adb reverse tcp:8765 tcp:8765
```

In that archived client, use `http://127.0.0.1:8765` in its settings. Alternatively, explicitly bind
with `python local-sync/server.py --host 0.0.0.0` on a trusted local network and
use `http://<computer-LAN-IP>:8765`. The default is loopback. HTTP is enabled only
in debug APKs; release builds require HTTPS. Local HTTP does not encrypt the
upload or token. The player and uploaded takes are readable by anyone who can
reach the server; there is no publication/admin distinction in this prototype.

In the archived client, record a short take, stop, then tap its cloud-upload icon. Wait for the Uploaded
message and refresh the browser library. MIDI lights the keyboard below audio:
green for held keys, amber for sustain. Pause/seek follow audio. Uploads are manual.

## Files and retries

`data/` is gitignored and contains the token and one directory per recording ID.
The ID hashes the original WAV + MIDI contents, so renaming locally does not
create another copy. A preflight receipt prevents re-encoding/re-upload after a
successful transfer whose response was lost. Interrupted uploads are discarded;
tap Upload again to retry. A completed ID cannot be replaced with different
assets. The first uploaded title is retained on retries.

The protocol is `PUT /api/recordings/<64-character SHA-256>` with a bearer token
and a ZIP containing `audio.m4a`, optional `performance.mid`, and `metadata.json`
(`{"version":1,"title":"…"}`). Limit: 128 MiB including expanded contents.
Bundles become visible atomically. No ZIP paths are extracted into the filesystem.
Successful uploads are immediately listed in the local player.

Local rename/delete acts on the WAV/MIDI pair, without affecting server copies.
A failed cleanup can leave recoverable `.deleted-*` folders in Downloads/Pyano;
errors identify them. If MIDI sidecar writing fails, a recovery copy remains in
app cache as `pyano_pending_*.mid`. Do not clear app storage before recovering it.

## Current limits
MIDI captures synth-bound live/touch/loop notes, velocity, controller changes,
selected bank/program, and initial controller/program state. The native metronome
is not MIDI-captured. Pitch bend/aftertouch are not yet rendered by the existing
input path. Notes already sounding before Record are not reconstructed.
MIDI uses a monotonic millisecond timeline and fixed tempo metadata. Native audio
buffering and AAC encoder delay can introduce offset; no sample-accurate
alignment or mastering-derived MIDI pairing is claimed yet. Mastered WAV exports
currently upload without a MIDI sidecar unless explicitly paired.

Uploads are manual, in-process jobs, not persistent background sync. Leaving the
app process ends the job; retry manually after restart. OAuth, publishing, PDFs,
statistics, and fluid visuals remain future work. This is a local prototype.

## Verify

```bash
python -m unittest discover -s local-sync -v
node --test local-sync/test-midi.mjs
./check-source.sh
cd platform_android && ./gradlew testDebugUnitTest
```

The server tests use real AAC when ffmpeg/ffprobe are installed. Android Kotlin/UI
compilation and JVM tests passed with a temporary native-build override: 30 passed,
one existing SoundFont-dependent test skipped. See [JVM verification](../docs/testing.md)
for the reproducible command and limits. A native arm64 APK was also built using
the installed app's FluidSynth binaries and installed as `com.pyano.localtest`
(label: Pyano Local Test), preserving the original app. The tablet's WAV/MIDI
recording and AAC-only upload smoke test passed; the test take was removed.
Desktop Chrome decoded uploaded AAC. Musical timing alignment still needs a
played-performance check. Native prerequisites are in [build.md](../docs/build.md).
