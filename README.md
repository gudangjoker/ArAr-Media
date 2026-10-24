# ArAr Media

Aplikasi editor video multi-track dan pengolah audio (DAW) untuk Android yang dibangun menggunakan **Kotlin (Jetpack Compose)**, **C++17 (Android NDK / JNI)**, serta model visi & pemrosesan sinyal neural yang dibundel langsung di dalam APK.

![License](https://img.shields.io/badge/Lisensi-Apache_2.0-blue.svg)
![Platform](https://img.shields.io/badge/Platform-Android_8.0%2B-green.svg)
![NDK](https://img.shields.io/badge/Engine-C%2B%2B17_NDK_%2B_Bundled_ML-orange.svg)

---

## Ringkasan Aplikasi

ArAr Media adalah aplikasi pengeditan video dan audio lokal (offline) yang menggabungkan penyusunan klip video multi-track, mesin visi komputer on-device, serta alat pemrosesan sinyal audio (DAW) dalam satu antarmuka. Seluruh proses dekode, inferensi AI, efek visual, pemrosesan audio, penyimpanan draf proyek, dan ekspor MP4/WAV/M4A dijalankan langsung di perangkat tanpa memerlukan koneksi internet atau server eksternal.

---

## Komponen, Pustaka, dan Mesin AI yang Dipasang

1. **Antarmuka Pengguna & Arsitektur (Kotlin)**:
   - `androidx.compose.material3` & `material-icons-extended` (Compose BOM `2024.09.03`) untuk antarmuka pengguna.
   - `androidx.lifecycle:lifecycle-viewmodel-compose`, `kotlinx-coroutines-android`, dan `kotlinx-coroutines-play-services` untuk pengelolaan state asinkron.
2. **Model AI Visi Komputer Terbundel (On-Device Bundled Neural Models)**:
   - `com.google.mlkit:segmentation-selfie:16.0.0-beta6`: Model jaringan saraf *MediaPipe/BlazePose Selfie Segmentation* yang dibundel langsung di dalam APK untuk memisahkan subjek manusia dari latar belakang kompleks secara akurat.
   - `com.google.mlkit:face-detection:16.1.7`: Model deteksi wajah dan titik fitur wajah (*facial landmarks/contours*) terbundel untuk pelacakan wajah otomatis (*Auto-Reframe 9:16*) dan penghalusan kulit potret (*Face Retouch & Portrait Relight*).
3. **Mesin Pemrosesan Native (`libarar_media_engine.so` - C++17 NDK)**:
   - Dikompilasi dengan CMake `3.22.1` dan Android NDK `26.1.10909125` untuk arsitektur `arm64-v8a` dan `x86_64` dengan optimasi `-O3 -ffast-math`.
   - Mencakup algoritma **RNNoise 22-Bark-Band GRU Denoiser**, **WPE Dereverberation**, **2048-point 75%-Overlap 2D-Median HPSS Stem Separator**, **Bilateral Skin Retouching**, **9-Anchor Median Optical Flow Stabilization**, dan **64-Bin Chi-Square Scene Cut Detector**.
4. **Pipeline Multimedia & Transkripsi Sistem Android**:
   - **Hardware Audio Master Clock (`AudioTrack.getPlaybackHeadPosition()`)**: Sinkronisasi waktu video dan audio berbasis posisi sampel perangkat keras dengan streaming chunk PCM (`1024` sampel per blok) dan kumpulan instance `MediaMetadataRetriever` persisten (`retrieverPool`).
   - **Disk-Backed 16-bit PCM Cache (`.pcm16`)**: Menyimpan hasil dekode audio ke penyimpanan cache lokal dengan `PrimitiveFloatBuilder` (tanpa pembengkakan memori `ArrayList<Float>`), mendukung file berdurasi hingga 25 menit.
   - `MediaExtractor`, `MediaCodec`, & `MediaMuxer` untuk dekode media dan enkode hasil akhir ke H.264 MP4 (`video/avc` + `audio/mp4a-latm`), WAV 16-bit, atau M4A AAC.
   - **On-Device Audio-Pipe Speech Transcriber (`OfflineSpeechTranscriber`)**: Mengalirkan PCM 16kHz dari klip video langsung ke `SpeechRecognizer` Android melalui `ParcelFileDescriptor` (`EXTRA_AUDIO_SOURCE` pada Android 13+) dengan dukungan Bahasa Indonesia (`id-ID`) dan Bahasa Inggris (`en-US`), dilengkapi analisis kontur forman/prosodi akustik sebagai cadangan offline.

---

## Fitur yang Tersedia

### 1. Mesin AI & Otomasi (Terbundel di APK)
- **AI Smart Cutout (Penghapus Latar Neural)**: Menggunakan model *Google ML Kit Selfie Segmentation* yang dikombinasikan dengan *guided bilinear alpha feathering* dan koreksi *color spill* di C++ untuk memotong subjek manusia dengan tepi halus.
- **AI Auto-Reframe (9:16 Face Tracking)**: Memindai posisi wajah subjek sepanjang klip menggunakan *ML Kit Face Detection* dan secara otomatis membuat *keyframe* pergeseran kamera (`translationX`/`Y` & `scale`) yang teredam halus agar subjek tetap di tengah kanvas vertikal `9:16`.
- **AI Face Retouch & Portrait Relight**: Mendeteksi kotak area wajah secara otomatis dan menerapkan *edge-preserving 5x5 bilateral filter* di C++ untuk menghaluskan tekstur kulit tanpa mengaburkan mata, alis, atau bibir, ditambah pencahayaan wajah (*Portrait Relight*).
- **AI Smart Scene Cut Detection**: Menganalisis jarak *Chi-Square* pada histogram RGB 64-bin dan perubahan luminansi antar-frame di C++ untuk mendeteksi pergantian adegan kamera dan memotong (*split*) klip secara otomatis.
- **AI Denoise (RNNoise 22-Bark-Band Neural Gate)**: Meredam derau latar menggunakan analisis fitur 22 pita frekuensi skala Bark, *pitch comb filtering*, dan rekurensi *Gated Recurrent Unit* (GRU) di C++.
- **AI Voice Enhancer & Dereverb (Studio Podcast Mic)**: Menggabungkan peredam gema ruangan (*WPE late-reverb tail subtraction*), peredam derau RNNoise, equalizer kondensor 5-band, dan kompresor siaran untuk memperjelas rekaman suara.
- **AI 2-Stem Separation (Pemisah Vokal & Instrumen)**: Memisahkan vokal utama dan instrumen pengiring menggunakan STFT 2048-titik dengan *overlap* 75%, filter median 2D waktu-frekuensi Fitzgerald (HPSS), bobot forman vokal, dan koherensi fase stereo.
- **Auto-Captions (Bahasa Indonesia & Inggris)**: Menghasilkan teks subtitle otomatis dari trek audio video dengan segmentasi aktivitas suara (VAD) dan transkripsi ucapan.

### 2. Editor Timeline Video (5 Track)
- **5 Track Terpisah**: Video Utama (`MAIN_VIDEO`), Overlay PIP (`PIP_OVERLAY`), Teks/Keterangan (`TEXT_STICKER`), Musik (`MUSIC_BGM`), dan Rekaman Suara (`VOICEOVER`).
- **Sinkronisasi Audio-Video Presisi**: Pemutaran pratinjau dikunci langsung ke jam perangkat keras `AudioTrack` dengan mekanisme *frame drop* otomatis jika dekode frame video terlambat, menjaga keselarasan bibir dan suara (*lip-sync*).
- **Pengeditan Klip Lengkap**: Split, trim awal/akhir, geser posisi waktu, duplikasi, balik arah video (*reverse*), cerminkan (*mirror*), putar 90°, bekukan frame (*freeze frame*), stabilisasi 9-titik (*optical flow*), serta Undo/Redo 25 langkah.
- **Koreksi Warna, 3D LUT, Kecepatan Kurva, Animasi, Keyframe, & Transisi**: 9 parameter warna, preset 3D LUT `17x17x17`, kurva kecepatan non-linier, animasi masuk/kombo, keyframe transformasi manual, dan transisi antar-klip.
- **Penyimpanan Draf Proyek Otomatis (`ProjectPersistenceManager`)**: Seluruh perubahan pada timeline disimpan secara otomatis ke file JSON di penyimpanan lokal lengkap dengan izin akses URI persisten (`takePersistableUriPermission`), sehingga draf proyek dapat dibuka kembali kapan saja dari layar utama.

### 3. Editor Audio (DAW)
- **Equalizer 10-Band & Kompresor Dinamis**: Equalizer biquad `31Hz`–`16kHz` dan kompresor/limiter dengan indikator reduksi gain.
- **Pengeditan Gelombang Suara**: Trim seleksi, potong tengah (*cut & stitch*), bisukan area (*silence*), normalisasi `0 dB` / `-1 dB`, *fade in/out*, *reverse*, pengubah nada (*pitch shift* `-12` s/d `+12` semitone), preset karakter suara, serta efek *reverb* & *delay*.
- **Ekspor Audio**: Mendukung ekspor ke format **WAV Lossless (16-bit 44.1kHz)** dan **M4A (AAC 192kbps)**.

---

## Keunggulan dan Kekurangan (Apa Adanya)

### Keunggulan
1. **Sinkronisasi Audio-Video Berbasis Hardware Clock & Streaming Langsung**: Pemutaran timeline tidak lagi menunggu pencampuran seluruh trek di awal dan tidak lagi menggunakan *timer* terpisah yang menyebabkan suara dan gambar meleset (*drift*).
2. **Model AI Visi & DSP Nyata Terbundel di Dalam APK**: Segmentasi subjek (*Selfie Segmentation*) dan pelacakan/retouch wajah (*Face Detection*) menggunakan model neural Google ML Kit yang tertanam langsung di APK, sehingga bekerja instan secara offline tanpa perlu mengunduh model tambahan saat pertama kali dijalankan.
3. **Hemat Memori dengan Disk-Backed PCM Cache**: Dekode audio menggunakan buffer primitif dan cache file `.pcm16` di disk serta ringkasan gelombang 512-titik (`waveformPeaks`), sehingga antarmuka tetap responsif dan tidak mengalami *Out of Memory* pada proyek berdurasi panjang.
4. **Sepenuhnya Offline & Privasi Terjaga**: Tanpa iklan, tanpa pelacak, dan seluruh proyek disimpan secara lokal di perangkat pengguna.

### Kekurangan & Keterbatasan
1. **Kualitas Pemisah Vokal (2-Stem) Masih Berbasis Spektral 2D-HPSS, Bukan Demucs/MDX-Net Penuh**:
   - Meskipun algoritma pemisah vokal di C++ telah ditingkatkan menggunakan STFT 2048-titik (overlap 75%), filter median 2D Fitzgerald, dan bobot forman vokal, metode ini tetap merupakan pemisahan spektral-fase matematis dan belum menyertakan model jaringan saraf pemisah instrumen berukuran ratusan megabyte (seperti *Hybrid Demucs*). Pada lagu dengan aransemen padat atau efek reverb vokal yang lebar, masih dapat terdengar sisa bocoran instrumen pada trek vokal.
2. **Transkripsi Auto-Caption Bergantung pada Dukungan Layanan Pengenal Ucapan Perangkat**:
   - Pada perangkat dengan Android 13+ (API 33+) yang memiliki paket bahasa offline *Google Speech Services* terpasang, aliran PCM dari video dapat ditranskripsi langsung menjadi kalimat akurat. Namun pada perangkat Android versi lama (API 26–32) atau perangkat tanpa mesin pengenal suara offline, sistem otomatis beralih ke analisis segmen suara/prosodi akustik lokal atau dikte mikrofon, sehingga teks hasil otomatis tetap perlu disunting ulang oleh pengguna.
3. **Komposisi Frame Pratinjau Masih Berbasis Bitmap/Canvas CPU**:
   - Meskipun pengambilan frame telah dipercepat menggunakan kumpulan `MediaMetadataRetriever` persisten (`retrieverPool`) dan cache LRU, penggabungan multi-layer dan efek piksel masih dirender melalui `Bitmap` CPU dan JNI C++, belum menggunakan *shader* OpenGL ES / Vulkan secara langsung. Pada video 4K dengan banyak efek aktif sekaligus, laju frame pratinjau dapat turun menyesuaikan kemampuan prosesor perangkat.

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
