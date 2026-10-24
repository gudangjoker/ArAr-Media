# ArAr Media 🎬🎙️
**Offline Native Video & Audio Editing Studio for Android**

![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android_8.0+-green.svg)
![C++ NDK](https://img.shields.io/badge/DSP_&_Vision_Engine-C%2B%2B17_NDK-orange.svg)

ArAr Media combines a **CapCut-style Multi-Track Video Studio** with a **Precision Waveform Audio DAW**. Designed for speed and privacy, all video compositing, audio DSP, and AI tools run 100% natively on-device with zero internet permissions.

---

## ✨ Key Features & CapCut-Level Workflow

1. **CapCut-Style 5-Track Magnetic Timeline (`MultiTrackTimeline`)**:
   - **Interactive Left & Right Trim Handles**: Select any clip on the timeline and drag its left or right edge handles to trim `startOnTimelineMs`, `sourceOffsetMs`, and `durationMs` with magnetic snapping to beats and the playhead.
   - **Inline Visual Thumbnails & Audio Waveforms**: Video/PIP clips render thumbnail strips directly inside the timeline lane, and Music/Voiceover tracks display real-time PCM waveform peaks.
   - **Direct Canvas Touch Manipulation**: Pinch-to-zoom, two-finger rotate, and drag any selected Video, PIP Overlay, or Text/Caption clip directly on the live Video Canvas Preview.
   - **Speed Curves & Normal Speed**: Constant speed (`0.2x` to `4.0x`) plus non-linear **CapCut Speed Curves** (*Montage*, *Hero*, *Bullet*, *Jump Cut*, *Flash In*) synchronized across both video frame extraction and audio playback.
   - **Clip Animations (In & Combo) + Transitions**: Includes *Fade In*, *Zoom In*, *Slide Up*, *Slide Left*, *Spin In*, *Bounce*, *Pendulum*, and *Beat Pulse* animations alongside junction transitions (*Dissolve*, *Glitch*, *Whip Pan*, *Zoom Blur*, *Flash White*, *Shake*, *VHS*).
   - **Canvas Aspect Ratio & True Blur Background**: Switch between `9:16`, `16:9`, `1:1`, `4:5`, and `21:9` with real multi-pass blurred canvas backgrounds, solid black, studio dark, or gradient backdrops.
   - **Full 9-Parameter Color Grading & 3D LUTs**: Real-time *Brightness*, *Contrast*, *Saturation*, *Exposure*, *Highlights*, *Shadows*, *Temperature*, *Sharpness*, and *Vignette* sliders plus 3D LUT presets.

2. **On-Device Native AI Tools (`native_media_engine.cpp`)**:
   - **AI Auto Cutout (Smart Background Removal) & Chroma Key**: Removes backgrounds automatically without a green screen using C++ border-cluster color modeling + spatial subject saliency + smoothstep edge feathering, alongside full Chroma Key with **Auto Pick Key Color** and spill suppression.
   - **AI Auto-Captions & Voice Dictation**: Segments speech phrases from Voiceover/Video tracks using C++ speech-bandpass VAD + syllable energy peak analysis, supports Android on-device offline speech dictation (`RecognizerIntent`), and renders animated left-to-right **Karaoke Highlighting**.
   - **AI Studio Noise Reduction (`spectralDenoise`)**: 1024-point STFT decision-directed Wiener spectral gating with temporal smoothing and 75Hz sub-rumble filtering, applicable directly to timeline video/voiceover clips or in the Audio DAW.
   - **AI 2-Stem Separation (Vocals vs. Instrumental)**: Hybrid **Harmonic-Percussive Source Separation (HPSS)** + **Stereo Mid-Side Correlation** + **Vocal Formant Mask (135Hz–4.2kHz)** with 1-tap routing of separated Vocal or Instrumental stems directly onto the Video Timeline.
   - **AI Auto-Beat Sync & BPM Detection**: Low-frequency weighted spectral flux onset detection and autocorrelation BPM estimation with magnetic timeline beat markers.
   - **AI Video Motion Stabilization**: Multi-anchor SAD block matching across consecutive 30fps frames with trajectory smoothing and overscan border compensation.

3. **Clean, Professional Creator UI/UX**:
   - Streamlined CapCut-inspired Home screen with a prominent **New Project** hero card, 1-tap **Quick Tools** grid (*Auto Captions*, *Remove BG*, *Vocal Remover*, *AI Denoise*, *Speed Curve*, *Stabilize*, *Color LUTs*, *Audio DAW*), and an **Active Project** card—free of debug banners or unnecessary informational badges.
   - Full-screen Video Editor and Audio DAW workspaces with an instant top-bar `Video | Audio` mode switcher.

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
chmod +x gradlew
./gradlew assembleDebug
```
The compiled APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📜 License
Apache License 2.0
