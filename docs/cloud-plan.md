<!-- Concern: plans tablet cloud integration | Non-concern: cloud service implementation | IO: none -->
# Pyano: cloud library, MIDI recordings, and sheet music

Status: proposed. Cloud counterpart: `music.incatpacitated.com`; see
`../../incatpacitated_dotcom/docs/music-plan.md` for its plan.

## Recording bundles

- Give each take a stable ID and metadata; save its WAV and standard MIDI file
  (`.mid`) together. Retain originals when creating mastered/listening versions.
- Capture notes, velocity, channels, sustain/control changes, pitch bend, and
  instrument changes needed to describe the performance. Include initial tempo
  and instrument/controller state; close held notes when recording stops.
- Use a shared monotonic recording timeline and store any audio/MIDI offset so
  note-driven visuals align with audible playback, including mastered versions.
- Capture USB MIDI, touch keyboard, and audible loop playback through a shared
  event path without duplicating events. Fan events out to loop recording and
  song recording independently: the current single `recordingListener` is used
  by the looper and cannot simply be reassigned.
- Keep older WAV-only recordings playable locally; compress them before upload
  and show visualization availability explicitly when a take has no MIDI.

## Manual cloud upload

- Record and discard takes locally. Add an explicit Upload action for selected
  takes; uploads remain private until published through the admin website.
- Configure the service URL and a revocable device token issued in the website's
  OAuth-protected admin. Prefer QR provisioning; protect credentials at rest.
- Encode the selected original/mastered audio on the tablet before upload. Upload
  only compressed audio, MIDI, and metadata; keep original WAV files local.
  Choose the audio codec/bitrate during implementation and account for encoder
  delay in audio/MIDI alignment.
- Upload these assets as one logical bundle. Use stable IDs,
  persisted background jobs, retries, checksums, and explicit completion so
  interrupted uploads neither duplicate takes nor expose incomplete bundles.
- Show queued/uploading/uploaded/failed state. Local deletion does not delete
  cloud copies; make cloud deletion a separate explicit action.
- Associate devices and recordings with an owner from the start, while initially
  supporting only Ryan's account.

## Sheet music

- Browse PDFs uploaded through the website; download/cache selected scores for
  offline use and show download state.
- Add a full-screen PDF viewer with zoom, manual page navigation, and saved page
  position. Keep the synth/audio service running while viewing music.
- No split view, automatic page turns, or pedal controls in the first version.

## Note-driven visuals

- First target: synchronized visuals in the website player, driven by the saved
  MIDI timeline rather than audio analysis. Keep tablet live visuals optional
  and later, to protect low-latency audio performance.
- Prototype fluid-like ink: pitch determines position/color, velocity controls
  impulse strength, and held notes/sustain feed trails into the simulation.
- Render visuals live in the website player; no video export is planned.

## Delivery and validation

1. Recording bundles and MIDI export; verify overlapping notes, sustain, loops,
   touch input, and alignment against recorded and compressed audio.
2. Device provisioning and manual upload; verify retry/restart, duplicate
   requests, revocation, and partial-bundle recovery.
3. PDF library and offline viewer; verify audio continuity and page restoration.
4. Website visualization prototype; optionally add tablet visuals later.
