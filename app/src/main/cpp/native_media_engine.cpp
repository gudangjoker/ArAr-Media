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
    compressor(sr), reverb(), pitchShifter(),
    pitchSemitones(0.0f), activeVoicePreset(0), robotPhase(0.0),
    delayWriteIndex(0), delaySamples(0), delayFeedback(0.35), delayWet(0.0) {

    for (int i = 0; i < NUM_BANDS; ++i) {
        bandGains[i] = 0.0;
        eqBands[i].configurePeaking(EQ_FREQUENCIES[i], sampleRate, 0.0);
    }

    // Allocate 2 seconds delay buffer
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
    if (delayMs > 0.0 && wetMix > 0.001) {
        delaySamples = std::clamp(static_cast<size_t>(sampleRate * (delayMs * 0.001)), static_cast<size_t>(1), delayBuffer.size() - 1);
        delayWet = std::clamp(wetMix * 0.5, 0.0, 0.7);
    } else {
        delayWet = 0.0;
    }
}

void StudioDspEngine::setPitchSemitones(float semitones) {
    pitchSemitones = std::clamp(semitones, -12.0f, 12.0f);
}

void StudioDspEngine::setVoicePreset(int presetId) {
    activeVoicePreset = presetId;
    // Always reset all bands and effects to clean baseline before applying preset
    for (int i = 0; i < NUM_BANDS; ++i) {
        setEqGain(i, 0.0);
    }
    compressor.setParams(-18.0, 4.0, 20.0, 200.0, 0.0);
    reverb.updateParams(0.5, 0.3, 0.0);
    delayWet = 0.0;
    pitchSemitones = 0.0f;

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
            for (int i = 0; i < 3; ++i) setEqGain(i, -15.0); // Cut lows
            setEqGain(4, 3.0);  // 500Hz
            setEqGain(5, 8.0);  // 1kHz peak
            setEqGain(6, 5.0);  // 2kHz peak
            for (int i = 8; i < NUM_BANDS; ++i) setEqGain(i, -14.0); // Cut highs
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
            setEqGain(5, 3.0);
            pitchSemitones = 0.0f;
            break;
        case 6: // Monster
            setEqGain(0, 6.0); // 31Hz rumble
            setEqGain(1, 6.0); // 62Hz sub
            setEqGain(2, 4.0);
            setEqGain(7, -5.0);
            pitchSemitones = -6.5f;
            compressor.setParams(-18.0, 6.0, 8.0, 250.0, 3.0);
            reverb.updateParams(0.75, 0.25, 0.28);
            break;
        case 7: // Cathedral Reverb
            reverb.updateParams(0.92, 0.12, 0.65);
            delaySamples = static_cast<size_t>(sampleRate * 0.22);
            delayWet = 0.25;
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

        // 1. Real-Time Granular Pitch Shifter
        sample = pitchShifter.process(sample, pitchFactor);

        // 2. Cascaded 10-Band Biquad Parametric EQ
        for (int b = 0; b < NUM_BANDS; ++b) {
            sample = eqBands[b].process(sample);
        }

        // 3. Voice Preset / Ring Modulation / Overdrive
        if (activeVoicePreset == 5) {
            // Robot Ring Modulation with 55Hz carrier
            double carrier = std::sin(robotPhase);
            robotPhase += 2.0 * M_PI * 55.0 / sampleRate;
            if (robotPhase >= 2.0 * M_PI) robotPhase -= 2.0 * M_PI;
            sample = sample * 0.35 + (sample * carrier) * 0.65;
        } else if (activeVoicePreset == 3) {
            // Megaphone Overdrive / Soft Saturation
            sample = std::tanh(sample * 2.4) * 0.75;
        }

        // 4. Dynamics Processor (Compressor & Limiter)
        sample = compressor.process(sample);
        if (compressor.lastGainReductionDb > maxGainReductionDb) {
            maxGainReductionDb = compressor.lastGainReductionDb;
        }

        // 5. Delay Slapback
        if (delayWet > 0.001) {
            size_t readIndex = (delayWriteIndex + delayBuffer.size() - delaySamples) % delayBuffer.size();
            double delayed = delayBuffer[readIndex];
            delayBuffer[delayWriteIndex] = sample + delayed * delayFeedback;
            delayWriteIndex = (delayWriteIndex + 1) % delayBuffer.size();
            sample = (1.0 - delayWet) * sample + delayWet * delayed;
        }

        // 6. Schroeder/Freeverb Spatial Reverb
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

    const size_t N = 1024;
    const size_t hop = 256;
    const double floorLinear = std::pow(10.0, -std::abs(reductionDb) / 20.0);

    if (static_cast<size_t>(length) < N) {
        for (jsize i = 0; i < length; ++i) outPtr[i] = inPtr[i];
        env->ReleaseFloatArrayElements(inSamples, inPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(outSamples, outPtr, 0);
        return;
    }

    // Compute Hann window
    std::vector<double> window(N);
    for (size_t i = 0; i < N; ++i) {
        window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (N - 1)));
    }

    // Estimate noise profile from the lowest-energy frames across the audio
    size_t totalFrames = (static_cast<size_t>(length) - N) / hop + 1;
    std::vector<std::pair<double, size_t>> frameEnergies;
    frameEnergies.reserve(totalFrames);

    for (size_t f = 0; f < totalFrames; ++f) {
        double e = 0.0;
        size_t base = f * hop;
        for (size_t i = 0; i < N && (base + i) < static_cast<size_t>(length); ++i) {
            double v = inPtr[base + i];
            e += v * v;
        }
        frameEnergies.emplace_back(e, f);
    }
    std::sort(frameEnergies.begin(), frameEnergies.end());

    size_t noiseFramesCount = std::clamp(totalFrames / 5, static_cast<size_t>(1), static_cast<size_t>(32));
    std::vector<double> noisePsd(N / 2 + 1, 1e-7);

    for (size_t nf = 0; nf < noiseFramesCount; ++nf) {
        size_t fIdx = frameEnergies[nf].second;
        std::vector<std::complex<double>> frame(N, 0.0);
        for (size_t i = 0; i < N; ++i) {
            size_t idx = fIdx * hop + i;
            if (idx < static_cast<size_t>(length)) {
                frame[i] = inPtr[idx] * window[i];
            }
        }
        fft_radix2(frame, false);
        for (size_t k = 0; k <= N / 2; ++k) {
            double p = std::norm(frame[k]);
            noisePsd[k] = (noisePsd[k] * nf + p) / (nf + 1);
        }
    }

    std::vector<double> accumOut(length + N, 0.0);
    std::vector<double> windowAccum(length + N, 0.0);
    std::vector<double> prevGain(N / 2 + 1, 1.0);

    for (size_t pos = 0; pos + N <= static_cast<size_t>(length); pos += hop) {
        std::vector<std::complex<double>> frame(N, 0.0);
        for (size_t i = 0; i < N; ++i) {
            frame[i] = inPtr[pos + i] * window[i];
        }

        fft_radix2(frame, false);

        // Apply decision-directed Wiener spectral suppression + 75Hz sub-rumble roll-off
        for (size_t k = 0; k <= N / 2; ++k) {
            double freq = (static_cast<double>(k) * sampleRate) / N;
            double p = std::norm(frame[k]);
            double snr = p / (noisePsd[k] * 2.5 + 1e-9);
            double rawGain = std::clamp(1.0 - (1.0 / (snr + 1.0)), floorLinear, 1.0);

            // Temporal smoothing to prevent musical noise artifacts
            double smoothedGain = 0.60 * prevGain[k] + 0.40 * rawGain;
            prevGain[k] = smoothedGain;

            // Sub-75Hz rumble attenuation
            if (freq < 75.0) {
                smoothedGain *= std::clamp(freq / 75.0, 0.1, 1.0);
            }

            frame[k] *= smoothedGain;
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
            outPtr[i] = static_cast<float>( std::clamp(accumOut[i] / windowAccum[i], -1.0, 1.0) );
        } else {
            outPtr[i] = inPtr[i];
        }
    }

    env->ReleaseFloatArrayElements(inSamples, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outSamples, outPtr, 0);
}

