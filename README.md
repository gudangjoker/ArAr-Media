# ArAr Media 🎬🎙️
**Production-Ready Offline Native Video & Audio Editing Studio for Android**

![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android_8.0+-green.svg)
![Privacy](https://img.shields.io/badge/Privacy-100%25_Offline_Zero_Internet-brightgreen.svg)
![C++ NDK](https://img.shields.io/badge/DSP_Engine-C%2B%2B17_SIMD-orange.svg)

ArAr Media combines the intuitive multi-track timeline workflow of **CapCut Pro / CyberLink PowerDirector** with the deep waveform audio precision of **Audacity / Adobe Podcast**. Built strictly for personal privacy: **100% OFFLINE, ZERO telemetry, ZERO cloud dependencies, and ZERO internet permissions.**

---

## 🚀 Key Highlights

- **🔒 100% Offline & Private**: Strictly NO `android.permission.INTERNET` in `AndroidManifest.xml`.
- **⚡ C++ Real-Time DSP Acceleration (`libarar_media_engine.so`)**: 10-band cascaded Biquad EQ, dynamics compressor with live Gain Reduction (GR) metering, Schroeder Freeverb, and voice shifter.
- **🎧 AI Audio Suite (Offline)**:
  - 1-Tap Studio Denoise (STFT spectral gating eliminating fan noise, room echo, and hiss).
  - 2-Stem Center-Phase Splitter (isolates lead vocals vs. instrumental backing track).
  - Beat & BPM Onset Detector (drops yellow beat markers on timeline).
  - Speech Cadence & Auto-Captions generator.
- **🎞️ 5-Track Magnetic Timeline**:
  1. Main Video Track (razor split, delete, duplicate, freeze frame, reverse).
  2. PIP / B-Roll Overlay Track (1-Tap Green Screen Chroma Key with edge spill suppression).
  3. Text, Auto-Captions & Sticker Track (karaoke highlight styling).
  4. Music / BGM Track (with yellow beat markers and ducking).
  5. Voiceover Track (live mic recording directly onto playhead).
- **🎨 Color Grading & 3D LUTs**: Brightness, Contrast, Saturation, Vignette, and Cinematic LUT presets (Teal & Orange, Cyberpunk, Vintage Film, Noir, Anime).
- **💎 Keyframe Engine (`♦`)**: Smooth interpolation of scale, translation, rotation, and opacity.
- **💾 MediaStore Hardware Encoding**:
  - Video: H.264 MP4 (`720p`, `1080p FHD`, `4K UHD` at `24`, `30`, `60 FPS`) into `Movies/ArArMedia/`.
  - Audio: Lossless 16-bit WAV or AAC M4A into `Music/ArArMedia/`.

---

## 🛠️ Build & Installation

### Requirements:
- Android Studio Iguana+ / Koala / Ladybug
- Android SDK 34
- Android NDK `26.1.10909125`
- CMake `3.22.1`
- JDK 17

### Build Debug APK:
```bash
./gradlew assembleDebug
```
The compiled APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📜 License
Apache License 2.0
