<!-- Concern: documents keep/discard and private recording sync | Non-concern: cloud publication | IO: none -->
# Recording workflow

Stop finishes the temporary capture and asks Save or Discard. The decision stays
with the shared ViewModel across rotation and tab changes. Another take cannot
start until the decision completes. Discard removes temporary PCM without encoding
WAV or uploading. Capture still streams PCM to temporary storage so long takes do
not need to fit in RAM; no permanent library WAV/MIDI is created before Save.

Save opens an editable naming dialog; cancel returns to Save/Discard and keeps the take.
Confirming the name durably tracks staging before encoding the WAV/MIDI bundle, then records an automatic-upload intent
before atomically publishing the WAV in the library. Interrupted complete staging stays available under Recover;
incomplete staging is preserved with a warning. Recovery without a saved upload intent keeps the take local.
Content-digest proofs protect deferred uploads against edited files. Save then
queues private Cloud upload using the selected credential profile. Upload waits
for a connection and retries through WorkManager. If Cloud is not configured, the
take stays local and pending; configuring Cloud or restarting resumes pending
saved takes. Pre-upgrade intents without a verified file identity pause with a warning
and remain local until file identity and prior Cloud ownership can be proven.
Unbound local takes can be uploaded explicitly. Unknown-owner historical receipts
remain read-only. Publication remains an action in the Cloud portal.

Each row shows a pending cloud, a progress indicator during upload, a green cloud
check after confirmed completion, or a retry action on failure. Receipts persist
independently of WorkManager history. Rename keeps the upload identity. Completed
snapshots are removed only after WorkManager reports success; uploads keep their
original owner/profile identity and use the latest verified token for that same owner.
Token ownership is verified with Cloud before saving credentials and before uploads or title updates.
Cloud must expose authenticated `/api/v1/device`; older servers pause configuration and sync until updated.

Tablet renames queue a separate authenticated Cloud title update. The immutable
upload title and asset identity stay unchanged for upload retries; the latest name
is sent independently; it can update pending Cloud metadata before upload completes. Offline title edits persist across restart, and
rapid edits converge on the newest name. Both Cloud pages show it on their next
fetch. A title failure uses the recording warning icon without changing its audio
upload receipt. Bound owners cannot be changed by selecting another account;
rotating credentials for the same owner/origin is supported. Older receipts with
ambiguous owner history keep the local rename and show a warning instead.

## Validation

Historical keep/discard validation (before the title-sync queue) passed source and
Android JVM/debug/test-APK checks: 92 JVM tests, one SoundFont skip, and 14 isolated
tablet tests on SM-X210 / Android 16. These counts do not cover later title-sync changes.

JVM tests exercise temporary capture/discard, Save pairing, queue-intent failure
and retry without duplicate WAVs, and cloud indicator state priority. Isolated
Android UI tests exercise Save/Discard, busy decisions, indicators, and durable
receipts/pending paths across store reconstruction, rename and deletion. An Android
integration test verifies Save schedules the production upload worker in WorkManager. Live Cloud upload,
offline retry and audible recording/MIDI continuity require tablet validation with
configured Cloud credentials. Never include real tokens or recordings in fixtures.
