package com.xingang.ttsreader

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Process-wide reader: owns the TextToSpeech engine and the playback state,
 * which the phone UI, the notification and the Android Auto screens all
 * observe. Speaks one sentence per utterance so the current sentence is
 * always known and can be highlighted.
 */
object ReaderEngine {

    data class State(
        val passageId: String? = null,
        val title: String = "",
        val sentences: List<Sentence> = emptyList(),
        val index: Int = 0,
        val playing: Boolean = false,
        val rate: Float = 1.0f,
        val shuffle: Boolean = false,
        val speakMode: SpeakMode = SpeakMode.FULL,
        val ready: Boolean = false,
        val error: String? = null,
    ) {
        val current: Sentence? get() = sentences.getOrNull(index)
        val hasContent: Boolean get() = sentences.isNotEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    private lateinit var appContext: Context
    private lateinit var library: Library
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    /** The English voice chosen by the user, re-applied after each switch back from Chinese. */
    private var englishVoice: Voice? = null
    /** Utterance ids are "<passageId>#<index>"; ignore callbacks from stale ones. */
    private var speakingId: String? = null
    /** Consecutive failures of the current utterance; the audio path can hiccup on car heads. */
    private var retries = 0
    /** An output error is followed by a spurious focus loss; ignore losses until this time. */
    private var ignoreFocusLossUntil = 0L
    /** Which part of the current sentence (word / 中文 / example) is being spoken. */
    private var partIndex = 0
    /** Whether the engine can speak Chinese; when it cannot, those parts are skipped. */
    private var chineseAvailable = false
    /** Language currently loaded into the engine, to avoid redundant switches. */
    private var currentLocale: Locale? = null
    /** Reading order: the identity permutation, or a shuffled one when [State.shuffle] is on. */
    private var order: List<Int> = emptyList()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        library = Library(appContext)
        _state.value = _state.value.copy(
            rate = library.speechRate, shuffle = library.shuffle, speakMode = library.speakMode,
        )
        createEngine()
    }

    /** Engines installed on the device (system default first). */
    fun engines(): List<TextToSpeech.EngineInfo> = tts?.engines ?: emptyList()

    /** English voices of the current engine, best quality first. */
    fun voices(): List<Voice> = try {
        (tts?.voices ?: emptySet())
            .filter { it.locale.language == "en" }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
    } catch (e: Exception) { emptyList() }

    fun currentEngine(): String? = library.ttsEngine ?: tts?.defaultEngine
    fun currentVoice(): String? = tts?.voice?.name

    /** Switch engine (package name, null = system default); re-creates the TTS object. */
    fun setEngine(packageName: String?) {
        library.ttsEngine = packageName
        library.ttsVoice = null
        val wasPlaying = _state.value.playing
        stopSpeaking()
        tts?.shutdown()
        _state.value = _state.value.copy(ready = false, playing = wasPlaying)
        createEngine()
    }

    fun setVoice(name: String) {
        val v = voices().firstOrNull { it.name == name } ?: return
        library.ttsVoice = name
        tts?.voice = v
        if (_state.value.playing) { stopSpeaking(); speakCurrent() }
    }

