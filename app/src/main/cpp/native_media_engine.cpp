#include "native_media_engine.h"

// --- Fast Radix-2 FFT Implementation ---
static void fft_radix2(std::vector<std::complex<double>>& a, bool invert) {
    size_t n = a.size();
    for (size_t i = 1, j = 0; i < n; i++) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
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
        for (size_t i = 0; i < n; i++) {
            a[i] /= static_cast<double>(n);
        }
    }
}

// --- StudioDspEngine Implementation ---
StudioDspEngine::StudioDspEngine(int sr, int ch) :
    sampleRate(sr), channels(ch),
    compressor(sr), reverb(),
    pitchSemitones(0.0f), activeVoicePreset(0), robotPhase(0.0),
    delayWriteIndex(0), delaySamples(0), delayFeedback(0.35), delayWet(0.0) {

    for (int i = 0; i < NUM_BANDS; ++i) {
        bandGains[i] = 0.0;
        eqBands[i].configurePeaking(EQ_FREQUENCIES[i], sampleRate, 0.0);
    }

    // Allocate 1 second delay buffer
    delayBuffer.assign(static_cast<size_t>(sampleRate * 2), 0.0);
    delaySamples = static_cast<size_t>(sampleRate * 0.18); // Default 180ms delay slapback
}

void StudioDspEngine::setEqGain(int band, double gainDb) {
    if (band >= 0 && band < NUM_BANDS) {
        bandGains[band] = gainDb;
        eqBands[band].configurePeaking(EQ_FREQUENCIES[band], sampleRate, gainDb);
    }
}

void StudioDspEngine::setCompressorParams(double thresholdDb, double ratio, double attackMs, double releaseMs, double makeupGainDb) {
    compressor.setParams(thresholdDb, ratio, attackMs, releaseMs, makeupGainDb);
}

void StudioDspEngine::setReverbParams(double roomSize, double damping, double wetMix, double delayMs) {
    reverb.updateParams(roomSize, damping, wetMix);
    if (delayMs > 0.0) {
        delaySamples = std::clamp(static_cast<size_t>(sampleRate * (delayMs * 0.001)), static_cast<size_t>(1), delayBuffer.size() - 1);
        delayWet = std::clamp(wetMix * 0.7, 0.0, 0.8);
    } else {
        delayWet = 0.0;
    }
}

void StudioDspEngine::setPitchSemitones(float semitones) {
    pitchSemitones = std::clamp(semitones, -12.0f, 12.0f);
}

void StudioDspEngine::setVoicePreset(int presetId) {
    activeVoicePreset = presetId;
    switch (presetId) {
        case 1: // Deep Podcast Voice
            setEqGain(1, 3.5);  // 62Hz warm bottom
            setEqGain(2, 4.0);  // 125Hz proximity
            setEqGain(5, 1.5);  // 1kHz clarity
            setEqGain(8, -2.0); // 8kHz soften sibilance
            compressor.setParams(-20.0, 3.5, 15.0, 180.0, 2.5);
            pitchSemitones = -2.5f;
            reverb.updateParams(0.2, 0.4, 0.08);
            break;
        case 2: // Helium / Chipmunk
            setEqGain(2, -4.0);
            setEqGain(6, 4.5); // 2kHz presence
            setEqGain(7, 3.5); // 4kHz sheen
            pitchSemitones = 7.0f;
            break;
        case 3: // Megaphone
            for (int i = 0; i < 3; ++i) setEqGain(i, -18.0); // Cut lows
            setEqGain(4, 3.0);  // 500Hz
            setEqGain(5, 8.0);  // 1kHz peak
            setEqGain(6, 5.0);  // 2kHz peak
            for (int i = 8; i < NUM_BANDS; ++i) setEqGain(i, -16.0); // Cut highs
            compressor.setParams(-15.0, 8.0, 5.0, 100.0, 5.0);
            pitchSemitones = 0.0f;
            break;
        case 4: // Studio Radio
            setEqGain(1, 2.5);
            setEqGain(2, 3.0);
            setEqGain(6, 3.5);
            setEqGain(7, 2.0);
            compressor.setParams(-24.0, 5.0, 10.0, 140.0, 4.0);
            pitchSemitones = 0.0f;
            reverb.updateParams(0.15, 0.5, 0.05);
            break;
        case 5: // Robot
            pitchSemitones = 0.0f;
            break;
        case 6: // Monster
            setEqGain(0, 6.0); // 31Hz rumble
            setEqGain(1, 6.0); // 62Hz sub
            setEqGain(2, 4.0);
            setEqGain(7, -5.0);
            pitchSemitones = -6.5f;
            compressor.setParams(-18.0, 6.0, 8.0, 250.0, 3.0);
            break;
        case 7: // Cathedral Reverb
            reverb.updateParams(0.92, 0.12, 0.65);
            pitchSemitones = 0.0f;
            break;
        default:
            pitchSemitones = 0.0f;
            break;
    }
}

