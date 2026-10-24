// audio-engine.cpp: Native real-time audio core — FluidSynth synthesis + Oboe output, metronome, and recording.
// NOT concerned with: JNI marshaling (see native-lib.c), UI state, or Kotlin-level logic.
#include <oboe/Oboe.h>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstring>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <atomic>
#include <string>
#include <vector>

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

// A MIDI or control change handed from a non-RT thread to the audio callback,
// which applies it to the synth just before rendering. Trivially copyable so it
// can travel through SpscRingBuffer.
struct SynthEvent {
    enum Type : uint8_t { NoteOn, NoteOff, CC, ProgramSelect, Gain, Reverb, ReverbOn,
                          Chorus, ChorusOn };
    Type type;
    int32_t i[4];
    float f[4];
};

struct CachedPreset {
    int bank;
    int program;
    std::string name;
};

// Threading model: FluidSynth runs with its internal API lock disabled and the
// audio callback is the only thread that touches the synth during playback.
// Notes, CCs and effect changes are queued (pushEvent) and applied at the top
// of the next callback. Heavy operations that must run elsewhere (SoundFont
// loading, sample-rate changes, teardown) take synthMutex; the callback only
// try_locks it and outputs silence for that buffer instead of blocking.
class PyanoEngine : public oboe::AudioStreamCallback {
public:
    fluid_settings_t* settings = nullptr;
    fluid_synth_t* synth = nullptr;
    oboe::ManagedStream stream;
    std::mutex synthMutex;

    // Event queue: any number of producers serialized by producerMutex, one
    // consumer (the RT callback) that never takes a lock.
    static constexpr size_t kEventQueueCapacity = 1024;
    SpscRingBuffer<SynthEvent> eventQueue{kEventQueueCapacity};
    std::mutex producerMutex;

    // Preset lists captured at load time so the UI never has to walk a
    // SoundFont while the audio thread owns the synth. Keyed by sfId.
    std::mutex presetCacheMutex;
    std::map<int, std::vector<CachedPreset>> presetCache;
    int sampleRate = 48000;
    // Output buffer size in frames (the latency knob). 0 = automatic: start at
    // two bursts and let latencyTuner grow it one burst per underrun.
    int requestedBufferFrames = 0;
    int audioDeviceId = oboe::kUnspecified;
    std::unique_ptr<oboe::LatencyTuner> latencyTuner;   // non-null only in automatic mode

    // Output level monitoring (peak level per callback)
    std::atomic<float> peakLevel{0.0f};

    // Glitch diagnostics, written by the RT callback and polled by the UI thread.
    // xruns accumulate across stream reopens; max callback time and peak voices
    // are reset on each read so every poll reports the worst case since the last.
    std::atomic<int> totalXruns{0};
    int streamXrunsSeen = 0;                    // RT-only: xruns already counted for this stream
    std::atomic<int> streamBufferFrames{0};
    std::atomic<int> streamBurstFrames{0};
    std::atomic<int> lastCallbackFrames{0};
    std::atomic<int> maxCallbackMicros{0};
    std::atomic<int> peakActiveVoices{0};
    std::atomic<int> droppedEvents{0};          // queue full; event discarded
    std::atomic<int> lockedOutCallbacks{0};     // callback output silence: synth busy (load)

    // Recording ring buffer: allocated once, reused across recordings
    // ~60s of stereo float at 48kHz = 48000 * 2 * 60 = 5,760,000 floats (~32MB after power-of-2 rounding)
    static constexpr size_t kRecordingBufferFloats = 48000 * 2 * 60;
    SpscRingBuffer<float>* recordingBuffer = nullptr;
    std::atomic<bool> recordingActive{false};

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

