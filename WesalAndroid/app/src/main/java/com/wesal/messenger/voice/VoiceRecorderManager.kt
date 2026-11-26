package com.wesal.messenger.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitudes: List<Int> = emptyList(),
    val outputFile: File? = null
)

@Singleton
class VoiceRecorderManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var timerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(VoiceRecordingState())
    val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

    fun startRecording(): Boolean {
        if (_state.value.isRecording) return false
        return runCatching {
            val dir = File(context.filesDir, "voice").apply { mkdirs() }
            val file = File(dir, "voice_${UUID.randomUUID()}.m4a")
            currentFile = file

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                // Speech-grade AAC (about 4 KB/s) so a voice note fits the 540 KB Firestore message limit for ~2 minutes.
                setAudioChannels(1)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setMaxDuration(120_000)
                setMaxFileSize(500_000L)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = mr

            val startTs = System.currentTimeMillis()
            val samples = mutableListOf<Int>()
            _state.value = VoiceRecordingState(
                isRecording = true,
                elapsedMs = 0L,
                amplitudes = emptyList(),
                outputFile = file
            )

            timerJob = scope.launch {
                while (isActive) {
                    delay(120L)
                    val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = ((amp / 32767f) * 100f).toInt().coerceIn(8, 100)
                    samples.add(normalized)
                    if (samples.size > 48) samples.removeAt(0)
                    _state.value = VoiceRecordingState(
                        isRecording = true,
                        elapsedMs = System.currentTimeMillis() - startTs,
                        amplitudes = samples.toList(),
                        outputFile = file
                    )
                }
            }
            true
        }.getOrElse {
            cleanupRecorder()
            false
        }
    }

    fun stopRecording(): VoiceRecordingState? {
        if (!_state.value.isRecording) return null
        timerJob?.cancel()
        val finalState = _state.value.copy(isRecording = false)
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        _state.value = VoiceRecordingState()
        return if (finalState.outputFile?.exists() == true && finalState.elapsedMs >= 500L) {
            finalState
        } else {
            finalState.outputFile?.delete()
            null
        }
    }

    fun cancelRecording() {
        timerJob?.cancel()
        cleanupRecorder()
        currentFile?.delete()
        currentFile = null
        _state.value = VoiceRecordingState()
    }

    private fun cleanupRecorder() {
        runCatching {
            recorder?.reset()
            recorder?.release()
        }
        recorder = null
    }
}