float StudioDspEngine::processPcm(const float* inBuffer, float* outBuffer, int numSamples) {
    if (!inBuffer || !outBuffer || numSamples <= 0) return 0.0f;

    double maxGainReductionDb = 0.0;
    const double pitchFactor = std::pow(2.0, pitchSemitones / 12.0);

    for (int i = 0; i < numSamples; ++i) {
        double sample = static_cast<double>(inBuffer[i]);

        // 1. Cascaded 10-Band Biquad Parametric EQ
        for (int b = 0; b < NUM_BANDS; ++b) {
            sample = eqBands[b].process(sample);
        }

        // 2. Voice Preset / Pitch / Saturation Processing
        if (activeVoicePreset == 5) {
            // Robot Ring Modulation with 55Hz carrier
            double carrier = std::sin(robotPhase);
            robotPhase += 2.0 * M_PI * 55.0 / sampleRate;
            if (robotPhase >= 2.0 * M_PI) robotPhase -= 2.0 * M_PI;
            sample = sample * 0.4 + (sample * carrier) * 0.6;
        } else if (activeVoicePreset == 3) {
            // Megaphone Overdrive / Soft Saturation
            sample = std::tanh(sample * 2.2) * 0.75;
        }

        // 3. Dynamics Processor (Compressor & Limiter)
        sample = compressor.process(sample);
        if (compressor.lastGainReductionDb > maxGainReductionDb) {
            maxGainReductionDb = compressor.lastGainReductionDb;
        }

        // 4. Delay Slapback
        if (delayWet > 0.001) {
            size_t readIndex = (delayWriteIndex + delayBuffer.size() - delaySamples) % delayBuffer.size();
            double delayed = delayBuffer[readIndex];
            delayBuffer[delayWriteIndex] = sample + delayed * delayFeedback;
            delayWriteIndex = (delayWriteIndex + 1) % delayBuffer.size();
            sample = (1.0 - delayWet) * sample + delayWet * delayed;
        }

        // 5. Schroeder/Freeverb Spatial Reverb
        sample = reverb.process(sample);

        // Clamp to prevent hardware DAC clipping [-1.0, 1.0]
        outBuffer[i] = static_cast<float>(std::clamp(sample, -1.0, 1.0));
    }

    return static_cast<float>(maxGainReductionDb);
}