    // requestedSampleRate should be the device's native output rate so the stream
    // opens without resampling; startAudio() corrects it if the device differs.
    bool create(int requestedSampleRate, int bufferFrames) {
        sampleRate = requestedSampleRate;
        requestedBufferFrames = bufferFrames;

        settings = new_fluid_settings();
        if (!settings) {
            LOGE("Failed to create FluidSynth settings");
            return false;
        }

        fluid_settings_setnum(settings, "synth.sample-rate", (double)sampleRate);
        fluid_settings_setint(settings, "synth.audio-channels", 1); // 1 stereo pair
        fluid_settings_setint(settings, "synth.polyphony", 512);
        // Only the audio callback (or a holder of synthMutex) calls into the synth,
        // so FluidSynth's own API mutex would only add contention with the RT thread.
        fluid_settings_setint(settings, "synth.threadsafe-api", 0);

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

        LOGI("FluidSynth created: sampleRate=%d, bufferFrames=%d", sampleRate, requestedBufferFrames);
        return true;
    }

    bool startAudio() {
        streamXrunsSeen = 0;  // new stream starts its xrun counter at zero
        latencyTuner.reset(); // holds a reference to the stream being replaced
        // Sample rate and callback size are deliberately left unset: the device's
        // native rate and burst-sized callbacks are what keep the stream on the
        // low-latency (MMAP / fast mixer) path with evenly spaced callbacks.
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)  // falls back to Shared
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(2)
               ->setUsage(oboe::Usage::Media)
               ->setContentType(oboe::ContentType::Music)
               ->setDeviceId(audioDeviceId)
               ->setCallback(this);

        oboe::Result result = builder.openManagedStream(stream);
        if (result != oboe::Result::OK) {
            LOGE("Failed to open stream: %s", oboe::convertToText(result));
            return false;
        }

        // Render at whatever rate the device gave us. Done before start, so no
        // callback is running; voices are reset by the rate change.
        int actualRate = stream->getSampleRate();
        if (actualRate != sampleRate && synth) {
            LOGI("Adjusting FluidSynth sample rate from %d to %d", sampleRate, actualRate);
            std::lock_guard<std::mutex> lock(synthMutex);
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            // Deprecated because it is unsafe while rendering; the stream is stopped here.
            fluid_synth_set_sample_rate(synth, (float)actualRate);
#pragma clang diagnostic pop
            sampleRate = actualRate;
            recalcSamplesPerBeat();
        }

        if (requestedBufferFrames > 0) {
            stream->setBufferSizeInFrames(requestedBufferFrames);
        } else {
            latencyTuner = std::make_unique<oboe::LatencyTuner>(*stream);
        }

