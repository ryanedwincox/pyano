#include <oboe/Oboe.h>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <mutex>
#include <atomic>

#include "ring-buffer.h"

extern "C" {
#include <fluidsynth.h>
}

#define LOG_TAG "Pyano"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Metronome plays real GM percussion samples on FluidSynth channel 9,
// routed to a dedicated drum-kit soundfont (FluidR3_GM bank 128 preset 0)
// so it is independent of whichever piano soundfont the user has loaded.
static constexpr int kMetronomeChannel = 9;
static constexpr int kMetronomeDrumBank = 128;
static constexpr int kMetronomeDrumPreset = 0;
static constexpr int kMetronomeDownbeatVelocity = 120;
static constexpr int kMetronomeSubbeatVelocity  = 85;
static constexpr int kMetronomeNoteOffMs = 80;

class PyanoEngine : public oboe::AudioStreamCallback {
public:
    fluid_settings_t* settings = nullptr;
    fluid_synth_t* synth = nullptr;
    oboe::ManagedStream stream;
    std::mutex synthMutex;
    int sampleRate = 48000;
    int bufferSize = 256;
    int audioDeviceId = oboe::kUnspecified;

    // Output level monitoring (peak level per callback)
    std::atomic<float> peakLevel{0.0f};

    // Recording ring buffer: allocated once, reused across recordings
    // ~60s of stereo float at 48kHz = 48000 * 2 * 60 = 5,760,000 floats (~32MB after power-of-2 rounding)
    static constexpr size_t kRecordingBufferFloats = 48000 * 2 * 60;
    SpscRingBuffer<float>* recordingBuffer = nullptr;
    std::atomic<bool> recordingActive{false};
    std::atomic<bool> recordingOverflow{false};

    // Metronome state. Control fields are atomic (UI/RT thread). RT-only state
    // (sample counter, pending noteoff) is touched only by the RT audio thread.
    std::atomic<bool> metronomeRunning{false};
    std::atomic<int> metronomeBpm{120};
    std::atomic<int> metronomeTimeSigBeats{4};
    std::atomic<int> metronomeCurrentBeat{0};
    std::atomic<int> metronomeSamplesPerBeat{0};  // sampleRate * 60 / bpm
    // Drum note pair — downbeat / subbeat GM percussion note numbers
    std::atomic<int> metronomeDownbeatNote{34}; // Metronome Bell
    std::atomic<int> metronomeSubbeatNote{33};  // Metronome Click
    std::atomic<float> metronomeVolume{1.0f};
    int metronomeDrumSfId = -1;                 // sfId of dedicated drum SF2
    int metronomeSampleCounter = 0;
    int metronomePendingNoteOff = -1;           // note number awaiting noteoff, -1 = none
    int metronomeNoteOffCountdown = 0;          // frames remaining before noteoff

    bool create(int requestedSampleRate, int requestedBufferSize) {
        sampleRate = requestedSampleRate;
        bufferSize = requestedBufferSize;

        settings = new_fluid_settings();
        if (!settings) {
            LOGE("Failed to create FluidSynth settings");
            return false;
        }

        fluid_settings_setnum(settings, "synth.sample-rate", (double)sampleRate);
        fluid_settings_setint(settings, "synth.audio-channels", 1); // 1 stereo pair
        fluid_settings_setint(settings, "synth.polyphony", 512);
        fluid_settings_setint(settings, "synth.threadsafe-api", 1);

        synth = new_fluid_synth(settings);
        if (!synth) {
            LOGE("Failed to create FluidSynth synth");
            delete_fluid_settings(settings);
            settings = nullptr;
            return false;
        }

        // Enable reverb and chorus with defaults
        fluid_synth_set_reverb(synth, 0.2, 0.0, 0.5, 0.9);
        fluid_synth_set_chorus(synth, 3, 2.0, 0.3, 8.0, FLUID_CHORUS_MOD_SINE);

        LOGI("FluidSynth created: sampleRate=%d, bufferSize=%d", sampleRate, bufferSize);
        return true;
    }

