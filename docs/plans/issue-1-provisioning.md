<!-- Concern: plans private tablet credential import | Non-concern: cloud admin implementation | IO: none -->
# Issue #1: private provisioning import

Status: active. Base: unattended/2026-11-19-tablet; implement before #2/#3.
The owner explicitly permits proceeding while cloud prerequisites build in parallel.
Use cloud #9's specified v1 payload; do not edit another repository.

## Contract and validation

Accept one JSON object containing exactly version, endpoint, ownerId, and token.
Require numeric integer version 1, string fields, no duplicates, trailing input,
unknown fields, coercions, or nested values. Bound input to 16 KiB UTF-8 before
parsing; use a strict reader with real JVM test support rather than Android's
mocked org.json methods. All parse failures become fixed nonsecret messages.
Require HTTPS origin only: host present, no credentials/path/query/fragment,
no localhost, loopback, unspecified, link-local, or private IP literals; reject
control characters and invalid ports. Do not perform DNS on the audio/UI thread.
Keep the local prototype's explicit HTTP exception in its existing manual path.
Owner identifiers use [A-Za-z0-9][A-Za-z0-9_-]{0,127}. Require a nonempty bounded
printable ASCII bearer token without whitespace/control characters; preserve its
exact bytes. Do not invent a mandatory token prefix absent from cloud #9.

## Scanner and UI

Add bundled com.journeyapps:zxing-android-embedded:4.3.0 using its ScanContract,
QR-only options, beep disabled and barcode-image capture explicitly disabled.
It supports existing minSdk 26 without Google Play Services or a network model.
Use an app-private nonexported CaptureActivity subclass with FLAG_SECURE;
CAMERA permission and optional camera feature must not exclude camera-less tablets.
Request permission only from Scan QR; denial/missing camera/launch failure/cancel
returns an actionable fixed message and leaves Paste JSON immediately available.
Retain manual Cloud fields, Local mode, and the existing recording-sync panel.
Both imports feed the same parser and a confirmation showing endpoint/owner only.
Use a password-transformed paste field; never echo raw QR/JSON or tokens in errors.
Use ordinary in-memory state only, never rememberSaveable, Bundles, logs, analytics,
clipboard writes, or generated data-class toString values containing credentials.
Clear raw text after parse and all pending credentials on dismiss/success/disposal.
Backgrounding or rotation may safely discard pending confirmation and require
reimport; no save occurs automatically. Scanner rotation/resume must return once
through the activity-result API. Ignore late results after importer cancellation.
PyanoApplication retains the shared ViewModel/engine; MainActivity starts the service
and stops it when the last main activity finishes. Scanner navigation must never
finish the main activity or reinitialize its shared audio owner.

## Persistence and ownership

Validate completely before calling a single credential-save boundary. Encrypt
before changing persisted selection; publish one atomic preferences edit containing
profile data and selected-cloud configuration, and report persistence failure.
Make that selected record authoritative for cloud endpoint/owner/profile on restart;
avoid split current-profile versus main-settings selection transactions. Preserve
legacy configuration through a one-time read fallback and preserve local mode.
Run encryption/persistence off the main thread and update visible state only after
success. Keep controls disabled during save and return explicit success/failure so
the dialog cannot claim success on a failed save. Never expose exception messages.
Always allocate a new profile; retain old encrypted profiles so queued WorkManager
jobs continue loading their captured profile IDs without credential substitution.
Tests use injected storage/encryption boundaries; Android Keystore remains the
production mechanism. Do not add a plaintext credential persistence path for tests.

## Verification and delivery

Test strict shape/types/duplicates/size/version, endpoints, owner/token boundaries,
redacted errors, and unchanged selection after rejection/encryption/store failure.
Test confirmation-before-save, cancel/late scanner results, pending-state clearing,
new-profile selection and an existing queued upload retaining its original profile.
Add Compose instrumentation coverage for paste, masked token, nonsecret confirmation,
cancel and save failure; compile its APK even if no device is attached. Report unrun
device tests explicitly; pure state-boundary tests run on JVM without camera mocks.
Run ./check-source.sh plus testDebugUnitTest, assembleDebug and assembleDebugAndroidTest
with installed JDK21/SDK/native prerequisites; obtain independent review/attestation.
Add a manual tablet checklist covering camera grant/deny/no camera, airplane-mode
QR/paste import, malformed payloads, rotation/background/cancel, process restart,
queued old-profile uploads, and successful new-profile upload with no leaked token.
Record actual device execution separately from the checklist; never claim it ran.
Move this plan to done only after the reviewed implementation lands on the run branch.

Scanner reference: https://github.com/journeyapps/zxing-android-embedded#usage-with-scancontract
