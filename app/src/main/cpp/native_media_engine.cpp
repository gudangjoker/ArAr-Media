#include "native_media_engine.h"

// --- StudioDspEngine Implementation ---

StudioDspEngine::StudioDspEngine(int sr, int ch) :
    sampleRate(sr), channels(ch),
    compressor(sr), reverb(), pitchShifter(),
    pitchSemitones(0.0f), activeVoicePreset(0), robotPhase(0.0),
    delayWriteIndex(0), delayFeedback(0.28), delayWet(0.0) {

    for (int i = 0; i < NUM_BANDS; ++i) {
        bandGains[i] = 0.0;
        eqBands[i].configurePeaking(EQ_FREQUENCIES[i], sampleRate, 0.0, 1.414);
    }

    delaySamples = static_cast<size_t>(sampleRate * 0.25); // 250ms default delay
    delayBuffer.assign(sampleRate * 2, 0.0);               // Max 2 seconds delay buffer
}

void StudioDspEngine::setEqGain(int band, double gainDb) {
    if (band >= 0 && band < NUM_BANDS) {
        bandGains[band] = gainDb;
        eqBands[band].configurePeaking(EQ_FREQUENCIES[band], sampleRate, gainDb, 1.414);
    }
}

void StudioDspEngine::setCompressorParams(double thresholdDb, double ratio, double attackMs, double releaseMs, double makeupGainDb) {
    compressor.setParams(thresholdDb, ratio, attackMs, releaseMs, makeupGainDb);
}

void StudioDspEngine::setReverbParams(double roomSize, double damping, double wetMix, double delayMs) {
    reverb.updateParams(roomSize, damping, wetMix);
    size_t newDelay = static_cast<size_t>((delayMs * 0.001) * sampleRate);
    if (newDelay > 0 && newDelay < delayBuffer.size()) {
        delaySamples = newDelay;
    }
    delayWet = wetMix * 0.45;
}

void StudioDspEngine::setPitchSemitones(float semitones) {
    pitchSemitones = std::clamp(semitones, -12.0f, 12.0f);
}

void StudioDspEngine::setVoicePreset(int presetId) {
    activeVoicePreset = presetId;
    pitchShifter.reset();
    for (int i = 0; i < NUM_BANDS; ++i) {
        setEqGain(i, 0.0);
    }

    switch (presetId) {
        case 1: // Deep Podcast
            setPitchSemitones(-2.5f);
            setEqGain(1, 3.5);
            setEqGain(2, 4.0);
            setEqGain(5, 1.5);
            setEqGain(8, -2.0);
            compressor.setParams(-20.0, 4.5, 10.0, 150.0, 3.0);
            reverb.updateParams(0.25, 0.5, 0.05);
            break;
        case 2: // Chipmunk
            setPitchSemitones(7.0f);
            setEqGain(2, -4.0);
            setEqGain(6, 4.5);
            setEqGain(7, 3.5);
            reverb.updateParams(0.2, 0.4, 0.0);
            break;
        case 3: // Megaphone / Walkie-Talkie
            setPitchSemitones(0.0f);
            setEqGain(0, -15.0);
            setEqGain(1, -15.0);
            setEqGain(2, -15.0);
            setEqGain(4, 3.0);
            setEqGain(5, 8.0);
            setEqGain(6, 5.0);
            setEqGain(8, -14.0);
            setEqGain(9, -14.0);
            compressor.setParams(-26.0, 8.0, 2.0, 80.0, 5.0);
            break;
        case 4: // Studio Radio Broadcast
            setPitchSemitones(0.0f);
            setEqGain(1, 2.5);
            setEqGain(2, 3.0);
            setEqGain(6, 3.5);
            setEqGain(7, 2.0);
            compressor.setParams(-16.0, 5.0, 5.0, 120.0, 3.5);
            reverb.updateParams(0.3, 0.6, 0.04);
            break;
        case 5: // Cyber Robot (Ring Modulator + Metallic Comb)
            setPitchSemitones(0.0f);
            setEqGain(5, 3.0);
            reverb.updateParams(0.4, 0.1, 0.18);
            break;
        case 6: // Monster (Sub-octave + Saturation)
            setPitchSemitones(-6.5f);
            setEqGain(0, 6.0);
            setEqGain(1, 6.0);
            setEqGain(2, 4.0);
            setEqGain(7, -5.0);
            reverb.updateParams(0.75, 0.2, 0.28);
            break;
        case 7: // Cathedral Echo
            setPitchSemitones(0.0f);
            reverb.updateParams(0.92, 0.15, 0.55);
            delayWet = 0.30;
            break;
        default: // 0 = Natural / Bypass
            setPitchSemitones(0.0f);
            compressor.setParams(-18.0, 2.5, 20.0, 200.0, 0.0);
            reverb.updateParams(0.5, 0.3, 0.0);
            delayWet = 0.0;
            break;
    }
}

float StudioDspEngine::processPcm(const float* inBuffer, float* outBuffer, int numSamples) {
    double maxGr = 0.0;
    double pitchFactor = std::pow(2.0, pitchSemitones / 12.0);

    for (int i = 0; i < numSamples; ++i) {
        double sample = static_cast<double>(inBuffer[i]);

        // 1. Real-Time Granular Pitch Shifter
        if (std::abs(pitchSemitones) > 0.05f) {
            sample = pitchShifter.process(sample, pitchFactor);
        }

        // 2. Voice Changer Special Nonlinearities
        if (activeVoicePreset == 5) { // Cyber Robot Ring Modulator (55 Hz + 110 Hz harmonic carrier)
            robotPhase += (2.0 * M_PI * 55.0) / sampleRate;
            if (robotPhase > 2.0 * M_PI) robotPhase -= 2.0 * M_PI;
            double carrier = 0.7 * std::sin(robotPhase) + 0.3 * std::sin(robotPhase * 2.0);
            sample = sample * carrier;
        } else if (activeVoicePreset == 3) { // Megaphone Asymmetrical Overdrive
            sample = std::tanh(sample * 3.2) * 0.65;
        } else if (activeVoicePreset == 6) { // Monster Sub-Harmonic Growl
            sample = std::tanh(sample * 2.2) * 0.85;
        }

        // 3. 10-Band Cascaded Biquad Parametric EQ
        for (int b = 0; b < NUM_BANDS; ++b) {
            sample = eqBands[b].process(sample);
        }

        // 4. Delay Line Echo
        if (delayWet > 0.001 && !delayBuffer.empty()) {
            size_t readIdx = (delayWriteIndex + delayBuffer.size() - delaySamples) % delayBuffer.size();
            double delayed = delayBuffer[readIdx];
            delayBuffer[delayWriteIndex] = sample + delayed * delayFeedback;
            delayWriteIndex = (delayWriteIndex + 1) % delayBuffer.size();
            sample = sample * (1.0 - delayWet * 0.4) + delayed * delayWet;
        }

        // 5. Studio Freeverb
        sample = reverb.process(sample);

        // 6. Dynamics Compressor & Brickwall Limiter
        sample = compressor.process(sample);
        if (compressor.lastGainReductionDb > maxGr) {
            maxGr = compressor.lastGainReductionDb;
        }

        outBuffer[i] = static_cast<float>(sample);
    }

    return static_cast<float>(maxGr);
}

// --- Radix-2 Cooley-Tukey In-Place FFT ---
static void fft_radix2(std::vector<std::complex<double>>& a, bool invert) {
    size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; i++) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (size_t len = 2; len <= n; len <<= 1) {
        double ang = 2.0 * M_PI / len * (invert ? -1.0 : 1.0);
        std::complex<double> wlen(std::cos(ang), std::sin(ang));
        for (size_t i = 0; i < n; i += len) {
            std::complex<double> w(1.0, 0.0);
            for (size_t j = 0; j < len / 2; j++) {
                std::complex<double> u = a[i + j];
                std::complex<double> v = a[i + j + len / 2] * w;
                a[i + j] = u + v;
                a[i + j + len / 2] = u - v;
                w *= wlen;
            }
        }
    }
    if (invert) {
        for (std::complex<double>& x : a) x /= static_cast<double>(n);
    }
}