    bool startAudio() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Shared)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(2)
               ->setFramesPerCallback(bufferSize)
               ->setUsage(oboe::Usage::Media)
               ->setContentType(oboe::ContentType::Music)
               ->setDeviceId(audioDeviceId)
               ->setCallback(this);
        // Let Oboe pick the optimal sample rate for the device
        if (audioDeviceId == oboe::kUnspecified) {
            builder.setSampleRate(sampleRate);
        }

        oboe::Result result = builder.openManagedStream(stream);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open stream: %s", oboe::convertToText(result));
            return false;
        }

        result = stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start stream: %s", oboe::convertToText(result));
            return false;
        }

        // Update FluidSynth sample rate to match the actual stream
        int actualRate = stream->getSampleRate();
        if (actualRate != sampleRate && synth) {
            LOGI("Adjusting FluidSynth sample rate from %d to %d", sampleRate, actualRate);
            sampleRate = actualRate;
            std::lock_guard<std::mutex> lock(synthMutex);
            fluid_settings_setnum(settings, "synth.sample-rate", (double)actualRate);
        }

        LOGI("Audio stream started: sampleRate=%d, framesPerCallback=%d, deviceId=%d",
             stream->getSampleRate(), stream->getFramesPerCallback(), stream->getDeviceId());
        return true;
    }

    void stopAudio() {
        if (stream) {
            stream->requestStop();
            stream->close();
            stream.reset();
        }
    }

    void destroy() {
        recordingActive.store(false, std::memory_order_relaxed);
        stopAudio();
        delete recordingBuffer;
        recordingBuffer = nullptr;
        std::lock_guard<std::mutex> lock(synthMutex);
        if (synth) {
            delete_fluid_synth(synth);
            synth = nullptr;
        }
        if (settings) {
            delete_fluid_settings(settings);
            settings = nullptr;
        }
    }

    int loadSoundFont(const char* path) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (!synth) return -1;
        // reset=0: do not disturb channel 9's drum-kit program selection
        int sfId = fluid_synth_sfload(synth, path, 0);
        if (sfId == FLUID_FAILED) {
            LOGE("Failed to load soundfont: %s", path);
            return -1;
        }
        // Re-assert channel 9 routing to the drum soundfont in case this
        // new SF2 contains bank 128 presets that FluidSynth would otherwise
        // pick up for the drum channel.
        if (metronomeDrumSfId >= 0) {
            fluid_synth_program_select(synth, kMetronomeChannel, metronomeDrumSfId,
                                       kMetronomeDrumBank, kMetronomeDrumPreset);
        }
        LOGI("Loaded soundfont: %s (id=%d)", path, sfId);
        return sfId;
    }

    void programSelect(int channel, int sfId, int bank, int preset) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (synth) fluid_synth_program_select(synth, channel, sfId, bank, preset);
    }

    // MIDI events — called from MIDI thread, must be fast
    void noteOn(int channel, int note, int velocity) {
        if (synth) {
            fluid_synth_noteon(synth, channel, note, velocity);
        } else {
            LOGE("noteOn called but synth is null! ch=%d note=%d vel=%d", channel, note, velocity);
        }
    }

    void noteOff(int channel, int note) {
        if (synth) fluid_synth_noteoff(synth, channel, note);
    }

    void cc(int channel, int ctrl, int value) {
        if (synth) fluid_synth_cc(synth, channel, ctrl, value);
    }

    void setGain(float gain) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (synth) fluid_synth_set_gain(synth, gain);
    }

    void setReverb(float roomsize, float damping, float width, float level) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (synth) fluid_synth_set_reverb(synth, roomsize, damping, width, level);
    }

    void setChorus(int nr, float level, float speed, float depth, int type) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (synth) fluid_synth_set_chorus(synth, nr, level, speed, depth, type);
    }

    void setReverbOn(bool on) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (synth) fluid_synth_set_reverb_on(synth, on ? 1 : 0);
    }

    void setChorusOn(bool on) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (synth) fluid_synth_set_chorus_on(synth, on ? 1 : 0);
    }

    // Returns preset count and fills arrays with bank, program, name for each
    int getPresets(int sfId, int* banks, int* programs, const char** names, int maxPresets) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (!synth) return 0;
        fluid_sfont_t* sfont = fluid_synth_get_sfont_by_id(synth, sfId);
        if (!sfont) return 0;

        fluid_sfont_iteration_start(sfont);
        int count = 0;
        fluid_preset_t* preset;
        while ((preset = fluid_sfont_iteration_next(sfont)) != nullptr && count < maxPresets) {
            banks[count] = fluid_preset_get_banknum(preset);
            programs[count] = fluid_preset_get_num(preset);
            names[count] = fluid_preset_get_name(preset);
            count++;
        }
        return count;
    }

    bool setBufferSize(int newBufferSize) {
        bufferSize = newBufferSize;
        stopAudio();
        return startAudio();
    }

    bool setAudioDevice(int deviceId) {
        audioDeviceId = deviceId;
        LOGI("Setting audio device ID: %d", deviceId);
        stopAudio();
        return startAudio();
    }

    float getPeakLevel() {
        return peakLevel.exchange(0.0f);
    }

    // --- Recording control (called from UI/drain thread) ---

    bool getRecordingOverflow() {
        return recordingOverflow.exchange(false, std::memory_order_relaxed);
    }

    void startRecording() {
        if (!recordingBuffer) {
            recordingBuffer = new SpscRingBuffer<float>(kRecordingBufferFloats);
        } else {
            recordingBuffer->reset();
        }
        recordingOverflow.store(false, std::memory_order_relaxed);
        recordingActive.store(true, std::memory_order_release);
        LOGI("Recording started");
    }

    void stopRecording() {
        recordingActive.store(false, std::memory_order_release);
        LOGI("Recording stopped");
    }

    int readRecordingBuffer(float* out, int maxFloats) {
        if (!recordingBuffer) return 0;
        return static_cast<int>(recordingBuffer->read(out, static_cast<size_t>(maxFloats)));
    }

    int getRecordingSampleRate() {
        return sampleRate;
    }

    // --- Metronome control (called from UI/main thread) ---

    void recalcSamplesPerBeat() {
        int bpm = metronomeBpm.load(std::memory_order_relaxed);
        if (bpm <= 0) bpm = 120;
        // sampleRate * 60 / bpm — integer math, no allocation
        metronomeSamplesPerBeat.store(sampleRate * 60 / bpm, std::memory_order_relaxed);
    }

    void startMetronome() {
        recalcSamplesPerBeat();
        metronomeCurrentBeat.store(0, std::memory_order_relaxed);
        metronomeRunning.store(true, std::memory_order_release);
        LOGI("Metronome started: bpm=%d, timeSig=%d, samplesPerBeat=%d",
             metronomeBpm.load(), metronomeTimeSigBeats.load(), metronomeSamplesPerBeat.load());
    }

    void stopMetronome() {
        metronomeRunning.store(false, std::memory_order_release);
        LOGI("Metronome stopped");
    }

    void setMetronomeBpm(int bpm) {
        metronomeBpm.store(bpm, std::memory_order_relaxed);
        recalcSamplesPerBeat();
    }

    void setMetronomeTimeSig(int beats) {
        metronomeTimeSigBeats.store(beats, std::memory_order_relaxed);
    }

    void setMetronomeClickNotes(int downbeatNote, int subbeatNote) {
        metronomeDownbeatNote.store(downbeatNote, std::memory_order_relaxed);
        metronomeSubbeatNote.store(subbeatNote, std::memory_order_relaxed);
    }

    void setMetronomeVolume(float v) {
        if (v < 0.0f) v = 0.0f;
        if (v > 4.0f) v = 4.0f;
        metronomeVolume.store(v, std::memory_order_relaxed);
    }

    // Load a dedicated drum-kit soundfont for the metronome and route channel 9
    // to its bank 128 preset 0 (GM standard drum kit). Safe to call multiple
    // times; subsequent calls reload and re-route.
    int loadMetronomeDrumKit(const char* path) {
        std::lock_guard<std::mutex> lock(synthMutex);
        if (!synth) return -1;
        // Pass reset=0 so we don't disturb channel 0 program selection
        int sfId = fluid_synth_sfload(synth, path, 0);
        if (sfId == FLUID_FAILED) {
            LOGE("Failed to load metronome drum SF: %s", path);
            return -1;
        }
        metronomeDrumSfId = sfId;
        int rc = fluid_synth_program_select(synth, kMetronomeChannel, sfId,
                                            kMetronomeDrumBank, kMetronomeDrumPreset);
        if (rc == FLUID_FAILED) {
            LOGE("program_select ch9 bank=%d preset=%d failed in %s",
                 kMetronomeDrumBank, kMetronomeDrumPreset, path);
        } else {
            LOGI("Metronome drum kit loaded: %s (sfId=%d)", path, sfId);
        }
        return sfId;
    }

    int getMetronomeBeat() {
        return metronomeCurrentBeat.load(std::memory_order_relaxed);
    }

    // Schedule metronome noteon/noteoff events on channel 9. Called from the RT
    // audio callback BEFORE fluid_synth_write_float so the events land in the
    // same render pass. No allocations, no mutex, no logging.
    void processMetronomeTick(int numFrames) {
        bool running = metronomeRunning.load(std::memory_order_acquire);
        if (!running) {
            // Cleanup any lingering note when stopped
            if (metronomePendingNoteOff >= 0) {
                fluid_synth_noteoff(synth, kMetronomeChannel, metronomePendingNoteOff);
                metronomePendingNoteOff = -1;
            }
            metronomeNoteOffCountdown = 0;
            metronomeSampleCounter = 0;
            return;
        }

        int spb = metronomeSamplesPerBeat.load(std::memory_order_relaxed);
        int timeSig = metronomeTimeSigBeats.load(std::memory_order_relaxed);
        if (spb <= 0 || timeSig <= 0) return;

        int beat = metronomeCurrentBeat.load(std::memory_order_relaxed);
        int downNote = metronomeDownbeatNote.load(std::memory_order_relaxed);
        int subNote = metronomeSubbeatNote.load(std::memory_order_relaxed);
        float volume = metronomeVolume.load(std::memory_order_relaxed);
        int noteOffFrames = sampleRate * kMetronomeNoteOffMs / 1000;

        for (int i = 0; i < numFrames; i++) {
            // Deliver pending noteoff when countdown hits zero
            if (metronomePendingNoteOff >= 0) {
                if (metronomeNoteOffCountdown <= 0) {
                    fluid_synth_noteoff(synth, kMetronomeChannel, metronomePendingNoteOff);
                    metronomePendingNoteOff = -1;
                } else {
                    metronomeNoteOffCountdown--;
                }
            }

            // Beat boundary: fire a new click
            if (metronomeSampleCounter == 0) {
                bool downbeat = (beat == 0);
                int note = downbeat ? downNote : subNote;
                int baseVel = downbeat ? kMetronomeDownbeatVelocity : kMetronomeSubbeatVelocity;
                int vel = (int)(baseVel * volume);
                if (vel < 1) vel = 1;
                if (vel > 127) vel = 127;

                // Cut any still-active previous click before new noteon
                if (metronomePendingNoteOff >= 0) {
                    fluid_synth_noteoff(synth, kMetronomeChannel, metronomePendingNoteOff);
                }
                fluid_synth_noteon(synth, kMetronomeChannel, note, vel);
                metronomePendingNoteOff = note;
                metronomeNoteOffCountdown = noteOffFrames;

                beat = (beat + 1) % timeSig;
            }

            metronomeSampleCounter++;
            if (metronomeSampleCounter >= spb) {
                metronomeSampleCounter = 0;
            }
        }

        metronomeCurrentBeat.store(beat, std::memory_order_relaxed);
    }

    // Oboe audio callback — renders FluidSynth audio into output buffer
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
        auto* output = static_cast<float*>(audioData);

        if (synth) {
            // Schedule metronome noteon/noteoff before render so they land
            // in this buffer's audio output
            processMetronomeTick(numFrames);

            // FluidSynth renders interleaved stereo float samples, overwriting output
            fluid_synth_write_float(synth, numFrames,
                                    output, 0, 2,   // left: offset 0, stride 2
                                    output, 1, 2);  // right: offset 1, stride 2

            // Recording: write rendered audio to ring buffer (RT-safe, no alloc/lock/log)
            if (recordingActive.load(std::memory_order_acquire) && recordingBuffer) {
                size_t expected = static_cast<size_t>(numFrames * 2);
                size_t written = recordingBuffer->write(output, expected);
                if (written < expected) {
                    recordingOverflow.store(true, std::memory_order_relaxed);
                }
            }

            // Compute peak level for output meter
            float peak = 0.0f;
            int totalSamples = numFrames * 2;
            for (int i = 0; i < totalSamples; i++) {
                float abs = output[i] < 0 ? -output[i] : output[i];
                if (abs > peak) peak = abs;
            }
            // Keep the max of current and previous (will be reset on read)
            float prev = peakLevel.load();
            if (peak > prev) {
                peakLevel.store(peak);
                // Log occasionally when there's significant audio
                static int logCounter = 0;
                if (peak > 0.01f && (logCounter++ % 200) == 0) {
                    LOGI("Audio output peak: %.4f", peak);
                }
            }
        } else {
            memset(output, 0, numFrames * 2 * sizeof(float));
        }

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorBeforeClose(oboe::AudioStream* stream, oboe::Result error) override {
        LOGE("Audio stream error: %s", oboe::convertToText(error));
    }

    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override {
        LOGE("Audio stream closed with error: %s, restarting...", oboe::convertToText(error));
        startAudio();
    }
};

