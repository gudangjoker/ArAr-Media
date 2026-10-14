#ifndef NATIVE_MEDIA_ENGINE_H
#define NATIVE_MEDIA_ENGINE_H

#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <vector>
#include <cmath>
#include <complex>
#include <algorithm>
#include <memory>
#include <cstring>

#define TAG "ArArMediaNativeEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// --- Biquad Peaking EQ Filter ---
class BiquadFilter {
public:
    double b0, b1, b2, a1, a2;
    double x1, x2, y1, y2;

    BiquadFilter() {
        reset();
        setPassThrough();
    }

    void reset() {
        x1 = x2 = y1 = y2 = 0.0;
    }

    void setPassThrough() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0;
        a1 = 0.0; a2 = 0.0;
    }

    void configurePeaking(double frequency, double sampleRate, double gainDb, double q = 1.414) {
        if (std::abs(gainDb) < 0.01) {
            setPassThrough();
            return;
        }
        double omega = 2.0 * M_PI * frequency / sampleRate;
        if (omega >= M_PI) omega = M_PI * 0.999;
        double sn = std::sin(omega);
        double cs = std::cos(omega);
        double alpha = sn / (2.0 * q);
        double A = std::pow(10.0, gainDb / 40.0);

        double a0 = 1.0 + alpha / A;
        b0 = (1.0 + alpha * A) / a0;
        b1 = (-2.0 * cs) / a0;
        b2 = (1.0 - alpha * A) / a0;
        a1 = (-2.0 * cs) / a0;
        a2 = (1.0 - alpha / A) / a0;
    }

    inline double process(double input) {
        double out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = input;
        y2 = y1;
        y1 = out;
        return out;
    }
};

// --- Schroeder Reverb Comb & Allpass ---
class CombFilter {
public:
    std::vector<double> buffer;
    size_t bufferSize;
    size_t bufferIndex;
    double feedback;
    double filterStore;
    double damp;

    CombFilter(size_t size = 1116) : bufferSize(size), bufferIndex(0), feedback(0.8), filterStore(0.0), damp(0.2) {
        buffer.assign(bufferSize, 0.0);
    }

    void setSize(size_t size) {
        bufferSize = size;
        bufferIndex = 0;
        buffer.assign(bufferSize, 0.0);
    }

    inline double process(double input) {
        double output = buffer[bufferIndex];
        filterStore = (output * (1.0 - damp)) + (filterStore * damp);
        buffer[bufferIndex] = input + (filterStore * feedback);
        if (++bufferIndex >= bufferSize) bufferIndex = 0;
        return output;
    }
};

class AllpassFilter {
public:
    std::vector<double> buffer;
    size_t bufferSize;
    size_t bufferIndex;
    double feedback;

    AllpassFilter(size_t size = 441) : bufferSize(size), bufferIndex(0), feedback(0.5) {
        buffer.assign(bufferSize, 0.0);
    }

    inline double process(double input) {
        double bufOut = buffer[bufferIndex];
        double out = -input + bufOut;
        buffer[bufferIndex] = input + (bufOut * feedback);
        if (++bufferIndex >= bufferSize) bufferIndex = 0;
        return out;
    }
};

// --- Studio Reverb Engine (Freeverb implementation) ---
class Freeverb {
public:
    std::vector<CombFilter> combFilters;
    std::vector<AllpassFilter> allpassFilters;
    double roomSize;
    double damp;
    double wet;
    double dry;

