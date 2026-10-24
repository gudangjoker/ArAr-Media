# ArAr Media

Aplikasi editor video multi-track dan pengolah audio (DAW) untuk Android yang dibangun menggunakan **Kotlin (Jetpack Compose)** dan **C++17 (Android NDK / JNI)**.

![License](https://img.shields.io/badge/Lisensi-Apache_2.0-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android_8.0%2B-green.svg)
![NDK](https://img.shields.io/badge/Engine-C%2B%2B17_NDK-orange.svg)

---

## Ringkasan Aplikasi

ArAr Media adalah proyek aplikasi pengeditan video dan audio lokal (offline) yang menggabungkan penyusunan klip video multi-track dengan alat pemrosesan sinyal audio dalam satu antarmuka. Seluruh proses dekode, efek visual, pemrosesan audio, dan ekspor MP4/WAV dijalankan langsung di perangkat tanpa koneksi internet.

---

## Komponen & Pustaka yang Dipasang

Aplikasi ini tidak menggunakan pustaka pihak ketiga berukuran besar (seperti FFmpeg, OpenCV, atau TensorFlow Lite), melainkan dibangun di atas komponen berikut:

1. **Antarmuka Pengguna & Arsitektur (Kotlin)**:
   - `androidx.compose.material3` & `material-icons-extended` (Compose BOM `2024.09.03`) untuk seluruh tampilan UI.
   - `androidx.lifecycle:lifecycle-viewmodel-compose` & `kotlinx-coroutines-android` untuk pengelolaan state dan pemrosesan latar belakang.
2. **Mesin Pemrosesan Native (`libarar_media_engine.so`)**:
   - Ditulis dalam **C++17** (`app/src/main/cpp/native_media_engine.cpp`), dikompilasi dengan CMake `3.22.1` dan Android NDK `26.1.10909125` untuk arsitektur `arm64-v8a` dan `x86_64` dengan flag optimasi `-O3 -ffast-math`.
3. **Pipeline Multimedia Sistem Android**:
   - `MediaMetadataRetriever` untuk pengambilan frame video pada pratinjau dan ekspor.
   - `MediaExtractor` & `MediaCodec` untuk dekode audio dari file video/musik (MP3, AAC, WAV, M4A, MP4) serta enkode video H.264 (`video/avc`) dan audio AAC (`audio/mp4a-latm`).
   - `MediaMuxer` untuk penggabungan stream video dan audio menjadi file `.mp4`.
   - `AudioRecord` & `AudioTrack` untuk perekaman suara mikrofon dan pemutaran audio PCM secara langsung.
   - `RecognizerIntent` (`SpeechRecognizer` bawaan Android) untuk input dikte teks keterangan (caption).

---

## Fitur yang Tersedia

### 1. Editor Timeline Video (5 Track)
- **Track Terpisah**: Video Utama (`MAIN_VIDEO`), Overlay Gambar-dalam-Gambar (`PIP_OVERLAY`), Teks/Keterangan (`TEXT_CAPTION`), Musik (`MUSIC`), dan Rekaman Suara (`VOICEOVER`).
- **Pengeditan Klip**: Potong klip di posisi playhead (split), pangkas durasi awal/akhir dengan menggeser ujung kiri/kanan klip (trim handles), geser posisi waktu klip, duplikasi, balik arah video (reverse), cerminkan horizontal (mirror), serta riwayat Undo/Redo hingga 25 langkah.
- **Manipulasi Kanvas Langsung**: Cubit untuk memperbesar/memperkecil (pinch-to-zoom), putar dua jari, dan geser posisi klip langsung pada layar pratinjau.
- **Rasio & Latar Kanvas**: Pilihan rasio `9:16`, `16:9`, `1:1`, `4:5`, dan `21:9` dengan opsi latar belakang blur (multi-pass box blur), hitam pekat, abu-abu gelap, atau gradien.
- **Kecepatan & Kurva**: Pengaturan kecepatan konstan (`0.2x` hingga `4.0x`) dan preset kurva kecepatan non-linier (*Montage*, *Hero*, *Bullet*, *Jump Cut*, *Flash In*).
- **Animasi & Transisi**: Animasi klip (*Fade In*, *Zoom In*, *Slide Up/Left*, *Spin In*, *Bounce*, *Pendulum*, *Beat Pulse*), keyframe transformasi manual, serta efek transisi antar klip (*Dissolve*, *Glitch*, *Whip Pan*, *Zoom Blur*, *Flash White*, *Shake*, *VHS*).
- **Koreksi Warna & LUT**: 9 parameter warna (*Brightness*, *Contrast*, *Saturation*, *Exposure*, *Highlights*, *Shadows*, *Temperature*, *Sharpness*, *Vignette*) dan preset 3D LUT `17x17x17`.
- **Chroma Key & Penghapus Latar**: Penghapusan warna latar (hijau/biru/kustom) dengan pengambilan sampel warna otomatis dari tepi frame, serta fitur *Smart Cutout* berbasis analisis kluster warna tepi.

### 2. Editor Audio (DAW) & Pemrosesan Sinyal (DSP)
- **Equalizer & Dinamika**: Equalizer 10-band biquad (`31Hz`–`16kHz`) dan kompresor/limiter rentang dinamis.
- **Pitch & Tempo**: Pengubah nada (`-12` hingga `+12` semitone) dan kecepatan putar (`0.5x`–`2.0x`) menggunakan algoritma time-domain SOLA, ditambah preset efek suara (*Chipmunk*, *Deep*, *Robot*, *Echo*, *Telephone*, *Radio*).
- **Pengurang Derau (Denoise)**: Spectral gating berbasis STFT 1024-titik dengan penghalusan gain temporal dan filter high-pass 75Hz.
- **Pemisah Vokal & Instrumen (2-Stem)**: Memisahkan komponen vokal dan musik pengiring menggunakan kombinasi *Harmonic-Percussive Source Separation* (HPSS), korelasi stereo Mid-Side, dan filter frekuensi vokal (135Hz–4.2kHz), yang hasilnya dapat langsung dikirim ke timeline video.
- **Deteksi Ketukan (BPM) & Segmen Suara**: Deteksi onset ketukan berbasis *spectral flux* dan pembuatan segmen teks otomatis berdasarkan deteksi aktivitas suara (VAD).
- **Ekspor Audio**: Penyimpanan hasil olahan audio ke format WAV PCM 16-bit.

---

## Keunggulan dan Kekurangan (Apa Adanya)

### Keunggulan
1. **Ukuran Ringan & Mandiri**: Tidak membundel pustaka berat atau model jaringan saraf ratusan megabyte, sehingga ukuran APK tetap kecil dan mudah dikompilasi.
2. **Sepenuhnya Offline & Tanpa Telemetri**: Tidak memerlukan izin internet (`INTERNET` permission tidak digunakan), tanpa iklan, tanpa login, dan seluruh file media diproses di dalam penyimpanan lokal perangkat.
3. **Integrasi Video dan Audio dalam Satu Aplikasi**: Pengguna dapat mengolah audio di halaman DAW (seperti melakukan denoise atau memisahkan vokal/instrumen) lalu langsung mengirim hasilnya ke track musik/voiceover di timeline video.
4. **Pemrosesan Piksel dan DSP di C++**: Operasi intensif seperti koreksi warna per piksel, chroma key, interpolasi 3D LUT, STFT audio, dan equalizer dijalankan di kode native C++17 untuk mengurangi beban garbage collector di Kotlin/Java.

### Kekurangan & Keterbatasan
1. **Fitur "AI" Menggunakan Algoritma Klasik (Bukan Deep Learning)**:
   - Seluruh fitur otomatis di aplikasi ini menggunakan algoritma pemrosesan sinyal digital (DSP) dan statistik citra klasik di C++, **bukan** model jaringan saraf tiruan (neural network/deep learning).
   - **Penghapus Latar (*Smart Cutout*)**: Bekerja dengan memodelkan warna tepi gambar dan mengasumsikan objek berada di area tengah. Fitur ini bekerja cukup baik pada latar belakang yang warnanya kontras dan relatif seragam, namun **tidak akurat** untuk latar belakang yang ramai/kompleks dibandingkan model segmentasi potret berbasis deep learning.
   - **Pemisah Vokal (*Stem Separation*)**: Mengandalkan pemisahan harmonik-perkusi (HPSS) dan sinyal tengah stereo. Hasil pemisahan masih dapat menyisakan bocoran instrumen atau artefak fase, tidak sebersih model pemisah berbasis AI modern (seperti Demucs atau Spleeter).
   - **Auto-Caption**: Hanya mendeteksi rentang waktu suara aktif (VAD) secara akustik dan mengandalkan fitur dikte suara bawaan sistem Android (`SpeechRecognizer`) jika tersedia di perangkat; belum memiliki model transkripsi suara-ke-teks mandiri di dalam aplikasi.
2. **Performa Pratinjau Video Berbasis CPU**:
   - Pengambilan frame pratinjau menggunakan `MediaMetadataRetriever` dan penggabungan layer dilakukan di atas `Bitmap`/`Canvas` CPU (dibantu cache memori 36 frame), belum menggunakan pipeline rendering GPU OpenGL/Vulkan penuh. Pada video beresolusi tinggi (4K) atau saat banyak layer aktif bersamaan, pratinjau dapat terasa patah-patah.
3. **Batas Durasi Audio di Memori (RAM)**:
   - Audio yang diimpor didekode ke dalam RAM sebagai array PCM `FloatArray` (dibatasi maksimal sekitar 3 menit per klip pada 44.1kHz stereo untuk mencegah aplikasi keluar paksa karena *Out of Memory*). Aplikasi ini cocok untuk klip pendek hingga menengah, namun tidak dirancang untuk mengedit audio/video berdurasi panjang (misalnya puluhan menit).
4. **Penyimpanan Proyek Masih Berbasis Sesi**:
   - Susunan timeline saat ini disimpan di memori selama aplikasi aktif dan belum dilengkapi penyimpanan database lokal untuk mengelola banyak draf proyek secara permanen setelah aplikasi ditutup paksa.

---

## Cara Membangun (Build)

### Prasyarat
- Android Studio (Ladybug / Koala atau lebih baru)
- JDK 17
- Android SDK 34
- Android NDK `26.1.10909125`
- CMake `3.22.1`

### Perintah Kompilasi
```bash
# Build APK Debug
./gradlew assembleDebug

# Build APK Release
./gradlew assembleRelease
```

---

## Lisensi
Didistribusikan di bawah lisensi **Apache License 2.0**.
