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
    /** Whether the engine can speak Chinese; when it cannot, those parts are skipped. */
    private var chineseAvailable = false
    /** Language currently loaded into the engine, to avoid redundant switches. */
    private var currentLocale: Locale? = null
    /** Reading order: the identity permutation, or a shuffled one when [State.shuffle] is on. */
    private var order: List<Int> = emptyList()
    /** Makes each utterance id unique, so a stale callback cannot match a re-read of the same part. */
    private var utteranceSeq = 0
    /** Consecutive failures to bind the TTS service. */
    private var initAttempts = 0
    /** Token for the retry and watchdog callbacks of the current entry. */
    private val speakToken = Any()
    /** Batch number of the utterances now queued; stale callbacks carry an older one. */
    private var speakingSeq = 0

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
        // Switching to Chinese clears the voice, and speakCurrent() restores it
        // from englishVoice; without this the next switch back would undo the choice.
        englishVoice = v
        if (_state.value.playing) { stopSpeaking(); speakCurrent() }
    }

    private fun createEngine() {
        try { tts?.shutdown() } catch (e: Exception) { /* the old client may already be dead */ }
        tts = null
        val preferred = library.ttsEngine
        var created: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    val engine = created ?: return@post
                    // A newer client has replaced this one; let that one configure itself.
                    if (engine !== tts) return@post
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
                    initAttempts = 0
                    _state.value = _state.value.copy(ready = true)
                    Log.d(TAG, "tts ready, engine=${engine.defaultEngine}")
                    if (_state.value.playing) speakCurrent()
                } else {
                    // Binding fails while the engine app is being updated, or after
                    // the system has frozen it. Back off and try again rather than
                    // leaving the reader permanently mute.
                    Log.w(TAG, "tts init failed (attempt $initAttempts)")
                    if (initAttempts < MAX_INIT_ATTEMPTS) {
                        initAttempts++
                        main.postDelayed({ createEngine() }, 2_000L * initAttempts)
                    } else {
                        _state.value = _state.value.copy(error = "TTS 引擎初始化失败")
                    }
                }
            }
        }
        created = if (preferred != null) TextToSpeech(appContext, listener, preferred) else TextToSpeech(appContext, listener)
        tts = created
    }

    /**
     * Build a fresh TextToSpeech client after the link to the engine process is
     * lost. [createEngine] resumes playback once the new client reports ready.
     */
    private fun rebuildEngine() {
        speakingId = null
        main.removeCallbacksAndMessages(speakToken)
        currentLocale = null
        englishVoice = null
        _state.value = _state.value.copy(ready = false)
        createEngine()
    }

    // ---- commands (main thread) -------------------------------------------

    /** Load a passage and start from its remembered position (or [startIndex]). */
    fun load(passage: Passage, startIndex: Int = library.position(passage.id), autoPlay: Boolean = true) {
        val sentences = Sentences.split(passage.body)
        val index = startIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        stopSpeaking()
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

    /**
     * Queue every part of the current entry at once (word, 中文释义, example).
     * The engine then plays them back to back; waiting for each callback before
     * issuing the next one adds an audible pause between them.
     */
    private fun speakCurrent() {
        main.removeCallbacksAndMessages(speakToken)
        val s = _state.value
        val engine = tts ?: return
        if (!s.ready || !s.playing) return
        val sentence = s.current ?: run { finish(); return }
        val parts = partsOf(sentence)
        if (parts.isEmpty()) { finish(); return }
        val seq = ++utteranceSeq
        speakingSeq = seq
        s.passageId?.let { library.savePosition(it, s.index) }
        var mode = TextToSpeech.QUEUE_FLUSH
        var last: String? = null
        for ((i, part) in parts.withIndex()) {
            // The language travels with each queued utterance, so switching here
            // affects only the part being queued. Switching also drops the chosen
            // voice, which has to be re-applied for English.
            if (currentLocale != part.locale) {
                engine.setLanguage(part.locale)
                currentLocale = part.locale
                if (part.locale.language == "en") englishVoice?.let { engine.voice = it }
            }
            val id = "${s.passageId}#${s.index}.$i@$seq"
            val r = engine.speak(part.text, mode, null, id)
            Log.d(TAG, "speak $id -> $r")
            if (r != TextToSpeech.SUCCESS) {
                // speak() only fails synchronously when the link to the engine process
                // is gone, and the client never reconnects by itself, so retrying on it
                // would fail just as surely. Build a new one instead.
                Log.w(TAG, "speak failed for $id (attempt $retries); rebuilding engine")
                if (retries < MAX_RETRIES) {
                    retries++
                    rebuildEngine()
                } else {
                    retries = 0
                    _state.value = s.copy(playing = false, error = "朗读失败")
                    abandonFocus()
                }
                return
            }
            mode = TextToSpeech.QUEUE_ADD
            last = id
        }
        // Only the last part signals that the entry is finished.
        speakingId = last
        // Watchdog: some audio paths swallow an utterance without any callback
        // (seen on the Automotive emulator right after start-up). Re-issue it.
        val units = parts.sumOf { p ->
            if (p.locale.language == "zh") p.text.length else p.text.split(' ').size
        }
        val budget = (units * 700L / s.rate).toLong().coerceAtLeast(8_000L) + 4_000L
        main.postDelayed({
            if (speakingId == last && _state.value.playing) {
                Log.w(TAG, "entry $last timed out after $budget ms; retrying")
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
        }, speakToken, budget)
    }

    /** Whether [id] belongs to the batch of utterances now queued. */
    private fun inCurrentBatch(id: String?) = id != null && id.endsWith("@$speakingSeq")

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
        main.removeCallbacksAndMessages(speakToken)
        try { tts?.stop() } catch (e: Exception) { /* the client may already be dead */ }
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
                // Earlier parts of the entry need no action: the rest is already queued.
                if (utteranceId != speakingId) return@post
                retries = 0
                val s = _state.value
                if (!s.playing) return@post
                val next = nextInOrder(s.index)
                if (next != null) {
                    _state.value = s.copy(index = next)
                    speakCurrent()
                } else {
                    // Reached the end: stop and rewind so the next play starts over.
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
                if (!inCurrentBatch(utteranceId)) return@post
                if (retries < MAX_RETRIES && _state.value.playing) {
                    // Transient output errors happen right after start-up on some heads; try again.
                    // The system also reports a focus loss right after such an error: not a real one.
                    retries++
                    ignoreFocusLossUntil = System.currentTimeMillis() + 3_000
                    main.postDelayed({
                        if (inCurrentBatch(utteranceId) && _state.value.playing) { requestFocus(); speakCurrent() }
                    }, speakToken, 1_500)
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

    private fun requestFocus(): Boolean {
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
        val granted = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        // Speaking without focus often still works, but it can come out silent;
        // say so rather than showing a playing state the user cannot hear.
        if (!granted) _state.value = _state.value.copy(error = "未取得音频焦点，可能听不到声音")
        return granted
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
    private const val MAX_INIT_ATTEMPTS = 3
}