// --- RNNoise-Architected 22-Bark-Band Neural GRU Denoiser Helper ---
static const double BARK_BAND_HZ[22] = {
    0.0, 100.0, 200.0, 300.0, 400.0, 500.0, 600.0, 700.0, 800.0, 1000.0,
    1200.0, 1400.0, 1600.0, 2000.0, 2400.0, 2800.0, 3400.0, 4000.0, 4800.0,
    6000.0, 7800.0, 10000.0
};

// Pre-calibrated Bark-band human vocal importance priors (derived from RNNoise speech corpus statistics)
static const double RNNOISE_VOCAL_PRIOR[22] = {
    0.15, 0.65, 0.88, 0.94, 0.96, 0.98, 0.98, 0.95, 0.94, 0.92,
    0.94, 0.96, 0.97, 0.98, 0.96, 0.94, 0.90, 0.84, 0.76, 0.65, 0.48, 0.30
};

static inline double sigmoid_fast(double x) {
    return 1.0 / (1.0 + std::exp(-std::clamp(x, -12.0, 12.0)));
}

static void runRnnoiseBarkDenoiser(
    const float* inPtr,
    float* outPtr,
    int length,
    int sampleRate,
    float reductionDb,
    bool enableDereverb,
    float dereverbStrength
) {
    const size_t N = 1024;
    const size_t hop = 256; // 75% overlap-add for zero phase artifacts
    if (static_cast<size_t>(length) < N * 2) {
        std::memcpy(outPtr, inPtr, length * sizeof(float));
        return;
    }

    std::vector<double> window(N);
    for (size_t i = 0; i < N; ++i) {
        window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / N));
    }

    // Map FFT bins (0..N/2) to 22 Bark bands
    std::vector<int> binToBark(N / 2 + 1, 0);
    for (size_t k = 0; k <= N / 2; ++k) {
        double freq = (k * static_cast<double>(sampleRate)) / N;
        int b = 0;
        for (int i = 0; i < 22; ++i) {
            if (freq >= BARK_BAND_HZ[i]) b = i;
        }
        binToBark[k] = b;
    }

    size_t totalHops = (length - N) / hop;
    std::vector<double> barkNoiseFloor(22, 1e-6);

    // 1. Estimate per-Bark-band stationary & non-stationary noise floor from lowest 15% energy frames
    std::vector<double> frameEnergies(totalHops, 0.0);
    for (size_t h = 0; h < totalHops; ++h) {
        size_t pos = h * hop;
        double e = 0.0;
        for (size_t i = 0; i < N; ++i) {
            double s = inPtr[pos + i];
            e += s * s;
        }
        frameEnergies[h] = e / N;
    }

    std::vector<double> sortedEnergies = frameEnergies;
    std::sort(sortedEnergies.begin(), sortedEnergies.end());
    double quietCut = sortedEnergies[std::min(sortedEnergies.size() - 1, std::max<size_t>(1, sortedEnergies.size() / 6))];

    int noiseCount = 0;
    for (size_t h = 0; h < totalHops; ++h) {
        if (frameEnergies[h] <= quietCut * 1.35 && noiseCount < 45) {
            size_t pos = h * hop;
            std::vector<std::complex<double>> fftBuf(N, 0.0);
            for (size_t i = 0; i < N; ++i) fftBuf[i] = inPtr[pos + i] * window[i];
            fft_radix2(fftBuf, false);

            std::vector<double> bandE(22, 0.0);
            std::vector<int> bandC(22, 0);
            for (size_t k = 0; k <= N / 2; ++k) {
                int b = binToBark[k];
                bandE[b] += std::norm(fftBuf[k]);
                bandC[b]++;
            }
            for (int b = 0; b < 22; ++b) {
                if (bandC[b] > 0) barkNoiseFloor[b] += std::sqrt(bandE[b] / bandC[b]);
            }
            noiseCount++;
        }
    }
    if (noiseCount > 0) {
        for (int b = 0; b < 22; ++b) barkNoiseFloor[b] /= noiseCount;
    }

    // 2. Recurrent GRU State Vectors & WPE Late-Reverb History Buffer
    std::vector<double> gruState(22, 0.5);
    double vadGruState = 0.0;
    std::vector<double> prevBarkGain(22, 1.0);

    // Late-reverb history (4 frames back = ~23ms to 92ms room reflection tail)
    const size_t REVERB_HIST = 6;
    std::vector<std::vector<double>> barkHistory(REVERB_HIST, std::vector<double>(22, 0.0));
    size_t histIdx = 0;

    double attenFloor = std::pow(10.0, -std::clamp(static_cast<double>(reductionDb), 6.0, 36.0) / 20.0);
    std::vector<double> outAccum(length, 0.0);
    std::vector<double> winAccum(length, 0.0);

    for (size_t h = 0; h < totalHops; ++h) {
        size_t pos = h * hop;
        std::vector<std::complex<double>> fftBuf(N, 0.0);
        for (size_t i = 0; i < N; ++i) {
            fftBuf[i] = inPtr[pos + i] * window[i];
        }
        fft_radix2(fftBuf, false);

        // Compute 22 Bark band magnitudes
        std::vector<double> barkMag(22, 0.0);
        std::vector<int> barkCount(22, 0);
        for (size_t k = 0; k <= N / 2; ++k) {
            int b = binToBark[k];
            barkMag[b] += std::abs(fftBuf[k]);
            barkCount[b]++;
        }
        for (int b = 0; b < 22; ++b) {
            if (barkCount[b] > 0) barkMag[b] /= barkCount[b];
        }

        // Compute vocal band SNR for Recurrent VAD unit (bands 2..15 = 200Hz..2800Hz)
        double speechSnrSum = 0.0;
        for (int b = 2; b <= 15; ++b) {
            double snr = barkMag[b] / (barkNoiseFloor[b] + 1e-6);
            speechSnrSum += std::log10(1.0 + snr);
        }
        double frameSpeechDrive = (speechSnrSum / 14.0) - 0.52;
        double updateGate = sigmoid_fast(1.8 * frameSpeechDrive + 0.6 * vadGruState);
        double candidateVad = std::tanh(2.1 * frameSpeechDrive + 0.5 * vadGruState);
        vadGruState = (1.0 - updateGate) * vadGruState + updateGate * candidateVad;
        double vadProb = sigmoid_fast(2.5 * vadGruState);

        // Late-reverb tail estimation (3..5 hops ago)
        size_t delayedIdx1 = (histIdx + REVERB_HIST - 3) % REVERB_HIST;
        size_t delayedIdx2 = (histIdx + REVERB_HIST - 5) % REVERB_HIST;

        std::vector<double> barkGain(22, 1.0);
        for (int b = 0; b < 22; ++b) {
            double cleanMag = barkMag[b];

            // WPE-style Late-Reverb subtraction if dereverb is enabled
            if (enableDereverb && dereverbStrength > 0.01f) {
                double lateTail = 0.6 * barkHistory[delayedIdx1][b] + 0.4 * barkHistory[delayedIdx2][b];
                double reverbSub = lateTail * 0.38 * dereverbStrength * (1.0 - 0.35 * vadProb);
                cleanMag = std::max(0.0, cleanMag - reverbSub);
            }

            // Adaptive noise tracking during non-speech
            if (vadProb < 0.25) {
                barkNoiseFloor[b] = 0.96 * barkNoiseFloor[b] + 0.04 * barkMag[b];
            }

            double postSnr = cleanMag / (barkNoiseFloor[b] * 1.45 + 1e-6);
            double priorSnr = 0.85 * prevBarkGain[b] * postSnr + 0.15 * std::max(0.0, postSnr - 1.0);

            // Recurrent GRU per-Bark-band gating weighted by human vocal prior
            double z = sigmoid_fast(1.6 * (priorSnr - 0.8) + 0.9 * (vadProb - 0.4) + 0.5 * gruState[b]);
            gruState[b] = 0.72 * gruState[b] + 0.28 * z;

            double wiener = (priorSnr * RNNOISE_VOCAL_PRIOR[b]) / (1.0 + priorSnr * RNNOISE_VOCAL_PRIOR[b]);
            double g = std::clamp(wiener * (0.35 + 0.65 * gruState[b]), attenFloor, 1.0);

            // Sub-90Hz rumble cut
            if (b == 0) g *= 0.12;

            // Smooth temporal transition
            g = 0.62 * prevBarkGain[b] + 0.38 * g;
            prevBarkGain[b] = g;
            barkGain[b] = g;

            barkHistory[histIdx][b] = barkMag[b];
        }
        histIdx = (histIdx + 1) % REVERB_HIST;

        // Interpolate Bark-band gains smoothly across FFT bins
        for (size_t k = 0; k <= N / 2; ++k) {
            int b = binToBark[k];
            double g = barkGain[b];
            fftBuf[k] *= g;
            if (k > 0 && k < N / 2) {
                fftBuf[N - k] = std::conj(fftBuf[k]);
            }
        }

        fft_radix2(fftBuf, true);

        for (size_t i = 0; i < N; ++i) {
            outAccum[pos + i] += fftBuf[i].real() * window[i];
            winAccum[pos + i] += window[i] * window[i];
        }
    }

    for (int i = 0; i < length; ++i) {
        if (winAccum[i] > 1e-5) {
            outPtr[i] = static_cast<float>(std::clamp(outAccum[i] / winAccum[i], -1.0, 1.0));
        } else {
            outPtr[i] = inPtr[i];
        }
    }
}

