<!-- Concern: documents native audio invariants | Non-concern: UI behavior | IO: none -->
# Native audio constraints

The audio callback owns synthesis during playback, with FluidSynth's internal
API lock disabled. Other threads queue notes, controllers, and effect changes.
SoundFont loading, sample-rate changes, and teardown hold `synthMutex`; the
callback only tries that lock and emits silence rather than blocking.

The event queue serializes producers; its callback consumer takes no producer
lock. Reserved slots protect NoteOff, ProgramSelect, and ReverbOn. Sustain-off
uses separate bounded lock-free channel FIFOs once that reserve is reached;
queued events and deferred releases are merged by producer sequence. New pedal-ons are dropped while no emergency release slot remains,
so deferred off/on/off edges survive overflow without silencing unrelated loops.
Dropped NoteOff triggers all-notes-off recovery. Stale NoteOns after
downtime/loading are discarded. Stream open/close is serialized separately,
including Oboe error-thread recovery; that mutex is never taken by the callback.

Idle Android processes can have large SoundFont heaps compressed into zram.
The heap warmer reads nonresident malloc-region pages off the real-time thread;
`mlock` limits are too small for typical fonts. `process_vm_readv` on the current
process tolerates mappings disappearing between discovery and reading. Restored
page totals include untouched/released zero pages, so are not exact swap-in counts.

Callbacks pin to available big cores and recheck after Android cpuset changes.
Automatic jitter buffering is capped; no buffer can fix rendering slower than
real time. Real pauses reset added latency. Silent streams pause to avoid Android
counting idle time toward its safe-volume hearing warning; sound sources resume.

## Diagnostics array contract

`engine_get_audio_stats` writes these 17 values in order (see the exported native function
and Kotlin binding for names). Peak callback time and voices reset on reads;
xruns accumulate across stream reopens.

| Index | Value |
| --- | --- |
| 0 | Total xruns |
| 1 | Buffer size in frames |
| 2 | Burst size in frames |
| 3 | Last callback frames |
| 4 | Maximum callback microseconds since last read |
| 5 | Peak active voices since last read |
| 6 | Sample rate |
| 7 | Dropped events |
| 8 | Callbacks silenced by synth lock contention |
| 9 | CPU of slowest callback |
| 10 | Callbacks using over 80% of their time budget |
| 11 | Heap pages restored |
| 12 | Automatic latency resets |
| 13 | Pin result: 0 pinned, 1 untried, otherwise errno |
| 14 | Paused for idle (0/1) |
| 15 | Last resume request duration in microseconds |
| 16 | Last resume request to first callback in microseconds |