    private fun createEngine() {
        val preferred = library.ttsEngine
        val listener = TextToSpeech.OnInitListener { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@post
                    val res = engine.setLanguage(Locale.US)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        _state.value = _state.value.copy(error = "TTS 引擎缺少英语语音包")
                    }
                    library.ttsVoice?.let { name -> voices().firstOrNull { it.name == name }?.let { engine.voice = it } }
                    englishVoice = engine.voice
                    currentLocale = Locale.US
                    chineseAvailable = try {
                        engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
                    } catch (e: Exception) { false }
                    Log.d(TAG, "chinese available=$chineseAvailable")
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    engine.setSpeechRate(_state.value.rate)
                    engine.setOnUtteranceProgressListener(progressListener)
                    _state.value = _state.value.copy(ready = true)
                    Log.d(TAG, "tts ready, engine=${engine.defaultEngine}")
                    if (_state.value.playing) speakCurrent()
                } else {
                    _state.value = _state.value.copy(error = "TTS 引擎初始化失败")
                }
            }
        }
        tts = if (preferred != null) TextToSpeech(appContext, listener, preferred) else TextToSpeech(appContext, listener)
    }

    // ---- commands (main thread) -------------------------------------------

    /** Load a passage and start from its remembered position (or [startIndex]). */
    fun load(passage: Passage, startIndex: Int = library.position(passage.id), autoPlay: Boolean = true) {
        val sentences = Sentences.split(passage.body)
        val index = startIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        stopSpeaking()
        partIndex = 0
        _state.value = _state.value.copy(
            passageId = passage.id, title = passage.title, sentences = sentences, index = index, playing = false,
        )
        rebuildOrder()
        if (autoPlay) play()
    }

    fun play() {
        val s = _state.value
        if (!s.hasContent) return
        val index = if (s.index >= s.sentences.size) 0 else s.index
        if (index != s.index) partIndex = 0
        _state.value = s.copy(index = index, playing = true, error = null)
        ReaderService.start(appContext)
        appContext.startService(Intent(appContext, ReaderMediaService::class.java))
        requestFocus()
        speakCurrent()
    }

    fun pause() {
        stopSpeaking()
        _state.value = _state.value.copy(playing = false)
        abandonFocus()
        ReaderService.stopForegroundIfIdle(appContext)
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun seek(index: Int) {
        val s = _state.value
        if (!s.hasContent) return
        val i = index.coerceIn(0, s.sentences.size - 1)
        stopSpeaking()
        partIndex = 0
        _state.value = s.copy(index = i)
        s.passageId?.let { library.savePosition(it, i) }
        if (s.playing) speakCurrent()
    }

    fun next() = step(+1)
    fun previous() = step(-1)

    /** The entry that follows [index] in the reading order, or null at the end. */
    private fun nextInOrder(index: Int): Int? {
        if (order.size != _state.value.sentences.size) rebuildOrder()
        val pos = order.indexOf(index)
        return if (pos >= 0 && pos + 1 < order.size) order[pos + 1] else null
    }

    /** Move [delta] places along the reading order (shuffled or not). */
    private fun step(delta: Int) {
        val s = _state.value
        if (!s.hasContent) return
        if (order.size != s.sentences.size) rebuildOrder()
        val pos = order.indexOf(s.index).takeIf { it >= 0 } ?: 0
        val target = pos + delta
        if (target < 0 || target >= order.size) return
        seek(order[target])
    }

    /** Switch between reading in order and in random order. */
    fun setShuffle(on: Boolean) {
        library.shuffle = on
        _state.value = _state.value.copy(shuffle = on)
        rebuildOrder()
    }

    fun toggleShuffle() = setShuffle(!_state.value.shuffle)

    /**
     * Rebuild the reading order. In shuffle mode the current entry stays first
     * so toggling does not jump away from what is being read.
     */
    private fun rebuildOrder() {
        val s = _state.value
        val n = s.sentences.size
        order = if (!s.shuffle) {
            List(n) { it }
        } else {
            val rest = (0 until n).filter { it != s.index }.shuffled()
            if (n == 0) emptyList() else listOf(s.index) + rest
        }
    }

    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.5f, 2.0f)
        library.speechRate = r
        tts?.setSpeechRate(r)
        _state.value = _state.value.copy(rate = r)
        if (_state.value.playing) { stopSpeaking(); speakCurrent() }
    }

    // ---- speaking -----------------------------------------------------------

    private fun speakCurrent() {
        val s = _state.value
        val engine = tts ?: return
        if (!s.ready || !s.playing) return
        val sentence = s.current ?: run { finish(); return }
        val parts = partsOf(sentence)
        if (partIndex >= parts.size) partIndex = 0
        val part = parts.getOrNull(partIndex) ?: run { finish(); return }
        val text = part.text
        // Switching locale resets the selected voice, so re-apply it for English.
        if (currentLocale != part.locale) {
            engine.setLanguage(part.locale)
            currentLocale = part.locale
            if (part.locale.language == "en") englishVoice?.let { engine.voice = it }
        }
        val id = "${s.passageId}#${s.index}.$partIndex"
        speakingId = id
        s.passageId?.let { library.savePosition(it, s.index) }
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        Log.d(TAG, "speak $id -> $r ready=${s.ready}")
        if (r != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak failed for $id (retry $retries)")
            if (retries < MAX_RETRIES) {
                retries++
                main.postDelayed({ if (speakingId == id && _state.value.playing) speakCurrent() }, 1_500)
            } else {
                retries = 0
                _state.value = s.copy(playing = false, error = "朗读失败")
                abandonFocus()
            }
            return
        }
        // Watchdog: some audio paths swallow an utterance without any callback
        // (seen on the Automotive emulator right after start-up). Re-issue it.
        val units = if (part.locale.language == "zh") text.length else text.split(' ').size
        val budget = (units * 700L / s.rate).toLong().coerceAtLeast(8_000L) + 4_000L
        main.postDelayed({
            if (speakingId == id && _state.value.playing) {
                Log.w(TAG, "utterance $id timed out after $budget ms; retrying")
                if (retries < MAX_RETRIES) {
                    retries++
                    engine.stop()
                    speakCurrent()
                } else {
                    retries = 0
                    _state.value = _state.value.copy(playing = false, error = "朗读无响应")
                    abandonFocus()
                }
            }
        }, budget)
    }

    /** Utterances for one entry under the current mode, dropping Chinese if the engine lacks it. */
    private fun partsOf(sentence: Sentence): List<SpokenPart> {
        val parts = sentence.parts(_state.value.speakMode)
            .filter { chineseAvailable || it.locale.language != "zh" }
        // A Chinese-only mode on an engine without Chinese would be silent; fall back to the word.
        return parts.ifEmpty { listOf(SpokenPart(sentence.headline, java.util.Locale.US)) }
    }

    /** Switch what gets read aloud for each entry. */
    fun setSpeakMode(mode: SpeakMode) {
        library.speakMode = mode
        _state.value = _state.value.copy(speakMode = mode)
        partIndex = 0
        if (_state.value.playing) { stopSpeaking(); speakCurrent() }
    }

    /**
     * File the entry at [index] into 已掌握 / 未掌握 and drop it from the passage
     * being read. Returns the entry so the caller can offer an undo.
     */
    fun fileEntry(index: Int, bucket: Bucket): Sentence? {
        val s = _state.value
        val sentence = s.sentences.getOrNull(index) ?: return null
        val passageId = s.passageId ?: return null
        library.moveEntry(passageId, sentence, bucket)
        val rest = s.sentences.toMutableList().apply { removeAt(index) }
        val wasCurrent = index == s.index
        val newIndex = when {
            rest.isEmpty() -> 0
            index < s.index -> s.index - 1
            else -> s.index.coerceAtMost(rest.size - 1)
        }
        if (wasCurrent) stopSpeaking()
        _state.value = s.copy(sentences = rest, index = newIndex)
        rebuildOrder()
        if (rest.isEmpty()) {
            _state.value = _state.value.copy(playing = false)
        } else if (wasCurrent && s.playing) {
            partIndex = 0
            speakCurrent()
        }
        return sentence
    }

    /** Undo a filing: put the entry back and reload the passage. */
    fun unfile(sentence: Sentence) {
        library.unfile(sentence.key)
        val s = _state.value
        val id = s.passageId ?: return
        library.find(id)?.let { load(it, startIndex = s.index, autoPlay = s.playing) }
    }

    private fun stopSpeaking() {
        speakingId = null
        tts?.stop()
    }

    private fun finish() {
        _state.value = _state.value.copy(playing = false)
        abandonFocus()
        ReaderService.stopForegroundIfIdle(appContext)
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) { Log.d(TAG, "utterance start $utteranceId") }

        override fun onDone(utteranceId: String?) {
            Log.d(TAG, "utterance done $utteranceId")
            main.post {
                if (utteranceId != speakingId) return@post
                retries = 0
                val s = _state.value
                if (!s.playing) return@post
                val parts = s.current?.let { partsOf(it) } ?: emptyList()
                if (partIndex + 1 < parts.size) {
                    // More parts of this entry: word -> 中文释义 -> example.
                    partIndex++
                    speakCurrent()
                } else if (nextInOrder(s.index) != null) {
                    partIndex = 0
                    _state.value = s.copy(index = nextInOrder(s.index)!!)
                    speakCurrent()
                } else {
                    // Reached the end: stop and rewind so the next play starts over.
                    partIndex = 0
                    val first = order.firstOrNull() ?: 0
                    _state.value = s.copy(playing = false, index = first)
                    s.passageId?.let { library.savePosition(it, first) }
                    abandonFocus()
                    ReaderService.stopForegroundIfIdle(appContext)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = onError(utteranceId, -1)

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.w(TAG, "utterance error $utteranceId code=$errorCode")
            main.post {
                if (utteranceId != speakingId) return@post
                if (retries < MAX_RETRIES && _state.value.playing) {
                    // Transient output errors happen right after start-up on some heads; try again.
                    // The system also reports a focus loss right after such an error: not a real one.
                    retries++
                    ignoreFocusLossUntil = System.currentTimeMillis() + 3_000
                    main.postDelayed({
                        if (speakingId == utteranceId && _state.value.playing) { requestFocus(); speakCurrent() }
                    }, 1_500)
                    return@post
                }
                retries = 0
                _state.value = _state.value.copy(playing = false, error = "朗读出错 ($errorCode)")
                abandonFocus()
            }
        }
    }

    // ---- audio focus ----------------------------------------------------------

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.d(TAG, "audio focus change $change")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                if (System.currentTimeMillis() < ignoreFocusLossUntil) {
                    Log.d(TAG, "ignoring focus loss right after an output error")
                } else if (_state.value.playing) {
                    pause()
                }
        }
    }

    private fun requestFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
            .also { focusRequest = it }
        val r = am.requestAudioFocus(req)
        Log.d(TAG, "requestAudioFocus -> $r")
    }

    private fun abandonFocus() {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
    }

    /** Open the phone reading screen for the current passage. */
    fun openIntent(context: Context): Intent =
        Intent(context, ReadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val TAG = "TtsReader"
    private const val MAX_RETRIES = 2
}
