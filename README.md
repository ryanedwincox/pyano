# Pyano

An Android MIDI-to-SoundFont synthesizer and mobile music workstation — turn a MIDI keyboard into a real instrument, with a metronome, loop station, recorder, and mastering suite.

> _This repo was generated entirely by Claude._

## Overview

Pyano is an Android app that renders live MIDI input through
[FluidSynth](https://github.com/FluidSynth/fluidsynth) and a user-supplied `.sf2`
SoundFont, giving you a low-latency software instrument driven by any connected
MIDI controller. It is a Kotlin / Jetpack Compose app backed by native C/C++
(Oboe + FluidSynth): a full mobile music workstation pairing the synth with a
metronome, loop station, recorder, and a mastering DSP suite.

## Screenshots

The Android app (tablet, portrait):

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/01-synth.png" width="220"><br>Synth</td>
    <td align="center"><img src="docs/screenshots/02-metronome.png" width="220"><br>Metronome</td>
    <td align="center"><img src="docs/screenshots/03-loops.png" width="220"><br>Loop Station</td>
    <td align="center"><img src="docs/screenshots/04-recorder.png" width="220"><br>Recorder</td>
    <td align="center"><img src="docs/screenshots/05-mastering.png" width="220"><br>Mastering</td>
  </tr>
</table>

## Features

- **Synth** — touch keyboard and live MIDI (USB host) into FluidSynth, with a
  SoundFont browser and an effects panel.
- **Metronome** — BPM control with audible click.
- **Loop Station** — multi-layer MIDI looper: per-layer record/overdub, mute,
  rename, and persisted loops across sessions.
- **Recorder** — captures audio to PCM and exports `.wav`.
- **Mastering** — a stereo DSP chain: 3-band parametric EQ (RBJ biquads),
  envelope-follower compressor, and a brick-wall lookahead limiter.
- Native low-latency audio through **Oboe**; a robust hand-written MIDI parser;
  a foreground media service for background playback.

## Tech stack

| Area | Android app |
| --- | --- |
| Language | Kotlin 2.0.0 + C/C++ (C17 / C11) |
| UI | Jetpack Compose (Material 3) |
| Synth core | FluidSynth 2.3.3 (native `.so`) |
| Audio I/O | Oboe 1.9.0 |
| MIDI | Android MIDI API + custom parser |
| Build | Gradle 8.9, AGP 8.5.0, NDK + CMake 3.22.1 |
| Targets | SDK 26+ (min/target/compile 26/35/35), JVM 17 |

## Architecture

The Android app is layered:

```
ui/ (Compose tabs)
      │
      ▼
PyanoViewModel ──► midi/ (device mgr + event parser)
      │
      ▼
audio/ (FluidSynthEngine, LoopEngine, AudioRecorder, MasteringEngine)
      │  JNI
      ▼
cpp/ (native-lib.c, audio-engine.cpp) ──► Oboe (RT callback) + FluidSynth
```

The synthesis core is FluidSynth loading a `.sf2` SoundFont, mixed out through
Oboe's lowest-latency audio path.

## Build & Run

**Prerequisites:** Android SDK platforms 34 & 35, the NDK, CMake **3.22.1**, and
**JDK 21** (Gradle 8.9 does not run on JDK 24/25 — use 21).

Important gotchas before your first build:

1. **FluidSynth native libraries are NOT included** (gitignored). You must supply
   `libfluidsynth.so` for each ABI plus the public headers, laid out exactly as
   the CMake build expects (see
   `platform_android/app/src/main/cpp/CMakeLists.txt`):

   ```
   platform_android/fluidsynth-libs/
     ├── arm64-v8a/libfluidsynth.so
     ├── armeabi-v7a/libfluidsynth.so
     ├── x86_64/libfluidsynth.so
     └── include/            # fluidsynth.h + fluidsynth/ headers
   ```

   FluidSynth is **LGPL-2.1**; build it from
   <https://github.com/FluidSynth/fluidsynth> with the Android NDK.
2. **Oboe is fetched automatically** at build time (CMake `FetchContent`,
   tag 1.9.0) — no manual setup.
3. **A `.sf2` SoundFont is needed at runtime.** Provide one through the in-app
   SoundFont browser (none is bundled).
4. **Signing:** debug builds need nothing. The release build reads its keystore
   credentials from environment / Gradle properties (after the concurrent
   security fix); supply those before `assembleRelease`.

> Note: the full Gradle wrapper — `gradlew`, `gradlew.bat`,
> `gradle/wrapper/gradle-wrapper.properties`, and `gradle/wrapper/gradle-wrapper.jar`
> — is committed, so `./gradlew` works out of the box on a fresh clone.

```bash
cd platform_android
./gradlew assembleDebug
./gradlew installDebug
```

> **Build status:** Only the debug-signed build (`./gradlew assembleDebug`) is
> currently configured and tested; no signed release build is set up. Release
> signing reads its keystore credentials from environment variables / Gradle
> properties, but no keystore is included — you must supply your own to produce a
> release APK.

## Tests

Test scaffolding is being added concurrently.

```bash
# Android: JVM unit tests
cd platform_android && ./gradlew testDebugUnitTest
```

## Project structure

```
.
├── platform_android/       # Kotlin + Compose + native C/C++ app (com.pyano)
│   ├── app/src/main/
│   │   ├── java/com/pyano/
│   │   │   ├── ui/         # Compose tabs (Synth, Metronome, Loop, Recorder, Mastering)
│   │   │   ├── audio/      # FluidSynthEngine, LoopEngine, AudioRecorder, MasteringEngine
│   │   │   ├── midi/       # MidiDeviceManager, MidiEventHandler
│   │   │   └── *.kt        # MainActivity, PyanoViewModel, PyanoAudioService
│   │   └── cpp/            # native-lib.c, audio-engine.cpp, CMakeLists.txt (Oboe + FluidSynth)
│   └── fluidsynth-libs/    # user-supplied native libs + headers (gitignored)
├── docs/screenshots/       # tablet captures used above
├── README.md
├── LICENSE
└── THIRD_PARTY_NOTICES.md
```

## License & attribution

Pyano is released under the [MIT License](./LICENSE) — Copyright (c) 2026 Ryan Cox.

Third-party components Pyano uses (including ones not committed to this repo, such
as the FluidSynth libraries and the SoundFont) are documented, with their licenses
and obligations, in [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md). In
particular, FluidSynth is **LGPL-2.1** and no SoundFont is bundled.

Author: **Ryan Cox** — <https://github.com/ryanedwincox>