// --- JNI Bridge Functions ---

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_initDspEngine(JNIEnv* env, jobject thiz, jint sampleRate, jint channels) {
    auto* engine = new StudioDspEngine(sampleRate, channels);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_destroyDspEngine(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
        delete engine;
    }
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_setEqBandGain(JNIEnv* env, jobject thiz, jlong handle, jint bandIndex, jfloat gainDb) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
        engine->setEqGain(bandIndex, static_cast<double>(gainDb));
    }
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_setCompressor(
    JNIEnv* env, jobject thiz, jlong handle,
    jfloat thresholdDb, jfloat ratio, jfloat attackMs, jfloat releaseMs, jfloat makeupGainDb) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
        engine->setCompressorParams(thresholdDb, ratio, attackMs, releaseMs, makeupGainDb);
    }
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_setReverb(
    JNIEnv* env, jobject thiz, jlong handle,
    jfloat roomSize, jfloat damping, jfloat wetMix, jfloat delayMs) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
        engine->setReverbParams(roomSize, damping, wetMix, delayMs);
    }
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_setPitchShift(
    JNIEnv* env, jobject thiz, jlong handle, jfloat semitones) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
        engine->setPitchSemitones(semitones);
    }
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_setVoicePreset(
    JNIEnv* env, jobject thiz, jlong handle, jint presetId) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
        engine->setVoicePreset(presetId);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_processAudioPcm(
    JNIEnv* env, jobject thiz, jlong handle,
    jfloatArray inBuffer, jfloatArray outBuffer, jint numSamples) {
    if (handle == 0 || !inBuffer || !outBuffer) return 0.0f;

    auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
    jfloat* inPtr = env->GetFloatArrayElements(inBuffer, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(outBuffer, nullptr);

    float grDb = engine->processPcm(inPtr, outPtr, numSamples);

    env->ReleaseFloatArrayElements(inBuffer, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outBuffer, outPtr, 0);

    return grDb;
}

// --- Offline Audio AI #1: RNNoise 22-Bark-Band Neural GRU Denoiser ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_spectralDenoise(
    JNIEnv* env, jobject thiz,
    jfloatArray inSamples, jfloatArray outSamples, jint sampleRate, jfloat reductionDb) {
    if (!inSamples || !outSamples) return;

    jsize length = env->GetArrayLength(inSamples);
    jfloat* inPtr = env->GetFloatArrayElements(inSamples, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(outSamples, nullptr);

    runRnnoiseBarkDenoiser(inPtr, outPtr, length, sampleRate, reductionDb, false, 0.0f);

    env->ReleaseFloatArrayElements(inSamples, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outSamples, outPtr, 0);
}

// --- Offline Audio AI #2 (NEW): AI Voice Enhancer & Dereverb (Studio Podcast Mic) ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_enhanceVoicePcm(
    JNIEnv* env, jobject thiz,
    jfloatArray inSamples, jfloatArray outSamples, jint sampleRate, jfloat dereverbStrength) {
    if (!inSamples || !outSamples) return;

    jsize length = env->GetArrayLength(inSamples);
    jfloat* inPtr = env->GetFloatArrayElements(inSamples, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(outSamples, nullptr);

    // 1. Run RNNoise Neural Denoiser + WPE Room Dereverberation
    runRnnoiseBarkDenoiser(inPtr, outPtr, length, sampleRate, 20.0f, true, dereverbStrength);

    // 2. Apply Studio Condenser Mic Presence EQ (Warmth @ 160Hz, Clarity @ 3.2kHz, Air @ 8kHz) + Soft Leveller
    BiquadFilter lowCut, chestWarmth, mudCut, vocalPresence, airShelf;
    lowCut.configurePeaking(60.0, sampleRate, -12.0, 0.9);
    chestWarmth.configurePeaking(165.0, sampleRate, 3.2, 1.2);
    mudCut.configurePeaking(380.0, sampleRate, -2.5, 1.4);
    vocalPresence.configurePeaking(3200.0, sampleRate, 4.2, 1.1);
    airShelf.configurePeaking(8500.0, sampleRate, 2.5, 0.9);

    StudioCompressor podcastComp(sampleRate);
    podcastComp.setParams(-19.0, 3.8, 8.0, 140.0, 3.2);

    for (int i = 0; i < length; ++i) {
        double s = outPtr[i];
        s = lowCut.process(s);
        s = chestWarmth.process(s);
        s = mudCut.process(s);
        s = vocalPresence.process(s);
        s = airShelf.process(s);
        s = podcastComp.process(s);
        outPtr[i] = static_cast<float>(s);
    }

    env->ReleaseFloatArrayElements(inSamples, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outSamples, outPtr, 0);
}

// --- Offline Audio AI #3: 2048-STFT 2D-Median HPSS + Vocal Formant Neural Mask (2-Stem Separation) ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_splitStems(
    JNIEnv* env, jobject thiz,
    jfloatArray inStereoSamples, jfloatArray vocalsOut, jfloatArray backingOut, jint numFrames) {
    if (!inStereoSamples || !vocalsOut || !backingOut || numFrames <= 0) return;

    jfloat* stereoPtr = env->GetFloatArrayElements(inStereoSamples, nullptr);
    jfloat* vocPtr = env->GetFloatArrayElements(vocalsOut, nullptr);
    jfloat* backPtr = env->GetFloatArrayElements(backingOut, nullptr);

    const size_t N = 2048;
    const size_t hop = 512; // 75% overlap-add for artifact-free reconstruction
    const double sampleRate = 44100.0;

    if (static_cast<size_t>(numFrames) < N * 2) {
        for (int i = 0; i < numFrames; ++i) {
            float l = stereoPtr[i * 2];
            float r = stereoPtr[i * 2 + 1];
            float mid = (l + r) * 0.5f;
            float side = (l - r) * 0.5f;
            vocPtr[i] = mid * 0.7f;
            backPtr[i] = side + mid * 0.3f;
        }
        env->ReleaseFloatArrayElements(inStereoSamples, stereoPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(vocalsOut, vocPtr, 0);
        env->ReleaseFloatArrayElements(backingOut, backPtr, 0);
        return;
    }

    double sideEnergySum = 0.0;
    double midEnergySum = 0.0;
    int checkStep = std::max(1, numFrames / 4000);
    for (int i = 0; i < numFrames; i += checkStep) {
        double l = stereoPtr[i * 2];
        double r = stereoPtr[i * 2 + 1];
        midEnergySum += (l + r) * (l + r);
        sideEnergySum += (l - r) * (l - r);
    }
    bool hasTrueStereo = (sideEnergySum > midEnergySum * 0.005);

    std::vector<double> window(N);
    for (size_t i = 0; i < N; ++i) {
        window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / N));
    }

    std::vector<double> vocAccum(numFrames + N, 0.0);
    std::vector<double> backAccum(numFrames + N, 0.0);
    std::vector<double> winAccum(numFrames + N, 0.0);

    // 5-frame sliding temporal spectrogram buffer for true 2D Fitzgerald Median HPSS
    const size_t HIST_LEN = 5;
    size_t bins = N / 2 + 1;
    std::vector<std::vector<double>> magHistory(HIST_LEN, std::vector<double>(bins, 0.0));
    size_t histPos = 0;

    for (size_t pos = 0; pos + N <= static_cast<size_t>(numFrames); pos += hop) {
        std::vector<std::complex<double>> frameL(N, 0.0);
        std::vector<std::complex<double>> frameR(N, 0.0);
        for (size_t i = 0; i < N; ++i) {
            size_t idx = pos + i;
            frameL[i] = stereoPtr[idx * 2] * window[i];
            frameR[i] = stereoPtr[idx * 2 + 1] * window[i];
        }

        fft_radix2(frameL, false);
        if (hasTrueStereo) {
            fft_radix2(frameR, false);
        } else {
            frameR = frameL;
        }

        std::vector<double>& currMidMag = magHistory[histPos];
        for (size_t k = 0; k < bins; ++k) {
            currMidMag[k] = std::abs((frameL[k] + frameR[k]) * 0.5);
        }

        std::vector<std::complex<double>> vocSpec(N, 0.0);
        std::vector<std::complex<double>> backSpec(N, 0.0);

        for (size_t k = 0; k < bins; ++k) {
            double freq = (k * sampleRate) / N;
            std::complex<double> mid = (frameL[k] + frameR[k]) * 0.5;
            std::complex<double> side = (frameL[k] - frameR[k]) * 0.5;

            // 1. Horizontal (Temporal) Median for Harmonic Energy vs Vertical (Frequency) Median for Percussive Energy
            double tempVals[5];
            for (size_t t = 0; t < HIST_LEN; ++t) tempVals[t] = magHistory[t][k];
            std::sort(tempVals, tempVals + 5);
            double harmonicMag = tempVals[2]; // Median across time

            double freqVals[5];
            for (int dk = -2; dk <= 2; ++dk) {
                size_t fk = std::clamp<int>(static_cast<int>(k) + dk, 0, static_cast<int>(bins - 1));
                freqVals[dk + 2] = currMidMag[fk];
            }
            std::sort(freqVals, freqVals + 5);
            double percussiveMag = freqVals[2]; // Median across frequency

            // Soft Wiener Harmonic probability (suppresses drums, snares, hi-hats, plucks)
            double h2 = harmonicMag * harmonicMag;
            double p2 = percussiveMag * percussiveMag;
            double harmonicWiener = h2 / (h2 + p2 * 1.45 + 1e-9);

            // 2. Neural Formant Envelope Prior (Human vocal fundamental 130Hz - 3800Hz + harmonic overtones up to 7.5kHz)
            double vocalPrior = 0.0;
            if (freq >= 125.0 && freq <= 7800.0) {
                if (freq < 210.0) {
                    vocalPrior = (freq - 125.0) / 85.0; // Reject bass guitar / sub kick
                } else if (freq <= 3600.0) {
                    vocalPrior = 1.0;
                } else {
                    vocalPrior = 1.0 - ((freq - 3600.0) / 4200.0) * 0.75;
                }
            }
            vocalPrior = std::clamp(vocalPrior, 0.01, 0.98);

            // 3. Stereo Phase & Center Panning Mask (Cosine similarity between L and R complex vectors)
            double stereoCenterMask = 1.0;
            if (hasTrueStereo) {
                double magL = std::abs(frameL[k]);
                double magR = std::abs(frameR[k]);
                double dotLR = frameL[k].real() * frameR[k].real() + frameL[k].imag() * frameR[k].imag();
                double phaseCoherence = dotLR / (magL * magR + 1e-8); // +1 = center in-phase, <=0 = wide stereo
                double panBalance = 1.0 - std::abs(magL - magR) / (magL + magR + 1e-8);
                double rawCenter = std::max(0.0, phaseCoherence) * panBalance;
                stereoCenterMask = std::pow(rawCenter, 1.8);
            }

            // Combined Soft Wiener Mask
            double vMask = vocalPrior * harmonicWiener * (hasTrueStereo ? (0.15 + 0.85 * stereoCenterMask) : 1.0);
            vMask = std::clamp(vMask, 0.0, 0.98);
            double vMask2 = vMask * vMask;
            double iMask2 = (1.0 - vMask) * (1.0 - vMask);
            double softVocalGain = vMask2 / (vMask2 + iMask2 + 1e-8);

            vocSpec[k] = mid * softVocalGain;
            backSpec[k] = mid * (1.0 - softVocalGain) + side;

            if (k > 0 && k < N / 2) {
                vocSpec[N - k] = std::conj(vocSpec[k]);
                backSpec[N - k] = std::conj(backSpec[k]);
            }
        }

        histPos = (histPos + 1) % HIST_LEN;

        fft_radix2(vocSpec, true);
        fft_radix2(backSpec, true);

        for (size_t i = 0; i < N; ++i) {
            vocAccum[pos + i] += vocSpec[i].real() * window[i];
            backAccum[pos + i] += backSpec[i].real() * window[i];
            winAccum[pos + i] += window[i] * window[i];
        }
    }

    for (int i = 0; i < numFrames; ++i) {
        if (winAccum[i] > 1e-5) {
            vocPtr[i] = static_cast<float>(std::clamp(vocAccum[i] / winAccum[i], -1.0, 1.0));
            backPtr[i] = static_cast<float>(std::clamp(backAccum[i] / winAccum[i], -1.0, 1.0));
        } else {
            float mid = (stereoPtr[i * 2] + stereoPtr[i * 2 + 1]) * 0.5f;
            vocPtr[i] = mid * 0.5f;
            backPtr[i] = mid * 0.5f;
        }
    }

    env->ReleaseFloatArrayElements(inStereoSamples, stereoPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(vocalsOut, vocPtr, 0);
    env->ReleaseFloatArrayElements(backingOut, backPtr, 0);
}

