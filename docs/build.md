<!-- Concern: documents Android build setup | Non-concern: source quality gates | IO: none -->
# Android build

**Prerequisites:** Android SDK platforms 34 & 35, the NDK, CMake **3.22.1**, and
**JDK 21** (Gradle 8.9 does not run on JDK 24/25 — use 21).

Important gotchas before your first build:

1. **FluidSynth native libraries are NOT included** (gitignored). You must supply
   `libfluidsynth.so` for each ABI plus the public headers, laid out exactly as
   the CMake build expects (see
   `../platform_android/app/src/main/cpp/CMakeLists.txt`):

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
