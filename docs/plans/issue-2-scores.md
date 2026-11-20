<!-- Concern: plans private offline score caching | Non-concern: PDF rendering or cloud administration | IO: none -->
# Issue #2: private offline score library

Status: active. Base: 945467f; build before viewer #3.
Owner directs proceeding assuming concurrent cloud prerequisites complete. Cloud #13
specifies semantics but currently published main has no score wire contract. Keep
all provisional wire choices in one client/parser and document them for integration.
Never edit the cloud repository; compare its published contract again before PR.

## Device wire contract

Use selected Cloud credentials with Authorization: Bearer; never send owner in URL.
Provisional GET /api/v1/scores?limit=100[&cursor=...] returns an object with scores
and nextCursor (null/absent ends pagination). Entries contain scoreId, title,
version, sizeBytes and sha256. GET /api/v1/scores/{scoreId}/download returns PDF.
Version is a bounded portable string or positive integer, normalized to string.
Identifiers match existing portable IDs; titles have 1–200 characters; size is
1–26,214,400 bytes; checksum is 64 lowercase hex. Reject duplicate identities,
malformed metadata/cursors and repeated cursors; bound page JSON and total entries.
Encode cursor as query data, never follow server URLs, and disable HTTP redirects
so credentials stay on the configured HTTPS origin. Keep legacy configured HTTPS
origins usable; no new HTTP cloud exception. Set connect/read timeouts.
List all pages before replacing the remote snapshot; a failed/canceled pagination
must preserve prior metadata. Download full PDF without ranges/resume for this issue.
Validate status/type, streamed byte limit, exact advertised size, SHA-256 and %PDF-
signature before marking ready. Do not trust Content-Length or remote filenames.
If server changed version between list/download, checksum mismatch requires refresh;
keep previous offline versions. Sanitize response failures; never echo bodies/token.
401/403 guidance requests new provisioning with scores:read; 404 asks for refresh.

## Private storage and state

Add score models, CloudScoreClient, ScoreCache and ScoreLibraryController in a
focused scores package with annotations. Inject transport/storage boundaries for
JVM fixtures; use existing Gson rather than Android mock JSON APIs. Cache lives in
context.noBackupFilesDir/scores, excluded from Android backup without config changes.
Namespace by SHA-256 of profileId, ownerId and canonical endpoint; immutable PDF
paths include hashed score/version/checksum identity. Persist validated metadata,
never tokens/remote paths; resolve all file access through the captured namespace.
Store a versioned manifest with the last complete remote snapshot plus downloaded
versions, titles and checksums. Preserve old cached rows when remote content changes
or disappears; label them older version or removed remotely, still available offline.
Read/verify completed local files on IO after controller creation, without network.
Missing/corrupt files become unavailable with actionable status, not ready entries.
Write downloads to unique private .part files; close and fsync, then atomic rename.
Never overwrite a referenced PDF before manifest commit, including same-version
checksum changes; a failed new publication leaves all previous bytes intact.
Publish readiness only after a complete atomic manifest write; remove unpublished
files on failure. Startup removes stale temporary/unreferenced files, never valid
referenced PDFs. Failed cleanup remains visible/retryable. Removing a local version
only alters local manifest/files; never issue DELETE to cloud. Handle partial remove
failures visibly and recover on reload; do not silently delete another cached version.
Serialize cache mutations; preserve previous manifest on write/rename failure.

## Selection, cancellation and UI

Append Scores to AppTab, preserving existing tab indexes. Screen shows selected
Cloud origin/owner, refresh, explicit per-score download/cancel and remove-local
copy actions, clear online/latest/cached-older/deleted/failed states and empty state.
Local mode/missing profile shows provisioning guidance and no previous owner rows.
Use a controller StateFlow collected by Compose; snapshot selected profile/credentials
at operation start. Expose selection updates from successful sync configuration;
immediately clear visible old namespace, cancel old operations and load the new one.
Tag results by captured namespace/generation so late completions cannot enter new UI.
All disk/network/hash work runs on IO, never audio callbacks or UI-critical work.
Disconnect/close network streams on cancellation; check cancellation between chunks;
finally cleans partial files. Screen exit cancels network work; shared audio owner,
MainActivity and foreground-service lifecycle remain unchanged. Reopening/restarting
loads cached metadata first; refresh is explicit. No viewer or remote-delete UI.

## Verification and delivery

JVM tests use real temporary directories plus injectable bounded HTTP streams:
validate wire types/limits/pagination, namespace and traversal isolation, successful
checksum download and cache reconstruction offline, mismatch/truncation/oversize,
interrupted/canceled operations and temp cleanup, no-backup root selection, failed
manifest publication after same-version checksum changes preserving prior bytes,
profile-switch late results, local removal, remote deletion/version change, and
401/403 guidance with redacted bodies. Verify malformed refresh preserves snapshot.
Compose tests cover offline rows/actions, failure guidance and profile-state clearing;
run on the isolated test package used by #1, preserving existing tablet sessions.
Run source gates, testDebugUnitTest, assembleDebug, assembleDebugAndroidTest and
isolated connectedDebugAndroidTest. Record actual counts/skips and manual gaps.
Add a manual checklist for offline process restart, real scores:read token, owner
switches, canceled downloads and uninterrupted recording/MIDI/loops/metronome.
Do not claim live cloud interoperability or audible continuity from fixture tests.
Move plan to done with reviewed implementation; report provisional wire assumptions
and any observed contract mismatch in the run PR, without weakening cache invariants.
