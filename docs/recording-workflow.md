<!-- Concern: documents keep/discard and private recording sync | Non-concern: cloud publication | IO: none -->
# Recording workflow

Stop finishes the temporary capture and asks Save or Discard. The decision stays
with the shared ViewModel across rotation and tab changes. Another take cannot
start until the decision completes. Discard removes temporary PCM without encoding
WAV or uploading. Capture still streams PCM to temporary storage so long takes do
not need to fit in RAM; no permanent library WAV/MIDI is created before Save.

Save prepares the WAV/MIDI bundle and durably records an automatic-upload intent
before atomically publishing the WAV in the library, then
queues private Cloud upload using the selected credential profile. Upload waits
for a connection and retries through WorkManager. If Cloud is not configured, the
take stays local and pending; configuring Cloud or restarting resumes pending
saved takes. Existing older recordings are not bulk-uploaded; their cloud icon can
queue them explicitly. Publication remains an action in the Cloud portal.

Each row shows a pending cloud, a progress indicator during upload, a green cloud
check after confirmed completion, or a retry action on failure. Receipts persist
independently of WorkManager history. Rename keeps the upload identity. Completed
snapshots are removed only after WorkManager reports success; upload snapshots keep
their original credentials even when settings change.

## Validation

Source checks and Android JVM/debug/test-APK builds passed. The executed JVM suite
has 92 tests with zero failures and one existing SoundFont skip; all 14 isolated
tablet tests passed on SM-X210 / Android 16.

JVM tests exercise temporary capture/discard, Save pairing, queue-intent failure
and retry without duplicate WAVs, and cloud indicator state priority. Isolated
Android UI tests exercise Save/Discard, busy decisions, indicators, and durable
receipts/pending paths across store reconstruction, rename and deletion. An Android
integration test verifies Save schedules the production upload worker in WorkManager. Live Cloud upload,
offline retry and audible recording/MIDI continuity require tablet validation with
configured Cloud credentials. Never include real tokens or recordings in fixtures.