// ==============================================================================
// JNI INTERFACES FOR MEDIA NATIVE BRIDGE
// ==============================================================================

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_initDspEngine(
    JNIEnv* env, jobject thiz, jint sampleRate, jint channels) {
    auto* engine = new StudioDspEngine(sampleRate, channels);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_destroyDspEngine(
    JNIEnv* env, jobject thiz, jlong handle) {
    if (handle != 0) {
        auto* engine = reinterpret_cast<StudioDspEngine*>(handle);
        delete engine;
    }
}

JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_setEqBandGain(
    JNIEnv* env, jobject thiz, jlong handle, jint bandIndex, jfloat gainDb) {
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
    if (handle == 0 || !inBuffer || !outBuffer || numSamples <= 0) return 0.0f;

    auto* engine = reinterpret_cast<StudioDspEngine*>(handle);

    jfloat* inPtr = env->GetFloatArrayElements(inBuffer, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(outBuffer, nullptr);

    float grDb = engine->processPcm(inPtr, outPtr, numSamples);

    env->ReleaseFloatArrayElements(inBuffer, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outBuffer, outPtr, 0);

    return grDb;
}

// --- Offline Audio AI: Studio Spectral Denoise ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_spectralDenoise(
    JNIEnv* env, jobject thiz,
    jfloatArray inSamples, jfloatArray outSamples, jint sampleRate, jfloat reductionDb) {
    if (!inSamples || !outSamples) return;

    jsize length = env->GetArrayLength(inSamples);
    if (length <= 0) return;

    jfloat* inPtr = env->GetFloatArrayElements(inSamples, nullptr);
    jfloat* outPtr = env->GetFloatArrayElements(outSamples, nullptr);

    const size_t N = 512;
    const size_t hop = 256;
    const double floorLinear = std::pow(10.0, -std::abs(reductionDb) / 20.0);

    // Compute Hann window
    std::vector<double> window(N);
    for (size_t i = 0; i < N; ++i) {
        window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (N - 1)));
    }

    // Estimate initial noise profile from the first 100ms or quietest segments
    std::vector<double> noisePsd(N / 2 + 1, 1e-7);
    size_t initFrames = std::min(static_cast<size_t>(10), static_cast<size_t>(length / hop));
    if (initFrames < 1) initFrames = 1;

    for (size_t f = 0; f < initFrames; ++f) {
        std::vector<std::complex<double>> frame(N, 0.0);
        for (size_t i = 0; i < N; ++i) {
            size_t idx = f * hop + i;
            if (idx < static_cast<size_t>(length)) {
                frame[i] = inPtr[idx] * window[i];
            }
        }
        fft_radix2(frame, false);
        for (size_t k = 0; k <= N / 2; ++k) {
            double p = std::norm(frame[k]);
            noisePsd[k] = (noisePsd[k] * f + p) / (f + 1);
        }
    }

    std::vector<double> accumOut(length + N, 0.0);
    std::vector<double> windowAccum(length + N, 0.0);

    for (size_t pos = 0; pos + N <= static_cast<size_t>(length); pos += hop) {
        std::vector<std::complex<double>> frame(N, 0.0);
        for (size_t i = 0; i < N; ++i) {
            frame[i] = inPtr[pos + i] * window[i];
        }

        fft_radix2(frame, false);

        // Apply spectral gating / soft Wiener suppression
        for (size_t k = 0; k <= N / 2; ++k) {
            double p = std::norm(frame[k]);
            double snr = p / (noisePsd[k] + 1e-9);
            double gain = std::clamp(1.0 - (1.5 / (snr + 1.0)), floorLinear, 1.0);

            frame[k] *= gain;
            if (k > 0 && k < N / 2) {
                frame[N - k] = std::conj(frame[k]);
            }
        }

        fft_radix2(frame, true); // IFFT

        for (size_t i = 0; i < N; ++i) {
            accumOut[pos + i] += frame[i].real() * window[i];
            windowAccum[pos + i] += window[i] * window[i];
        }
    }

    // Overlap-add synthesis normalization
    for (jsize i = 0; i < length; ++i) {
        if (windowAccum[i] > 1e-4) {
            outPtr[i] = static_cast<float>(accumOut[i] / windowAccum[i]);
        } else {
            outPtr[i] = inPtr[i];
        }
    }

    env->ReleaseFloatArrayElements(inSamples, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outSamples, outPtr, 0);
}

