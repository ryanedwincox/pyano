#include <oboe/Oboe.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <atomic>

extern "C" {
#include <fluidsynth.h>
}

#define LOG_TAG "Pyano"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// GM percussion constants for metronome click sounds (channel 9 / GM channel 10)
static constexpr int kPercussionChannel = 9;
static constexpr int kDownbeatNote = 76;       // Hi wood block
static constexpr int kDownbeatVelocity = 120;
static constexpr int kSubbeatNote = 37;        // Side stick
static constexpr int kSubbeatVelocity = 100;
static constexpr int kClickDurationMs = 50;    // Noteoff delay after click onset

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

    // Metronome state (all accessed from RT audio thread — must be atomic)
    std::atomic<bool> metronomeRunning{false};
    std::atomic<int> metronomeBpm{120};
    std::atomic<int> metronomeTimeSigBeats{4};
    std::atomic<int> metronomeCurrentBeat{0};
    std::atomic<int> metronomeSampleCounter{0};
    std::atomic<int> metronomeSamplesPerBeat{0};  // calculated from sampleRate * 60 / bpm
    // Noteoff scheduling: frames remaining until we send noteoff (-1 = inactive)
    std::atomic<int> metronomeNoteOffCountdown{-1};
    std::atomic<int> metronomeLastNote{-1};

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
        stopAudio();
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
        int sfId = fluid_synth_sfload(synth, path, 1);
        if (sfId == FLUID_FAILED) {
            LOGE("Failed to load soundfont: %s", path);
            return -1;
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

    // --- Metronome control (called from UI/main thread) ---

    void recalcSamplesPerBeat() {
        int bpm = metronomeBpm.load(std::memory_order_relaxed);
        if (bpm <= 0) bpm = 120;
        // sampleRate * 60 / bpm — integer math, no allocation
        metronomeSamplesPerBeat.store(sampleRate * 60 / bpm, std::memory_order_relaxed);
    }

    void startMetronome() {
        recalcSamplesPerBeat();
        metronomeSampleCounter.store(0, std::memory_order_relaxed);
        metronomeCurrentBeat.store(0, std::memory_order_relaxed);
        metronomeNoteOffCountdown.store(-1, std::memory_order_relaxed);
        metronomeLastNote.store(-1, std::memory_order_relaxed);
        metronomeRunning.store(true, std::memory_order_release);
        LOGI("Metronome started: bpm=%d, timeSig=%d, samplesPerBeat=%d",
             metronomeBpm.load(), metronomeTimeSigBeats.load(), metronomeSamplesPerBeat.load());
    }

    void stopMetronome() {
        metronomeRunning.store(false, std::memory_order_release);
        // Noteoff cleanup is handled in the audio callback on the next render pass
        // to avoid racing with a concurrent noteon from the RT thread.
        LOGI("Metronome stopped");
    }

    void setMetronomeBpm(int bpm) {
        metronomeBpm.store(bpm, std::memory_order_relaxed);
        recalcSamplesPerBeat();
    }

    void setMetronomeTimeSig(int beats) {
        metronomeTimeSigBeats.store(beats, std::memory_order_relaxed);
    }

    int getMetronomeBeat() {
        return metronomeCurrentBeat.load(std::memory_order_relaxed);
    }

    // Process metronome clicks for numFrames. Called from RT audio callback — no allocations,
    // no mutex, no logging. All state accessed via locals cached from atomics.
    void processMetronomeTick(int numFrames) {
        bool running = metronomeRunning.load(std::memory_order_acquire);

        if (!running) {
            // Cleanup: send noteoff for any lingering note when stopped
            int lastNote = metronomeLastNote.exchange(-1, std::memory_order_relaxed);
            if (lastNote >= 0) {
                fluid_synth_noteoff(synth, kPercussionChannel, lastNote);
            }
            metronomeNoteOffCountdown.store(-1, std::memory_order_relaxed);
            return;
        }

        int spb = metronomeSamplesPerBeat.load(std::memory_order_relaxed);
        int timeSig = metronomeTimeSigBeats.load(std::memory_order_relaxed);
        if (spb <= 0 || timeSig <= 0) return;  // guard against division by zero

        // Cache all atomic state into locals for the tight per-sample loop
        int counter = metronomeSampleCounter.load(std::memory_order_relaxed);
        int noteOffCount = metronomeNoteOffCountdown.load(std::memory_order_relaxed);
        int lastNote = metronomeLastNote.load(std::memory_order_relaxed);
        int beat = metronomeCurrentBeat.load(std::memory_order_relaxed);
        int noteOffDuration = sampleRate * kClickDurationMs / 1000;

        for (int i = 0; i < numFrames; i++) {
            // Handle pending noteoff
            if (noteOffCount >= 0) {
                noteOffCount--;
                if (noteOffCount <= 0) {
                    if (lastNote >= 0) {
                        fluid_synth_noteoff(synth, kPercussionChannel, lastNote);
                        lastNote = -1;
                    }
                    noteOffCount = -1;
                }
            }

            // Beat boundary: trigger click
            if (counter == 0) {
                int note = (beat == 0) ? kDownbeatNote : kSubbeatNote;
                int velocity = (beat == 0) ? kDownbeatVelocity : kSubbeatVelocity;

                // Noteoff any previous click before new noteon
                if (lastNote >= 0) {
                    fluid_synth_noteoff(synth, kPercussionChannel, lastNote);
                }

                fluid_synth_noteon(synth, kPercussionChannel, note, velocity);
                lastNote = note;
                noteOffCount = noteOffDuration;

                // Advance beat
                beat = (beat + 1) % timeSig;
            }

            counter++;
            if (counter >= spb) {
                counter = 0;
            }
        }

        // Write locals back to atomics
        metronomeSampleCounter.store(counter, std::memory_order_relaxed);
        metronomeNoteOffCountdown.store(noteOffCount, std::memory_order_relaxed);
        metronomeLastNote.store(lastNote, std::memory_order_relaxed);
        metronomeCurrentBeat.store(beat, std::memory_order_relaxed);
    }

    // Oboe audio callback — renders FluidSynth audio into output buffer
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
        auto* output = static_cast<float*>(audioData);

        if (synth) {
            // FluidSynth renders interleaved stereo float samples
            fluid_synth_write_float(synth, numFrames,
                                    output, 0, 2,   // left: offset 0, stride 2
                                    output, 1, 2);  // right: offset 1, stride 2

            // Metronome tick processing (after FluidSynth render)
            processMetronomeTick(numFrames);

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

} // extern "C"