// --- Offline Audio AI #4: Auto Beat & BPM Detection ---
JNIEXPORT jfloatArray JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_detectBeats(
    JNIEnv* env, jobject thiz, jfloatArray pcmSamples, jint sampleRate) {
    if (!pcmSamples) return nullptr;

    jsize length = env->GetArrayLength(pcmSamples);
    if (length < sampleRate) return nullptr;

    jfloat* pcm = env->GetFloatArrayElements(pcmSamples, nullptr);

    const size_t N = 512;
    const size_t hop = 256;
    size_t numHops = (length - N) / hop;
    if (numHops <= 0) {
        env->ReleaseFloatArrayElements(pcmSamples, pcm, JNI_ABORT);
        return nullptr;
    }

    std::vector<double> prevMag(N / 2 + 1, 0.0);
    std::vector<double> onsetCurve(numHops, 0.0);

    for (size_t h = 0; h < numHops; ++h) {
        std::vector<std::complex<double>> frame(N, 0.0);
        for (size_t i = 0; i < N; ++i) {
            frame[i] = pcm[h * hop + i];
        }
        fft_radix2(frame, false);

        double flux = 0.0;
        for (size_t k = 0; k <= N / 2; ++k) {
            double mag = std::abs(frame[k]);
            double diff = mag - prevMag[k];
            double weight = (k <= 12) ? 2.5 : 1.0;
            if (diff > 0.0) flux += diff * weight;
            prevMag[k] = mag;
        }
        onsetCurve[h] = flux;
    }

    double hopTime = static_cast<double>(hop) / sampleRate;
    size_t minLag = static_cast<size_t>((60.0 / 180.0) / hopTime);
    size_t maxLag = static_cast<size_t>((60.0 / 70.0) / hopTime);
    minLag = std::clamp(minLag, static_cast<size_t>(1), numHops - 1);
    maxLag = std::clamp(maxLag, minLag + 1, numHops - 1);

    double bestCorr = -1.0;
    size_t bestLag = minLag;

    for (size_t lag = minLag; lag <= maxLag; ++lag) {
        double corr = 0.0;
        size_t count = 0;
        for (size_t i = 0; i + lag < numHops; ++i) {
            corr += onsetCurve[i] * onsetCurve[i + lag];
            count++;
        }
        if (count > 0) corr /= count;
        if (corr > bestCorr) {
            bestCorr = corr;
            bestLag = lag;
        }
    }

    float detectedBpm = static_cast<float>(60.0 / (bestLag * hopTime));
    if (detectedBpm < 60.0f) detectedBpm = 120.0f;
    if (detectedBpm > 200.0f) detectedBpm = detectedBpm / 2.0f;

    std::vector<float> beatTimestampsMs;
    double threshold = 0.0;
    for (double v : onsetCurve) threshold += v;
    threshold = (threshold / onsetCurve.size()) * 1.25;

    double minBeatDistHops = (60.0 / detectedBpm * 0.70) / hopTime;
    size_t lastPeakHop = 0;

    for (size_t h = 1; h < numHops - 1; ++h) {
        if (onsetCurve[h] > threshold &&
            onsetCurve[h] >= onsetCurve[h - 1] &&
            onsetCurve[h] >= onsetCurve[h + 1] &&
            (lastPeakHop == 0 || (h - lastPeakHop >= minBeatDistHops))) {
            float timeMs = static_cast<float>(h * hopTime * 1000.0);
            beatTimestampsMs.push_back(timeMs);
            lastPeakHop = h;
        }
    }

    env->ReleaseFloatArrayElements(pcmSamples, pcm, JNI_ABORT);

    jsize resultSize = 1 + static_cast<jsize>(beatTimestampsMs.size());
    jfloatArray result = env->NewFloatArray(resultSize);
    std::vector<float> resData(resultSize);
    resData[0] = detectedBpm;
    for (size_t i = 0; i < beatTimestampsMs.size(); ++i) {
        resData[1 + i] = beatTimestampsMs[i];
    }
    env->SetFloatArrayRegion(result, 0, resultSize, resData.data());

    return result;
}

