package com.example.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.data.Surah
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class PlaybackState(
    val currentSurah: Surah? = null,
    val isPlaying: Boolean = false,
    val isPreparing: Boolean = false,
    val progress: Float = 0f,
    val currentPositionMs: Int = 0,
    val durationMs: Int = 0,
    val isOfflineMode: Boolean = false,
    val errorMessage: String? = null
)

object QuranAudioPlayer : TextToSpeech.OnInitListener {
    private const val TAG = "QuranAudioPlayer"

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var meditativeSynth: MeditativeSynth? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var progressJob: Job? = null

    // For offline mode text simulation
    private var offlineDurationMs = 180000 // 3 minutes default for offline simulation
    private var offlinePositionMs = 0

    fun initTts(context: Context) {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(context.applicationContext, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS Language is not supported")
            } else {
                isTtsInitialized = true
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed")
        }
    }

    private fun getLocalRawResourceId(context: Context, surahNumber: Int): Int {
        val possibleNames = listOf(
            "surah_${surahNumber.toString().padStart(3, '0')}",
            "surah_$surahNumber",
            "s${surahNumber.toString().padStart(3, '0')}",
            "s$surahNumber"
        )
        for (name in possibleNames) {
            val resId = context.resources.getIdentifier(name, "raw", context.packageName)
            if (resId != 0) return resId
        }
        return 0
    }

    private fun getLocalAssetPath(context: Context, surahNumber: Int): String? {
        val possiblePaths = listOf(
            "quran/${surahNumber.toString().padStart(3, '0')}.mp3",
            "quran/surah_${surahNumber.toString().padStart(3, '0')}.mp3",
            "quran/surah_$surahNumber.mp3",
            "surah_${surahNumber.toString().padStart(3, '0')}.mp3",
            "surah_$surahNumber.mp3",
            "${surahNumber.toString().padStart(3, '0')}.mp3",
            "$surahNumber.mp3"
        )
        for (path in possiblePaths) {
            try {
                context.assets.open(path).close()
                return path
            } catch (e: Exception) {
                // Not found
            }
        }
        return null
    }

