package com.wesal.messenger.voice

import android.media.MediaPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class VoicePlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

@Singleton
class VoicePlayerManager @Inject constructor() {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    fun togglePlay(messageId: String, audioFile: File) {
        val current = _playbackState.value
        if (current.activeMessageId == messageId && mediaPlayer != null) {
            if (current.isPlaying) {
                mediaPlayer?.pause()
                _playbackState.value = current.copy(isPlaying = false)
            } else {
                mediaPlayer?.start()
                _playbackState.value = current.copy(isPlaying = true)
                startProgressLoop()
            }
            return
        }

        stop()
        runCatching {
            val mp = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepare()
                setOnCompletionListener {
                    stop()
                }
                start()
            }
            mediaPlayer = mp
            _playbackState.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(1)
            )
            startProgressLoop()
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.let {
            it.seekTo(positionMs)
            _playbackState.value = _playbackState.value.copy(positionMs = positionMs)
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                _playbackState.value = _playbackState.value.copy(
                    positionMs = mediaPlayer?.currentPosition ?: 0,
                    durationMs = mediaPlayer?.duration?.coerceAtLeast(1) ?: 1
                )
                delay(100L)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        _playbackState.value = VoicePlaybackState()
    }
}