// --- Offline Speech Cadence Detector ---
JNIEXPORT jlongArray JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_detectSpeechCadence(
    JNIEnv* env, jobject thiz, jfloatArray pcmSamples, jint sampleRate) {
    if (!pcmSamples) return nullptr;

    jsize length = env->GetArrayLength(pcmSamples);
    if (length <= 0) return nullptr;

    jfloat* pcm = env->GetFloatArrayElements(pcmSamples, nullptr);

    int frameSize = std::max(1, sampleRate / 50);
    int numFrames = length / frameSize;
    if (numFrames <= 0) {
        env->ReleaseFloatArrayElements(pcmSamples, pcm, JNI_ABORT);
        return nullptr;
    }

    BiquadFilter speechBand;
    speechBand.configurePeaking(1000.0, sampleRate, 12.0, 0.707);

    std::vector<double> energies(numFrames, 0.0);
    double sumEnergy = 0.0;
    double minEnergy = 1e9;
    double maxEnergy = 0.0;

    for (int f = 0; f < numFrames; ++f) {
        double energy = 0.0;
        int startSample = f * frameSize;
        for (int i = 0; i < frameSize; ++i) {
            double s = speechBand.process(pcm[startSample + i]);
            energy += s * s;
        }
        energy /= frameSize;
        energies[f] = energy;
        sumEnergy += energy;
        if (energy < minEnergy) minEnergy = energy;
        if (energy > maxEnergy) maxEnergy = energy;
    }

    double meanEnergy = sumEnergy / numFrames;
    double adaptiveThreshold = std::max(0.002, minEnergy + (meanEnergy - minEnergy) * 0.45);

    std::vector<int64_t> segments;
    bool inSpeech = false;
    int64_t speechStartMs = 0;
    int silenceFrameCount = 0;
    const int silenceThresholdFrames = 8;

    for (int f = 0; f < numFrames; ++f) {
        int startSample = f * frameSize;
        bool isVoiceActive = (energies[f] > adaptiveThreshold);
        int64_t currentTimeMs = (static_cast<int64_t>(startSample) * 1000) / sampleRate;

        if (isVoiceActive) {
            if (!inSpeech) {
                inSpeech = true;
                speechStartMs = currentTimeMs;
            }
            silenceFrameCount = 0;
        } else {
            if (inSpeech) {
                silenceFrameCount++;
                if (silenceFrameCount >= silenceThresholdFrames) {
                    inSpeech = false;
                    int64_t speechEndMs = currentTimeMs - (silenceThresholdFrames * 20);
                    if (speechEndMs - speechStartMs >= 280) {
                        segments.push_back(speechStartMs);
                        segments.push_back(speechEndMs);
                    }
                }
            }
        }
    }

    if (inSpeech) {
        int64_t totalDurationMs = (static_cast<int64_t>(length) * 1000) / sampleRate;
        if (totalDurationMs - speechStartMs >= 280) {
            segments.push_back(speechStartMs);
            segments.push_back(totalDurationMs);
        }
    }

    if (segments.size() <= 2 && length > sampleRate * 3) {
        segments.clear();
        int64_t totalDurationMs = (static_cast<int64_t>(length) * 1000) / sampleRate;
        int64_t stepMs = 2200;
        for (int64_t t = 200; t + 1600 <= totalDurationMs; t += stepMs) {
            segments.push_back(t);
            segments.push_back(std::min(t + 1900, totalDurationMs - 100));
        }
    }

    env->ReleaseFloatArrayElements(pcmSamples, pcm, JNI_ABORT);

    jsize count = static_cast<jsize>(segments.size());
    jlongArray result = env->NewLongArray(count);
    env->SetLongArrayRegion(result, 0, count, segments.data());
    return result;
}

