<!-- Concern: plans private offline PDF reading | Non-concern: cloud APIs or audio processing | IO: none -->
# Issue #3: full-screen offline score viewer

Status: done. Base: bc1eb76; reviewed cache prerequisite #2 is on this run branch.
Open downloaded versions only; no network, annotations, pedal input or remote edits.

## Navigation and ownership

Add Open PDF to ready ScoreLibrary rows and a viewer controller owned by the shared
ViewModel. Show its full-size screen above the normal scaffold in the same activity;
hide workstation tabs while reading, with explicit Back to scores and system-back
handling. Keep MainActivity, shared engine and foreground service ownership intact.
Retain selected namespace/score in the shared controller across activity recreation;
after process restart, opening the cached score restores its persisted reading state.
Opening captures profile namespace and score identity; a profile change immediately
clears visible content, cancels work and closes the old session. No raw file paths,
Intents or public file URIs cross the viewer boundary. Add a cache read-lease method
that verifies membership/checksum and opens a read-only ParcelFileDescriptor while
holding its mutation mutex, preventing remove/open races. Transfer descriptor
ownership to the renderer; an acquired descriptor may finish reading an unlinked
file, while later opens show download-again guidance. Never delete a cached PDF
because rendering failed. Serialize opening with selection changes and reject late
results by a monotonically increasing session generation.

## Rendering and resources

Use platform PdfRenderer behind an injectable document/session interface, with all
open/page/render/close and file/hash work on a serial IO worker, never audio callbacks
or the UI thread. Own at most one renderer and descriptor per active session and one
open page at a time; close each page in finally before renderer/descriptor teardown.
Renderer construction failure closes the descriptor; render failure closes the page.
Reject pageCount <= 0 before clamping or opening page 0; close all resources and show
fixed empty/unsupported-document guidance with Retry/Back, preserving audio.
Cancellation cannot interrupt native rendering: let that call finish on the serial
worker, discard its stale result, then close resources without concurrent close/render.
Coalesce rapid page requests so only the latest queued page renders. Bound each
ARGB_8888 bitmap to 2,000,000 pixels and each dimension to 4096, with finite positive
page dimensions and overflow-safe scaling. Keep at most the displayed bitmap and
one replacement in flight (16 MB pixel budget); no prefetch or full-document cache.
Zoom uses the bounded bitmap instead of allocating larger render targets. Recycle
unpublished canceled bitmaps; drop UI-published bitmap references on replacement,
exit/background/trim rather than recycling a bitmap still referenced by Compose.
On ON_STOP or memory trim, immediately hide/drop displayed content and enqueue
renderer teardown; resume reopens the same cached version and rerenders saved page.
If trim occurs while resumed, show a memory-release message and enabled Retry that
reopens immediately without requiring another lifecycle event; do not leave blank UI.
Map missing/corrupt/encrypted/unsupported PDFs, invalid page dimensions and allocation
failure to fixed actionable Retry/Back guidance; catch allocation failure at bitmap
creation/render boundary, release resources, and preserve the audio session.

## Reading state and interaction

Render one page with manual Previous/Next, page/count text, pinch zoom and drag pan,
plus accessible zoom-in/out/reset controls. Clamp zoom to 1–4 and normalized pan to
the visible image bounds; reset pan on page change, avoid nonfinite gesture values,
and recompute bounds after rotation. Disable invalid page navigation and show loading.
Persist zero-based page, zoom and normalized pan as nonsecret bounded JSON under
noBackupFilesDir, namespaced by profile/owner/origin and hashed score ID; entries use
version/checksum keys, with at most 16 version states and a 64 KiB record limit.
Write state atomically on IO after page/zoom/pan changes with serialized latest-wins
writes; do not depend only on activity destruction. Keep a per-score last-read fallback
in the same atomic record so a new version can inherit then clamp the old page to
0..pageCount-1; exact-version state takes precedence. Retain the 16 most recently used version
states and reject/reset malformed or oversized records with visible guidance.
Storage failure remains visible and keeps prior state; reading itself remains usable.

## Verification and delivery

JVM tests cover page/version fallback and clamping, finite zoom/pan bounds, namespace
isolation, atomic state failure/reconstruction, session generations, rapid navigation,
late/canceled results, zero-page rejection/closure, resumed-trim Retry recovery, and
page/renderer/descriptor close ownership via injected fakes.
Android tests generate synthetic multipage PDFs with PdfDocument, then exercise real
PdfRenderer dimensions, bounded pixels, valid page navigation, failed opens and close.
Compose/device tests cover Open/Back, prev/next, zoom controls/gestures and restoration,
profile clearing, missing/corrupt-document guidance and safe retry. Use the isolated
com.pyano.unattendedverify package and test host show-over-keyguard setup; never replace
the installed Pyano/staging packages or use personal scores/recordings as fixtures.
Run source checks, JVM tests, debug/test-APK builds and isolated connected tests, then
restore normal builds. Record counts/skips and distinguish fakes from real rendering.
Add a manual tablet matrix for airplane-mode process restart, rotation/background,
version changes, encrypted/corrupt/missing PDFs, rapid navigation/memory pressure and
uninterrupted synth/loops/metronome/recording/MIDI/service while reading. Record actual
execution separately; fixture UI tests do not prove audible continuity or live Cloud.
Move plan to done with independently reviewed implementation and commit attestation.

Platform reference: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
