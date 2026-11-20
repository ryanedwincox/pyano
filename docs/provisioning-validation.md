<!-- Concern: validates tablet credential provisioning | Non-concern: cloud deployment operation | IO: none -->
# Tablet provisioning validation

Automated JVM coverage validates strict v1 JSON, byte limits, public HTTPS origins,
portable owners, bearer-token boundaries, redacted failures, cancellation/late
scanner results, atomic profile selection and persistence/encryption failures.
Store reconstruction over in-memory encrypted fixtures verifies old profile IDs
remain loadable; this does not run a queued worker or restart the Android process.
Compose instrumentation tests cover masked paste, nonsecret identity confirmation,
cancel without saving, and failed save clearing the pending import.

## Manual tablet checklist

Use a disposable Cloud device token; the QR/JSON is a bearer credential. Never
include it in screenshots, logs, crash reports, test output, or issue comments.

- With audio, a loop, metronome, MIDI input and recording active, open recording
  sync settings and choose Cloud. Verify manual entry and Local prototype remain.
- Scan a provisioning QR offline. Verify only origin and owner appear at confirmation;
  cancel and confirm that previous settings and queued uploads remain unchanged.
- Deny CAMERA permission, deny permanently, and test a camera-less tablet. Verify
  Paste JSON remains usable and actionable fallback guidance appears.
- Paste a valid v1 payload. Confirm the field is masked and the confirmation shows
  no token. Save, restart Pyano, and upload a disposable recording successfully.
- Reject malformed/oversized JSON, extra/duplicate fields, version 2, HTTP/private
  endpoints, invalid owner IDs and tokens. Verify existing settings remain selected.
- Rotate/background/resume in paste, scan, permission and confirmation flows. A
  discarded pending import requires reimport; no automatic or duplicated save occurs.
- Cancel the scanner and start paste immediately. Verify a late scanner result
  cannot replace a newer pending import or change settings.
- Queue an upload with profile A, import B, then resume/restart uploads. Verify the
  queued upload still uses A; newly queued uploads use B.
- Verify synth, recording, loops, metronome, MIDI and foreground service continue
  through scanning, rotation and returning to the main screen.
- Verify screen capture/recents hide paste/confirmation and scanner content. Confirm
  app-private storage contains encrypted tokens and no retained camera images.

Device execution is not claimed by this checklist. Run it with test credentials
and record the tablet/Android version and actual results separately.
