package com.ararmedia.editor.engine

import android.graphics.Bitmap

/**
 * High-Performance JNI Bridge connecting Kotlin NLE & DAW pipelines
 * directly to the C++ DSP and Vision Acceleration Engine (libarar_media_engine.so).
 */
object MediaNativeBridge {

    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("arar_media_engine")
            isNativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("MediaNativeBridge", "Native library failed to load: ${e.message}")
            isNativeLoaded = false
        }
    }

    fun isLoaded(): Boolean = isNativeLoaded

    // --- C++ DSP Rack Engine Lifecycle ---
    external fun initDspEngine(sampleRate: Int, channels: Int): Long
    external fun destroyDspEngine(handle: Long)

    // --- DSP Parameters ---
    external fun setEqBandGain(handle: Long, bandIndex: Int, gainDb: Float)
    external fun setCompressor(
        handle: Long,
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupGainDb: Float
    )
    external fun setReverb(
        handle: Long,
        roomSize: Float,
        damping: Float,
        wetMix: Float,
        delayMs: Float
    )
    external fun setPitchShift(handle: Long, semitones: Float)
    external fun setVoicePreset(handle: Long, presetId: Int)

    // --- Real-Time Streaming PCM Processing (Returns live GR meter in dB) ---
    external fun processAudioPcm(
        handle: Long,
        inBuffer: FloatArray,
        outBuffer: FloatArray,
        numSamples: Int
    ): Float

    // --- Offline Audio AI Suite ---
    external fun spectralDenoise(
        inSamples: FloatArray,
        outSamples: FloatArray,
        sampleRate: Int,
        reductionDb: Float
    )

    external fun splitStems(
        inStereoSamples: FloatArray,
        vocalsOut: FloatArray,
        backingOut: FloatArray,
        numFrames: Int
    )

    external fun detectBeats(
        pcmSamples: FloatArray,
        sampleRate: Int
    ): FloatArray?

    external fun detectSpeechCadence(
        pcmSamples: FloatArray,
        sampleRate: Int
    ): LongArray?

    // --- Offline Vision AI Suite ---
    external fun processChromaKey(
        srcBitmap: Bitmap,
        dstBitmap: Bitmap,
        keyColorRgb: Int,
        similarity: Float,
        smoothness: Float,
        spillSuppression: Float
    )

    external fun processStabilization(
        prevFrame: Bitmap,
        currFrame: Bitmap,
        transformOut: FloatArray
    )
}
