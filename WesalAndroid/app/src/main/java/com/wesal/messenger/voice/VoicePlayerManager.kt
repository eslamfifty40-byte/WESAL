package com.wesal.messenger.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val durationMs: Int = 0,
    val volume: Float = 1.0f,
    /** Last playback failure, surfaced by the UI instead of failing silently. */
    val error: String? = null
)

@Singleton
class VoicePlayerManager @Inject constructor(
    @ApplicationContext context: Context
) {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { }

    private val _playbackState = MutableStateFlow(VoicePlaybackState())
    val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

    private fun requestAudioFocus() {
        runCatching {
            audioManager?.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        runCatching { audioManager?.abandonAudioFocus(focusChangeListener) }
    }

    fun togglePlay(messageId: String, audioFile: File) {
        val current = _playbackState.value
        if (current.activeMessageId == messageId && mediaPlayer != null) {
            if (current.isPlaying) {
                mediaPlayer?.pause()
                _playbackState.value = current.copy(isPlaying = false)
            } else {
                mediaPlayer?.start()
                _playbackState.value = current.copy(isPlaying = true, error = null)
                startProgressLoop()
            }
            return
        }

        val preservedVolume = current.volume
        stop()
        runCatching {
            val mp = MediaPlayer().apply {
                // Without explicit media attributes + audio focus the player inherits
                // whatever audio mode the device is in (e.g. a call's communication
                // mode), which can route playback to the earpiece at call volume —
                // perceptually "no sound". USAGE_MEDIA keeps it on the media stream.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    _playbackState.value = VoicePlaybackState(
                        volume = preservedVolume,
                        error = PLAYBACK_ERROR_MESSAGE
                    )
                    true
                }
                prepare()
                setVolume(preservedVolume, preservedVolume)
                setOnCompletionListener {
                    stop()
                }
                start()
            }
            mediaPlayer = mp
            requestAudioFocus()
            _playbackState.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(1),
                volume = preservedVolume
            )
            startProgressLoop()
        }.onFailure {
            // Previously this failure was swallowed whole: prepare() throwing on a
            // corrupt/partial/wrong-format file made the tap do nothing at all —
            // no sound, no message. Surface it in the playback state instead.
            releasePlayer()
            _playbackState.value = VoicePlaybackState(
                volume = preservedVolume,
                error = PLAYBACK_ERROR_MESSAGE
            )
        }
    }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.let { mp ->
            val clamped = positionMs.coerceIn(0, mp.duration.coerceAtLeast(1))
            mp.seekTo(clamped)
            _playbackState.value = _playbackState.value.copy(positionMs = clamped)
        }
    }

    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        runCatching { mediaPlayer?.setVolume(clamped, clamped) }
        _playbackState.value = _playbackState.value.copy(volume = clamped)
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

    private fun releasePlayer() {
        progressJob?.cancel()
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        abandonAudioFocus()
    }

    fun stop() {
        releasePlayer()
        _playbackState.value = VoicePlaybackState(volume = _playbackState.value.volume)
    }

    private companion object {
        const val PLAYBACK_ERROR_MESSAGE = "تعذر تشغيل الملف الصوتي"
    }
}
