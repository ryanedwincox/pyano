<!-- Concern: documents private score-library verification | Non-concern: PDF viewer behavior | IO: none -->
# Offline score library validation

The tablet uses the selected Cloud profile and an owner/profile/origin-isolated
cache under Android's no-backup private directory. PDFs and their metadata never
enter shared storage or Android Auto Backup. Removing a local copy does not send
a Cloud deletion request. Cached older versions and removed-remote scores remain
readable locally until explicitly removed.

## Cloud device wire

The adapter follows the deployed Cloud score contract:

- GET `/api/v1/scores`, with optional percent-encoded `cursor`; page size is server-controlled.
- JSON `{scores:[{id,title,metadataVersion,sizeBytes,sha256,contentType,downloadUrl}],nextCursor:null}`.
- `metadataVersion` is a positive integer; SHA-256 is lowercase hex and contentType is `application/pdf`.
- GET `/api/v1/scores/{id}/pdf` returns `application/pdf`; the client constructs the fixed path.
- All requests use the selected bearer token with `scores:read`; no owner is sent
  in the URL and redirects are rejected. PDF downloads are bounded to 25 MiB.

The wire adapter maps `id`/`metadataVersion` into the existing cache identity;
offline manifests retain their original `scoreId`/`version` format.

## Automated coverage

JVM tests use real temporary directories to exercise atomic manifest publication,
checksum/size/signature failures, cancellation and temp cleanup, cache reconstruction,
profile/owner/origin isolation, local removal, corrupt files, and remote changes.
A failed manifest publication after a same-version checksum change preserves the
previously referenced bytes. Injected transport tests cover malformed/paginated
responses, scope failures with redacted bodies, size limits and blocked-read
cancellation. Controller tests cover failed/malformed refresh and late operations
following profile switches, download-before-open, and suppressed opens after failures
or cancellation. Compose tests exercise compact rows, local-removal confirmation,
profile clearing, provisioning guidance and icon controls using synthetic metadata.

## Manual tablet checklist

Use disposable private scores and test credentials. Do not publish credentials,
personal score files, screenshots of private titles, or camera frames as artifacts.

- Provision a token with scores:read, choose Scores, and explicitly refresh.
- Tap a score title to download and open its PDF; tap again offline to reopen it.
- Switch to airplane mode, fully restart Pyano and check its title still opens offline.
- Change score content remotely and refresh; the old local copy must remain readable.
  Delete remotely and refresh; the offline copy must remain present.
- Interrupt/cancel a download and restart; no incomplete PDF may appear ready.
- Switch Cloud profile and owner; no previous profile's rows may appear.
- Revoke the token/remove its scope and refresh; provisioning guidance must appear
  while existing cached rows remain browsable. Reprovision and retry.
  An unchanged token keeps its existing profile and cached scores. A changed token
  creates a new profile and requires downloading again. Old private files remain
  isolated; the current UI does not select a previous credential profile.
- Tap trash and cancel; the local copy must remain. Confirm removal and check the
  Cloud file and other versions remain; Cloud rows can be downloaded again.
- Repeat refresh/download/cancel/remove while recording, loops, metronome and MIDI
  run. Verify audible continuity and foreground-service ownership manually.

The checklist is not a claim of live Cloud or audible continuity validation.
