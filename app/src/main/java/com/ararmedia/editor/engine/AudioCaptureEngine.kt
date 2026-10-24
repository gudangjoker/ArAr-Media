package com.ararmedia.editor.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

class AudioCaptureEngine(private val context: Context, private val sampleRate: Int = 44100) {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _currentRmsLevel = MutableStateFlow(0f)
    val currentRmsLevel: StateFlow<Float> = _currentRmsLevel.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun startRecording(onRecordedBuffer: (FloatArray, File) -> Unit) {
        if (_isRecording.value) return

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBufSize * 2, 4096 * 2)
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            return
        }

        audioRecord?.startRecording()
        _isRecording.value = true

        recordingJob = scope.launch {
            val recordedFloats = mutableListOf<Float>()
            val shortBuf = ShortArray(2048)

            try {
                while (isActive && _isRecording.value) {
                    val read = audioRecord?.read(shortBuf, 0, shortBuf.size) ?: 0
                    if (read > 0) {
                        var sumSquares = 0.0
                        for (i in 0 until read) {
                            val f = shortBuf[i] / 32768.0f
                            recordedFloats.add(f)
                            sumSquares += f * f
                        }
                        val rms = sqrt(sumSquares / read).toFloat()
                        _currentRmsLevel.value = rms
                    }
                }
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
                _currentRmsLevel.value = 0f

                if (recordedFloats.isNotEmpty()) {
                    val capturedArray = recordedFloats.toFloatArray()
                    val tempFile = File(context.cacheDir, "voiceover_${System.currentTimeMillis()}.wav")
                    DemoMediaGenerator.savePcmToWavFile(capturedArray, sampleRate, tempFile)

                    withContext(Dispatchers.Main) {
                        onRecordedBuffer(capturedArray, tempFile)
                    }
                }
            }
        }
    }

    fun stopRecording() {
        // Flip flag to false so the recording loop exits gracefully and invokes onRecordedBuffer!
        _isRecording.value = false
    }
}
