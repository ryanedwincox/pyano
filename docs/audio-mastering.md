<!-- Concern: documents local loudness matching and export behavior | Non-concern: recording capture or cloud processing | IO: none -->
# Recording export and mastering

The recording's mastering dialog opens on **Simple**. It silently analyzes the
whole source, starting with loudness matching enabled at −20 LUFS, Gentle
compression and a final −3 dBFS sample-peak limiter. Preview and export stay
unavailable until analysis for the current settings finishes. Originals remain
unchanged; export creates a new `_final[N].wav` beside the source. Saving a new
recording opens this dialog immediately, including when recording from a score.
Exports copy the source `.mid` sidecar byte-for-byte to the matching output name;
lookahead compensation preserves audio duration, so MIDI timing stays unchanged.
An occupied WAV or MIDI filename is skipped. Re-mastering a `_final[N]` copy uses
the next free suffix. Initial Cloud titles match the output filename without
`.wav`; each different mastered audio/MIDI bundle has its own stable Cloud ID.
Original uploads are not replaced. For a new bundle, rename the copy before
uploading to choose a title, or rename it afterward to update its Cloud title.
Byte-identical re-exports deduplicate to the same Cloud recording; its existing
title remains unless explicitly renamed.
Discarding a take or a failed save does not open mastering.

## Presets and controls

| Preset | Threshold | Ratio | Attack | Release |
| --- | --- | --- | --- | --- |
| Gentle | −16 dB | 1.25:1 | 40 ms | 250 ms |
| Balanced | −20 dB | 1.5:1 | 30 ms | 250 ms |
| Even | −24 dB | 1.8:1 | 25 ms | 250 ms |

**Advanced** exposes the existing EQ, compressor and limiter controls, plus the
loudness target. Both tabs edit one shared settings value. Changing a preset
updates compression controls, preserving EQ, target and limiter adjustments.
Manual compression changes show Custom until another preset is chosen. Parameter
changes cancel the old analysis, stop preview and scan again after a short pause.
Settings apply to this dialog; opening another recording restores the defaults.

## Analysis and graphs

The stereo meter uses K weighting, overlapping 400 ms blocks at 100 ms steps,
an absolute −70 LUFS gate and a relative −10 LU gate. Short clips use their
available duration. The implementation is tested against stereo sine calibration
at 44.1/48 kHz; it is not a certified EBU meter.

First the input gain moves the recording toward the target; compression follows
EQ. A post-compression gain adjustment then matches the processed loudness,
with up to three correction passes through the final limiter. Each automatic
gain stage is limited to ±24 dB. Silence is never amplified. If the target cannot
be reached, the measured result and a warning remain visible.

Graphs show original/processed momentary loudness and the maximum compressor /
limiter gain reduction per display interval, with at most 1,000 points. Tapping
a graph previews that passage. Strong limiter reduction gets a warning rather
than silently claiming that the requested loudness was reached.

Dynamics use linked stereo gain to preserve the stereo image. The limiter caps
sample peaks, not reconstructed intersample peaks; the displayed dBFS ceiling
is not a true-peak guarantee after lossy encoding. The ceiling limits peaks;
average listening level is controlled by the LUFS target and speaker volume.
Changing defaults affects new exports; existing mastered files stay unchanged.

## Files and cancellation

Analysis and export stream audio buffers on background dispatchers. The meter
retains one energy value per 100 ms for gating; graphs have at most 1,000 points. Supported
sources are stereo 16-bit PCM WAV at 8–192 kHz and smaller than 2 GB. The source
identity is checked across analysis/export; changed or truncated sources fail.
Export compensates limiter lookahead and flushes its tail to preserve duration.
It stages a hidden temporary file, cleans it on failure/cancellation, and moves
it to an unused filename only after the complete WAV has been written.

Tests cover loudness calibration and matching across input levels, all presets,
stereo balance, isolated limiting peaks, analysis/export agreement, source and
existing-export preservation, tail/duration preservation and cancellation.
Tablet tests cover shared tab controls and silent analysis through a real export.