    private fun startLocalPlayback(context: Context, surah: Surah, rawResId: Int, assetPath: String?) {
        scope.launch {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    
                    if (rawResId != 0) {
                        val afd = context.resources.openRawResourceFd(rawResId)
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                    } else if (assetPath != null) {
                        val afd = context.assets.openFd(assetPath)
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                    }

                    setOnPreparedListener { mp ->
                        _state.value = _state.value.copy(
                            isPreparing = false,
                            isPlaying = true,
                            durationMs = mp.duration
                        )
                        mp.start()
                        startProgressTracker(context)
                        triggerService(context, "UPDATE")
                    }
                    setOnCompletionListener {
                        stopAll()
                        triggerService(context, "STOP")
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "Local MediaPlayer Error: what=$what extra=$extra")
                        stopAll()
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start local playback", e)
                // Fall back to synthetic generator or streaming if it fails
                if (_state.value.isOfflineMode) {
                    startOfflinePlayback(context, surah)
                } else {
                    startOnlinePlayback(context, surah)
                }
            }
        }
    }

    fun playSurah(context: Context, surah: Surah, offlineMode: Boolean) {
        stopAll()
        
        val localRawId = getLocalRawResourceId(context, surah.number)
        val localAssetPath = getLocalAssetPath(context, surah.number)
        val hasLocalFile = localRawId != 0 || localAssetPath != null

        _state.value = PlaybackState(
            currentSurah = surah,
            isPreparing = true,
            isOfflineMode = if (hasLocalFile) false else offlineMode
        )

        triggerService(context, "PLAY")

        if (hasLocalFile) {
            startLocalPlayback(context, surah, localRawId, localAssetPath)
        } else if (offlineMode) {
            startOfflinePlayback(context, surah)
        } else {
            startOnlinePlayback(context, surah)
        }
    }

    private fun startOnlinePlayback(context: Context, surah: Surah) {
        scope.launch {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                    setDataSource(surah.audioUrl)
                    setOnPreparedListener { mp ->
                        _state.value = _state.value.copy(
                            isPreparing = false,
                            isPlaying = true,
                            durationMs = mp.duration
                        )
                        mp.start()
                        startProgressTracker(context)
                        triggerService(context, "UPDATE")
                    }
                    setOnCompletionListener {
                        stopAll()
                        triggerService(context, "STOP")
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer Error: what=$what extra=$extra")
                        // Fall back to offline mode gracefully if streaming fails
                        playSurah(context, surah, offlineMode = true)
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start online playback", e)
                playSurah(context, surah, offlineMode = true)
            }
        }
    }

    private fun startOfflinePlayback(context: Context, surah: Surah) {
        offlinePositionMs = 0
        offlineDurationMs = surah.versesCount * 4000 // 4 seconds per verse approximation

        meditativeSynth = MeditativeSynth().apply {
            start()
        }

        _state.value = _state.value.copy(
            isPreparing = false,
            isPlaying = true,
            durationMs = offlineDurationMs,
            currentPositionMs = 0,
            progress = 0f
        )

        // Read out Surah introduction using TTS
        if (isTtsInitialized) {
            textToSpeech?.speak(
                "Reciting Chapter ${surah.number}, ${surah.name}. English translation: ${surah.nameTranslation}.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "surah_intro"
            )
        }

        startProgressTracker(context)
        triggerService(context, "UPDATE")
    }

    fun pause(context: Context) {
        val currentState = _state.value
        if (!currentState.isPlaying) return

        if (currentState.isOfflineMode) {
            meditativeSynth?.pause()
            textToSpeech?.stop()
        } else {
            mediaPlayer?.pause()
        }

        _state.value = currentState.copy(isPlaying = false)
        stopProgressTracker()
        triggerService(context, "PAUSE")
    }

    fun resume(context: Context) {
        val currentState = _state.value
        if (currentState.isPlaying || currentState.currentSurah == null) return

        _state.value = currentState.copy(isPlaying = true)

        if (currentState.isOfflineMode) {
            meditativeSynth?.resume()
            if (isTtsInitialized) {
                textToSpeech?.speak(
                    "Resuming ${currentState.currentSurah.name}.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "surah_resume"
                )
            }
        } else {
            mediaPlayer?.start()
        }

        startProgressTracker(context)
        triggerService(context, "UPDATE")
    }

    fun seekTo(context: Context, positionPercent: Float) {
        val currentState = _state.value
        if (currentState.currentSurah == null) return

        val targetMs = (positionPercent * currentState.durationMs).toInt()

        if (currentState.isOfflineMode) {
            offlinePositionMs = targetMs
            _state.value = currentState.copy(
                currentPositionMs = targetMs,
                progress = positionPercent
            )
        } else {
            mediaPlayer?.seekTo(targetMs)
            _state.value = currentState.copy(
                currentPositionMs = targetMs,
                progress = positionPercent
            )
        }
        triggerService(context, "UPDATE")
    }

    fun stop(context: Context) {
        stopAll()
        triggerService(context, "STOP")
    }

    private fun stopAll() {
        stopProgressTracker()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPlayer", e)
        }
        mediaPlayer = null

        meditativeSynth?.stop()
        meditativeSynth = null

        try {
            textToSpeech?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }

        _state.value = PlaybackState()
    }

    private fun startProgressTracker(context: Context) {
        stopProgressTracker()
        progressJob = scope.launch {
            while (true) {
                delay(1000)
                val currentState = _state.value
                if (!currentState.isPlaying) break

                if (currentState.isOfflineMode) {
                    offlinePositionMs += 1000
                    if (offlinePositionMs >= offlineDurationMs) {
                        stopAll()
                        triggerService(context, "STOP")
                        break
                    } else {
                        val newProgress = offlinePositionMs.toFloat() / offlineDurationMs
                        _state.value = currentState.copy(
                            currentPositionMs = offlinePositionMs,
                            progress = newProgress
                        )
                    }
                } else {
                    mediaPlayer?.let { mp ->
                        if (mp.isPlaying) {
                            val pos = mp.currentPosition
                            val dur = mp.duration
                            val newProgress = if (dur > 0) pos.toFloat() / dur else 0f
                            _state.value = currentState.copy(
                                currentPositionMs = pos,
                                durationMs = dur,
                                progress = newProgress
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun triggerService(context: Context, action: String) {
        try {
            val intent = Intent(context, QuranAudioService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action != "STOP") {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service with action $action", e)
        }
    }

    fun release() {
        stopAll()
        try {
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TTS", e)
        }
        textToSpeech = null
    }

    // Helper med meditative synthesizer class
    private class MeditativeSynth {
        private var audioTrack: AudioTrack? = null
        private var isPlaying = false
        private var isPaused = false
        private var thread: Thread? = null

        fun start() {
            if (isPlaying) return
            isPlaying = true
            isPaused = false
            val sampleRate = 44100
            val minSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            try {
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minSize,
                    AudioTrack.MODE_STREAM
                )
                audioTrack?.play()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack init failed", e)
                return
            }

            thread = Thread {
                val samples = ShortArray(1024)
                var angle = 0.0
                val frequency = 136.1 // calming Ohm tone
                val pulseFreq = 0.15 // calming rhythm pulse
                var pulseAngle = 0.0

                while (isPlaying) {
                    if (isPaused) {
                        try {
                            Thread.sleep(100)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    }

                    for (i in samples.indices) {
                        val pulse = 0.7 + 0.3 * kotlin.math.sin(pulseAngle)
                        samples[i] = (kotlin.math.sin(angle) * 8000 * pulse).toInt().toShort()
                        angle += 2.0 * Math.PI * frequency / sampleRate
                        pulseAngle += 2.0 * Math.PI * pulseFreq / sampleRate

                        // Keep angles bound to avoid overflows
                        if (angle > 2.0 * Math.PI) angle -= 2.0 * Math.PI
                        if (pulseAngle > 2.0 * Math.PI) pulseAngle -= 2.0 * Math.PI
                    }
                    audioTrack?.write(samples, 0, samples.size)
                }
            }
            thread?.start()
        }

        fun pause() {
            isPaused = true
            try {
                audioTrack?.pause()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack pause failed", e)
            }
        }

        fun resume() {
            isPaused = false
            try {
                audioTrack?.play()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack resume failed", e)
            }
        }

        fun stop() {
            isPlaying = false
            isPaused = false
            thread?.interrupt()
            try {
                thread?.join(1000)
            } catch (e: Exception) {
                // Ignore
            }
            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                // Ignore
            }
            audioTrack = null
        }
    }
}
