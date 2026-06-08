# Third-Party Notices

Pyano is licensed under the MIT License (see `LICENSE`).

This file lists third-party components that Pyano **depends on, links against, or
redistributes** — including components that are not committed to this repository
but that a builder must supply or that the build fetches automatically (the
FluidSynth native libraries, the Oboe library, the Python dependencies, and the
recommended SoundFont). Each entry records the component name, version, license,
upstream source, and any obligations that apply. Pure development tooling that is
not shipped is not listed here.

Several components below are **not bundled in this repository**: the FluidSynth
native `.so` libraries are gitignored (the builder supplies them), no SoundFont
is shipped, and the Python packages are installed from PyPI at the user's site.
They are documented anyway so that anyone redistributing a built artifact knows
the obligations they inherit.

---

## FluidSynth

- **Name:** FluidSynth (libfluidsynth)
- **Version:** 2.3.3 on Android (from
  `platform_android/fluidsynth-libs/include/fluidsynth/version.h`,
  `FLUIDSYNTH_VERSION "2.3.3"`, when the libs are supplied); the Linux CLI uses
  whatever FluidSynth the system / `pyfluidsynth` provides.
- **License:** GNU Lesser General Public License, version 2.1 (LGPL-2.1)
- **Source:** https://github.com/FluidSynth/fluidsynth
- **Form used / redistributed:**
  - **Android:** prebuilt shared libraries (`libfluidsynth.so`) for the ABIs
    `arm64-v8a`, `armeabi-v7a`, and `x86_64`, plus public headers, expected under
    `platform_android/fluidsynth-libs/`. These libraries are **NOT committed to
    this repo** (gitignored); the builder supplies them and they are bundled into
    any resulting APK.
  - **Linux:** linked dynamically via the `pyfluidsynth` Python binding against
    the system `libfluidsynth`.

**Obligation note (LGPL-2.1):**
The full text of the GNU LGPL-2.1 must accompany any distribution that includes
FluidSynth (https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html). FluidSynth
is used as a **dynamically linked** shared library on both platforms, so a user
can replace/relink it with a modified version, satisfying LGPL-2.1 §6. To remain
compliant, the corresponding source for the exact FluidSynth version built/used,
or a written offer to provide it, must be made available to recipients. Upstream
source (including the v2.3.3 tagged release used by the Android build) is at:
https://github.com/FluidSynth/fluidsynth/releases/tag/v2.3.3

Because the Android `.so` files are not committed here, this repository itself
does not redistribute FluidSynth binaries; the obligation attaches to whoever
builds and distributes an APK containing them.

---

## Google Oboe

- **Name:** Oboe
- **Version:** 1.9.0 (CMake `FetchContent` `GIT_TAG 1.9.0` in
  `platform_android/app/src/main/cpp/CMakeLists.txt`)
- **License:** Apache License 2.0 (Apache-2.0)
- **Source:** https://github.com/google/oboe
- **Form used / redistributed:** Fetched at build time via CMake FetchContent and
  linked into the app's native library; the resulting code ships in any built APK.

**Obligation note (Apache-2.0):**
A copy of the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0) and the upstream NOTICE/attribution
must be retained for the redistributed Apache-2.0 component.

---

## AndroidX / Jetpack Compose

- **Name:** AndroidX and Jetpack Compose libraries
- **Publisher:** Google / The Android Open Source Project
- **License:** Apache License 2.0 (Apache-2.0)
- **Source:** https://developer.android.com/jetpack/androidx
- **Form used / redistributed:** Resolved as Gradle dependencies (see
  `platform_android/app/build.gradle.kts`) and packaged into the APK. Components
  include, among others:
  - `androidx.core:core-ktx:1.13.1`
  - `androidx.activity:activity-compose:1.9.0`
  - `androidx.compose:compose-bom:2024.06.00` (BOM) and the Compose UI /
    Material3 / Material-Icons artifacts it manages (`androidx.compose.ui:ui`,
    `androidx.compose.ui:ui-graphics`, `androidx.compose.material3:material3`,
    `androidx.compose.material:material-icons-extended`,
    `androidx.compose.ui:ui-tooling-preview`, `androidx.compose.ui:ui-tooling`)
  - `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2`
  - `androidx.lifecycle:lifecycle-runtime-compose:2.8.2`

**Obligation note (Apache-2.0):**
A copy of the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0) must be retained for these
redistributed Apache-2.0 components.

---

## pyfluidsynth (Linux Python dependency)

- **Name:** pyfluidsynth (Python module `fluidsynth`)
- **License:** GNU Lesser General Public License, version 2.1 (LGPL-2.1)
- **Source:** https://github.com/nwhitehead/pyfluidsynth (PyPI: `pyfluidsynth`)
- **Form used:** Installed from PyPI (`platform_linux/requirements.txt`); a thin
  ctypes binding that dynamically loads the system FluidSynth library at runtime.

**Obligation note (LGPL-2.1):**
As with FluidSynth itself, the LGPL-2.1 terms apply; the binding loads FluidSynth
dynamically, so recipients can relink against a modified FluidSynth. Retain the
LGPL-2.1 text and a source offer when redistributing.

---

## mido (Linux Python dependency)

- **Name:** mido (MIDI Objects for Python)
- **License:** MIT License
- **Source:** https://github.com/mido/mido (PyPI: `mido`)
- **Form used:** Installed from PyPI (`platform_linux/requirements.txt`); used to
  enumerate and read MIDI input ports.

**Obligation note (MIT):** Retain the copyright and permission notice when
redistributing.

---

## python-rtmidi (Linux Python dependency)

- **Name:** python-rtmidi
- **License:** MIT License (the Python binding; it wraps the RtMidi C++ library,
  which is also MIT-licensed)
- **Source:** https://github.com/SpotlightKid/python-rtmidi (PyPI: `python-rtmidi`)
- **Form used:** Installed from PyPI (`platform_linux/requirements.txt`); the MIDI
  I/O backend used by `mido`.

**Obligation note (MIT):** Retain the copyright and permission notice when
redistributing.

---

## FluidR3_GM.sf2 (recommended SoundFont — NOT bundled)

- **Name:** Fluid (R3) GM SoundFont (`FluidR3_GM.sf2`)
- **Author / copyright:** Frank (Toshiya) Wen. The bank identifies itself as
  "Fluid R3 GM" and records the copyright as
  "Frank Wen 2000-2002, 2008; Toby Smithe 2008".
- **License:** MIT License. Stated in the file's own embedded copyright/INFO
  metadata ("Licensed under the MIT License.") and corroborated by authoritative
  redistributors (see Sources below).
- **Source:** The Fluid Release 3 General-MIDI SoundFont by Frank Wen,
  https://member.keymusician.com/Member/FluidR3_GM/ (the canonical distribution
  point referenced by downstream packagers). License corroboration:
  https://github.com/musescore/MuseScore/blob/master/share/sound/FluidR3Mono_License.md
- **Form used / redistributed:** **NOT bundled in this repository.** Pyano
  recommends it as the default SoundFont; the user supplies a `.sf2` at runtime
  on both platforms.

**Obligation note (MIT):**
If you redistribute this SoundFont, the MIT permission notice and the copyright
notice ("Frank Wen 2000-2002, 2008; Toby Smithe 2008") must be retained. The MIT
permission/warranty text reads:

> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions: The above copyright
> notice and this permission notice shall be included in all copies or
> substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS",
> WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
> THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT.

Note: the exact upstream FluidR3 GM revision is not formally versioned by the
author beyond the "R3" (Release 3) designation, so a precise revision number
cannot be verified.