// C wrapper functions for JNI bridge (called from native-lib.c)
extern "C" {

void* engine_create(int sampleRate, int bufferSize) {
    auto* engine = new PyanoEngine();
    if (!engine->create(sampleRate, bufferSize)) {
        delete engine;
        return nullptr;
    }
    return engine;
}

void engine_destroy(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) {
        engine->destroy();
        delete engine;
    }
}

int engine_load_soundfont(void* ptr, const char* path) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine ? engine->loadSoundFont(path) : -1;
}

void engine_program_select(void* ptr, int channel, int sfId, int bank, int preset) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->programSelect(channel, sfId, bank, preset);
}

void engine_note_on(void* ptr, int channel, int note, int velocity) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->noteOn(channel, note, velocity);
}

void engine_note_off(void* ptr, int channel, int note) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->noteOff(channel, note);
}

void engine_cc(void* ptr, int channel, int ctrl, int value) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->cc(channel, ctrl, value);
}

void engine_set_gain(void* ptr, float gain) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setGain(gain);
}

void engine_set_reverb(void* ptr, float roomsize, float damping, float width, float level) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setReverb(roomsize, damping, width, level);
}

void engine_set_chorus(void* ptr, int nr, float level, float speed, float depth, int type) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setChorus(nr, level, speed, depth, type);
}

