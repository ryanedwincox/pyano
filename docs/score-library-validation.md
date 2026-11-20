<!-- Concern: documents private score-library verification | Non-concern: PDF viewer behavior | IO: none -->
# Offline score library validation

The tablet uses the selected Cloud profile and an owner/profile/origin-isolated
cache under Android's no-backup private directory. PDFs and their metadata never
enter shared storage or Android Auto Backup. Removing a local copy does not send
a Cloud deletion request. Cached older versions and removed-remote scores remain
readable locally until explicitly removed.

## Provisional device wire

The owner directed building while Cloud #13 was being implemented concurrently.
The wire adapter currently expects these additive v1 routes:

- GET `/api/v1/scores?limit=100`, with optional percent-encoded `cursor`.
- JSON `{scores:[{scoreId,title,version,sizeBytes,sha256}],nextCursor:null}`.
- `version` is a portable string or positive integer; SHA-256 is lowercase hex.
- GET `/api/v1/scores/{scoreId}/download` returns `application/pdf`.
- All requests use the selected bearer token with `scores:read`; no owner is sent
  in the URL and redirects are rejected. PDF downloads are bounded to 25 MiB.

The adapter is centralized in CloudScoreClient/ScoreModels. These paths and field
names must be compared with the published Cloud contract before integration.
Fixture verification does not establish interoperability with a live deployment.

## Automated coverage

JVM tests use real temporary directories to exercise atomic manifest publication,
checksum/size/signature failures, cancellation and temp cleanup, cache reconstruction,
profile/owner/origin isolation, local removal, corrupt files, and remote changes.
A failed manifest publication after a same-version checksum change preserves the
previously referenced bytes. Injected transport tests cover malformed/paginated
responses, scope failures with redacted bodies, size limits and blocked-read
cancellation. Controller tests cover failed/malformed refresh and late operations
following profile switches. Compose tests exercise local actions, profile clearing,
provisioning guidance and cancellation controls using synthetic metadata.

## Manual tablet checklist

Use disposable private scores and test credentials. Do not publish credentials,
personal score files, screenshots of private titles, or camera frames as artifacts.

- Provision a token with scores:read, choose Scores, and explicitly refresh.
- Download one PDF, switch to airplane mode, fully restart Pyano and check its
  title/version remains available offline. Opening/rendering belongs to issue #3.
- Change score content remotely and refresh; the old local version must remain
  marked older. Delete remotely and refresh; the offline copy must remain present.
- Interrupt/cancel a download and restart; no incomplete PDF may appear ready.
- Switch profile, owner and Local mode; no previous profile's rows may appear.
- Revoke the token/remove its scope and refresh; provisioning guidance must appear
  while existing cached rows remain browsable. Reprovision and retry.
  Reprovisioning creates a new profile even for the same owner/origin: the new
  library starts empty and requires downloading again. Old private files remain
  isolated; the current UI does not select a previous credential profile.
- Remove one local version and confirm the Cloud file and other versions remain.
- Repeat refresh/download/cancel/remove while recording, loops, metronome and MIDI
  run. Verify audible continuity and foreground-service ownership manually.

The checklist is not a claim of live Cloud or audible continuity validation.