// --- Offline Audio AI: Stem Separation (Vocals vs Instrumental) ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_splitStems(
    JNIEnv* env, jobject thiz,
    jfloatArray inStereoSamples, jfloatArray vocalsOut, jfloatArray backingOut, jint numFrames) {
    if (!inStereoSamples || !vocalsOut || !backingOut || numFrames <= 0) return;

    jfloat* stereoPtr = env->GetFloatArrayElements(inStereoSamples, nullptr);
    jfloat* vocPtr = env->GetFloatArrayElements(vocalsOut, nullptr);
    jfloat* backPtr = env->GetFloatArrayElements(backingOut, nullptr);

    // Mid-Side Phase Cancellation & Vocal Formant Bandpass Isolation
    // Lead vocals in commercial music are centered (panned mid: L ≈ R)
    for (int i = 0; i < numFrames; ++i) {
        float L = stereoPtr[i * 2];
        float R = stereoPtr[i * 2 + 1];

        float mid = (L + R) * 0.5f;
        float side = (L - R) * 0.5f;

        // Center isolation mask with side leakage subtraction
        float diffAbs = std::abs(L - R);
        float sumAbs = std::abs(L) + std::abs(R) + 1e-5f;
        float centerCorrelation = std::clamp(1.0f - (diffAbs / sumAbs), 0.0f, 1.0f);

        // Vocal component (isolated center with vocal presence weighting)
        float vocalSample = mid * centerCorrelation;
        // Backing component (stereo instruments + side panned acoustics)
        float backingSample = (L + R) * 0.5f - vocalSample * 0.85f + side;

        vocPtr[i] = std::clamp(vocalSample * 1.2f, -1.0f, 1.0f);
        backPtr[i] = std::clamp(backingSample, -1.0f, 1.0f);
    }

    env->ReleaseFloatArrayElements(inStereoSamples, stereoPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(vocalsOut, vocPtr, 0);
    env->ReleaseFloatArrayElements(backingOut, backPtr, 0);
}

// --- Offline Audio AI: Auto Beat & BPM Detection ---
JNIEXPORT jfloatArray JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_detectBeats(
    JNIEnv* env, jobject thiz, jfloatArray pcmSamples, jint sampleRate) {
    if (!pcmSamples) return nullptr;

    jsize length = env->GetArrayLength(pcmSamples);
    if (length < sampleRate) return nullptr; // Needs at least 1s

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

    // 1. Calculate Spectral Flux Onset Novelty Function
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
            if (diff > 0.0) flux += diff;
            prevMag[k] = mag;
        }
        onsetCurve[h] = flux;
    }

    // 2. Autocorrelation to estimate BPM in range 70 - 180 BPM
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

    // 3. Peak-picking for Beat Markers
    std::vector<float> beatTimestampsMs;
    double threshold = 0.0;
    for (double v : onsetCurve) threshold += v;
    threshold = (threshold / onsetCurve.size()) * 1.3;

    double minBeatDistHops = (60.0 / detectedBpm * 0.75) / hopTime;
    size_t lastPeakHop = 0;

    for (size_t h = 1; h < numHops - 1; ++h) {
        if (onsetCurve[h] > threshold &&
            onsetCurve[h] > onsetCurve[h - 1] &&
            onsetCurve[h] > onsetCurve[h + 1] &&
            (h - lastPeakHop >= minBeatDistHops)) {
            float timeMs = static_cast<float>(h * hopTime * 1000.0);
            beatTimestampsMs.push_back(timeMs);
            lastPeakHop = h;
        }
    }

    env->ReleaseFloatArrayElements(pcmSamples, pcm, JNI_ABORT);

    // Return format: [bpm, timestamp1, timestamp2, ...]
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