        result = stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start stream: %s", oboe::convertToText(result));
            return false;
        }

        LOGI("Audio stream started: sampleRate=%d, framesPerCallback=%d, deviceId=%d, "
             "burst=%d, bufferSize=%d, capacity=%d, sharing=%s, perfMode=%s, api=%s",
             stream->getSampleRate(), stream->getFramesPerCallback(), stream->getDeviceId(),
             stream->getFramesPerBurst(), stream->getBufferSizeInFrames(),
             stream->getBufferCapacityInFrames(),
             oboe::convertToText(stream->getSharingMode()),
             oboe::convertToText(stream->getPerformanceMode()),
             stream->usesAAudio() ? "AAudio" : "OpenSL ES");
        streamBurstFrames.store(stream->getFramesPerBurst(), std::memory_order_relaxed);
        return true;
    }

    void stopAudio() {
        if (stream) {
            stream->requestStop();
            stream->close();
            latencyTuner.reset();
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
        cachePresetsLocked(sfId);
        LOGI("Loaded soundfont: %s (id=%d)", path, sfId);
        return sfId;
    }

    // Caller must hold synthMutex.
    void cachePresetsLocked(int sfId) {
        fluid_sfont_t* sfont = fluid_synth_get_sfont_by_id(synth, sfId);
        if (!sfont) return;
        std::vector<CachedPreset> presets;
        fluid_sfont_iteration_start(sfont);
        fluid_preset_t* preset;
        while ((preset = fluid_sfont_iteration_next(sfont)) != nullptr) {
            const char* name = fluid_preset_get_name(preset);
            presets.push_back({fluid_preset_get_banknum(preset), fluid_preset_get_num(preset),
                               name ? name : ""});
        }
        std::lock_guard<std::mutex> lock(presetCacheMutex);
        presetCache[sfId] = std::move(presets);
    }

    // Called from any non-RT thread. Never blocks on the audio thread.
    void pushEvent(const SynthEvent& ev) {
        std::lock_guard<std::mutex> lock(producerMutex);
        if (eventQueue.write(&ev, 1) == 0) {
            droppedEvents.fetch_add(1, std::memory_order_relaxed);
        }
    }

    void programSelect(int channel, int sfId, int bank, int preset) {
        pushEvent({SynthEvent::ProgramSelect, {channel, sfId, bank, preset}, {}});
    }

    void noteOn(int channel, int note, int velocity) {
        pushEvent({SynthEvent::NoteOn, {channel, note, velocity, 0}, {}});
    }

    void noteOff(int channel, int note) {
        pushEvent({SynthEvent::NoteOff, {channel, note, 0, 0}, {}});
    }

    void cc(int channel, int ctrl, int value) {
        pushEvent({SynthEvent::CC, {channel, ctrl, value, 0}, {}});
    }

    void setGain(float gain) {
        pushEvent({SynthEvent::Gain, {}, {gain, 0, 0, 0}});
    }

    void setReverb(float roomsize, float damping, float width, float level) {
        pushEvent({SynthEvent::Reverb, {}, {roomsize, damping, width, level}});
    }

    void setChorus(int nr, float level, float speed, float depth, int type) {
        pushEvent({SynthEvent::Chorus, {nr, type, 0, 0}, {level, speed, depth, 0}});
    }

    void setReverbOn(bool on) {
        pushEvent({SynthEvent::ReverbOn, {on ? 1 : 0, 0, 0, 0}, {}});
    }

    void setChorusOn(bool on) {
        pushEvent({SynthEvent::ChorusOn, {on ? 1 : 0, 0, 0, 0}, {}});
    }

    // RT thread only, with synthMutex held.
    void drainEvents() {
        SynthEvent ev;
        while (eventQueue.read(&ev, 1) == 1) {
            switch (ev.type) {
                case SynthEvent::NoteOn:
                    fluid_synth_noteon(synth, ev.i[0], ev.i[1], ev.i[2]);
                    break;
                case SynthEvent::NoteOff:
                    fluid_synth_noteoff(synth, ev.i[0], ev.i[1]);
                    break;
                case SynthEvent::CC:
                    fluid_synth_cc(synth, ev.i[0], ev.i[1], ev.i[2]);
                    break;
                case SynthEvent::ProgramSelect:
                    fluid_synth_program_select(synth, ev.i[0], ev.i[1], ev.i[2], ev.i[3]);
                    break;
                case SynthEvent::Gain:
                    fluid_synth_set_gain(synth, ev.f[0]);
                    break;
                case SynthEvent::Reverb:
                    fluid_synth_set_reverb(synth, ev.f[0], ev.f[1], ev.f[2], ev.f[3]);
                    break;
                case SynthEvent::ReverbOn:
                    fluid_synth_set_reverb_on(synth, ev.i[0]);
                    break;
                case SynthEvent::Chorus:
                    fluid_synth_set_chorus(synth, ev.i[0], ev.f[0], ev.f[1], ev.f[2], ev.i[1]);
                    break;
                case SynthEvent::ChorusOn:
                    fluid_synth_set_chorus_on(synth, ev.i[0]);
                    break;
            }
        }
    }

    // Returns preset count and fills arrays with bank, program, name for each.
    // Name pointers stay valid until destroy(): cache entries are never replaced
    // because FluidSynth never reuses an sfId.
    int getPresets(int sfId, int* banks, int* programs, const char** names, int maxPresets) {
        std::lock_guard<std::mutex> lock(presetCacheMutex);
        auto it = presetCache.find(sfId);
        if (it == presetCache.end()) return 0;
        int count = 0;
        for (const auto& p : it->second) {
            if (count >= maxPresets) break;
            banks[count] = p.bank;
            programs[count] = p.program;
            names[count] = p.name.c_str();
            count++;
        }
        return count;
    }

    // frames == 0 selects automatic tuning.
    bool setBufferSize(int frames) {
        requestedBufferFrames = frames;
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

    // Fills out[kAudioStatsCount]; see the layout comment on engine_get_audio_stats.
    static constexpr int kAudioStatsCount = 9;
    void getAudioStats(int* out) {
        out[0] = totalXruns.load(std::memory_order_relaxed);
        out[1] = streamBufferFrames.load(std::memory_order_relaxed);
        out[2] = streamBurstFrames.load(std::memory_order_relaxed);
        out[3] = lastCallbackFrames.load(std::memory_order_relaxed);
        out[4] = maxCallbackMicros.exchange(0, std::memory_order_relaxed);
        out[5] = peakActiveVoices.exchange(0, std::memory_order_relaxed);
        out[6] = sampleRate;
        out[7] = droppedEvents.load(std::memory_order_relaxed);
        out[8] = lockedOutCallbacks.load(std::memory_order_relaxed);
    }

    // --- Recording control (called from UI/drain thread) ---

    void startRecording() {
        if (!recordingBuffer) {
            recordingBuffer = new SpscRingBuffer<float>(kRecordingBufferFloats);
        } else {
            recordingBuffer->reset();
        }
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
        cachePresetsLocked(sfId);
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

    // RT-safe: getXRunCount/getBufferSizeInFrames are plain reads on AAudio, and
    // steady_clock is a vDSO clock_gettime call. Only atomics are written.
    void recordCallbackStats(oboe::AudioStream* audioStream, int32_t numFrames,
                             std::chrono::steady_clock::time_point callbackStart) {
        auto xruns = audioStream->getXRunCount();
        if (xruns && xruns.value() > streamXrunsSeen) {
            totalXruns.fetch_add(xruns.value() - streamXrunsSeen, std::memory_order_relaxed);
            streamXrunsSeen = xruns.value();
        }
        streamBufferFrames.store(audioStream->getBufferSizeInFrames(), std::memory_order_relaxed);
        lastCallbackFrames.store(numFrames, std::memory_order_relaxed);

        int micros = (int)std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - callbackStart).count();
        if (micros > maxCallbackMicros.load(std::memory_order_relaxed)) {
            maxCallbackMicros.store(micros, std::memory_order_relaxed);
        }
    }

    // Oboe audio callback — renders FluidSynth audio into output buffer
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
        auto callbackStart = std::chrono::steady_clock::now();
        auto* output = static_cast<float*>(audioData);

        // Never wait for a non-RT thread: if a SoundFont load (or teardown) holds
        // the synth, emit silence for this buffer and leave queued events for later.
        std::unique_lock<std::mutex> lock(synthMutex, std::try_to_lock);
        if (synth && !lock.owns_lock()) {
            lockedOutCallbacks.fetch_add(1, std::memory_order_relaxed);
        }

        if (synth && lock.owns_lock()) {
            drainEvents();

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
                recordingBuffer->write(output, expected);
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
            }

            int voices = fluid_synth_get_active_voice_count(synth);
            if (voices > peakActiveVoices.load(std::memory_order_relaxed)) {
                peakActiveVoices.store(voices, std::memory_order_relaxed);
            }
        } else {
            memset(output, 0, numFrames * 2 * sizeof(float));
        }

        // Grows the buffer one burst after an underrun (automatic mode only).
        if (latencyTuner) latencyTuner->tune();

        recordCallbackStats(audioStream, numFrames, callbackStart);
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

void* engine_create(int sampleRate, int bufferFrames) {
    auto* engine = new PyanoEngine();
    if (!engine->create(sampleRate, bufferFrames)) {
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

int engine_set_buffer_size(void* ptr, int bufferFrames) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    return engine && engine->setBufferSize(bufferFrames) ? 1 : 0;
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

// Fills out[9]: {totalXruns, bufferSizeFrames, burstFrames, lastCallbackFrames,
// maxCallbackMicros (since last call), peakActiveVoices (since last call), sampleRate,
// droppedEvents, lockedOutCallbacks}.
// Returns the number of values written.
int engine_get_audio_stats(void* ptr, int* out, int maxValues) {
    auto* engine = static_cast<PyanoEngine*>(ptr);
    if (!engine || maxValues < PyanoEngine::kAudioStatsCount) return 0;
    engine->getAudioStats(out);
    return PyanoEngine::kAudioStatsCount;
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

} // extern "C"