void engine_set_reverb_on(void* ptr, int on) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setReverbOn(on != 0);
}

void engine_set_chorus_on(void* ptr, int on) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setChorusOn(on != 0);
}

int engine_start_audio(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine && engine->startAudio() ? 1 : 0;
}

void engine_stop_audio(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->stopAudio();
}

int engine_set_buffer_size(void* ptr, int bufferSize) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine && engine->setBufferSize(bufferSize) ? 1 : 0;
}

int engine_get_presets(void* ptr, int sfId, int* banks, int* programs, const char** names, int maxPresets) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine ? engine->getPresets(sfId, banks, programs, names, maxPresets) : 0;
}

int engine_set_audio_device(void* ptr, int deviceId) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine && engine->setAudioDevice(deviceId) ? 1 : 0;
}

float engine_get_peak_level(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine ? engine->getPeakLevel() : 0.0f;
}

// --- Metronome C wrappers ---

void engine_start_metronome(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->startMetronome();
}

void engine_stop_metronome(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->stopMetronome();
}

void engine_set_metronome_bpm(void* ptr, int bpm) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setMetronomeBpm(bpm);
}

void engine_set_metronome_time_sig(void* ptr, int beats) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setMetronomeTimeSig(beats);
}

int engine_get_metronome_beat(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine ? engine->getMetronomeBeat() : 0;
}

void engine_set_metronome_click_notes(void* ptr, int downbeatNote, int subbeatNote) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setMetronomeClickNotes(downbeatNote, subbeatNote);
}

void engine_set_metronome_volume(void* ptr, float volume) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->setMetronomeVolume(volume);
}

int engine_load_metronome_drum_kit(void* ptr, const char* path) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine ? engine->loadMetronomeDrumKit(path) : -1;
}

// --- Recording C wrappers ---

void engine_start_recording(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->startRecording();
}

void engine_stop_recording(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (engine) engine->stopRecording();
}

int engine_read_recording_buffer(void* ptr, float* out, int maxFloats) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine ? engine->readRecordingBuffer(out, maxFloats) : 0;
}

int engine_get_recording_sample_rate(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine ? engine->getRecordingSampleRate() : 48000;
}

int engine_get_recording_overflow(void* ptr) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine && engine->getRecordingOverflow() ? 1 : 0;
}

} // extern "C"