    Freeverb() : roomSize(0.6), damp(0.25), wet(0.3), dry(0.7) {
        const size_t combTunings[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        const size_t allpassTunings[4] = {556, 441, 341, 225};

        for (int i = 0; i < 8; ++i) {
            combFilters.emplace_back(combTunings[i]);
            combFilters[i].feedback = roomSize;
            combFilters[i].damp = damp;
        }
        for (int i = 0; i < 4; ++i) {
            allpassFilters.emplace_back(allpassTunings[i]);
        }
    }

    void updateParams(double room, double dampingVal, double wetMix) {
        roomSize = std::clamp(room, 0.0, 0.98);
        damp = std::clamp(dampingVal, 0.0, 1.0);
        wet = std::clamp(wetMix, 0.0, 1.0);
        dry = 1.0 - (wet * 0.5);

        for (auto& c : combFilters) {
            c.feedback = roomSize;
            c.damp = damp;
        }
    }

    inline double process(double input) {
        double outComb = 0.0;
        for (auto& c : combFilters) {
            outComb += c.process(input);
        }
        double outAllpass = outComb * 0.125;
        for (auto& a : allpassFilters) {
            outAllpass = a.process(outAllpass);
        }
        return (dry * input) + (wet * outAllpass);
    }
};

// --- Dynamics Processor (Compressor & Limiter) ---
class StudioCompressor {
public:
    double thresholdDb;
    double ratio;
    double attackMs;
    double releaseMs;
    double makeupGainDb;
    double sampleRate;
    double envelope;
    double lastGainReductionDb;

    double attackCoeff;
    double releaseCoeff;

    StudioCompressor(double sr = 44100.0) :
        thresholdDb(-18.0), ratio(4.0), attackMs(20.0), releaseMs(200.0), makeupGainDb(0.0),
        sampleRate(sr), envelope(0.0), lastGainReductionDb(0.0) {
        recalculateCoefficients();
    }

    void setParams(double thresh, double r, double att, double rel, double makeup) {
        thresholdDb = thresh;
        ratio = std::max(1.0, r);
        attackMs = std::max(0.1, att);
        releaseMs = std::max(1.0, rel);
        makeupGainDb = makeup;
        recalculateCoefficients();
    }

    void recalculateCoefficients() {
        attackCoeff = std::exp(-1.0 / (attackMs * 0.001 * sampleRate));
        releaseCoeff = std::exp(-1.0 / (releaseMs * 0.001 * sampleRate));
    }

    inline double process(double input) {
        double inputAbs = std::abs(input);
        if (inputAbs > envelope) {
            envelope = attackCoeff * envelope + (1.0 - attackCoeff) * inputAbs;
        } else {
            envelope = releaseCoeff * envelope + (1.0 - releaseCoeff) * inputAbs;
        }

        double envDb = (envelope > 1e-6) ? 20.0 * std::log10(envelope) : -120.0;
        double grDb = 0.0;
        if (envDb > thresholdDb) {
            grDb = (thresholdDb - envDb) * (1.0 - 1.0 / ratio);
        }
        lastGainReductionDb = -grDb;

        double totalGainLinear = std::pow(10.0, (grDb + makeupGainDb) / 20.0);
        double out = input * totalGainLinear;

        // Brickwall limiter at -0.1 dB (0.988)
        if (out > 0.988) out = 0.988;
        else if (out < -0.988) out = -0.988;

        return out;
    }
};

// --- Full Studio DSP Rack ---
class StudioDspEngine {
public:
    int sampleRate;
    int channels;

    // 10-Band EQ (frequencies: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz)
    static const int NUM_BANDS = 10;
    const double EQ_FREQUENCIES[10] = {31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0};
    BiquadFilter eqBands[NUM_BANDS];
    double bandGains[NUM_BANDS];

    StudioCompressor compressor;
    Freeverb reverb;

    // Pitch Shift & Voice Changer
    float pitchSemitones;
    int activeVoicePreset; // 0=None, 1=Deep Podcast, 2=Chipmunk, 3=Megaphone, 4=Studio Radio, 5=Robot, 6=Monster, 7=Cathedral
    double robotPhase;

    // Delay line
    std::vector<double> delayBuffer;
    size_t delayWriteIndex;
    size_t delaySamples;
    double delayFeedback;
    double delayWet;

    StudioDspEngine(int sr = 44100, int ch = 2);

    void setEqGain(int band, double gainDb);
    void setCompressorParams(double thresholdDb, double ratio, double attackMs, double releaseMs, double makeupGainDb);
    void setReverbParams(double roomSize, double damping, double wetMix, double delayMs);
    void setPitchSemitones(float semitones);
    void setVoicePreset(int presetId);
    float processPcm(const float* inBuffer, float* outBuffer, int numSamples);
};

#endif // NATIVE_MEDIA_ENGINE_H
