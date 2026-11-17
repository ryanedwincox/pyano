<!-- Concern: documents JVM checks without native builds | Non-concern: device validation | IO: none -->
# JVM verification without the native toolchain

The regular `./gradlew testDebugUnitTest` requires the Android/native build
prerequisites in [build.md](build.md), because Gradle configures CMake/NDK even
for host tests. A temporary init script can disable native compilation for JVM
verification while leaving tracked build configuration untouched:

```bash
cat > /tmp/pyano-jvm-only.init.gradle <<'GRADLE'
gradle.beforeProject { project ->
    project.afterEvaluate {
        if (project.path == ':app') {
            project.android.externalNativeBuild.cmake.path = null
        }
    }
}
GRADLE
```

From the repository root, with JDK 21 and Android SDK 35 / Build Tools 34 available:

```bash
./platform_android/gradlew -p platform_android \
  --init-script /tmp/pyano-jvm-only.init.gradle testDebugUnitTest
```

Set `JAVA_HOME` and `ANDROID_HOME` to the installed tool locations as needed.
This compiles the real Android Kotlin/UI and runs JVM tests using the repository's
existing empty JNI stub. It does not verify native synthesis, AAC hardware/software
codec behavior, or produce a working APK. Use the normal build for device checks.

The local prototype run passed 30 JVM tests; one existing external-SoundFont test
skipped. The two MIDI tests decode SMF using the host JDK's independent MIDI
reader, accessed reflectively because Android compile classpaths omit java.desktop.

Local server tests and strict source gates:

```bash
python -m unittest discover -s local-sync -v
./check-source.sh
```
