#include <jni.h>
#include <stdio.h>
#include <android/log.h>

#define LOG_TAG "Pyano"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Forward declarations for C++ engine (defined in audio-engine.cpp)
// We use opaque pointer and C wrapper functions

// The C++ PyanoEngine is accessed via these wrappers
#ifdef __cplusplus
extern "C" {
#endif

// C++ wrapper functions implemented below the JNI functions
void* engine_create(int sampleRate, int bufferSize);
void engine_destroy(void* engine);
int engine_load_soundfont(void* engine, const char* path);
void engine_program_select(void* engine, int channel, int sfId, int bank, int preset);
void engine_note_on(void* engine, int channel, int note, int velocity);
void engine_note_off(void* engine, int channel, int note);
void engine_cc(void* engine, int channel, int ctrl, int value);
void engine_set_gain(void* engine, float gain);
void engine_set_reverb(void* engine, float roomsize, float damping, float width, float level);
void engine_set_chorus(void* engine, int nr, float level, float speed, float depth, int type);
void engine_set_reverb_on(void* engine, int on);
void engine_set_chorus_on(void* engine, int on);
int engine_start_audio(void* engine);
void engine_stop_audio(void* engine);
int engine_set_buffer_size(void* engine, int bufferSize);
int engine_get_presets(void* engine, int sfId, int* banks, int* programs, const char** names, int maxPresets);
int engine_set_audio_device(void* engine, int deviceId);
float engine_get_peak_level(void* engine);
void engine_start_metronome(void* engine);
void engine_stop_metronome(void* engine);
void engine_set_metronome_bpm(void* engine, int bpm);
void engine_set_metronome_time_sig(void* engine, int beats);
int engine_get_metronome_beat(void* engine);
void engine_set_metronome_click_notes(void* engine, int downbeatNote, int subbeatNote);
void engine_set_metronome_volume(void* engine, float volume);
int engine_load_metronome_drum_kit(void* engine, const char* path);
void engine_start_recording(void* engine);
void engine_stop_recording(void* engine);
int engine_read_recording_buffer(void* engine, float* out, int maxFloats);
int engine_get_recording_sample_rate(void* engine);
int engine_get_recording_overflow(void* engine);

// JNI functions

JNIEXPORT jlong JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeCreate(JNIEnv* env, jobject thiz,
                                                    jint sampleRate, jint bufferSize) {
    void* engine = engine_create(sampleRate, bufferSize);
    return (jlong)(intptr_t)engine;
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeDestroy(JNIEnv* env, jobject thiz, jlong ptr) {
    engine_destroy((void*)(intptr_t)ptr);
}

JNIEXPORT jint JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeLoadSoundFont(JNIEnv* env, jobject thiz,
                                                           jlong ptr, jstring path) {
    const char* pathStr = (*env)->GetStringUTFChars(env, path, NULL);
    int result = engine_load_soundfont((void*)(intptr_t)ptr, pathStr);
    (*env)->ReleaseStringUTFChars(env, path, pathStr);
    return result;
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeProgramSelect(JNIEnv* env, jobject thiz,
                                                           jlong ptr, jint channel,
                                                           jint sfId, jint bank, jint preset) {
    engine_program_select((void*)(intptr_t)ptr, channel, sfId, bank, preset);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeNoteOn(JNIEnv* env, jobject thiz,
                                                    jlong ptr, jint channel,
                                                    jint note, jint velocity) {
    engine_note_on((void*)(intptr_t)ptr, channel, note, velocity);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeNoteOff(JNIEnv* env, jobject thiz,
                                                     jlong ptr, jint channel, jint note) {
    engine_note_off((void*)(intptr_t)ptr, channel, note);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeCC(JNIEnv* env, jobject thiz,
                                                jlong ptr, jint channel,
                                                jint ctrl, jint value) {
    engine_cc((void*)(intptr_t)ptr, channel, ctrl, value);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetGain(JNIEnv* env, jobject thiz,
                                                     jlong ptr, jfloat gain) {
    engine_set_gain((void*)(intptr_t)ptr, gain);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetReverb(JNIEnv* env, jobject thiz,
                                                       jlong ptr, jfloat roomsize,
                                                       jfloat damping, jfloat width,
                                                       jfloat level) {
    engine_set_reverb((void*)(intptr_t)ptr, roomsize, damping, width, level);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetChorus(JNIEnv* env, jobject thiz,
                                                       jlong ptr, jint nr, jfloat level,
                                                       jfloat speed, jfloat depth, jint type) {
    engine_set_chorus((void*)(intptr_t)ptr, nr, level, speed, depth, type);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetReverbOn(JNIEnv* env, jobject thiz,
                                                         jlong ptr, jboolean on) {
    engine_set_reverb_on((void*)(intptr_t)ptr, on ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetChorusOn(JNIEnv* env, jobject thiz,
                                                         jlong ptr, jboolean on) {
    engine_set_chorus_on((void*)(intptr_t)ptr, on ? 1 : 0);
}

JNIEXPORT jboolean JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeStartAudio(JNIEnv* env, jobject thiz, jlong ptr) {
    return engine_start_audio((void*)(intptr_t)ptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeStopAudio(JNIEnv* env, jobject thiz, jlong ptr) {
    engine_stop_audio((void*)(intptr_t)ptr);
}

JNIEXPORT jboolean JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetBufferSize(JNIEnv* env, jobject thiz,
                                                           jlong ptr, jint bufferSize) {
    return engine_set_buffer_size((void*)(intptr_t)ptr, bufferSize) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetAudioDevice(JNIEnv* env, jobject thiz,
                                                            jlong ptr, jint deviceId) {
    return engine_set_audio_device((void*)(intptr_t)ptr, deviceId) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeGetPeakLevel(JNIEnv* env, jobject thiz, jlong ptr) {
    return engine_get_peak_level((void*)(intptr_t)ptr);
}

JNIEXPORT jobjectArray JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeGetPresets(JNIEnv* env, jobject thiz,
                                                       jlong ptr, jint sfId) {
    #define MAX_PRESETS 512
    int banks[MAX_PRESETS];
    int programs[MAX_PRESETS];
    const char* names[MAX_PRESETS];

    int count = engine_get_presets((void*)(intptr_t)ptr, sfId, banks, programs, names, MAX_PRESETS);

    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    char buf[256];
    for (int i = 0; i < count; i++) {
        snprintf(buf, sizeof(buf), "%d:%d:%s", banks[i], programs[i], names[i] ? names[i] : "");
        jstring entry = (*env)->NewStringUTF(env, buf);
        (*env)->SetObjectArrayElement(env, result, i, entry);
        (*env)->DeleteLocalRef(env, entry);
    }

    return result;
    #undef MAX_PRESETS
}

// --- Metronome JNI functions ---

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeStartMetronome(JNIEnv* env, jobject thiz, jlong ptr) {
    engine_start_metronome((void*)(intptr_t)ptr);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeStopMetronome(JNIEnv* env, jobject thiz, jlong ptr) {
    engine_stop_metronome((void*)(intptr_t)ptr);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetMetronomeBpm(JNIEnv* env, jobject thiz,
                                                             jlong ptr, jint bpm) {
    engine_set_metronome_bpm((void*)(intptr_t)ptr, bpm);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetMetronomeTimeSig(JNIEnv* env, jobject thiz,
                                                                  jlong ptr, jint beats) {
    engine_set_metronome_time_sig((void*)(intptr_t)ptr, beats);
}

JNIEXPORT jint JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeGetMetronomeBeat(JNIEnv* env, jobject thiz, jlong ptr) {
    return engine_get_metronome_beat((void*)(intptr_t)ptr);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetMetronomeClickNotes(JNIEnv* env, jobject thiz,
                                                                     jlong ptr, jint downbeatNote,
                                                                     jint subbeatNote) {
    engine_set_metronome_click_notes((void*)(intptr_t)ptr, downbeatNote, subbeatNote);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeSetMetronomeVolume(JNIEnv* env, jobject thiz,
                                                                 jlong ptr, jfloat volume) {
    engine_set_metronome_volume((void*)(intptr_t)ptr, volume);
}

JNIEXPORT jint JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeLoadMetronomeDrumKit(JNIEnv* env, jobject thiz,
                                                                   jlong ptr, jstring path) {
    const char* pathStr = (*env)->GetStringUTFChars(env, path, NULL);
    int result = engine_load_metronome_drum_kit((void*)(intptr_t)ptr, pathStr);
    (*env)->ReleaseStringUTFChars(env, path, pathStr);
    return result;
}

// --- Recording JNI functions ---

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeStartRecording(JNIEnv* env, jobject thiz, jlong ptr) {
    engine_start_recording((void*)(intptr_t)ptr);
}

JNIEXPORT void JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeStopRecording(JNIEnv* env, jobject thiz, jlong ptr) {
    engine_stop_recording((void*)(intptr_t)ptr);
}

JNIEXPORT jint JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeReadRecordingBuffer(JNIEnv* env, jobject thiz,
                                                                 jlong ptr, jfloatArray buffer,
                                                                 jint maxFloats) {
    jfloat* buf = (*env)->GetFloatArrayElements(env, buffer, NULL);
    if (!buf) return 0;
    int read = engine_read_recording_buffer((void*)(intptr_t)ptr, buf, maxFloats);
    (*env)->ReleaseFloatArrayElements(env, buffer, buf, 0);
    return read;
}

JNIEXPORT jint JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeGetRecordingSampleRate(JNIEnv* env, jobject thiz,
                                                                    jlong ptr) {
    return engine_get_recording_sample_rate((void*)(intptr_t)ptr);
}

JNIEXPORT jboolean JNICALL
Java_com_pyano_audio_FluidSynthEngine_nativeGetRecordingOverflow(JNIEnv* env, jobject thiz,
                                                                   jlong ptr) {
    return engine_get_recording_overflow((void*)(intptr_t)ptr) ? JNI_TRUE : JNI_FALSE;
}

#ifdef __cplusplus
}
#endif