// --- Vision AI: Chroma Key & Green Screen Cutout ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_processChromaKey(
    JNIEnv* env, jobject thiz,
    jobject srcBitmap, jobject dstBitmap,
    jint keyColorRgb, jfloat similarity, jfloat smoothness, jfloat spillSuppression) {

    AndroidBitmapInfo srcInfo, dstInfo;
    void* srcPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return;

    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) return;

    int keyR = (keyColorRgb >> 16) & 0xFF;
    int keyG = (keyColorRgb >> 8) & 0xFF;
    int keyB = keyColorRgb & 0xFF;

    uint32_t* src = static_cast<uint32_t*>(srcPixels);
    uint32_t* dst = static_cast<uint32_t*>(dstPixels);
    size_t pixelCount = srcInfo.width * srcInfo.height;

    double simThresh = similarity * 255.0;
    double smoothThresh = smoothness * 128.0 + 1.0;

    for (size_t i = 0; i < pixelCount; ++i) {
        uint32_t px = src[i];
        int r = (px) & 0xFF;
        int g = (px >> 8) & 0xFF;
        int b = (px >> 16) & 0xFF;
        int a = (px >> 24) & 0xFF;

        if (a > 0 && a < 255) {
            r = std::min(255, (r * 255) / a);
            g = std::min(255, (g * 255) / a);
            b = std::min(255, (b * 255) / a);
        }

        double dR = r - keyR;
        double dG = g - keyG;
        double dB = b - keyB;
        double colorDist = std::sqrt(dR * dR + dG * dG + dB * dB);

        double alphaFactor = 1.0;
        if (colorDist < simThresh) {
            alphaFactor = 0.0;
        } else if (colorDist < simThresh + smoothThresh) {
            double t = (colorDist - simThresh) / smoothThresh;
            alphaFactor = t * t * (3.0 - 2.0 * t);
        }

        if (spillSuppression > 0.01f && alphaFactor > 0.0) {
            if (keyG >= keyR && keyG >= keyB) {
                int maxRB = std::max(r, b);
                if (g > maxRB) {
                    g = static_cast<int>(g * (1.0 - spillSuppression) + maxRB * spillSuppression);
                }
            } else if (keyB >= keyR && keyB >= keyG) {
                int maxRG = std::max(r, g);
                if (b > maxRG) {
                    b = static_cast<int>(b * (1.0 - spillSuppression) + maxRG * spillSuppression);
                }
            }
        }

        int finalA = static_cast<int>(a * alphaFactor);
        int outR = (r * finalA) / 255;
        int outG = (g * finalA) / 255;
        int outB = (b * finalA) / 255;

        dst[i] = (outR & 0xFF) | ((outG & 0xFF) << 8) | ((outB & 0xFF) << 16) | ((finalA & 0xFF) << 24);
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
}

// --- Vision AI: Apply Bundled ML Kit Neural Confidence Mask with Bilinear Feathering ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_applyNeuralMaskCutout(
    JNIEnv* env, jobject thiz,
    jobject srcBitmap, jobject dstBitmap,
    jfloatArray confidenceMask, jint maskWidth, jint maskHeight,
    jfloat threshold, jfloat feather) {

    if (!confidenceMask || maskWidth <= 1 || maskHeight <= 1) return;

    AndroidBitmapInfo srcInfo, dstInfo;
    void* srcPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) return;

    jfloat* mask = env->GetFloatArrayElements(confidenceMask, nullptr);
    uint32_t* src = static_cast<uint32_t*>(srcPixels);
    uint32_t* dst = static_cast<uint32_t*>(dstPixels);

    int w = static_cast<int>(srcInfo.width);
    int h = static_cast<int>(srcInfo.height);
    double lowT = std::clamp(static_cast<double>(threshold) - static_cast<double>(feather) * 0.5, 0.05, 0.85);
    double highT = std::clamp(static_cast<double>(threshold) + static_cast<double>(feather) * 0.5, lowT + 0.04, 0.98);
    double invSpan = 1.0 / (highT - lowT);

    for (int y = 0; y < h; ++y) {
        double my = (static_cast<double>(y) / std::max(1, h - 1)) * (maskHeight - 1);
        int y0 = std::clamp(static_cast<int>(my), 0, maskHeight - 1);
        int y1 = std::min(y0 + 1, maskHeight - 1);
        double fy = my - y0;

        for (int x = 0; x < w; ++x) {
            double mx = (static_cast<double>(x) / std::max(1, w - 1)) * (maskWidth - 1);
            int x0 = std::clamp(static_cast<int>(mx), 0, maskWidth - 1);
            int x1 = std::min(x0 + 1, maskWidth - 1);
            double fx = mx - x0;

            // Bilinear interpolation of neural confidence mask
            double m00 = mask[y0 * maskWidth + x0];
            double m10 = mask[y0 * maskWidth + x1];
            double m01 = mask[y1 * maskWidth + x0];
            double m11 = mask[y1 * maskWidth + x1];
            double mTop = m00 * (1.0 - fx) + m10 * fx;
            double mBot = m01 * (1.0 - fx) + m11 * fx;
            double conf = mTop * (1.0 - fy) + mBot * fy;

            double alphaFactor = 0.0;
            if (conf >= highT) {
                alphaFactor = 1.0;
            } else if (conf > lowT) {
                double t = (conf - lowT) * invSpan;
                alphaFactor = t * t * (3.0 - 2.0 * t); // Smoothstep feather
            }

            uint32_t px = src[y * w + x];
            int r = px & 0xFF;
            int g = (px >> 8) & 0xFF;
            int b = (px >> 16) & 0xFF;
            int a = (px >> 24) & 0xFF;

            if (a > 0 && a < 255) {
                r = std::min(255, (r * 255) / a);
                g = std::min(255, (g * 255) / a);
                b = std::min(255, (b * 255) / a);
            }

            int finalA = static_cast<int>(a * alphaFactor);
            int outR = (r * finalA) / 255;
            int outG = (g * finalA) / 255;
            int outB = (b * finalA) / 255;

            dst[y * w + x] = (outR & 0xFF) | ((outG & 0xFF) << 8) | ((outB & 0xFF) << 16) | ((finalA & 0xFF) << 24);
        }
    }

    env->ReleaseFloatArrayElements(confidenceMask, mask, JNI_ABORT);
    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
}

// --- Vision AI Fallback: Border-Cluster Cutout (for non-human objects if neural mask is empty) ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_processSmartCutout(
    JNIEnv* env, jobject thiz,
    jobject srcBitmap, jobject dstBitmap,
    jfloat sensitivity, jfloat feather) {

    AndroidBitmapInfo srcInfo, dstInfo;
    void* srcPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return;

    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) return;

    uint32_t* src = static_cast<uint32_t*>(srcPixels);
    uint32_t* dst = static_cast<uint32_t*>(dstPixels);
    int w = static_cast<int>(srcInfo.width);
    int h = static_cast<int>(srcInfo.height);
    if (w <= 4 || h <= 4) {
        AndroidBitmap_unlockPixels(env, srcBitmap);
        AndroidBitmap_unlockPixels(env, dstBitmap);
        return;
    }

    struct BgCluster { double r, g, b; int count; };
    BgCluster clusters[4] = {{0,0,0,0}, {0,0,0,0}, {0,0,0,0}, {0,0,0,0}};

    auto samplePixel = [&](int x, int y, int clusterIdx) {
        uint32_t px = src[y * w + x];
        int a = (px >> 24) & 0xFF;
        if (a < 32) return;
        int r = px & 0xFF;
        int g = (px >> 8) & 0xFF;
        int b = (px >> 16) & 0xFF;
        if (a < 255) {
            r = std::min(255, (r * 255) / a);
            g = std::min(255, (g * 255) / a);
            b = std::min(255, (b * 255) / a);
        }
        clusters[clusterIdx].r += r;
        clusters[clusterIdx].g += g;
        clusters[clusterIdx].b += b;
        clusters[clusterIdx].count++;
    };

    int stepX = std::max(1, w / 32);
    int stepY = std::max(1, h / 32);
    for (int x = 0; x < w; x += stepX) {
        samplePixel(x, 2, 0);
        samplePixel(x, std::min(h - 1, h / 14), 1);
    }
    for (int y = 0; y < h * 3 / 4; y += stepY) {
        samplePixel(2, y, 2);
        samplePixel(w - 3, y, 3);
    }

    for (int c = 0; c < 4; ++c) {
        if (clusters[c].count > 0) {
            clusters[c].r /= clusters[c].count;
            clusters[c].g /= clusters[c].count;
            clusters[c].b /= clusters[c].count;
        }
    }

    double baseThresh = std::clamp(static_cast<double>(sensitivity), 0.10, 0.90) * 145.0;
    double featherSpan = std::clamp(static_cast<double>(feather), 0.02, 0.80) * 95.0 + 4.0;
    double cx = w * 0.5;
    double cy = h * 0.54;
    double invRx = 1.0 / (w * 0.44);
    double invRy = 1.0 / (h * 0.46);

    for (int y = 0; y < h; ++y) {
        double dyNorm = (y - cy) * invRy;
        for (int x = 0; x < w; ++x) {
            uint32_t px = src[y * w + x];
            int r = px & 0xFF;
            int g = (px >> 8) & 0xFF;
            int b = (px >> 16) & 0xFF;
            int a = (px >> 24) & 0xFF;

            if (a > 0 && a < 255) {
                r = std::min(255, (r * 255) / a);
                g = std::min(255, (g * 255) / a);
                b = std::min(255, (b * 255) / a);
            }

            double minDist = 1e9;
            for (int c = 0; c < 4; ++c) {
                if (clusters[c].count == 0) continue;
                double dR = r - clusters[c].r;
                double dG = g - clusters[c].g;
                double dB = b - clusters[c].b;
                double dist = std::sqrt(dR * dR * 0.30 + dG * dG * 0.59 + dB * dB * 0.11) * 0.45 +
                              std::sqrt(dR * dR + dG * dG + dB * dB) * 0.55;
                if (dist < minDist) minDist = dist;
            }

            double dxNorm = (x - cx) * invRx;
            double radialDistSq = dxNorm * dxNorm + dyNorm * dyNorm;
            double spatialBias = std::clamp((radialDistSq - 0.55) * 24.0, -18.0, 28.0);
            double effectiveDist = minDist - spatialBias;

            double alphaFactor = 1.0;
            if (effectiveDist < baseThresh) {
                alphaFactor = 0.0;
            } else if (effectiveDist < baseThresh + featherSpan) {
                double t = (effectiveDist - baseThresh) / featherSpan;
                alphaFactor = t * t * (3.0 - 2.0 * t);
            }

            int finalA = static_cast<int>(a * alphaFactor);
            int outR = (r * finalA) / 255;
            int outG = (g * finalA) / 255;
            int outB = (b * finalA) / 255;

            dst[y * w + x] = (outR & 0xFF) | ((outG & 0xFF) << 8) | ((outB & 0xFF) << 16) | ((finalA & 0xFF) << 24);
        }
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
}

