/* Stub native lib for JVM unit tests: satisfies System.loadLibrary("pyano-native")
   so FluidSynthEngine can be loaded/mocked without the real Oboe/FluidSynth engine.
   Defines no JNI functions; tests never invoke native methods. */
