<!-- Concern: validates offline PDF reading | Non-concern: cloud deployment or audio implementation | IO: none -->
# Offline score viewer validation

Open PDF is available on downloaded private score versions. Reading stays in the
same activity and shared engine; Back returns to Scores. The viewer never fetches
network content or deletes a PDF because rendering fails. Local removal after
opening keeps the acquired file descriptor readable; a later open requires a new
download. Profile changes close the viewer and clear visible old-owner content.

Page, zoom and normalized pan are saved atomically in no-backup storage for the
selected profile/owner/origin, score and version/checksum. Reopening restores the
exact version; a new version inherits the last position and clamps to its page count.
The most recent sixteen versions are retained per score. Failed position saves
show guidance while leaving the reader usable and prior saved state intact.

Page rendering uses one serial worker with one renderer and one page open at a
time. Clear horizontal swipes turn at 32 dp while the finger is still down, once
per gesture, with a 100 ms slide that does not block another turn. Zoomed views
keep gestures for panning. The next and previous pages are preloaded after the
current page is visible; at most two unpublished neighbor frames are cached.
Page turns publish before saving the new reading position; reopening first flushes pending writes. Each ARGB bitmap is limited to 2,000,000 pixels and dimensions at most 4096.
Zoom uses the existing bitmap. Old UI frames are released through ordinary bitmap
ownership rather than recycling memory still visible to Compose. Unpublished
stale frames are recycled. When the last visible reader window stops, frames drop
and rendering closes; returning reopens the cached version. Other visible windows
keep their reader. A teardown revision survives coalesced Stop/Start requests. Memory pressure while visible offers Retry.

## Automated coverage

JVM fixtures cover finite bounds, version fallback/clamping, atomic record failure,
namespace separation, bounded version retention, stale results, serialized close,
zero-page rejection, allocation/render failures, rapid navigation, and trim/retry.
Android tests generate synthetic multipage PDFs: real rendering, bitmap bounds,
descriptor ownership, cache removal/read leases, navigation, pinch/drag, reading
actual ActivityScenario recreation with renderer close/reopen, profile clearing and missing/corrupt-document retry guidance.
These tests do not establish audible continuity, real Cloud compatibility, physical
MIDI behavior, encrypted-PDF interoperability, or real process-kill recovery.

## Executed checks: 2026-11-19

- Source gate: ./check-source.sh passed, 115 annotated files; git diff --check passed.
- testDebugUnitTest passed: 88 tests, zero failures/errors, one existing skip;
  16 new viewer JVM tests cover state, persistence and controlled resource races.
- assembleDebug and assembleDebugAndroidTest passed with JDK 21, Android SDK
  platforms 34/35, Build Tools 34, NDK, CMake 3.22.1 and local FluidSynth libraries.
- connectedDebugAndroidTest passed: 10 tests, zero failures/errors/skips on SM-X210,
  Android 16; four new viewer tests use real synthetic PDFs. The test-only temporary
  Gradle init script selected com.pyano.unattendedverify; installed Pyano/staging
  packages were not replaced. Normal builds restored the default APK identity.
- Existing real-SoundFont test skipped: FLUIDR3_SF2 was unset; requires an external
  FluidR3_GM.sf2. No personal SoundFonts or scores were copied into the repository.
- Manual matrix remains unrun: requires physical MIDI input, audible synth/loops,
  metronome and recording observations, representative encrypted/unsupported PDFs,
  real process termination, and offline tablet restart. These are not inferred from
  synthetic UI or rendering tests. Live Cloud checks require a disposable account/token.

## Manual tablet matrix

Use disposable scores and recordings; never put private PDF contents or credentials
in screenshots, logs, reports or version control. Record actual device results.

| Scenario | Expected result |
| --- | --- |
| Airplane mode; restart process; reopen score | Cached pages open and saved position restores |
| Rotate at zoom and pan; navigate previous/next | Valid page, bounded view and usable controls |
| Background/resume while rendering; rapid page changes | Latest requested page; no stale frame or crash |
| Memory pressure while reading | Page drops safely; Retry reopens without leaving app |
| New PDF version with fewer pages | Last page clamps; exact older-version state retained |
| Remove local file while open; reopen | Open lease remains readable; reopen explains download |
| Corrupt, encrypted, empty or unsupported PDF | Actionable Retry/Back; no file deletion or crash |
| Change Cloud profile/owner | Old score closes; new library only; no stale result |
| Low storage or denied position persistence | Visible warning; prior saved state remains readable |
| Synth + loop + metronome + MIDI while reading | Continuous audio/events/service; no duplicate engine |
| Record while zooming, paging and rotating | Original WAV/MIDI preserved; capture stays continuous |
| Back/system Back; close final main window | Normal library navigation and existing service lifecycle |

Manual execution is not claimed by this matrix. Record tablet/Android versions,
score characteristics and audible/MIDI/recording observations before release.