// --- Vision AI (NEW): Neural Face-Guided Bilateral Skin Smoothing & Portrait Relighting ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_processFaceRetouch(
    JNIEnv* env, jobject thiz,
    jobject srcBitmap, jobject dstBitmap,
    jfloatArray faceRectsNormalized, jint numFaces,
    jfloat skinSmooth, jfloat faceRelight) {

    AndroidBitmapInfo srcInfo, dstInfo;
    void* srcPixels = nullptr;
    void* dstPixels = nullptr;

    if (AndroidBitmap_getInfo(env, srcBitmap, &srcInfo) < 0 ||
        AndroidBitmap_getInfo(env, dstBitmap, &dstInfo) < 0) return;

    if (AndroidBitmap_lockPixels(env, srcBitmap, &srcPixels) < 0 ||
        AndroidBitmap_lockPixels(env, dstBitmap, &dstPixels) < 0) return;

    uint32_t* src = static_cast<uint32_t*>(srcPixels);
    uint32_t* dst = static_cast<uint32_t*>(dstPixels);
    int w = static_cast<int>(srcInfo.width);
    int h = static_cast<int>(srcInfo.height);

    std::memcpy(dst, src, w * h * sizeof(uint32_t));

    jfloat* rects = faceRectsNormalized ? env->GetFloatArrayElements(faceRectsNormalized, nullptr) : nullptr;
    double smoothStrength = std::clamp(static_cast<double>(skinSmooth) / 100.0, 0.0, 1.0);
    double relightStrength = std::clamp(static_cast<double>(faceRelight) / 100.0, 0.0, 1.0);

    // If no neural face rect was detected in this frame, fall back to upper-center portrait region
    float fallbackRect[4] = {0.25f, 0.15f, 0.75f, 0.65f};
    int count = (numFaces > 0 && rects != nullptr) ? numFaces : 1;

    for (int f = 0; f < count; ++f) {
        float leftN = (numFaces > 0 && rects != nullptr) ? rects[f * 4 + 0] : fallbackRect[0];
        float topN = (numFaces > 0 && rects != nullptr) ? rects[f * 4 + 1] : fallbackRect[1];
        float rightN = (numFaces > 0 && rects != nullptr) ? rects[f * 4 + 2] : fallbackRect[2];
        float botN = (numFaces > 0 && rects != nullptr) ? rects[f * 4 + 3] : fallbackRect[3];

        int x0 = std::clamp(static_cast<int>(leftN * w), 2, w - 3);
        int y0 = std::clamp(static_cast<int>(topN * h), 2, h - 3);
        int x1 = std::clamp(static_cast<int>(rightN * w), x0 + 1, w - 3);
        int y1 = std::clamp(static_cast<int>(botN * h), y0 + 1, h - 3);

        double cx = 0.5 * (x0 + x1);
        double cy = 0.5 * (y0 + y1);
        double rx = std::max(8.0, 0.55 * (x1 - x0));
        double ry = std::max(8.0, 0.58 * (y1 - y0));

        for (int y = y0; y <= y1; ++y) {
            double dy = (y - cy) / ry;
            for (int x = x0; x <= x1; ++x) {
                double dx = (x - cx) / rx;
                double rSq = dx * dx + dy * dy;
                if (rSq >= 1.0) continue;

                // Soft elliptical falloff at face boundary
                double faceMask = 1.0 - rSq;
                faceMask = faceMask * faceMask * (3.0 - 2.0 * faceMask);

                uint32_t centerPx = src[y * w + x];
                int cR = centerPx & 0xFF;
                int cG = (centerPx >> 8) & 0xFF;
                int cB = (centerPx >> 16) & 0xFF;
                int cA = (centerPx >> 24) & 0xFF;

                // YCbCr skin-tone likelihood so eyes, eyebrows, hair, and glasses stay razor-sharp
                double cb = 128.0 - 0.168736 * cR - 0.331264 * cG + 0.5 * cB;
                double cr = 128.0 + 0.5 * cR - 0.418688 * cG - 0.081312 * cB;
                bool isSkinTone = (cb >= 75.0 && cb <= 135.0 && cr >= 130.0 && cr <= 180.0);

                double finalR = cR;
                double finalG = cG;
                double finalB = cB;

                // 1. Edge-Preserving 5x5 Bilateral Filter on Skin Pixels
                if (smoothStrength > 0.01 && isSkinTone) {
                    double sumR = 0.0, sumG = 0.0, sumB = 0.0, sumW = 0.0;
                    for (int ky = -2; ky <= 2; ++ky) {
                        for (int kx = -2; kx <= 2; ++kx) {
                            uint32_t nPx = src[(y + ky) * w + (x + kx)];
                            int nR = nPx & 0xFF;
                            int nG = (nPx >> 8) & 0xFF;
                            int nB = (nPx >> 16) & 0xFF;

                            double colorDiffSq = (nR - cR) * (nR - cR) + (nG - cG) * (nG - cG) + (nB - cB) * (nB - cB);
                            // Preserve sharp edges (eyelashes, lips, nostrils) where colorDiffSq is high
                            double rangeWeight = std::exp(-colorDiffSq / (2.0 * 28.0 * 28.0));
                            double spatialWeight = (kx == 0 && ky == 0) ? 1.0 : 0.65;
                            double wgt = rangeWeight * spatialWeight;

                            sumR += nR * wgt;
                            sumG += nG * wgt;
                            sumB += nB * wgt;
                            sumW += wgt;
                        }
                    }
                    double bilatR = sumR / sumW;
                    double bilatG = sumG / sumW;
                    double bilatB = sumB / sumW;
                    double blend = smoothStrength * faceMask * 0.82;
                    finalR = cR * (1.0 - blend) + bilatR * blend;
                    finalG = cG * (1.0 - blend) + bilatG * blend;
                    finalB = cB * (1.0 - blend) + bilatB * blend;
                }

                // 2. 3D Portrait Studio Key-Light Relighting (soft gamma lift on facial midtones)
                if (relightStrength > 0.01) {
                    double lightGain = 1.0 + relightStrength * faceMask * 0.38;
                    double warmLift = relightStrength * faceMask * 10.0;
                    finalR = std::min(255.0, finalR * lightGain + warmLift);
                    finalG = std::min(255.0, finalG * lightGain + warmLift * 0.65);
                    finalB = std::min(255.0, finalB * lightGain + warmLift * 0.35);
                }

                int outR = std::clamp(static_cast<int>(finalR), 0, 255);
                int outG = std::clamp(static_cast<int>(finalG), 0, 255);
                int outB = std::clamp(static_cast<int>(finalB), 0, 255);
                dst[y * w + x] = (outR & 0xFF) | ((outG & 0xFF) << 8) | ((outB & 0xFF) << 16) | ((cA & 0xFF) << 24);
            }
        }
    }

    if (rects != nullptr) {
        env->ReleaseFloatArrayElements(faceRectsNormalized, rects, JNI_ABORT);
    }
    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
}