// --- Offline Audio AI: Hybrid HPSS + Stereo + Spectral Formant Stem Separation (Vocals vs Instrumental) ---
JNIEXPORT void JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_splitStems(
    JNIEnv* env, jobject thiz,
    jfloatArray inStereoSamples, jfloatArray vocalsOut, jfloatArray backingOut, jint numFrames) {
    if (!inStereoSamples || !vocalsOut || !backingOut || numFrames <= 0) return;

    jfloat* stereoPtr = env->GetFloatArrayElements(inStereoSamples, nullptr);
    jfloat* vocPtr = env->GetFloatArrayElements(vocalsOut, nullptr);
    jfloat* backPtr = env->GetFloatArrayElements(backingOut, nullptr);

    // Check if input has true stereo differences or is dual-mono
    double stereoDiffSum = 0.0;
    int checkFrames = std::min(numFrames, 8192);
    for (int i = 0; i < checkFrames; ++i) {
        stereoDiffSum += std::abs(stereoPtr[i * 2] - stereoPtr[i * 2 + 1]);
    }
    bool hasTrueStereo = (stereoDiffSum / std::max(1, checkFrames)) > 1e-4;

    const size_t N = 1024;
    const size_t hop = 256;
    const double sampleRate = 44100.0;

    if (static_cast<size_t>(numFrames) < N) {
        for (int i = 0; i < numFrames; ++i) {
            float mid = (stereoPtr[i * 2] + stereoPtr[i * 2 + 1]) * 0.5f;
            vocPtr[i] = mid * 0.5f;
            backPtr[i] = mid * 0.5f;
        }
        env->ReleaseFloatArrayElements(inStereoSamples, stereoPtr, JNI_ABORT);
        env->ReleaseFloatArrayElements(vocalsOut, vocPtr, 0);
        env->ReleaseFloatArrayElements(backingOut, backPtr, 0);
        return;
    }

    std::vector<double> window(N);
    for (size_t i = 0; i < N; ++i) {
        window[i] = 0.5 * (1.0 - std::cos(2.0 * M_PI * i / (N - 1)));
    }

    std::vector<double> vocAccum(numFrames + N, 0.0);
    std::vector<double> backAccum(numFrames + N, 0.0);
    std::vector<double> winAccum(numFrames + N, 0.0);
    std::vector<double> prevMidMag(N / 2 + 1, 0.0);

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

        std::vector<double> midMag(N / 2 + 1, 0.0);
        for (size_t k = 0; k <= N / 2; ++k) {
            midMag[k] = std::abs((frameL[k] + frameR[k]) * 0.5);
        }

        std::vector<std::complex<double>> vocSpec(N, 0.0);
        std::vector<std::complex<double>> backSpec(N, 0.0);

        for (size_t k = 0; k <= N / 2; ++k) {
            double freq = (k * sampleRate) / N;
            std::complex<double> mid = (frameL[k] + frameR[k]) * 0.5;
            std::complex<double> side = (frameL[k] - frameR[k]) * 0.5;

            // 1. Spectral vocal formant mask (135 Hz - 4200 Hz fundamental + harmonics)
            double formantMask = 0.0;
            if (freq >= 135.0 && freq <= 4200.0) {
                if (freq < 230.0) {
                    formantMask = (freq - 135.0) / 95.0;
                } else if (freq > 3200.0) {
                    formantMask = 1.0 - (freq - 3200.0) / 1000.0;
                } else {
                    formantMask = 1.0;
                }
            }
            formantMask = std::clamp(formantMask, 0.02, 0.96);

            // 2. Harmonic vs Percussive transient mask (drums/kicks have sharp onset jump vs previous frame)
            double currM = midMag[k];
            double prevM = prevMidMag[k];
            double onsetFlux = std::max(0.0, currM - prevM * 1.35);
            double harmonicRatio = currM / (currM + onsetFlux * 2.2 + 1e-7);
            prevMidMag[k] = 0.55 * prevM + 0.45 * currM;

            // 3. Stereo Center Panning Mask
            double centerMask = 1.0;
            if (hasTrueStereo) {
                double magL = std::abs(frameL[k]);
                double magR = std::abs(frameR[k]);
                double diff = std::abs(magL - magR);
                double sum = magL + magR + 1e-7;
                centerMask = std::clamp(1.0 - (diff / sum) * 1.4, 0.0, 1.0);
            }

            double finalVocalMask = formantMask * harmonicRatio * (hasTrueStereo ? (0.35 + 0.65 * centerMask) : 1.0);
            finalVocalMask = std::clamp(finalVocalMask, 0.0, 1.0);

            vocSpec[k] = mid * finalVocalMask;
            backSpec[k] = mid * (1.0 - finalVocalMask) + side;

            if (k > 0 && k < N / 2) {
                vocSpec[N - k] = std::conj(vocSpec[k]);
                backSpec[N - k] = std::conj(backSpec[k]);
            }
        }

        fft_radix2(vocSpec, true);
        fft_radix2(backSpec, true);

        for (size_t i = 0; i < N; ++i) {
            vocAccum[pos + i] += vocSpec[i].real() * window[i];
            backAccum[pos + i] += backSpec[i].real() * window[i];
            winAccum[pos + i] += window[i] * window[i];
        }
    }

    for (int i = 0; i < numFrames; ++i) {
        if (winAccum[i] > 1e-4) {
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

    // 1. Calculate Low-Frequency Weighted Spectral Flux Onset Novelty Function
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
            // Weight kick/bass frequencies (bins 1..12) higher for tight beat detection
            double weight = (k <= 12) ? 2.5 : 1.0;
            if (diff > 0.0) flux += diff * weight;
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

// --- Offline Auto-Captions Cadence Detector (Band-Pass Speech Envelope & Adaptive Threshold) ---
JNIEXPORT jlongArray JNICALL
Java_com_ararmedia_editor_engine_MediaNativeBridge_detectSpeechCadence(
    JNIEnv* env, jobject thiz, jfloatArray pcmSamples, jint sampleRate) {
    if (!pcmSamples) return nullptr;

    jsize length = env->GetArrayLength(pcmSamples);
    if (length <= 0) return nullptr;

    jfloat* pcm = env->GetFloatArrayElements(pcmSamples, nullptr);

    // 20ms frames
    int frameSize = std::max(1, sampleRate / 50);
    int numFrames = length / frameSize;
    if (numFrames <= 0) {
        env->ReleaseFloatArrayElements(pcmSamples, pcm, JNI_ABORT);
        return nullptr;
    }

    // Bandpass filter around human speech frequencies (300Hz - 3000Hz) so bass/hiss doesn't fool VAD
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
    // Adaptive threshold between minimum floor and mean energy
    double adaptiveThreshold = std::max(0.002, minEnergy + (meanEnergy - minEnergy) * 0.45);

    std::vector<int64_t> segments;
    bool inSpeech = false;
    int64_t speechStartMs = 0;
    int silenceFrameCount = 0;
    const int silenceThresholdFrames = 8; // ~160ms pause indicates phrase boundary

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
                    if (speechEndMs - speechStartMs >= 280) { // Keep phrases >= 280ms
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

    // If continuous signal without deep dips, segment into natural 2.2s caption blocks
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

// --- Vision AI: Chroma Key & Green Screen Cutout with Premultiplied Alpha & Spill Suppression ---
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

        // Unpremultiply if source has partial alpha
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
            alphaFactor = t * t * (3.0 - 2.0 * t); // Smoothstep
        }

        // Spill suppression (desaturate dominant key channel on edges)
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

        // Android ARGB_8888 requires premultiplied RGB channels!
        int finalA = static_cast<int>(a * alphaFactor);
        int outR = (r * finalA) / 255;
        int outG = (g * finalA) / 255;
        int outB = (b * finalA) / 255;

        dst[i] = (outR & 0xFF) | ((outG & 0xFF) << 8) | ((outB & 0xFF) << 16) | ((finalA & 0xFF) << 24);
    }

    AndroidBitmap_unlockPixels(env, srcBitmap);
    AndroidBitmap_unlockPixels(env, dstBitmap);
}

// --- Vision AI: Smart Subject Cutout (Auto Background Removal without Green Screen) ---
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

    // 1. Sample 4 border regions (top, left, right, upper corners) to model background colors
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
        samplePixel(x, 2, 0);                 // Top edge
        samplePixel(x, std::min(h - 1, h / 14), 1); // Upper strip
    }
    for (int y = 0; y < h * 3 / 4; y += stepY) {
        samplePixel(2, y, 2);                 // Left edge
        samplePixel(w - 3, y, 3);             // Right edge
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
    double cy = h * 0.54; // Subject center slightly below vertical midpoint
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

            // Minimum color distance to any of the 4 border background clusters
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

            // Spatial center prior protects interior subject details while aggressively cutting outer background
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