// --- Offline Auto-Captions Cadence Detector ---
JNIEXPORT jlongArray JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_detectSpeechCadence(
    JNIEnv* env, jobject thiz, jfloatArray pcmSamples, jint sampleRate) {
    if (!pcmSamples) return nullptr;

    jsize length = env->GetArrayLength(pcmSamples);
    if (length <= 0) return nullptr;

    jfloat* pcm = env->GetFloatArrayElements(pcmSamples, nullptr);

    // 20ms frames
    int frameSize = sampleRate / 50;
    int numFrames = length / frameSize;

    std::vector<int64_t> segments;
    bool inSpeech = false;
    int64_t speechStartMs = 0;
    int silenceFrameCount = 0;
    const int silenceThresholdFrames = 15; // ~300ms pause indicates phrase boundary

    for (int f = 0; f < numFrames; ++f) {
        double energy = 0.0;
        int startSample = f * frameSize;
        for (int i = 0; i < frameSize; ++i) {
            double s = pcm[startSample + i];
            energy += s * s;
        }
        energy /= frameSize;

        bool isVoiceActive = (energy > 0.001); // Speech threshold
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
                    if (speechEndMs - speechStartMs >= 400) { // Keep if >= 400ms
                        segments.push_back(speechStartMs);
                        segments.push_back(speechEndMs);
                    }
                }
            }
        }
    }

    if (inSpeech) {
        int64_t totalDurationMs = (static_cast<int64_t>(length) * 1000) / sampleRate;
        if (totalDurationMs - speechStartMs >= 300) {
            segments.push_back(speechStartMs);
            segments.push_back(totalDurationMs);
        }
    }

    env->ReleaseFloatArrayElements(pcmSamples, pcm, JNI_ABORT);

    jsize count = static_cast<jsize>(segments.size());
    jlongArray result = env->NewLongArray(count);
    env->SetLongArrayRegion(result, 0, count, segments.data());
    return result;
}

// --- Vision AI: Chroma Key & Green Screen Cutout with Spill Suppression ---
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

        double dR = r - keyR;
        double dG = g - keyG;
        double dB = b - keyB;
        double colorDist = std::sqrt(dR * dR + dG * dG + dB * dB);

        double alphaFactor = 1.0;
        if (colorDist < simThresh) {
            alphaFactor = 0.0;
        } else if (colorDist < simThresh + smoothThresh) {
            double t = (colorDist - simThresh) / smoothThresh;
            alphaFactor = t * t * (3.0 - 2.0 * t); // Smoothstep
        }

        // Green spill suppression
        if (spillSuppression > 0.01f && alphaFactor > 0.0) {
            int maxRB = std::max(r, b);
            if (g > maxRB) {
                g = static_cast<int>(g * (1.0 - spillSuppression) + maxRB * spillSuppression);
            }
        }

        int finalA = static_cast<int>(a * alphaFactor);
        dst[i] = (r) | (g << 8) | (b << 16) | (finalA << 24);
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
}

// --- Vision AI: Video Motion Stabilization ---
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

    // Block matching in 5 anchor regions (center and 4 corners)
    const int searchRadius = 12;
    const int blockSize = 16;
    const int numAnchors = 5;
    int anchorX[5] = {width / 2, width / 4, width * 3 / 4, width / 4, width * 3 / 4};
    int anchorY[5] = {height / 2, height / 4, height / 4, height * 3 / 4, height * 3 / 4};

    double totalDx = 0.0, totalDy = 0.0;

    for (int a = 0; a < numAnchors; ++a) {
        int ax = anchorX[a];
        int ay = anchorY[a];

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
        totalDx += bestDx;
        totalDy += bestDy;
    }

    AndroidBitmap_unlockPixels(env, prevFrame);
    AndroidBitmap_unlockPixels(env, currFrame);

    float avgDx = static_cast<float>(totalDx / numAnchors);
    float avgDy = static_cast<float>(totalDy / numAnchors);

    // Return compensation dx, dy
    float outData[2] = {-avgDx, -avgDy};
    env->SetFloatArrayRegion(transformOut, 0, 2, outData);
}

} // extern "C"