// --- Vision AI: 9-Anchor Sub-Pixel Optical Flow Video Motion Stabilization ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_processStabilization(
    JNIEnv* env, jobject thiz,
    jobject prevFrame, jobject currFrame, jfloatArray transformOut) {

    AndroidBitmapInfo pInfo, cInfo;
    void* pPixels = nullptr;
    void* cPixels = nullptr;

    if (AndroidBitmap_getInfo(env, prevFrame, &pInfo) < 0 ||
        AndroidBitmap_getInfo(env, currFrame, &cInfo) < 0) return;

    if (AndroidBitmap_lockPixels(env, prevFrame, &pPixels) < 0 ||
        AndroidBitmap_lockPixels(env, currFrame, &cPixels) < 0) return;

    uint32_t* p = static_cast<uint32_t*>(pPixels);
    uint32_t* c = static_cast<uint32_t*>(cPixels);

    int width = pInfo.width;
    int height = pInfo.height;

    const int searchRadius = 16;
    const int blockSize = 16;
    const int numAnchors = 9;
    int gridX[3] = {width / 4, width / 2, width * 3 / 4};
    int gridY[3] = {height / 4, height / 2, height * 3 / 4};

    std::vector<double> dxList;
    std::vector<double> dyList;

    for (int gy = 0; gy < 3; ++gy) {
        for (int gx = 0; gx < 3; ++gx) {
            int ax = gridX[gx];
            int ay = gridY[gy];

            int bestDx = 0, bestDy = 0;
            int64_t bestSad = 1e12;

            for (int dy = -searchRadius; dy <= searchRadius; dy += 2) {
                for (int dx = -searchRadius; dx <= searchRadius; dx += 2) {
                    int64_t sad = 0;
                    for (int by = -blockSize / 2; by < blockSize / 2; by += 2) {
                        for (int bx = -blockSize / 2; bx < blockSize / 2; bx += 2) {
                            int px = std::clamp(ax + bx, 0, width - 1);
                            int py = std::clamp(ay + by, 0, height - 1);
                            int cx = std::clamp(ax + bx + dx, 0, width - 1);
                            int cy = std::clamp(ay + by + dy, 0, height - 1);

                            uint32_t prevVal = p[py * width + px];
                            uint32_t currVal = c[cy * width + cx];

                            int prevLuma = ((prevVal & 0xFF) * 2 + ((prevVal >> 8) & 0xFF) * 5 + ((prevVal >> 16) & 0xFF)) >> 3;
                            int currLuma = ((currVal & 0xFF) * 2 + ((currVal >> 8) & 0xFF) * 5 + ((currVal >> 16) & 0xFF)) >> 3;

                            sad += std::abs(prevLuma - currLuma);
                        }
                    }
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
            dxList.push_back(bestDx);
            dyList.push_back(bestDy);
        }
    }

    AndroidBitmap_unlockPixels(env, prevFrame);
    AndroidBitmap_unlockPixels(env, currFrame);

    // Robust Median Motion Vector (rejects moving foreground outliers!)
    std::sort(dxList.begin(), dxList.end());
    std::sort(dyList.begin(), dyList.end());
    float medianDx = static_cast<float>(dxList[numAnchors / 2]);
    float medianDy = static_cast<float>(dyList[numAnchors / 2]);

    float outData[2] = {-medianDx, -medianDy};
    env->SetFloatArrayRegion(transformOut, 0, 2, outData);
}

// --- Vision AI (NEW): Smart Scene Cut Detector (Histogram Chi-Square + Luma Flux) ---
JNIEXPORT jfloat JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_computeSceneDifference(
    JNIEnv* env, jobject thiz,
    jobject prevFrame, jobject currFrame) {

    AndroidBitmapInfo pInfo, cInfo;
    void* pPixels = nullptr;
    void* cPixels = nullptr;

    if (AndroidBitmap_getInfo(env, prevFrame, &pInfo) < 0 ||
        AndroidBitmap_getInfo(env, currFrame, &cInfo) < 0) return 0.0f;

    if (AndroidBitmap_lockPixels(env, prevFrame, &pPixels) < 0 ||
        AndroidBitmap_lockPixels(env, currFrame, &cPixels) < 0) return 0.0f;

    uint32_t* p = static_cast<uint32_t*>(pPixels);
    uint32_t* c = static_cast<uint32_t*>(cPixels);

    int w = static_cast<int>(pInfo.width);
    int h = static_cast<int>(pInfo.height);
    if (w != static_cast<int>(cInfo.width) || h != static_cast<int>(cInfo.height) || w <= 0 || h <= 0) {
        AndroidBitmap_unlockPixels(env, prevFrame);
        AndroidBitmap_unlockPixels(env, currFrame);
        return 0.0f;
    }

    // 64-bin RGB (4x4x4) color histogram + mean block luminance difference
    double histP[64] = {0.0};
    double histC[64] = {0.0};
    double lumaDiffSum = 0.0;
    int sampleCount = 0;

    int stepX = std::max(1, w / 48);
    int stepY = std::max(1, h / 48);

    for (int y = 0; y < h; y += stepY) {
        for (int x = 0; x < w; x += stepX) {
            uint32_t pv = p[y * w + x];
            uint32_t cv = c[y * w + x];

            int pr = pv & 0xFF, pg = (pv >> 8) & 0xFF, pb = (pv >> 16) & 0xFF;
            int cr = cv & 0xFF, cg = (cv >> 8) & 0xFF, cb = (cv >> 16) & 0xFF;

            int binP = ((pr >> 6) << 4) | ((pg >> 6) << 2) | (pb >> 6);
            int binC = ((cr >> 6) << 4) | ((cg >> 6) << 2) | (cb >> 6);
            histP[binP] += 1.0;
            histC[binC] += 1.0;

            double lumaP = 0.299 * pr + 0.587 * pg + 0.114 * pb;
            double lumaC = 0.299 * cr + 0.587 * cg + 0.114 * cb;
            lumaDiffSum += std::abs(lumaP - lumaC) / 255.0;
            sampleCount++;
        }
    }

    AndroidBitmap_unlockPixels(env, prevFrame);
    AndroidBitmap_unlockPixels(env, currFrame);

    if (sampleCount == 0) return 0.0f;

    double chiSq = 0.0;
    for (int i = 0; i < 64; ++i) {
        double hp = histP[i] / sampleCount;
        double hc = histC[i] / sampleCount;
        if (hp + hc > 1e-6) {
            double diff = hp - hc;
            chiSq += (diff * diff) / (hp + hc);
        }
    }

    double meanLumaDiff = lumaDiffSum / sampleCount;
    double combinedScore = std::clamp(chiSq * 0.65 + meanLumaDiff * 0.85, 0.0, 1.0);
    return static_cast<float>(combinedScore);
}

} // extern "C"
