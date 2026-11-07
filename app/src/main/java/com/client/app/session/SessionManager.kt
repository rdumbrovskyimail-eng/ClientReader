package com.client.app.session

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.api.*
import com.client.app.attach.AnalysisResult
import com.client.app.attach.VocabItem
import com.client.app.attach.VocabularyExtractor
import com.client.app.audio.AudioFocusEvent
import com.client.app.audio.AudioStreamControlEvent
import com.client.app.audio.AudioStreamDataEvent
import com.client.app.audio.BargeInMode
import com.client.app.audio.CaptureShutdownResult
import com.client.app.audio.MicEvent
import com.client.app.audio.NativeAudioEngine
import com.client.app.audio.PlaybackHoldOwner
import com.client.app.audio.PronunciationPlayer
import com.client.app.forvo.ForvoRepository
import com.client.app.forvo.ForvoResult
import com.client.app.reader.AudioDecision
import com.client.app.reader.LiveReaderController
import com.client.app.reader.PauseReason
import com.client.app.reader.ReaderAudioPort
import com.client.app.reader.ReaderCommand
import com.client.app.reader.ReaderHost
import com.client.app.reader.ReaderPace
import com.client.app.reader.ReaderProgressStore
import com.client.app.reader.ReaderPrompts
import com.client.app.reader.ReaderTransportPort
import com.client.app.reader.ReaderUiState
import com.client.app.service.LiveSessionForegroundService
import com.client.app.util.AppLogger
import com.client.app.util.AttachmentProcessor
import com.client.app.util.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private val idGen = AtomicLong(0)

data class ChatMessage(
    val id: Long = idGen.incrementAndGet(),
    val role: ClientRole,
    val text: String,
    val attachmentNames: List<String> = emptyList(),
    val modelReplayText: String? = null,
    val interim: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ForvoWord(
    val word: String,
    val query: String = word,
    val language: String = "de",
    val translation: String? = null,
    val audioUrl: String? = null,
    val isLoading: Boolean = true,
    val notFound: Boolean = false
)

enum class LinkState { IDLE, CONNECTING, LIVE, RECONNECTING }

/** Профиль Live-сессии: разговор с ассистентом или диктор-читалка (своя системная инструкция и настройки). */
enum class SessionProfile { CONVERSATION, READER }

data class SessionState(
    val link: LinkState = LinkState.IDLE,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val isAnalyzing: Boolean = false,
    val activePrompt: String = "",
    val error: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val forvoWords: List<ForvoWord> = emptyList(),
    val forvoUsed: Int = 0,
    val forvoLimit: Int = 500,
    val tokensUsed: Int = 0,
    val profile: SessionProfile = SessionProfile.CONVERSATION,
    val reader: ReaderUiState = ReaderUiState()
) {
    val isConnected: Boolean get() = link == LinkState.LIVE
    val isConnecting: Boolean get() = link == LinkState.CONNECTING || link == LinkState.RECONNECTING
}

data class ToolCallKey(
    val sessionId: Long,
    val epoch: Long,
    val callId: String
)

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GeminiProtobufLiveClient,
    private val audioEngine: NativeAudioEngine,
    private val forvoRepo: ForvoRepository,
    private val forvoPlayer: PronunciationPlayer,
    private val attachmentProcessor: AttachmentProcessor,
    private val extractor: VocabularyExtractor,
    private val dataStore: DataStore<Preferences>,
    private val cryptoManager: CryptoManager,
    private val logger: AppLogger
) {
    companion object {
        val KEY_API = stringPreferencesKey("gemini_api_key")
        val KEY_LIVE_MODEL = stringPreferencesKey("gemini_live_model")
        val KEY_THINKING_LEVEL = stringPreferencesKey("gemini_thinking_level")
        val KEY_ANALYZER_MODEL = stringPreferencesKey("analyzer_model")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("gemini_system_prompt")
        val KEY_VOICE = stringPreferencesKey("gemini_voice")
        val KEY_SPEECH_LANGUAGE = stringPreferencesKey("gemini_speech_language")
        val KEY_TEMPERATURE = floatPreferencesKey("gemini_temperature")
        val KEY_MEDIA_RESOLUTION = stringPreferencesKey("gemini_media_resolution")

        val KEY_INPUT_TRANSCRIPTION_ENABLED = booleanPreferencesKey("gemini_input_tx_enabled")
        val KEY_INPUT_TRANSCRIPTION_LANGUAGES = stringPreferencesKey("gemini_input_tx_languages")
        val KEY_INPUT_TRANSCRIPTION_VOCAB = stringPreferencesKey("gemini_input_tx_vocab")
        val KEY_INPUT_TRANSCRIPTION_MODE = stringPreferencesKey("gemini_input_tx_mode")

        val KEY_OUTPUT_TRANSCRIPTION_ENABLED = booleanPreferencesKey("gemini_output_tx_enabled")
        val KEY_OUTPUT_TRANSCRIPTION_LANGUAGES = stringPreferencesKey("gemini_output_tx_languages")
        val KEY_OUTPUT_TRANSCRIPTION_VOCAB = stringPreferencesKey("gemini_output_tx_vocab")
        val KEY_OUTPUT_TRANSCRIPTION_MODE = stringPreferencesKey("gemini_output_tx_mode")

        // v3: ручной VAD (activityStart/activityEnd) по умолчанию — сервер отвечает сразу по activityEnd.
        // В v2 был гибридный режим: сервер не получал тишину после фразы, не видел её конца и молчал.
        val KEY_AAD_ENABLED = booleanPreferencesKey("gemini_aad_enabled_v3")
        val KEY_AAD_START_SENSITIVITY = stringPreferencesKey("gemini_aad_start_sensitivity")
        val KEY_AAD_END_SENSITIVITY = stringPreferencesKey("gemini_aad_end_sensitivity")
        val KEY_PREFIX_PADDING_MS = intPreferencesKey("gemini_prefix_padding_ms")
        val KEY_SILENCE_DURATION_MS = intPreferencesKey("gemini_silence_duration_ms")
        val KEY_BT_HIFI_MODE = booleanPreferencesKey("bt_hifi_mode")
        val KEY_ACTIVITY_HANDLING = stringPreferencesKey("gemini_activity_handling")
        val KEY_TURN_COVERAGE = stringPreferencesKey("gemini_turn_coverage")

        val KEY_COMPRESSION_ENABLED = booleanPreferencesKey("gemini_compression_enabled")
        val KEY_COMPRESSION_TRIGGER_TOKENS = intPreferencesKey("gemini_compression_trigger_tokens")
        val KEY_COMPRESSION_TARGET_TOKENS = intPreferencesKey("gemini_compression_target_tokens")

        val KEY_SESSION_RESUMPTION_ENABLED = booleanPreferencesKey("gemini_session_resumption_enabled")
        val KEY_SESSION_RESUMPTION_HANDLE = stringPreferencesKey("gemini_session_resumption_handle")
        val KEY_SESSION_RESUMPTION_TIMESTAMP = longPreferencesKey("gemini_resumption_timestamp")

        val KEY_INITIAL_HISTORY_TURNS = intPreferencesKey("gemini_initial_history_turns")

        val KEY_ENABLE_FORVO = booleanPreferencesKey("enable_forvo")
        val KEY_ENABLE_SEARCH = booleanPreferencesKey("enable_search_v2")
        val KEY_VOLUME = floatPreferencesKey("audio_volume")
        val KEY_MIC_GAIN = floatPreferencesKey("audio_mic_gain")

        // Live-читалка
        val KEY_READER_PACE = stringPreferencesKey("reader_pace")
        val KEY_READER_VOICE_COMMANDS = booleanPreferencesKey("reader_voice_commands")

        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — интеллектуальный персональный голосовой ассистент с академической культурой речи. " +
            "Отвечай лаконично, точно и структурированно, без шаблонных вводных слов."

        const val DEFAULT_LIVE_MODEL = "gemini-3.8-live"
        const val READER_LIVE_MODEL = "gemini-3.8-live"

        // Мужской голос диктора: «Informative», рекомендован для повествования и документальной подачи
        const val DEFAULT_VOICE = "Charon"
        const val DEFAULT_SILENCE_DURATION_MS = 700
        // Hi-Fi Bluetooth по умолчанию: A2DP 48 кГц стерео вместо узкополосного HFP 16 кГц
        const val DEFAULT_BT_HIFI = true

        // Читалке не нужна длинная история: каждый фрагмент самодостаточен, а весь контекст
        // перевыставляется в счёт каждого хода. Окно ≈ 2–8 минут звука вместо 20+ минут
        private const val READER_COMPRESSION_TRIGGER_TOKENS = 12_000
        private const val READER_COMPRESSION_TARGET_TOKENS = 4_000

        private const val MAX_MESSAGES = 200
        // ≈ 9 минут попыток с шагом до 15 с: блокировка экрана/лифт/метро не должны рвать чтение
        private const val MAX_RECONNECT_ATTEMPTS = 40
        private const val MAX_RECONNECT_BACKOFF_MS = 15_000L
        // Разговор: ответ, удержанный потерей фокуса дольше минуты (звонок), не доигрывается
        private const val STALE_FOCUS_HOLD_MS = 60_000L
        // Читалка дочитала/остановлена: открытая сессия закрывается через 10 минут простоя
        private const val READER_IDLE_DISCONNECT_MS = 10 * 60_000L
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e("Unhandled coroutine exception in SessionManager", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)
    private val mutex = Mutex()
    private val micMutex = Mutex()
    private val commandMutex = Mutex()
    private val transcriptLock = Any()
    private val userTurnMutex = Mutex()
    private val toolResponseMutex = Mutex()

    private val _state = MutableStateFlow(SessionState(activePrompt = DEFAULT_SYSTEM_PROMPT))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val amplitude: StateFlow<Float> = combine(
        audioEngine.micLevel, audioEngine.outLevel
    ) { mic, out -> maxOf(mic, out) }
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    private var micAudioJob: Job? = null
    private var micControlJob: Job? = null
    private var reconnectJob: Job? = null
    private var goAwayJob: Job? = null
    private val reconnectGuard = Any()
    private val reconnectToken = AtomicLong(0L)
    private val goAwayToken = AtomicLong(0L)
    @Volatile private var resumptionHandle: String? = null
    private val resumptionPersistenceMutex = Mutex()
    @Volatile private var reconnectAttempts = 0
    @Volatile private var pendingGoAway = false
    @Volatile private var activeConnectUsedResumption = false

    @Volatile private var connectionDesired = false
    @Volatile private var userMicDesired = false
    private var transcriptStreamGenerationId: Long = -1L
    @Volatile private var currentOutputTranscriptionEnabled = true

    @Volatile private var currentAadEnabled = false
    @Volatile private var activeSessionId = 0L
    @Volatile private var interactionStatus: String? = null
    @Volatile private var turnCompleteSeen = false
    @Volatile private var activeLiveCapabilities =
        LiveModelCapabilitiesRegistry.forModel(DEFAULT_LIVE_MODEL)

    private val isManualActivityActive = AtomicBoolean(false)
    // Страховка ручного VAD: если в шуме локальный детектор не видит конца фразы,
    // activityEnd уходит принудительно — иначе модель ждёт бесконечно
    @Volatile private var manualActivityStartedAtMs = 0L
    // 20 мс цифровой тишины (16 кГц, 16 бит) для серверного VAD в гибридном режиме
    private val trailingSilenceFrame = ByteArray(640)
    private val isTextTurnInProgress = AtomicBoolean(false)

    private val activeToolJobs = ConcurrentHashMap<ToolCallKey, Job>()
    private val cancelledToolCallKeys = ConcurrentHashMap.newKeySet<ToolCallKey>()

    // --- Профиль сессии, видимость UI, фоновый режим ---
    @Volatile private var sessionProfile = SessionProfile.CONVERSATION
    @Volatile private var uiVisible = true
    @Volatile private var screenInteractive = true
    @Volatile private var currentServerSilenceMs = DEFAULT_SILENCE_DURATION_MS + 200
    @Volatile private var sessionSpeechLanguage = "ru-RU"
    @Volatile private var focusLostPermanently = false
    @Volatile private var focusHoldSinceMs = 0L
    @Volatile private var readerVoiceCommands = true
    @Volatile private var currentVolume = 1.0f

    // --- Сторож сбоев 3.8 («немой» ответ, «беглое» аудио) ---
    private val turnWatchdog = LiveTurnWatchdog { SystemClock.elapsedRealtime() }
    private val silentRecoveryTimes = ArrayDeque<Long>()
    @Volatile private var pendingSilentNudge = false

    // Закрытия 1008 у работающей сессии (сбой 3.8): чистое переподключение не чаще 2 раз за 2 минуты
    private val policyCloseTimes = ArrayDeque<Long>()

    // Для бесшовного goAway: конец последней реплики пользователя и последнего хода модели
    @Volatile private var lastUserTurnEndMs = 0L
    @Volatile private var lastModelTurnEndMs = 0L

    // Сеть снова доступна — переподключение без ожидания backoff
    private val networkAvailableSignal = Channel<Unit>(Channel.CONFLATED)

    // Ответ модели во время паузы читалки придерживается ~0,7 с, пока не ясно, команда это или ответ
    private val heldInteractionFrames = ArrayDeque<AudioFrame>()

    // Очередь подачи звука в движок (см. observeAudio) — объявлена до init: её читают корутины init
    private val playQueue = Channel<AudioFrame>(Channel.UNLIMITED)
    private val playQueueDepth = java.util.concurrent.atomic.AtomicInteger(0)

    // --- Live-читалка ---
    private val readerStore = ReaderProgressStore(File(context.filesDir, "reader"))

    private val readerTransport = object : ReaderTransportPort {
        override fun isSessionReady(): Boolean =
            sessionProfile == SessionProfile.READER && _state.value.link == LinkState.LIVE && client.isReady

        override fun sessionKey(): Long = client.epoch

        override suspend fun sendUserTurn(text: String): Boolean =
            if (activeLiveCapabilities.supportsMidSessionClientContent) {
                client.sendClientContent(listOf(ClientTurn(ClientRole.USER, text)), turnComplete = true)
            } else {
                client.sendRealtimeText(text)
                true
            }

        override fun isGenerationInProgress(): Boolean = client.isGenerationInProgress
        override fun activeServerTurnId(): Long = client.activeServerTurnId
        override fun transportBacklogFrames(): Long = client.pendingTransportAudioBytes() / 2L
    }

    private val readerAudio = object : ReaderAudioPort {
        override fun setReaderHold(held: Boolean) = audioEngine.setPlaybackHold(PlaybackHoldOwner.READER, held)
        override fun setBargeInHold(held: Boolean) = audioEngine.setPlaybackHold(PlaybackHoldOwner.BARGE_IN, held)
        override fun playbackGeneration(): Long = audioEngine.currentPlaybackGeneration
        override fun enqueuedFrames(): Long = audioEngine.enqueuedFramesInGeneration
        override fun pendingFrames(): Long = audioEngine.pendingModelFrames()
        // Без purge очереди транспорта: ещё не разобранные кадры нужного хода (ответ на вопрос)
        // иначе потерялись бы; ненужные кадры читалка отбрасывает сама (decideAudio → DROP),
        // а уже разобранные отсекаются по поколению воспроизведения
        override fun flushPlayback(reason: String): Long = audioEngine.invalidateAndFlushPlayback(reason)
        override fun adjustVolume(up: Boolean): Float {
            val next = (currentVolume + if (up) 0.15f else -0.15f).coerceIn(0.2f, 1.0f)
            currentVolume = next
            audioEngine.setVolume(next)
            scope.launch { runCatching { dataStore.edit { it[KEY_VOLUME] = next } } }
            return next
        }
    }

    private val readerHost = object : ReaderHost {
        override suspend fun ensureReaderSession(): Boolean = ensureReaderSessionInternal()
        override fun isGoAwayPending(): Boolean = pendingGoAway
        override fun requestPlannedReconnect(reason: String) {
            pendingGoAway = false
            goAwayToken.incrementAndGet()
            scheduleReconnect(reason, client.epoch, immediate = true)
        }
        override fun requestFreshSession(reason: String) = requestFreshSessionInternal(reason)
        override fun onUiState(state: ReaderUiState) {
            _state.update { it.copy(reader = state) }
        }
        override fun log(message: String) = logger.d(message)
        override fun now(): Long = SystemClock.elapsedRealtime()
    }

    private val reader = LiveReaderController(
        scope = scope,
        transport = readerTransport,
        audio = readerAudio,
        host = readerHost,
        store = readerStore
    )

    private fun readerActive(): Boolean = sessionProfile == SessionProfile.READER && reader.isActive

    init {
        observeSettings()
        observeEvents()
        observeAudio()
        observeBargeIn()
        observeFocus()
        observeForvoQuota()
        observeNetwork()
        observeTurnWatchdog()
        observeHeldInteractionFrames()
        observeReaderIdle()
        _state.update { it.copy(reader = reader.uiState()) }
        scope.launch(Dispatchers.IO) { audioEngine.prewarmVad() }
    }

    private fun observeForvoQuota() = scope.launch {
        forvoRepo.quota.collect { q ->
            _state.update { it.copy(forvoUsed = q.used, forvoLimit = q.limit) }
        }
    }

    fun toggleConnection() = scope.launch {
        commandMutex.withLock {
            mutex.withLock {
                if (_state.value.link != LinkState.IDLE) {
                    connectionDesired = false
                    userMicDesired = false
                    cancelReconnectWork()
                    if (reader.isActive) reader.stop()
                    stopInternal(full = true)
                } else {
                    // Кнопка Start Session — разговор; читалка поднимает свой профиль сама
                    sessionProfile = SessionProfile.CONVERSATION
                    connectionDesired = true
                    userMicDesired = true
                    reconnectAttempts = 0
                    resumptionHandle = null
                    cancelReconnectWork()
                    _state.update { it.copy(messages = emptyList(), error = null) }
                    try {
                        startInternal(resume = false)
                    } catch (cancelled: CancellationException) {
                        connectionDesired = false
                        cancelReconnectWork()
                        throw cancelled
                    } catch (t: Throwable) {
                        connectionDesired = false
                        cancelReconnectWork()
                        logger.e("SessionManager: initial connection start failed", t)
                    }
                }
            }
        }
    }

    fun toggleMic() = scope.launch {
        commandMutex.withLock {
            mutex.withLock {
                micMutex.withLock {
                    val physicallyActive =
                        _state.value.isMicActive ||
                            audioEngine.isCapturing.value

                    if (physicallyActive) {
                        userMicDesired = false
                        stopMicLocked()
                        return@withLock
                    }

                    userMicDesired = !userMicDesired
                    if (!userMicDesired) {
                        _state.update { it.copy(error = null) }
                        return@withLock
                    }

                    if (connectionDesired &&
                        _state.value.link == LinkState.LIVE &&
                        client.isReady
                    ) {
                        // Звук был занят другим приложением — включение микрофона = «я снова здесь»
                        reacquireFocusLocked()
                        startMicLocked()
                    } else {
                        _state.update {
                            it.copy(
                                error = "Сессия ещё не готова: микрофон будет запущен после подключения"
                            )
                        }
                    }
                }
            }
        }
    }

    fun stopSession() = scope.launch {
        commandMutex.withLock {
            mutex.withLock {
                connectionDesired = false
                userMicDesired = false
                if (reader.isActive) reader.stop()
                stopInternal(full = true)
            }
        }
    }

    // =====================================================================================
    // Live-читалка: публичное API для UI, уведомления и кнопок гарнитуры
    // =====================================================================================

    /** Прочитать вслух большой текст (не становится системным промптом). */
    fun startReading(text: String) = scope.launch {
        val clean = text.trim()
        if (clean.isEmpty()) return@launch
        // Звук занят другим приложением — фокус запрашивается заново (действие пользователя)
        val focusOk = regainFocusIfLost()
        commandMutex.withLock {
            if (!reader.load(clean)) {
                _state.update { it.copy(error = "В тексте нет предложений для чтения") }
            } else if (!focusOk) {
                reader.pause(PauseReason.FOCUS_PERMANENT)
            }
        }
    }

    fun resumeSavedReading() = scope.launch {
        val focusOk = regainFocusIfLost()
        commandMutex.withLock {
            if (!reader.resumeSaved()) {
                _state.update { it.copy(error = "Сохранённый текст не найден") }
            } else if (!focusOk) {
                reader.pause(PauseReason.FOCUS_PERMANENT)
            }
        }
    }

    fun discardSavedReading() = scope.launch { reader.discardSaved() }

    fun readerCommand(command: ReaderCommand) = scope.launch {
        if (command == ReaderCommand.Resume || command == ReaderCommand.Toggle) {
            regainFocusIfLost()
        }
        reader.command(command)
        if (command is ReaderCommand.SetPace) {
            runCatching { dataStore.edit { it[KEY_READER_PACE] = command.pace.wire } }
        }
    }

    /** Кнопка Play/Pause гарнитуры, экрана блокировки, уведомления. */
    fun mediaPlayPause() {
        if (reader.isActive) {
            readerCommand(ReaderCommand.Toggle)
            return
        }
        scope.launch {
            val held = audioEngine.isPlaybackHeld(PlaybackHoldOwner.USER)
            if (held) regainFocusIfLost()
            audioEngine.setPlaybackHold(PlaybackHoldOwner.USER, !held)
        }
    }

    fun mediaPlay() {
        if (reader.isActive) {
            readerCommand(ReaderCommand.Resume)
            return
        }
        scope.launch {
            regainFocusIfLost()
            audioEngine.setPlaybackHold(PlaybackHoldOwner.USER, false)
        }
    }

    fun mediaPause() {
        if (reader.isActive) {
            readerCommand(ReaderCommand.Pause)
            return
        }
        audioEngine.setPlaybackHold(PlaybackHoldOwner.USER, true)
    }

    /** Activity видна/скрыта: спектр, профиль буферизации, защитное окно от «шумов касания». */
    fun onUiVisibilityChanged(visible: Boolean) {
        uiVisible = visible
        audioEngine.setUiVisible(visible)
        audioEngine.armHandlingGuard(1_200L)
        applyPlaybackBufferProfile()
    }

    /** Экран включён/выключен (приходит из foreground-сервиса). */
    fun onScreenInteractiveChanged(interactive: Boolean) {
        screenInteractive = interactive
        audioEngine.armHandlingGuard(1_500L)
        applyPlaybackBufferProfile()
    }

    /**
     * После постоянной потери аудиофокуса (другое приложение заняло звук) фокус запрашивается снова
     * только по действию пользователя. Вызывать без mutex.
     */
    private suspend fun regainFocusIfLost(): Boolean {
        if (!focusLostPermanently) return true
        if (!reacquireFocusLocked()) return false
        restoreAudioAfterFocusGain()
        return true
    }

    /** То же без перезапуска микрофона — можно вызывать под mutex (toggleMic). */
    private suspend fun reacquireFocusLocked(): Boolean {
        if (!focusLostPermanently) return true
        val granted = withContext(Dispatchers.IO) { audioEngine.reacquireAudioFocus() }
        if (granted) {
            focusLostPermanently = false
            focusHoldSinceMs = 0L
            audioEngine.setPlaybackHold(PlaybackHoldOwner.FOCUS, false)
            _state.update { it.copy(error = null) }
        } else {
            _state.update { it.copy(error = "Звук сейчас занят (звонок или другое приложение) — попробуйте позже") }
        }
        return granted
    }

    /**
     * Буферизация вывода: на экране — минимальная задержка ответа; в фоне/при погашенном экране Wi-Fi
     * работает в power-save и пакеты приходят пачками — нужен запас; читалке задержка старта не важна,
     * важна непрерывность.
     */
    private fun applyPlaybackBufferProfile() {
        when {
            sessionProfile == SessionProfile.READER ->
                audioEngine.setPlaybackBufferProfile(primeMs = 450, minTargetMs = 200, maxTargetMs = 400)
            !uiVisible || !screenInteractive ->
                audioEngine.setPlaybackBufferProfile(primeMs = 260, minTargetMs = 80, maxTargetMs = 220)
            else ->
                audioEngine.setPlaybackBufferProfile(primeMs = 70, minTargetMs = 40, maxTargetMs = 120)
        }
    }

    /** Поднять Live-сессию в профиле READER (или дождаться текущей) — вызывает читалка. */
    private suspend fun ensureReaderSessionInternal(): Boolean {
        val needsStart = mutex.withLock {
            val link = _state.value.link
            if (sessionProfile == SessionProfile.READER && link != LinkState.IDLE) {
                false
            } else {
                sessionProfile = SessionProfile.READER
                connectionDesired = true
                userMicDesired = readerVoiceCommands
                reconnectAttempts = 0
                if (link != LinkState.IDLE) {
                    cancelReconnectWork()
                    stopInternal(full = false)
                }
                resumptionHandle = null
                try {
                    startInternal(resume = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logger.e("SessionManager: reader session start failed", t)
                }
                true
            }
        }
        if (needsStart) logger.d("SessionManager: Live-сессия переключена в профиль READER")
        return withTimeoutOrNull(12_000L) {
            var ready = readerTransport.isSessionReady()
            while (!ready && _state.value.link != LinkState.IDLE) {
                delay(50L)
                ready = readerTransport.isSessionReady()
            }
            ready
        } == true
    }

    /** Новая сессия без resumption (обход повторяющихся «немых» ответов gemini-3.8-live). */
    private fun requestFreshSessionInternal(reason: String) {
        scope.launch {
            mutex.withLock {
                if (!connectionDesired || _state.value.link == LinkState.IDLE) return@withLock
                resumptionHandle = null
                activeConnectUsedResumption = false
                resumptionPersistenceMutex.withLock {
                    dataStore.edit {
                        it.remove(KEY_SESSION_RESUMPTION_HANDLE)
                        it.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                    }
                }
            }
            logger.w("SessionManager: новая сессия без resumption — $reason")
            scheduleReconnect(reason, client.epoch, immediate = true)
        }
    }

    fun applyPrompt(newPrompt: String) = scope.launch {
        commandMutex.withLock {
            mutex.withLock {
                val changed = _state.value.activePrompt != newPrompt
                if (!changed) return@withLock

                // В профиле READER роль ассистента не используется — переподключение не нужно
                val shouldRestart = _state.value.link != LinkState.IDLE &&
                    sessionProfile == SessionProfile.CONVERSATION
                runCatching { dataStore.edit { it[KEY_SYSTEM_PROMPT] = newPrompt } }
                    .onFailure { logger.e("SessionManager: не удалось сохранить промпт", it) }
                _state.update { it.copy(activePrompt = newPrompt) }

                if (!shouldRestart || !connectionDesired) return@withLock

                resumptionHandle = null
                resumptionPersistenceMutex.withLock {
                    dataStore.edit {
                        it.remove(KEY_SESSION_RESUMPTION_HANDLE)
                        it.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                    }
                }
                cancelReconnectWork()
                stopInternal(full = false)

                if (connectionDesired) {
                    try {
                        startInternal(resume = false)
                    } catch (cancelled: CancellationException) {
                        connectionDesired = false
                        cancelReconnectWork()
                        throw cancelled
                    } catch (t: Throwable) {
                        connectionDesired = false
                        cancelReconnectWork()
                        logger.e("SessionManager: prompt restart failed", t)
                    }
                }
            }
        }
    }

    private suspend fun invalidateAndFlushAudio(reason: String): Long =
        withContext(Dispatchers.IO) {
            client.purgeAudioQueue()
            audioEngine.invalidateAndFlushPlayback(reason)
        }

    fun sendText(text: String, uris: List<Uri> = emptyList()) = scope.launch {
        commandMutex.withLock {
            userTurnMutex.withLock {
                val trimmed = text.trim()
                if (trimmed.isEmpty() && uris.isEmpty()) return@withLock

                if (readerActive()) {
                    // Вопрос диктору текстом во время чтения: чтение на паузу (прочитанное в буфере
                    // сохраняется), ответ модели звучит сразу, продолжение — по команде
                    reader.pause(PauseReason.USER)
                } else if (_state.value.isAiSpeaking) {
                    invalidateAndFlushAudio("user text")
                    _state.update { it.copy(isAiSpeaking = false) }
                }

                if (uris.isNotEmpty()) {
                    handleAttachments(trimmed, uris)
                    return@withLock
                }

                addMessage(ChatMessage(role = ClientRole.USER, text = trimmed))
                resetTranscriptRuntime()
                turnCompleteSeen = false
                interactionStatus = null

                if (!ensureLive()) {
                    _state.update { it.copy(error = "Нет соединения с сервером") }
                    return@withLock
                }

                isTextTurnInProgress.set(true)
                try {
                    sendUserText(trimmed)
                } finally {
                    delay(300L)
                    isTextTurnInProgress.set(false)
                }
            }
        }
    }

    /**
     * Текстовая реплика пользователя. Для 3.8 — clientContent(turnComplete=true): упорядоченно добавляется
     * в контекст и сразу запускает ответ (realtimeInput.text порядок не гарантирует).
     */
    private suspend fun sendUserText(text: String) {
        lastUserTurnEndMs = SystemClock.elapsedRealtime()
        if (activeLiveCapabilities.supportsMidSessionClientContent) {
            client.sendClientContent(listOf(ClientTurn(ClientRole.USER, text)), turnComplete = true)
        } else {
            client.sendRealtimeText(text)
        }
    }

    fun playForvo(word: ForvoWord) = scope.launch {
        commandMutex.withLock {
            val url = forvoRepo.freshUrl(word.query, word.language) ?: run {
                _state.update { it.copy(error = "Ссылка Forvo недоступна или устарела") }
                return@launch
            }

            val wasMic = _state.value.isMicActive
            if (wasMic) stopMic(userInitiated = false)

            invalidateAndFlushAudio("Forvo playback")
            forvoPlayer.play(url)

            if (wasMic && userMicDesired && connectionDesired) {
                delay(200)
                startMic()
            }
        }
    }

    fun refetchAllForvo() {
        val words = _state.value.forvoWords
        if (words.isEmpty()) return
        forvoRepo.clearMisses()
        scope.launch {
            resolveForvo(
                words.map { VocabItem(it.word, it.query, it.translation) },
                words.first().language
            )
        }
    }

    fun clearForvo() {
        forvoRepo.clearMisses()
        _state.update { it.copy(forvoWords = emptyList()) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearChat() = _state.update { it.copy(messages = emptyList()) }

    private suspend fun resolveForvo(items: List<VocabItem>, lang: String) {
        _state.update { s ->
            s.copy(forvoWords = items.map {
                ForvoWord(
                    word = it.lemma,
                    query = it.forvoQuery,
                    language = lang,
                    translation = it.translation,
                    isLoading = true
                )
            })
        }

        forvoRepo.lookupBatch(items.map { it.forvoQuery }, lang) { query, res ->
            _state.update { s ->
                s.copy(forvoWords = s.forvoWords.map { w ->
                    if (!w.query.equals(query, ignoreCase = true)) w
                    else when (res) {
                        is ForvoResult.Found -> w.copy(
                            audioUrl = res.pronunciation.mp3Url,
                            isLoading = false, notFound = false
                        )
                        else -> w.copy(isLoading = false, notFound = true)
                    }
                })
            }
        }
    }

    private suspend fun handleAttachments(text: String, uris: List<Uri>) {
        _state.update { it.copy(isAnalyzing = true, error = null) }
        try {
            val processed = attachmentProcessor.process(uris)
            val prefs = dataStore.data.first()
            val apiKey = decryptStoredValue(
                prefs[KEY_API]?.trim().orEmpty(),
                "Gemini API key"
            ).orEmpty()
            val forvoOn = prefs[KEY_ENABLE_FORVO] ?: false

            val displayText = text.ifEmpty { "Изучи приложенный документ." }

            addMessage(
                ChatMessage(
                    role = ClientRole.USER,
                    text = displayText,
                    attachmentNames = processed.accepted,
                    modelReplayText = processed.extractedText.takeIf { it.isNotBlank() }?.take(15000)
                )
            )

            val result = extractor.analyze(
                apiKey = apiKey,
                images = processed.images,
                plainText = processed.extractedText,
                forLanguageLearning = forvoOn,
                model = prefs[KEY_ANALYZER_MODEL]?.trim()?.takeIf { it.isNotBlank() }
                    ?: VocabularyExtractor.DEFAULT_MODEL
            )

            when (result) {
                is AnalysisResult.Success -> {
                    val a = result.analysis
                    synchronized(transcriptLock) {
                        _state.update { current ->
                            val idx = current.messages.indexOfLast {
                                it.role == ClientRole.USER &&
                                    it.attachmentNames == processed.accepted &&
                                    it.text == displayText
                            }
                            if (idx < 0) current else {
                                val updated = current.messages.toMutableList()
                                updated[idx] = updated[idx].copy(
                                    modelReplayText = a.fullText.take(15000)
                                )
                                current.copy(messages = updated)
                            }
                        }
                    }
                    if (forvoOn && a.vocabulary.isNotEmpty()) {
                        scope.launch { resolveForvo(a.vocabulary, a.language) }
                    }
                    if (!ensureLive()) return

                    isTextTurnInProgress.set(true)
                    try {
                        sendUserText(a.fullText.take(15000))
                    } finally {
                        delay(300L)
                        isTextTurnInProgress.set(false)
                    }
                }

                is AnalysisResult.Failure -> {
                    logger.w("SessionManager: анализ вложения не удался: ${result.reason}")
                    _state.update {
                        it.copy(error = "Ошибка анализа: ${result.reason}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Attachment error", e)
            _state.update {
                it.copy(error = "Сбой обработки файлов: ${e.localizedMessage}")
            }
        } finally {
            _state.update { it.copy(isAnalyzing = false) }
        }
    }

    private fun buildSystemInstruction(userPrompt: String, speechLang: String): String {
        val locale = java.util.Locale.forLanguageTag(speechLang)
        val langEn = locale.getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "Russian" }.uppercase()
        val langRu = locale.getDisplayLanguage(java.util.Locale.forLanguageTag("ru")).ifBlank { "русский" }
        val rules = """
            **Язык.** RESPOND IN $langEn. YOU MUST RESPOND UNMISTAKABLY IN $langEn.
            Основной язык разговора — $langRu. Слова и фразы на других языках произноси только как учебный материал внутри ответа на языке «$langRu», либо если пользователь прямо попросит перейти на другой язык.
            Никогда не меняй язык из-за короткой или неразборчивой реплики: если не расслышал, коротко переспроси на языке «$langRu».

            **Ведение разговора.** Держи нить разговора и помни, на чём вы остановились. Не начинай разговор заново, не здоровайся повторно и не спрашивай «чем могу помочь», если беседа уже идёт.
            Уточняющий вопрос относится к последней теме: отвечай в её контексте. Если не уверен, что имел в виду пользователь, переспроси, а не придумывай.
            Если ошибся, исправься одной короткой фразой без повторных извинений и продолжи с правильного места. Не выдумывай, что было сказано раньше: опирайся только на реальную историю разговора.

            **Перебивание.** Тебя слушают в реальном времени и могут перебить. Если тебя перебили, пользователь услышал только начало твоего последнего ответа. Не продолжай с того места, где закончился твой ответ: сначала ответь на то, что сказал пользователь, а если он просит продолжить — вернись к прерванной мысли и коротко повтори то, что он мог не услышать.

            **Реакция.** На каждую реплику, обращённую к тебе, отвечай сразу, без долгих пауз и вступлений. Если фраза неразборчива или оборвалась на полуслове — коротко переспроси, но не молчи. Кашель, дыхание, музыку, сигналы и звуки техники не комментируй.
        """.trimIndent()
        val base = userPrompt.trim()
        return if (base.isEmpty()) rules else "$base\n\n$rules"
    }

    private suspend fun ensureLive(): Boolean {
        val targetEpoch = mutex.withLock {
            if (client.isReady && _state.value.link == LinkState.LIVE) {
                return@withLock client.epoch
            }

            if (_state.value.link == LinkState.IDLE) {
                connectionDesired = true
                try {
                    startInternal(resume = false)
                } catch (cancelled: CancellationException) {
                    connectionDesired = false
                    cancelReconnectWork()
                    throw cancelled
                } catch (t: Throwable) {
                    connectionDesired = false
                    cancelReconnectWork()
                    logger.e("SessionManager: ensureLive startup failed", t)
                    return@withLock null
                }
            }

            if (!connectionDesired || _state.value.link == LinkState.IDLE) {
                return@withLock null
            }

            client.epoch
        } ?: return false

        return withTimeoutOrNull(8000L) {
            while (true) {
                val readyForTarget = mutex.withLock {
                    connectionDesired &&
                        _state.value.link == LinkState.LIVE &&
                        client.isReady &&
                        client.epoch == targetEpoch
                }

                if (readyForTarget) return@withTimeoutOrNull true
                delay(40)
            }
        } == true
    }

    private fun buildForvoToolDeclaration(): JsonObject = buildJsonObject {
        putJsonArray("functionDeclarations") {
            addJsonObject {
                put("name", "lookup_pronunciation")
                put(
                    "description",
                    "Запрашивает аудиозаписи произношения слов носителями языка из базы Forvo."
                )
                put("behavior", "NON_BLOCKING")

                putJsonObject("parameters") {
                    put("type", "OBJECT")

                    putJsonObject("properties") {
                        putJsonObject("words") {
                            put("type", "ARRAY")
                            putJsonObject("items") { put("type", "STRING") }
                            put(
                                "description",
                                "Список слов для поиска произношения."
                            )
                        }

                        putJsonObject("language") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Двухбуквенный код языка ISO 639-1 (например, 'de', 'en', 'fr', 'es'). По умолчанию 'de'."
                            )
                        }
                    }

                    putJsonArray("required") {
                        add(JsonPrimitive("words"))
                    }
                }
            }
        }
    }

    private fun recentHistory(maxTurns: Int): List<ClientTurn> {
        val raw = _state.value.messages
            .filter { !it.interim && it.text.isNotBlank() }

        if (raw.isEmpty()) return emptyList()

        val candidates = raw
            .takeLast(maxTurns.coerceIn(1, 100))
            .map {
                val replayText = it.modelReplayText?.takeIf { value -> value.isNotBlank() } ?: it.text
                ClientTurn(it.role, replayText.trim())
            }

        val firstUser = candidates.indexOfFirst { it.role == ClientRole.USER }
        if (firstUser < 0) return emptyList()

        var candidateList = candidates.drop(firstUser)
        while (candidateList.isNotEmpty() && candidateList.last().role == ClientRole.USER) {
            candidateList = candidateList.dropLast(1)
        }
        if (candidateList.isEmpty()) return emptyList()

        val maxHistoryChars = 24_000
        val out = ArrayDeque<ClientTurn>()
        var chars = 0

        for (turn in candidateList.asReversed()) {
            val cost = turn.text.length
            if (out.isNotEmpty() && chars + cost > maxHistoryChars) break
            if (out.isEmpty() && cost > maxHistoryChars) {
                out.addFirst(turn.copy(text = turn.text.take(maxHistoryChars)))
                break
            }
            out.addFirst(turn)
            chars += cost
        }
        return out.toList()
    }

    private suspend fun startInternal(resume: Boolean, preservePlayback: Boolean = false) {
        if (!resume) {
            resumptionHandle = null
        } else if (resumptionHandle.isNullOrBlank()) {
            val prefs = dataStore.data.first()
            val storedResumeHandle = decryptStoredValue(
                prefs[KEY_SESSION_RESUMPTION_HANDLE]?.trim().orEmpty(),
                "Live session resumption handle"
            ).orEmpty()
            if (storedResumeHandle.isNotBlank() && prefs[KEY_SESSION_RESUMPTION_ENABLED] != false) {
                resumptionHandle = storedResumeHandle
            }
        }
        activeConnectUsedResumption = resume && (resumptionHandle?.isNotBlank() == true)

        synchronized(reconnectGuard) {
            goAwayJob?.cancel()
            goAwayJob = null
        }

        pendingGoAway = false
        resetTranscriptRuntime()
        isManualActivityActive.set(false)
        isTextTurnInProgress.set(false)

        val prefs = dataStore.data.first()
        val encryptedApiKey = prefs[KEY_API]?.trim().orEmpty()
        val apiKeyResult = decryptStoredValue(
            encryptedApiKey,
            "Gemini API key"
        )

        if (apiKeyResult == null && encryptedApiKey.isNotBlank()) {
            connectionDesired = false
            userMicDesired = false
            cancelReconnectWork()
            _state.update {
                it.copy(
                    error = "Сохранённый Gemini API Key повреждён или недоступен. Введите ключ заново.",
                    link = LinkState.IDLE,
                    isMicActive = false,
                    isAiSpeaking = false
                )
            }
            return
        }

        val apiKey = apiKeyResult.orEmpty()

        if (apiKey.isEmpty()) {
            connectionDesired = false
            userMicDesired = false
            cancelReconnectWork()
            _state.update {
                it.copy(
                    error = "Укажите Gemini API Key в Настройках",
                    link = LinkState.IDLE,
                    isMicActive = false,
                    isAiSpeaking = false
                )
            }
            return
        }

        // Чтение вслух — всегда базовая gemini-3.8-live: минимальная задержка, SILENT-ответы функций
        // управления, без пауз на размышление (extended-thinking — для сложного диалога)
        val liveModel = if (sessionProfile == SessionProfile.READER) {
            READER_LIVE_MODEL
        } else {
            prefs[KEY_LIVE_MODEL]
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_LIVE_MODEL
        }

        val liveCapabilities = LiveModelCapabilitiesRegistry.forModel(liveModel)
        activeLiveCapabilities = liveCapabilities

        val thinkingLevel =
            if (
                liveCapabilities.supportsThinkingConfig &&
                liveModel
                    .removePrefix("models/")
                    .equals("gemini-3.8-live-extended-thinking", ignoreCase = true)
            ) {
                prefs[KEY_THINKING_LEVEL]
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it == "low" || it == "medium" || it == "high" }
                    ?: "low"
            } else {
                null
            }

        val readerProfile = sessionProfile == SessionProfile.READER
        val voice = prefs[KEY_VOICE]?.ifBlank { null } ?: DEFAULT_VOICE
        val speechLang = prefs[KEY_SPEECH_LANGUAGE]?.ifBlank { "ru-RU" } ?: "ru-RU"
        sessionSpeechLanguage = speechLang
        // Gemini 3: Google настоятельно рекомендует 1.0 — ниже риск зацикливания и «немых» ходов
        val temperature = if (readerProfile) 1.0f else prefs[KEY_TEMPERATURE] ?: 1.0f
        val mediaResolution = prefs[KEY_MEDIA_RESOLUTION] ?: "MEDIA_RESOLUTION_HIGH"
        readerVoiceCommands = prefs[KEY_READER_VOICE_COMMANDS] ?: true
        if (readerProfile && prefs[KEY_READER_PACE] != null) {
            reader.setPace(ReaderPace.from(prefs[KEY_READER_PACE]))
        }
        currentVolume = prefs[KEY_VOLUME] ?: 1.0f

        val inputTx = TranscriptionSettings(
            // Читалке нужна транскрипция реплик слушателя (резервное распознавание команд)
            enabled = readerProfile || (prefs[KEY_INPUT_TRANSCRIPTION_ENABLED] ?: true),
            languageCodes =
                prefs[KEY_INPUT_TRANSCRIPTION_LANGUAGES]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
            customVocabulary =
                prefs[KEY_INPUT_TRANSCRIPTION_VOCAB]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(1000)
                    ?: emptyList(),
            mode = prefs[KEY_INPUT_TRANSCRIPTION_MODE] ?: "VERBATIM"
        )

        // Читалке выходная транскрипция обязательна: по ней проверяется, что фрагмент прочитан целиком,
        // и вычисляется позиция для продолжения; сторож «немых» ходов 3.8 тоже опирается на неё
        currentOutputTranscriptionEnabled = readerProfile || (prefs[KEY_OUTPUT_TRANSCRIPTION_ENABLED] ?: true)

        val outputTx = TranscriptionSettings(
            enabled = currentOutputTranscriptionEnabled,
            languageCodes =
                prefs[KEY_OUTPUT_TRANSCRIPTION_LANGUAGES]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
            customVocabulary =
                prefs[KEY_OUTPUT_TRANSCRIPTION_VOCAB]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(1000)
                    ?: emptyList(),
            mode = prefs[KEY_OUTPUT_TRANSCRIPTION_MODE] ?: "VERBATIM"
        )

        // Читалка: только ручной VAD (activityStart/End по локальному Silero) — серверный VAD 3.8
        // не завершает ход без непрерывного аудиопотока
        val aadEnabled = !readerProfile && (prefs[KEY_AAD_ENABLED] ?: false)
        currentAadEnabled = aadEnabled
        audioEngine.isAadMode = aadEnabled
        audioEngine.setBluetoothHiFiEnabled(prefs[KEY_BT_HIFI_MODE] ?: DEFAULT_BT_HIFI)

        val realtimeInput = RealtimeInputSettings(
            aadEnabled = aadEnabled,
            startSensitivity = prefs[KEY_AAD_START_SENSITIVITY] ?: "START_SENSITIVITY_HIGH",
            endSensitivity = prefs[KEY_AAD_END_SENSITIVITY] ?: "END_SENSITIVITY_HIGH",
            prefixPaddingMs = prefs[KEY_PREFIX_PADDING_MS] ?: 60,
            silenceDurationMs = prefs[KEY_SILENCE_DURATION_MS] ?: DEFAULT_SILENCE_DURATION_MS,
            // Читалка: реплика слушателя не обрывает уже начатый фрагмент — он дочитывается в буфер,
            // а звук мгновенно удерживается локально; команда выполняется сразу после
            activityHandling = if (readerProfile) "NO_INTERRUPTION"
                else prefs[KEY_ACTIVITY_HANDLING] ?: "START_OF_ACTIVITY_INTERRUPTS",
            turnCoverage = prefs[KEY_TURN_COVERAGE] ?: "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO"
        )
        currentServerSilenceMs = (realtimeInput.silenceDurationMs + 200).coerceAtMost(3000)

        audioEngine.setEndOfSpeechHangoverMs(realtimeInput.silenceDurationMs)
        audioEngine.bargeInMode = if (readerProfile) BargeInMode.HOLD else BargeInMode.FLUSH
        audioEngine.localBargeInEnabled = readerProfile || realtimeInput.activityHandling != "NO_INTERRUPTION"
        client.flushPlaybackOnServerInterrupt = !readerProfile
        applyPlaybackBufferProfile()
        turnWatchdog.reset()
        _state.update { it.copy(profile = sessionProfile) }

        val compression = if (readerProfile) {
            CompressionSettings(
                enabled = true,
                triggerTokens = READER_COMPRESSION_TRIGGER_TOKENS,
                targetTokens = READER_COMPRESSION_TARGET_TOKENS
            )
        } else {
            CompressionSettings(
                enabled = prefs[KEY_COMPRESSION_ENABLED] ?: true,
                triggerTokens = prefs[KEY_COMPRESSION_TRIGGER_TOKENS] ?: 32_000,
                targetTokens = prefs[KEY_COMPRESSION_TARGET_TOKENS] ?: 16_000
            )
        }

        val resumptionEnabled = prefs[KEY_SESSION_RESUMPTION_ENABLED] ?: true
        val maxHistoryTurns = prefs[KEY_INITIAL_HISTORY_TURNS] ?: 20

        audioEngine.setVolume(prefs[KEY_VOLUME] ?: 1.0f)
        audioEngine.setMicGain(prefs[KEY_MIC_GAIN] ?: 1.0f)

        val startingFreshSession = !resume && _state.value.link == LinkState.IDLE

        if (startingFreshSession) {
            val foregroundServiceError = ensureForegroundServiceActive()
            if (foregroundServiceError != null) {
                audioEngine.stop()
                connectionDesired = false
                cancelReconnectWork()
                stopForegroundService()
                _state.update {
                    it.copy(
                        link = LinkState.IDLE,
                        isMicActive = false,
                        isAiSpeaking = false,
                        error = foregroundServiceError
                    )
                }
                return
            }
        } else if (!LiveSessionForegroundService.isServiceActive.value) {
            audioEngine.stop()
            connectionDesired = false
            userMicDesired = false
            cancelReconnectWork()
            _state.update {
                it.copy(
                    link = LinkState.IDLE,
                    isMicActive = false,
                    isAiSpeaking = false,
                    error = "Сессия остановлена: запустите её из приложения снова"
                )
            }
            return
        }

        _state.update {
            it.copy(
                link = if (resume) LinkState.RECONNECTING else LinkState.CONNECTING,
                error = null
            )
        }

        val forvoEnabled = !readerProfile && (prefs[KEY_ENABLE_FORVO] ?: false)
        val searchEnabled = !readerProfile && (prefs[KEY_ENABLE_SEARCH] ?: false)

        val dynamicTools = when {
            readerProfile -> ReaderPrompts.toolDeclarations()
            forvoEnabled -> buildJsonArray { add(buildForvoToolDeclaration()) }
            else -> null
        }

        val systemInstruction = if (readerProfile) {
            ReaderPrompts.narratorInstruction(speechLang)
        } else {
            buildSystemInstruction(_state.value.activePrompt, speechLang)
        }

        try {
            client.connect(
                LiveConfig(
                    apiKey = apiKey,
                    model = liveModel,
                    systemInstruction = systemInstruction,
                    voiceName = voice,
                    speechLanguage = speechLang,
                    temperature = temperature,
                    mediaResolution = mediaResolution,
                    thinkingLevel = thinkingLevel,
                    inputTranscription = inputTx,
                    outputTranscription = outputTx,
                    realtimeInput = realtimeInput.copy(
                        silenceDurationMs = (realtimeInput.silenceDurationMs + 200).coerceAtMost(3000)
                    ),
                    compression = compression,
                    sessionResumptionEnabled = resumptionEnabled,
                    resumptionHandle = if (resume && resumptionEnabled) resumptionHandle else null,
                    toolsJson = dynamicTools,
                    enableGoogleSearch = searchEnabled,
                    initialHistory = if (resume || readerProfile) emptyList() else recentHistory(maxHistoryTurns)
                ),
                beforeOpen = {
                    activeSessionId = client.sessionId
                    interactionStatus = null
                    turnCompleteSeen = false
                    // Переподключение (goAway, обрыв сети, смена сессии) не стирает уже принятый звук:
                    // дочитывается/договаривается то, что пришло до разрыва
                    if (!preservePlayback) {
                        invalidateAndFlushAudio(
                            if (resume) "Live reconnect" else "Live session start"
                        )
                    }
                }
            )
        } catch (cancelled: CancellationException) {
            runCatching { client.disconnect() }
            if (startingFreshSession) {
                connectionDesired = false
                cancelReconnectWork()
                runCatching { invalidateAndFlushAudio("connect cancellation") }
                audioEngine.stop()
                stopForegroundService()
                _state.update {
                    it.copy(
                        link = LinkState.IDLE,
                        isMicActive = false,
                        isAiSpeaking = false,
                        error = "Подключение отменено"
                    )
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            logger.e("SessionManager: Live connection setup failed", t)
            runCatching { client.disconnect() }

            if (startingFreshSession) {
                runCatching { invalidateAndFlushAudio("connect cancellation") }
                audioEngine.stop()
                stopForegroundService()
                _state.update {
                    it.copy(
                        link = LinkState.IDLE,
                        isMicActive = false,
                        isAiSpeaking = false,
                        error = "Не удалось подключиться к Gemini: ${t.localizedMessage.orEmpty()}"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        link = LinkState.RECONNECTING,
                        isMicActive = false,
                        isAiSpeaking = false
                    )
                }
            }

            throw t
        }

        val playbackStarted = try {
            audioEngine.startPlayback()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { client.disconnect() } }
            throw cancelled
        } catch (t: Throwable) {
            logger.e("SessionManager: playback start failed", t)
            false
        }

        if (!playbackStarted) {
            runCatching { client.disconnect() }
            audioEngine.stop()
            if (startingFreshSession) {
                connectionDesired = false
                cancelReconnectWork()
                stopForegroundService()
            }
            _state.update {
                it.copy(
                    error = "Сбой инициализации аудиодрайвера",
                    link = LinkState.IDLE,
                    isMicActive = false,
                    isAiSpeaking = false
                )
            }
        }
    }

    private suspend fun stopInternal(full: Boolean) {
        pendingGoAway = false
        resetTranscriptRuntime()

        cancelReconnectWork()
        cancelAllPendingToolJobs()

        withContext(NonCancellable) {
            try {
                micMutex.withLock {
                    stopMicLocked()
                }
            } catch (t: Throwable) {
                logger.e("SessionManager: microphone shutdown failed", t)
            }

            try {
                client.disconnect()
            } catch (t: Throwable) {
                logger.e("SessionManager: transport shutdown failed", t)
            }

            try {
                invalidateAndFlushAudio(
                    if (full) "session shutdown" else "session reset"
                )
            } catch (t: Throwable) {
                logger.e("SessionManager: playback flush during shutdown failed", t)
            }

            releaseHeldInteractionFrames()
            turnWatchdog.reset()

            isManualActivityActive.set(false)
            isTextTurnInProgress.set(false)

            if (full) {
                reconnectAttempts = 0
                resumptionHandle = null
                activeConnectUsedResumption = false
                resumptionPersistenceMutex.withLock {
                    dataStore.edit {
                        it.remove(KEY_SESSION_RESUMPTION_HANDLE)
                        it.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                    }
                }

                try {
                    audioEngine.stop()
                } catch (t: Throwable) {
                    logger.e("SessionManager: audio engine shutdown failed", t)
                }

                try {
                    stopForegroundService()
                } catch (t: Throwable) {
                    logger.e("SessionManager: foreground service shutdown failed", t)
                }
            }
        }

        _state.update {
            it.copy(
                link = LinkState.IDLE,
                isAiSpeaking = false,
                isMicActive = false
            )
        }
    }

    private fun cancelAllPendingToolJobs() {
        activeToolJobs.values.forEach { it.cancel() }
        activeToolJobs.clear()
        cancelledToolCallKeys.clear()
    }

    private fun cancelReconnectWork() {
        synchronized(reconnectGuard) {
            reconnectToken.incrementAndGet()
            goAwayToken.incrementAndGet()

            reconnectJob?.cancel()
            reconnectJob = null

            goAwayJob?.cancel()
            goAwayJob = null
        }

        pendingGoAway = false
    }

    private fun scheduleReconnect(
        reason: String,
        sourceEpoch: Long = client.epoch,
        immediate: Boolean = false
    ) {
        if (!connectionDesired) return

        val token: Long

        synchronized(reconnectGuard) {
            if (
                !connectionDesired ||
                _state.value.link == LinkState.IDLE ||
                client.epoch != sourceEpoch ||
                reconnectJob?.isActive == true
            ) {
                return
            }

            if (immediate) {
                reconnectAttempts = 0
            }

            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                val terminalToken = reconnectToken.incrementAndGet()

                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    try {
                        mutex.withLock {
                            if (
                                terminalToken != reconnectToken.get() ||
                                !connectionDesired ||
                                _state.value.link == LinkState.IDLE
                            ) {
                                return@withLock
                            }

                            connectionDesired = false
                            userMicDesired = false
                            stopInternal(full = true)

                            _state.update {
                                it.copy(
                                    error = "Соединение потеряно: $reason"
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        logger.e("SessionManager: terminal reconnect shutdown failed", t)
                    } finally {
                        synchronized(reconnectGuard) {
                            if (reconnectJob == coroutineContext[Job]) {
                                reconnectJob = null
                            }
                        }
                    }
                }
                return
            }

            token = reconnectToken.incrementAndGet()

            reconnectJob = scope.launch {
                try {
                    val attempt = synchronized(reconnectGuard) {
                        if (token != reconnectToken.get() || !connectionDesired) {
                            return@launch
                        }
                        if (immediate) 0 else ++reconnectAttempts
                    }

                    val stateAccepted = mutex.withLock {
                        if (
                            token != reconnectToken.get() ||
                            !connectionDesired ||
                            client.epoch != sourceEpoch ||
                            _state.value.link == LinkState.IDLE
                        ) {
                            false
                        } else {
                            _state.update {
                                it.copy(link = LinkState.RECONNECTING)
                            }
                            true
                        }
                    }
                    if (!stateAccepted) return@launch

                    if (!immediate) {
                        val baseDelay = minOf(
                            400L * (1L shl (attempt - 1).coerceAtMost(6)),
                            MAX_RECONNECT_BACKOFF_MS
                        )

                        val jitteredDelay = (baseDelay * (0.8 + Math.random() * 0.4)).toLong()
                        // Сеть вернулась (выход из лифта, смена Wi-Fi/LTE) — не ждём конца паузы
                        networkAvailableSignal.tryReceive()
                        withTimeoutOrNull(jitteredDelay) { networkAvailableSignal.receive() }
                    }

                    if (
                        token != reconnectToken.get() ||
                        !connectionDesired ||
                        client.epoch != sourceEpoch ||
                        _state.value.link == LinkState.IDLE
                    ) {
                        return@launch
                    }

                    mutex.withLock {
                        if (
                            token == reconnectToken.get() &&
                            connectionDesired &&
                            client.epoch == sourceEpoch &&
                            _state.value.link != LinkState.IDLE
                        ) {
                            val useResume = resumptionHandle != null
                            startInternal(resume = useResume, preservePlayback = true)

                            synchronized(reconnectGuard) {
                                if (token == reconnectToken.get()) {
                                    reconnectJob = null
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logger.e("SessionManager: reconnect attempt failed", t)

                    synchronized(reconnectGuard) {
                        if (token == reconnectToken.get()) {
                            reconnectJob = null
                        }
                    }

                    val retryEpoch = mutex.withLock {
                        if (
                            token != reconnectToken.get() ||
                            !connectionDesired ||
                            _state.value.link == LinkState.IDLE
                        ) {
                            return@withLock null
                        }

                        activeConnectUsedResumption = false
                        client.epoch
                    }

                    retryEpoch?.let { epoch ->
                        scheduleReconnect(
                            "ошибка попытки reconnect: ${t.localizedMessage}",
                            epoch
                        )
                    }
                } finally {
                    synchronized(reconnectGuard) {
                        if (token == reconnectToken.get()) {
                            reconnectJob = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun finalizeMicActivityBounded() {
        withTimeoutOrNull(500L) {
            if (currentAadEnabled) {
                client.sendAudioStreamEnd()
            } else {
                if (
                    isManualActivityActive.compareAndSet(true, false) &&
                    client.isReady
                ) {
                    client.sendActivityEnd()
                }
            }
        } ?: logger.w("SessionManager: bounded mic activity finalization timed out")
    }

    private suspend fun startMic() = mutex.withLock {
        micMutex.withLock {
            startMicLocked()
        }
    }

    private suspend fun startMicLocked() {
        if (
            !userMicDesired ||
            !connectionDesired ||
            _state.value.link != LinkState.LIVE ||
            !client.isReady
        ) {
            return
        }

        if (
            _state.value.isMicActive ||
            audioEngine.isCapturing.value
        ) {
            return
        }

        isManualActivityActive.set(false)

        if (!LiveSessionForegroundService.ensureMicrophoneForegroundType()) {
            _state.update {
                it.copy(error = "Не удалось активировать microphone foreground-service type")
            }
            return
        }

        if (!audioEngine.startCapture()) {
            _state.update {
                it.copy(error = "Микрофон недоступен")
            }
            return
        }

        _state.update { it.copy(isMicActive = true) }

        micAudioJob?.cancel()
        micControlJob?.cancel()
        micControlJob = null

        micAudioJob = scope.launch {
            try {
                for (event in audioEngine.micOutput) {
                    if (!isActive) break
                    when (event) {
                        is MicEvent.Audio -> {
                            val audio = event.data
                            try {
                                if (
                                    !currentAadEnabled &&
                                    isManualActivityActive.get() &&
                                    android.os.SystemClock.elapsedRealtime() - manualActivityStartedAtMs > 30_000L &&
                                    isManualActivityActive.compareAndSet(true, false)
                                ) {
                                    logger.w("SessionManager: реплика длиннее 30 с (шум?) -> принудительный activityEnd")
                                    withTimeoutOrNull(500L) { client.sendActivityEnd() }
                                }
                                if (
                                    !forvoPlayer.isPlaying.value &&
                                    !isTextTurnInProgress.get() &&
                                    (currentAadEnabled || isManualActivityActive.get()) &&
                                    connectionDesired &&
                                    client.isReady
                                ) {
                                    client.sendAudioPcm(audio.pcm, audio.length)
                                }
                            } finally {
                                audioEngine.releaseCapturedBuffer(audio.pcm)
                                audioEngine.recycleAudioEvent(audio)
                            }
                        }

                        is MicEvent.Control -> when (event.event) {
                            is AudioStreamControlEvent.SpeechStart -> {
                                if (readerActive()) {
                                    reader.onUserSpeechStart()
                                } else {
                                    // Новая реплика пользователя снимает ручную паузу ответа (кнопка гарнитуры)
                                    // и удержание перебивания, если читалка уже не активна
                                    audioEngine.setPlaybackHold(PlaybackHoldOwner.USER, false)
                                    audioEngine.setPlaybackHold(PlaybackHoldOwner.BARGE_IN, false)
                                }
                                if (!currentAadEnabled && client.isReady) {
                                    isManualActivityActive.set(true)
                                    manualActivityStartedAtMs = android.os.SystemClock.elapsedRealtime()
                                    logger.d("SessionManager: VAD SpeechStart -> activityStart")
                                    client.sendActivityStart()
                                }
                            }

                            is AudioStreamControlEvent.SpeechEnd -> {
                                lastUserTurnEndMs = android.os.SystemClock.elapsedRealtime()
                                if (readerActive()) reader.onUserSpeechEnd()
                                logger.d("SessionManager: VAD SpeechEnd -> ${if (currentAadEnabled) "тишина в потоке" else "activityEnd"} (ушло ${client.getTransportAudioStats().outboundMicBytesDelivered / 32} мс аудио за сессию)")
                                if (currentAadEnabled && client.isReady) {
                                    // Серверный VAD видит конец фразы только по тишине в самом аудиопотоке, а локальный
                                    // VAD обрывает поток сразу после фразы. gemini-3.8-live вдобавок игнорирует
                                    // audioStreamEnd: без досылки тишины длиннее порога сервера ход «зависает»
                                    // (модель молчит). Досылаем порог + 0,7 с тишины кадрами по 20 мс.
                                    val trailingFrames = ((currentServerSilenceMs + 700) / 20).coerceIn(60, 160)
                                    repeat(trailingFrames) { client.sendAudioPcm(trailingSilenceFrame, trailingSilenceFrame.size) }
                                    if (activeLiveCapabilities.endsTurnOnAudioStreamEnd) {
                                        client.sendAudioStreamEnd()
                                    } else {
                                        client.flushAudio()
                                    }
                                } else if (!currentAadEnabled && client.isReady) {
                                    isManualActivityActive.set(false)
                                    withTimeoutOrNull(500L) {
                                        client.sendActivityEnd()
                                    } ?: logger.w("SessionManager: SpeechEnd activityEnd timed out")
                                }
                            }

                            is AudioStreamControlEvent.StreamStop -> {
                                finalizeMicActivityBounded()
                                _state.update { it.copy(isMicActive = false) }
                                break
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.e("SessionManager: mic consumer crashed", t)
            }
        }
    }

    private suspend fun stopMic(userInitiated: Boolean = false) = mutex.withLock {
        micMutex.withLock {
            if (userInitiated) {
                userMicDesired = false
            }
            stopMicLocked()
        }
    }

    private suspend fun stopMicLocked() {
        val hasPhysicalCapture =
            _state.value.isMicActive || audioEngine.isCapturing.value

        if (hasPhysicalCapture) {
            val producerResult = audioEngine.stopCaptureGraceful(1500L)
            if (producerResult == CaptureShutdownResult.FORCED_TIMEOUT) {
                logger.w("SessionManager: producer shutdown forced")
            }
        }

        micAudioJob?.cancel()
        micControlJob?.cancel()
        micAudioJob = null
        micControlJob = null

        audioEngine.drainPendingMicOutput()
        if (hasPhysicalCapture && client.isReady) {
            runCatching { finalizeMicActivityBounded() }
        }
        isManualActivityActive.set(false)
        _state.update { it.copy(isMicActive = false) }
    }

    /**
     * Звук модели проходит два этапа:
     *  1) классификация сразу при приёме (сторож 3.8, решения читалки) — никогда не блокируется;
     *  2) подача в нативную очередь отдельным потребителем [observePlaybackFeed] через [playQueue].
     * Раньше это был один цикл: во время паузы чтения он стоял на заполненной очереди, и ответ модели
     * на вопрос слушателя застревал за ним — пауза «не отпускала».
     */
    private fun observeAudio() = scope.launch {
        launch { observePlaybackFeed() }
        while (isActive) {
            try {
                for (frame in client.audio) {
                    if (!isActive) break

                    var release = true
                    try {
                        if (frame.sessionId != client.sessionId) continue
                        if (frame.epoch != client.epoch) continue

                        turnWatchdog.onAudio(frame.serverTurnId, frame.pcm, frame.pcm.size)

                        if (sessionProfile == SessionProfile.READER) {
                            synchronized(heldInteractionFrames) {
                                val decision = reader.decideAudio(frame.serverTurnId, frame.pcm, frame.pcm.size)
                                // Решение по ходу могло определиться только что (этим кадром): сначала
                                // уходят придержанные ранее кадры, затем текущий — порядок звука сохраняется
                                resolveHeldInteractionFramesLocked()
                                when (decision) {
                                    AudioDecision.DROP -> Unit
                                    AudioDecision.HOLD -> {
                                        heldInteractionFrames.addLast(frame)
                                        release = false
                                    }
                                    AudioDecision.PLAY -> {
                                        if (heldInteractionFrames.any { it.serverTurnId == frame.serverTurnId }) {
                                            heldInteractionFrames.addLast(frame)
                                            release = false
                                        } else {
                                            if (reader.shouldAdopt(frame.serverTurnId)) {
                                                frame.generation = audioEngine.currentPlaybackGeneration
                                            }
                                            release = !enqueueForPlayback(frame)
                                        }
                                    }
                                }
                            }
                        } else {
                            release = !enqueueForPlayback(frame)
                        }
                    } finally {
                        if (release) client.releaseAudio(frame)
                    }
                }

                if (!isActive) break
                delay(50L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.e("SessionManager: audio observer crashed; restarting consumer", t)
                val shouldRecover = mutex.withLock {
                    if (connectionDesired && _state.value.link != LinkState.IDLE) {
                        _state.update { it.copy(link = LinkState.RECONNECTING, isAiSpeaking = false) }
                        true
                    } else false
                }
                if (shouldRecover) runCatching { client.disconnect() }
                delay(100L)
            }
        }
    }

    private fun enqueueForPlayback(frame: AudioFrame): Boolean {
        val accepted = playQueue.trySend(frame).isSuccess
        if (accepted) playQueueDepth.incrementAndGet()
        return accepted
    }

    private suspend fun observePlaybackFeed() {
        for (frame in playQueue) {
            playQueueDepth.decrementAndGet()
            try {
                feedPlayback(frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.e("SessionManager: playback feed failed", t)
            } finally {
                client.releaseAudio(frame)
            }
        }
    }

    private suspend fun feedPlayback(frame: AudioFrame) {
        // Кадры из прежнего соединения доигрываются (уже принятый звук); отсекаются только сброшенные
        if (frame.generation != audioEngine.currentPlaybackGeneration) return

        val now = SystemClock.elapsedRealtime()
        if (frame.isExpired(now)) {
            logger.w("SessionManager: Сброшен просроченный аудиофрейм (${now - frame.timestampMs} ms > TTL ${frame.ttlMs} ms)")
            return
        }

        if (!_state.value.isAiSpeaking) {
            _state.update { it.copy(isAiSpeaking = true) }
        }

        val before = audioEngine.enqueuedFramesInGeneration
        audioEngine.enqueuePlayback(frame.pcm, frame.generation)
        if (sessionProfile == SessionProfile.READER) {
            val written = audioEngine.enqueuedFramesInGeneration - before
            if (written > 0L) reader.onAudioEnqueued(frame.serverTurnId, written)
        }
    }

    /** Вызывается под lock(heldInteractionFrames). */
    private fun resolveHeldInteractionFramesLocked() {
        while (heldInteractionFrames.isNotEmpty()) {
            val head = heldInteractionFrames.first()
            when (reader.interactionDecision(head.serverTurnId)) {
                AudioDecision.HOLD -> return
                AudioDecision.DROP -> {
                    heldInteractionFrames.removeFirst()
                    client.releaseAudio(head)
                }
                AudioDecision.PLAY -> {
                    heldInteractionFrames.removeFirst()
                    if (reader.shouldAdopt(head.serverTurnId)) {
                        head.generation = audioEngine.currentPlaybackGeneration
                    }
                    if (!enqueueForPlayback(head)) client.releaseAudio(head)
                }
            }
        }
    }

    private fun observeHeldInteractionFrames() = scope.launch {
        while (isActive) {
            delay(100L)
            synchronized(heldInteractionFrames) { resolveHeldInteractionFramesLocked() }
        }
    }

    private fun releaseHeldInteractionFrames() {
        synchronized(heldInteractionFrames) {
            while (heldInteractionFrames.isNotEmpty()) client.releaseAudio(heldInteractionFrames.removeFirst())
        }
    }

    /** Сторож «немых» и «беглых» ходов 3.8: проверка каждые 250 мс. */
    private fun observeTurnWatchdog() = scope.launch {
        while (isActive) {
            delay(250L)
            if (!connectionDesired || _state.value.link != LinkState.LIVE) continue
            val verdict = turnWatchdog.check() ?: continue
            when (verdict) {
                is LiveTurnWatchdog.Verdict.SilentTurn -> handleSilentTurn(verdict)
                is LiveTurnWatchdog.Verdict.Runaway -> {
                    logger.w("SessionManager: сервер шлёт тишину после конца ответа (ход ${verdict.turnId}) — остаток отбрасывается")
                    if (!(readerActive() && reader.isReadTurn(verdict.turnId))) {
                        client.suppressActiveServerGeneration()
                    }
                }
            }
        }
    }

    private suspend fun handleSilentTurn(verdict: LiveTurnWatchdog.Verdict.SilentTurn) {
        logger.w("SessionManager: «немой» ход ${verdict.turnId}: текст без звука (${verdict.transcript.length} симв.) — восстановление")
        if (readerActive() && reader.isReadTurn(verdict.turnId)) {
            reader.onSilentReadTurn(verdict.turnId, verdict.voicedTranscriptLength)
            return
        }
        if (sessionProfile == SessionProfile.READER) return

        // Разговор: остаток генерации не играем и просим модель повторить ответ (clientContent с
        // turnComplete=true прерывает «зависший» ход — обход, подтверждённый на форуме Google)
        client.suppressActiveServerGeneration()
        val now = SystemClock.elapsedRealtime()
        val fresh = synchronized(silentRecoveryTimes) {
            silentRecoveryTimes.addLast(now)
            while (silentRecoveryTimes.isNotEmpty() && now - silentRecoveryTimes.first() > 90_000L) {
                silentRecoveryTimes.removeFirst()
            }
            silentRecoveryTimes.size >= 2
        }
        if (fresh) {
            synchronized(silentRecoveryTimes) { silentRecoveryTimes.clear() }
            pendingSilentNudge = true
            requestFreshSessionInternal("повторный «немой» ответ gemini-3.8-live")
        } else if (client.isReady) {
            sendUserText(ReaderPrompts.silentTurnNudge(sessionSpeechLanguage))
        }
    }

    /**
     * Документ дочитан или чтение остановлено, а сессия READER ещё открыта (микрофон для голосовых команд,
     * WebSocket, foreground-сервис): через 10 минут простоя она закрывается — батарея и трафик.
     * Новое «Читать» поднимет сессию заново автоматически.
     */
    private fun observeReaderIdle() = scope.launch {
        var idleSince = 0L
        while (isActive) {
            delay(5_000L)
            val readerProfileIdle = sessionProfile == SessionProfile.READER &&
                _state.value.link != LinkState.IDLE && !reader.isActive
            if (!readerProfileIdle) {
                idleSince = 0L
                continue
            }
            val playing = audioEngine.pendingModelFrames() > 0L || client.isGenerationInProgress
            if (!playing && _state.value.isAiSpeaking) _state.update { it.copy(isAiSpeaking = false) }
            if (playing || audioEngine.isUplinkActive) {
                idleSince = 0L
                continue
            }
            val now = SystemClock.elapsedRealtime()
            if (idleSince == 0L) {
                idleSince = now
                continue
            }
            if (now - idleSince < READER_IDLE_DISCONNECT_MS) continue
            idleSince = 0L
            commandMutex.withLock {
                mutex.withLock {
                    if (sessionProfile == SessionProfile.READER && !reader.isActive && _state.value.link != LinkState.IDLE) {
                        logger.d("SessionManager: читалка простаивает ${READER_IDLE_DISCONNECT_MS / 60_000} мин — сессия закрыта")
                        connectionDesired = false
                        userMicDesired = false
                        stopInternal(full = true)
                    }
                }
            }
        }
    }

    /** Сеть снова доступна — ускоряем переподключение (не ждём конца backoff). */
    private fun observeNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (connectionDesired && _state.value.link == LinkState.RECONNECTING) {
                        logger.d("SessionManager: сеть доступна — немедленное переподключение")
                        networkAvailableSignal.trySend(Unit)
                    }
                }
            })
        }.onFailure { logger.w("SessionManager: NetworkCallback не зарегистрирован: ${it.message}") }
    }

    private fun observeBargeIn() = scope.launch {
        audioEngine.bargeInEvents.collect {
            if (sessionProfile == SessionProfile.READER) {
                // Читалка: перебивание = мгновенное удержание вывода (BargeInMode.HOLD), прочитанное
                // не сбрасывается; дальше решает LiveReaderController (onUserSpeechStart)
                if (!reader.isActive) {
                    // Документ не читается (дочитан/остановлен), а сессия ещё в профиле READER:
                    // перебивание работает как в разговоре, удержание не должно «залипнуть»
                    audioEngine.setPlaybackHold(PlaybackHoldOwner.BARGE_IN, false)
                    client.suppressActiveServerGeneration()
                    invalidateAndFlushAudio("barge-in (reader idle)")
                    _state.update { it.copy(isAiSpeaking = false) }
                }
                return@collect
            }
            if (!_state.value.isAiSpeaking) return@collect
            _state.update {
                it.copy(isAiSpeaking = false)
            }

            resetTranscriptRuntime()
        }
    }

    /**
     * Аудиофокус. Раньше любая потеря фокуса (уведомление, навигатор, голосовой помощник, звонок)
     * СБРАСЫВАЛА принятый звук — модель «глохла» посреди фразы и не возвращалась. Теперь вывод
     * только удерживается (жёсткая пауза в нативном движке) и продолжается с того же сэмпла.
     */
    private fun observeFocus() = scope.launch {
        audioEngine.focusEvents.collect { event ->
            when (event) {
                AudioFocusEvent.Gain -> {
                    val heldForMs = if (focusHoldSinceMs > 0L) SystemClock.elapsedRealtime() - focusHoldSinceMs else 0L
                    focusHoldSinceMs = 0L
                    focusLostPermanently = false
                    // Разговор: ответ, «пролежавший» дольше минуты (звонок), неактуален — не доигрываем
                    if (sessionProfile == SessionProfile.CONVERSATION && heldForMs > STALE_FOCUS_HOLD_MS) {
                        client.suppressActiveServerGeneration()
                        invalidateAndFlushAudio("stale answer after focus loss")
                        _state.update { it.copy(isAiSpeaking = false) }
                    }
                    audioEngine.setPlaybackHold(PlaybackHoldOwner.FOCUS, false)
                    if (readerActive()) reader.onFocusGain()
                    restoreAudioAfterFocusGain()
                }
                AudioFocusEvent.CanDuck -> {
                    // Короткий звук другого приложения (уведомление, подсказка навигатора): речь не
                    // приглушаем, а ставим на паузу — пропущенные слова не теряются
                    holdForFocus()
                    if (readerActive()) reader.onFocusLoss(permanent = false)
                }
                AudioFocusEvent.LossTransient -> {
                    holdForFocus()
                    if (readerActive()) reader.onFocusLoss(permanent = false)
                    mutex.withLock { micMutex.withLock { stopMicLocked() } }
                    _state.update { it.copy(error = "Аудио приостановлено: звонок или другое приложение") }
                }
                AudioFocusEvent.LossPermanent -> {
                    holdForFocus()
                    focusLostPermanently = true
                    if (readerActive()) reader.onFocusLoss(permanent = true)
                    mutex.withLock { micMutex.withLock { stopMicLocked() } }
                    _state.update {
                        it.copy(error = "Звук занят другим приложением. Нажмите «Продолжить» (или Play на гарнитуре)")
                    }
                }
            }
        }
    }

    private fun holdForFocus() {
        if (focusHoldSinceMs == 0L) focusHoldSinceMs = SystemClock.elapsedRealtime()
        audioEngine.setPlaybackHold(PlaybackHoldOwner.FOCUS, true)
    }

    /** Фокус вернулся: перезапуск вывода (если поток закрыт) и микрофона. Вызывать без mutex. */
    private suspend fun restoreAudioAfterFocusGain() {
        mutex.withLock {
            if (connectionDesired && _state.value.link == LinkState.LIVE) {
                if (!audioEngine.isPlaying.value) {
                    runCatching { audioEngine.startPlayback() }
                        .onFailure { logger.w("SessionManager: не удалось восстановить playback после AudioFocus gain: ${it.message}") }
                }
                if (userMicDesired && client.isReady) {
                    micMutex.withLock {
                        if (!_state.value.isMicActive && !audioEngine.isCapturing.value) {
                            startMicLocked()
                        }
                    }
                }
                _state.update { it.copy(error = null) }
            }
        }
    }

    private fun observeEvents() = scope.launch {
        while (isActive) {
            try {
                client.events.collect { envelope ->
                    val event = envelope.event
                    val eventEpoch = envelope.epoch
                    val eventSessionId = envelope.sessionId

                    if (
                        eventSessionId != client.sessionId ||
                        eventEpoch != client.epoch
                    ) {
                        return@collect
                    }

                    activeSessionId = eventSessionId

                    when (event) {
                        is GeminiEvent.ModelText -> {
                            appendTranscript(
                                ClientRole.MODEL,
                                event.text,
                                false,
                                envelope.generationId
                            )
                        }

                        is GeminiEvent.InputTranscript -> {
                            // Резервное распознавание голосовых команд читалки (если модель не вызвала функцию)
                            if (readerActive()) reader.onUserTranscript(event.text, event.interim)
                            appendTranscript(
                                ClientRole.USER,
                                event.text,
                                event.interim,
                                envelope.generationId
                            )
                        }

                        is GeminiEvent.OutputTranscript -> {
                            turnWatchdog.onTranscript(envelope.generationId, event.text)
                            val readTurn = if (sessionProfile == SessionProfile.READER) {
                                reader.onModelTranscript(envelope.generationId, event.text, event.turnAudioBytes)
                                reader.isReadTurn(envelope.generationId)
                            } else {
                                false
                            }
                            // Прочитанный документ в чат не дублируется (он уже на экране и в памяти читалки);
                            // ответы диктора на вопросы — показываются
                            if (!readTurn) {
                                recordTranscriptGeneration(envelope.generationId)

                                appendTranscript(
                                    ClientRole.MODEL,
                                    event.text,
                                    false,
                                    envelope.generationId
                                )
                            }
                        }

                        is GeminiEvent.Usage -> {
                            _state.update {
                                it.copy(
                                    tokensUsed = event.totalTokens
                                )
                            }
                        }

                        else -> {
                            mutex.withLock {
                                if (
                                    envelope.sessionId != client.sessionId ||
                                    envelope.epoch != client.epoch
                                ) {
                                    return@withLock
                                }

                                when (event) {
                                    is GeminiEvent.SetupComplete -> {
                                        reconnectAttempts = 0
                                        pendingGoAway = false
                                        goAwayToken.incrementAndGet()
                                        interactionStatus = null
                                        turnCompleteSeen = false

                                        synchronized(reconnectGuard) {
                                            goAwayJob?.cancel()
                                            goAwayJob = null
                                        }

                                        _state.update {
                                            it.copy(
                                                link = LinkState.LIVE,
                                                error = null
                                            )
                                        }

                                        if (
                                            userMicDesired &&
                                            connectionDesired
                                        ) {
                                            micMutex.withLock {
                                                startMicLocked()
                                            }
                                        }

                                        if (sessionProfile == SessionProfile.READER) {
                                            reader.onSessionReady()
                                        } else if (pendingSilentNudge) {
                                            // Новая сессия после повторного «немого» ответа: история уже
                                            // передана (initialHistory) — просим повторить ответ
                                            pendingSilentNudge = false
                                            scope.launch { sendUserText(ReaderPrompts.silentTurnNudge(sessionSpeechLanguage)) }
                                        }
                                    }

                                    is GeminiEvent.ResumptionHandle -> {
                                        val usableHandle =
                                            event.handle
                                                ?.trim()
                                                ?.takeIf {
                                                    event.resumable &&
                                                        it.isNotBlank()
                                                }

                                        if (usableHandle != null) {
                                            resumptionHandle = usableHandle
                                            resumptionPersistenceMutex.withLock {
                                                when (val encrypted = cryptoManager.encrypt(usableHandle)) {
                                                    is com.client.app.util.CryptoResult.Success -> {
                                                        dataStore.edit { prefs ->
                                                            prefs[KEY_SESSION_RESUMPTION_HANDLE] =
                                                                encrypted.value
                                                            prefs[KEY_SESSION_RESUMPTION_TIMESTAMP] =
                                                                System.currentTimeMillis()
                                                        }
                                                    }
                                                    com.client.app.util.CryptoResult.Missing -> {
                                                        logger.w("SessionManager: resumption handle unexpectedly encrypted as missing")
                                                    }
                                                    is com.client.app.util.CryptoResult.Failure -> {
                                                        logger.e(
                                                            "SessionManager: could not persist Live session resumption handle: ${encrypted.reason}",
                                                            encrypted.cause
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        activeConnectUsedResumption = false
                                    }

                                    is GeminiEvent.InteractionStatus -> {
                                        interactionStatus =
                                            event.status.trim().uppercase()

                                        if (
                                            interactionStatus ==
                                            "IN_PROGRESS"
                                        ) {
                                            _state.update {
                                                it.copy(isAiSpeaking = true)
                                            }
                                        } else if (
                                            interactionStatus == "IDLE" &&
                                            clientCapabilitiesForCurrentSession()
                                                .supportsInteractionStatus
                                        ) {
                                            finishInteractionAfterPlayback(
                                                sourceSessionId = eventSessionId,
                                                sourceEpoch = eventEpoch,
                                                shouldPlanGoAway = pendingGoAway,
                                                capabilities =
                                                    clientCapabilitiesForCurrentSession()
                                            )
                                        }
                                    }

                                    is GeminiEvent.GoAway -> {
                                        val sourceEpoch = eventEpoch
                                        val timerToken =
                                            goAwayToken.incrementAndGet()

                                        pendingGoAway = true
                                        logger.d("SessionManager: goAway (осталось ${event.millisLeft ?: 0L} мс) — переподключение в тихий момент")

                                        synchronized(reconnectGuard) {
                                            goAwayJob?.cancel()

                                            goAwayJob = scope.launch {
                                                try {
                                                    val millisLeft = event.millisLeft ?: 0L
                                                    val deadline = SystemClock.elapsedRealtime() +
                                                        (millisLeft - 1500L).coerceAtLeast(0L)
                                                    while (SystemClock.elapsedRealtime() < deadline) {
                                                        if (isQuietMomentForReconnect()) break
                                                        delay(100L)
                                                    }

                                                    if (
                                                        timerToken ==
                                                            goAwayToken.get() &&
                                                        pendingGoAway &&
                                                        connectionDesired &&
                                                        client.epoch ==
                                                            sourceEpoch
                                                    ) {
                                                        pendingGoAway = false
                                                        goAwayToken.incrementAndGet()

                                                        scheduleReconnect(
                                                            "плановый переход goAway",
                                                            sourceEpoch,
                                                            immediate = true
                                                        )
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (t: Throwable) {
                                                    logger.e(
                                                        "SessionManager: GoAway scheduler failed",
                                                        t
                                                    )
                                                } finally {
                                                    synchronized(reconnectGuard) {
                                                        if (
                                                            goAwayJob ==
                                                            coroutineContext[Job]
                                                        ) {
                                                            goAwayJob = null
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is GeminiEvent.Interrupted -> {
                                        lastModelTurnEndMs = SystemClock.elapsedRealtime()
                                        turnWatchdog.onTurnClosed(envelope.generationId)
                                        audioEngine.resetBargeInState()

                                        if (sessionProfile == SessionProfile.READER) {
                                            // Прервана только текущая генерация (новой репликой); уже
                                            // принятые фрагменты продолжают звучать — читалка дочитает остаток
                                            reader.onServerInterrupted(envelope.generationId)
                                        } else {
                                            _state.update {
                                                it.copy(isAiSpeaking = false)
                                            }
                                        }

                                        resetTranscriptRuntime()
                                        turnCompleteSeen = false
                                        interactionStatus = null
                                    }

                                    is GeminiEvent.GenerationComplete -> {
                                        if (sessionProfile == SessionProfile.READER) {
                                            reader.onGenerationComplete(envelope.generationId)
                                        }
                                    }

                                    is GeminiEvent.TurnComplete -> {
                                        lastModelTurnEndMs = SystemClock.elapsedRealtime()
                                        turnWatchdog.onTurnClosed(envelope.generationId)
                                        turnCompleteSeen = true
                                        val capabilities =
                                            clientCapabilitiesForCurrentSession()

                                        val readTurn = sessionProfile == SessionProfile.READER &&
                                            reader.isReadTurn(envelope.generationId)
                                        if (sessionProfile == SessionProfile.READER) {
                                            reader.onTurnComplete(envelope.generationId)
                                        }

                                        // Фрагмент чтения — не «реплика»: за ним сразу идёт следующий,
                                        // индикатор и сброс состояния ответа не нужны
                                        if (readTurn) {
                                            turnCompleteSeen = false
                                        } else if (
                                            !capabilities.supportsInteractionStatus ||
                                            interactionStatus == "IDLE"
                                        ) {
                                            finishInteractionAfterPlayback(
                                                sourceSessionId =
                                                    eventSessionId,
                                                sourceEpoch = eventEpoch,
                                                shouldPlanGoAway =
                                                    pendingGoAway,
                                                capabilities = capabilities
                                            )
                                        }
                                    }

                                    is GeminiEvent.ToolCall -> {
                                        handleToolCall(
                                            calls = event.calls,
                                            sourceSessionId = eventSessionId,
                                            sourceEpoch = eventEpoch
                                        )
                                    }

                                    is GeminiEvent.ToolCallCancelled -> {
                                        event.ids.forEach { id ->
                                            val key =
                                                ToolCallKey(
                                                    sessionId =
                                                        eventSessionId,
                                                    epoch =
                                                        eventEpoch,
                                                    callId = id
                                                )

                                            toolResponseMutex.withLock {
                                                cancelledToolCallKeys.add(key)
                                                activeToolJobs
                                                    .remove(key)
                                                    ?.cancel()
                                            }
                                        }
                                    }

                                    is GeminiEvent.Error -> {
                                        _state.update {
                                            it.copy(
                                                error =
                                                    event.message
                                            )
                                        }

                                        if (event.fatal) {
                                            activeConnectUsedResumption =
                                                false
                                            connectionDesired = false
                                            userMicDesired = false
                                            cancelReconnectWork()
                                            stopInternal(full = true)
                                        }
                                    }

                                    is GeminiEvent.Disconnected -> {
                                        if (
                                            event.sessionId !=
                                            client.sessionId ||
                                            event.epoch !=
                                            client.epoch
                                        ) {
                                            return@withLock
                                        }

                                        cancelAllPendingToolJobs()
                                        turnWatchdog.reset()
                                        // Фрагмент, генерация которого оборвалась, читалка дочитает после переподключения
                                        if (sessionProfile == SessionProfile.READER) reader.onSessionLost()

                                        val configRejectCodes = setOf(400, 403, 404, 1007, 1008)
                                        val resumeRejected =
                                            activeConnectUsedResumption && event.code in configRejectCodes

                                        // gemini-3.8-live рвёт уже работающие сессии кодом 1008 (форум Google,
                                        // окт. 2026) — это не ошибка конфигурации: чистая сессия, но не по кругу
                                        val wasLive = _state.value.link == LinkState.LIVE
                                        val transientPolicyClose = !resumeRejected && wasLive &&
                                            event.code == 1008 && allowPolicyCloseRetry()

                                        if (resumeRejected || transientPolicyClose) {
                                            logger.w(
                                                "SessionManager: Сервер отклонил токен resumption (${event.code}). Сброс кэша и перезапуск чистой сессии."
                                            )
                                            resumptionHandle = null
                                            activeConnectUsedResumption = false
                                            scope.launch {
                                                resumptionPersistenceMutex.withLock {
                                                    dataStore.edit { prefs ->
                                                        prefs.remove(KEY_SESSION_RESUMPTION_HANDLE)
                                                        prefs.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                                                    }
                                                }
                                            }
                                        }

                                        val authOrClientFatal = !resumeRejected && !transientPolicyClose &&
                                            (event.code == 401 || event.code in configRejectCodes)

                                        if (authOrClientFatal) {
                                            logger.e(
                                                "SessionManager: Фатальная ошибка клиента (${event.code}). Остановка сессии."
                                            )
                                            _state.update {
                                                it.copy(error = "Сервер закрыл сессию (${event.code}): ${event.reason.ifBlank { "без описания" }}")
                                            }
                                            connectionDesired = false
                                            userMicDesired = false
                                            cancelReconnectWork()
                                            stopInternal(full = true)
                                            return@withLock
                                        }

                                        if (connectionDesired) {
                                            if (
                                                _state.value.isMicActive ||
                                                audioEngine.isCapturing.value
                                            ) {
                                                micMutex.withLock {
                                                    stopMicLocked()
                                                }
                                            }

                                            scheduleReconnect(
                                                reason =
                                                    if (resumeRejected || transientPolicyClose) {
                                                        "сервер закрыл сессию (${event.code}), запуск чистой сессии"
                                                    } else {
                                                        "код ${event.code}: ${event.reason.take(120)}"
                                                    },
                                                sourceEpoch =
                                                    event.epoch,
                                                immediate = resumeRejected || transientPolicyClose
                                            )
                                        }
                                    }

                                    is GeminiEvent.Connected -> Unit
                                    is GeminiEvent.GroundingMetadata,
                                    is GeminiEvent.UrlContextMetadata -> Unit
                                    else -> Unit
                                }
                            }
                        }
                    }
                }

                if (!isActive) break
                delay(50L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.e("SessionManager: event observer crashed; restarting consumer", t)
                val shouldRecover = mutex.withLock {
                    if (connectionDesired && _state.value.link != LinkState.IDLE) {
                        _state.update {
                            it.copy(
                                link = LinkState.RECONNECTING,
                                isAiSpeaking = false
                            )
                        }
                        true
                    } else false
                }
                if (shouldRecover) {
                    runCatching { client.disconnect() }
                }
                delay(100L)
            }
        }
    }

    private fun clientCapabilitiesForCurrentSession(): LiveModelCapabilities =
        activeLiveCapabilities

    /**
     * Можно ли переподключиться (goAway) без потерь. Уже принятый звук переживает переподключение
     * (preservePlayback), поэтому ждём не конца ВОСПРОИЗВЕДЕНИЯ, как раньше, а только конца генерации,
     * передачи реплики пользователя и ответа на неё. Раньше переподключение приходилось ровно на момент,
     * когда модель договорила и пользователь начинал отвечать — его первые слова терялись.
     */
    private fun isQuietMomentForReconnect(): Boolean {
        if (client.isGenerationInProgress || client.hasPendingAudioFrames) return false
        if (audioEngine.isUplinkActive || isManualActivityActive.get() || isTextTurnInProgress.get()) return false
        if (activeToolJobs.isNotEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        // Реплика пользователя ушла, а ответ ещё не начат — переподключение потеряло бы вопрос
        val awaitingAnswer = lastUserTurnEndMs > lastModelTurnEndMs && now - lastUserTurnEndMs < 8_000L
        if (awaitingAnswer) return false
        return sessionProfile != SessionProfile.READER || reader.canReconnectNow()
    }

    private fun allowPolicyCloseRetry(): Boolean = synchronized(policyCloseTimes) {
        val now = SystemClock.elapsedRealtime()
        while (policyCloseTimes.isNotEmpty() && now - policyCloseTimes.first() > 120_000L) {
            policyCloseTimes.removeFirst()
        }
        if (policyCloseTimes.size >= 2) return@synchronized false
        policyCloseTimes.addLast(now)
        true
    }

    private fun finishInteractionAfterPlayback(
        sourceSessionId: Long,
        sourceEpoch: Long,
        shouldPlanGoAway: Boolean,
        capabilities: LiveModelCapabilities
    ) {
        scope.launch {
            val generation = audioEngine.currentPlaybackGeneration

            val ingressDeadline = SystemClock.elapsedRealtime() + 2500L
            // Звук ещё в пути: в канале транспорта или в очереди подачи в движок
            while ((client.hasPendingAudioFrames || playQueueDepth.get() > 0) &&
                SystemClock.elapsedRealtime() < ingressDeadline
            ) {
                if (client.sessionId != sourceSessionId || client.epoch != sourceEpoch || !connectionDesired) {
                    return@launch
                }
                delay(20L)
            }

            delay(60L)

            val drained = audioEngine.awaitPlaybackDrained(generation = generation, stallTimeoutMs = 2500L)
            // Активная читалка сама следит за своим буфером: «зависание» здесь — это её законная пауза
            if (!drained && audioEngine.currentPlaybackGeneration == generation && !readerActive()) {
                logger.w("SessionManager: playback drain watchdog detected hardware stall; clearing stalled queue")
                invalidateAndFlushAudio("playback stall recovery")
            }

            // Ответ доигран: индикатор снимается, даже если за это время сессия переподключилась (goAway)
            if (client.epoch != sourceEpoch && !client.isGenerationInProgress && audioEngine.pendingModelFrames() == 0L) {
                _state.update { it.copy(isAiSpeaking = false) }
            }

            val shouldReconnect =
                mutex.withLock {
                    val lifecycleComplete =
                        if (capabilities.supportsInteractionStatus) {
                            interactionStatus == "IDLE"
                        } else {
                            turnCompleteSeen
                        }

                    if (
                        !connectionDesired ||
                        activeSessionId != sourceSessionId ||
                        client.sessionId != sourceSessionId ||
                        client.epoch != sourceEpoch ||
                        _state.value.link == LinkState.IDLE ||
                        !lifecycleComplete
                    ) {
                        return@withLock false
                    }

                    turnCompleteSeen = false
                    interactionStatus = null
                    resetTranscriptRuntime()
                    audioEngine.resetBargeInState()

                    _state.update {
                        it.copy(isAiSpeaking = false)
                    }

                    pendingGoAway = false
                    goAwayToken.incrementAndGet()

                    synchronized(reconnectGuard) {
                        goAwayJob?.cancel()
                        goAwayJob = null
                    }

                    shouldPlanGoAway
                }

            if (shouldReconnect) {
                scheduleReconnect(
                    "плановый переход goAway",
                    sourceEpoch,
                    immediate = true
                )
            }
        }
    }

    private suspend fun handleToolCall(
        calls: List<FunctionCall>,
        sourceSessionId: Long,
        sourceEpoch: Long
    ) {
        if (
            sourceSessionId != client.sessionId ||
            sourceEpoch != client.epoch
        ) return

        val currentSessionId = sourceSessionId
        val currentEpoch = sourceEpoch

        for (call in calls) {
            val callId = call.id

            if (callId.isNullOrBlank()) {
                logger.w("SessionManager: игнорирован tool call без id: ${call.name}")
                continue
            }

            val key = ToolCallKey(
                currentSessionId,
                currentEpoch,
                callId
            )

            if (ReaderPrompts.isReaderTool(call.name)) {
                // Управление читалкой: ответ SILENT (модель не комментирует команду), затем выполнение —
                // в этом порядке, чтобы следующий фрагмент не обогнал toolResponse
                client.sendToolResponses(
                    listOf(
                        ToolResponse(
                            name = call.name,
                            id = callId,
                            response = ReaderPrompts.okResult(),
                            scheduling = if (clientCapabilitiesForCurrentSession().supportsFunctionScheduling) {
                                FunctionResponseScheduling.SILENT
                            } else {
                                null
                            }
                        )
                    )
                )
                if (sessionProfile == SessionProfile.READER) {
                    // Здесь удерживается mutex: только вариант без перезапуска микрофона
                    if (call.name == ReaderPrompts.TOOL_RESUME) reacquireFocusLocked()
                    reader.onToolCall(call.name, call.args)
                }
                continue
            }

            if (call.name != "lookup_pronunciation") {
                client.sendToolResponses(
                    listOf(
                        ToolResponse(
                            name = call.name,
                            id = callId,
                            response = buildJsonObject {
                                put(
                                    "error",
                                    "unknown_tool"
                                )
                            },
                            scheduling =
                                FunctionResponseScheduling.SILENT
                        )
                    )
                )

                continue
            }

            val list = call.getStringList("words")
                .ifEmpty {
                    call.getString("words")
                        .split(",", ";")
                        .map { it.trim() }
                }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(40)

            val lang = call.getString(
                "language",
                default = "de"
            ).ifBlank {
                "de"
            }

            val job =
                scope.launch(
                    Dispatchers.IO,
                    start = CoroutineStart.LAZY
                ) {
                    try {
                        if (list.isNotEmpty()) {
                            val existing =
                                _state.value.forvoWords
                                    .map {
                                        it.query.lowercase()
                                    }
                                    .toSet()

                            val fresh =
                                list.filter {
                                    it.lowercase() !in existing
                                }

                            if (fresh.isNotEmpty()) {
                                _state.update { s ->
                                    s.copy(
                                        forvoWords =
                                            s.forvoWords +
                                                fresh.map {
                                                    ForvoWord(
                                                        word = it,
                                                        query = it,
                                                        language = lang
                                                    )
                                                }
                                    )
                                }

                                forvoRepo.lookupBatch(
                                    fresh,
                                    lang
                                ) { q, res ->
                                    if (
                                        currentEpoch != client.epoch ||
                                        cancelledToolCallKeys.contains(key) ||
                                        !coroutineContext.isActive
                                    ) {
                                        return@lookupBatch
                                    }

                                    _state.update { s ->
                                        s.copy(
                                            forvoWords =
                                                s.forvoWords.map { w ->
                                                    if (
                                                        !w.query.equals(
                                                            q,
                                                            true
                                                        )
                                                    ) {
                                                        w
                                                    } else {
                                                        when (res) {
                                                            is ForvoResult.Found ->
                                                                w.copy(
                                                                    audioUrl =
                                                                        res.pronunciation.mp3Url,
                                                                    isLoading =
                                                                        false,
                                                                    notFound =
                                                                        false
                                                                )

                                                            else ->
                                                                w.copy(
                                                                    isLoading =
                                                                        false,
                                                                    notFound =
                                                                        true
                                                                )
                                                        }
                                                    }
                                                }
                                        )
                                    }
                                }
                            }
                        }

                        val sent = toolResponseMutex.withLock {
                            if (
                                currentSessionId != client.sessionId ||
                                currentEpoch != client.epoch ||
                                cancelledToolCallKeys.contains(key) ||
                                !isActive
                            ) {
                                return@withLock false
                            }

                            val respPayload =
                                buildJsonObject {
                                    put("status", "ok")
                                    put(
                                        "accepted_words_count",
                                        list.size
                                    )
                                }

                            client.sendToolResponses(
                                listOf(
                                    ToolResponse(
                                        name = call.name,
                                        id = callId,
                                        response = respPayload,
                                        scheduling =
                                            if (
                                                clientCapabilitiesForCurrentSession()
                                                    .supportsFunctionScheduling
                                            ) {
                                                FunctionResponseScheduling.WHEN_IDLE
                                            } else {
                                                null
                                            },
                                        willContinue = false
                                    )
                                )
                            )
                        }

                        if (!sent) {
                            logger.w(
                                "SessionManager: FunctionResponse не отправлен для callId=$callId"
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        logger.e("SessionManager: tool $callId failed", t)
                        if (currentSessionId == client.sessionId && currentEpoch == client.epoch) {
                            client.sendToolResponses(
                                listOf(
                                    ToolResponse(
                                        name = call.name,
                                        id = callId,
                                        response = buildJsonObject {
                                            put("error", t.message ?: "tool_execution_failed")
                                        },
                                        scheduling = if (clientCapabilitiesForCurrentSession().supportsFunctionScheduling) FunctionResponseScheduling.WHEN_IDLE else null
                                    )
                                )
                            )
                        }
                    } finally {
                        activeToolJobs.remove(key)
                    }
                }

            val existing =
                activeToolJobs.putIfAbsent(
                    key,
                    job
                )

            if (existing == null) {
                if (
                    currentEpoch == client.epoch &&
                    !cancelledToolCallKeys.contains(key)
                ) {
                    job.start()
                } else {
                    job.cancel()
                    activeToolJobs.remove(
                        key,
                        job
                    )
                }
            } else {
                logger.w("SessionManager: duplicate active tool call ignored: $key")
            }
        }
    }

    private fun appendTranscript(
        role: ClientRole,
        text: String,
        interim: Boolean,
        generationId: Long
    ) {
        synchronized(transcriptLock) {
            _state.update { state ->
                val list = state.messages.toMutableList()

                if (interim) {
                    val idx = list.indexOfLast {
                        it.role == role && it.interim
                    }

                    val message = ChatMessage(
                        role = role,
                        text = text,
                        interim = true
                    )

                    if (idx >= 0) {
                        list[idx] = list[idx].copy(text = text)
                    } else {
                        list.add(message)
                    }

                    state.copy(messages = list.takeLast(MAX_MESSAGES))
                } else {
                    list.removeAll {
                        it.role == role && it.interim
                    }

                    val last = list.lastOrNull()
                    if (
                        last != null &&
                        last.role == role &&
                        !last.interim &&
                        transcriptStreamGenerationId == generationId
                    ) {
                        val previous = last.text
                        val incoming = text
                        val previousComparable =
                            normalizeTranscriptForComparison(previous)
                        val incomingComparable =
                            normalizeTranscriptForComparison(incoming)

                        val mergedText = when {
                            incomingComparable.isBlank() -> previous
                            previousComparable.isBlank() -> incoming
                            previousComparable == incomingComparable -> previous
                            incomingComparable.startsWith(previousComparable) -> incoming
                            previousComparable.startsWith(incomingComparable) -> previous
                            else -> joinTranscriptChunks(previous, incoming)
                        }

                        list[list.lastIndex] = last.copy(text = mergedText)
                    } else {
                        list.add(
                            ChatMessage(
                                role = role,
                                text = text
                            )
                        )
                    }

                    state.copy(messages = list.takeLast(MAX_MESSAGES))
                }
            }

            transcriptStreamGenerationId = generationId
        }
    }

    private fun normalizeTranscriptForComparison(text: String): String =
        text
            .trim()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', ',', '!', '?', ';', ':', '…')
            .lowercase()

    private fun joinTranscriptChunks(
        previous: String,
        incoming: String
    ): String {
        if (previous.isEmpty()) return incoming
        if (incoming.isEmpty()) return previous
        if (previous.last().isWhitespace() || incoming.first().isWhitespace()) {
            return previous + incoming
        }

        val noLeadingSpace = incoming.first() in ".,!?;:%)]}\"»”’…-–—"
        val noTrailingSpace = previous.last() in "([{\"«“‘"

        return if (noLeadingSpace || noTrailingSpace) {
            previous + incoming
        } else {
            "$previous $incoming"
        }
    }

    private fun resetTranscriptRuntime() {
        synchronized(transcriptLock) {
            transcriptStreamGenerationId = -1L
        }
    }

    private fun recordTranscriptGeneration(generationId: Long) {
        synchronized(transcriptLock) {
            transcriptStreamGenerationId = generationId
        }
    }

    private fun addMessage(msg: ChatMessage) {
        synchronized(transcriptLock) {
            transcriptStreamGenerationId = -1L
            _state.update {
                it.copy(
                    messages = (it.messages + msg).takeLast(MAX_MESSAGES)
                )
            }
        }
    }

    private fun decryptStoredValue(
        encryptedValue: String,
        label: String
    ): String? = when (val result = cryptoManager.decrypt(encryptedValue)) {
        is com.client.app.util.CryptoResult.Success -> result.value
        com.client.app.util.CryptoResult.Missing -> ""
        is com.client.app.util.CryptoResult.Failure -> {
            logger.e(
                "SessionManager: failed to decrypt $label: ${result.reason}",
                result.cause
            )
            null
        }
    }

    private fun observeSettings() = scope.launch {
        dataStore.data.collect { prefs ->
            prefs[KEY_SYSTEM_PROMPT]?.let { stored ->
                if (stored != _state.value.activePrompt) {
                    _state.update { it.copy(activePrompt = stored) }
                }
            }
            currentVolume = prefs[KEY_VOLUME] ?: 1.0f
            audioEngine.setVolume(currentVolume)
            audioEngine.setMicGain(prefs[KEY_MIC_GAIN] ?: 1.0f)
            audioEngine.setBluetoothHiFiEnabled(prefs[KEY_BT_HIFI_MODE] ?: DEFAULT_BT_HIFI)
            readerVoiceCommands = prefs[KEY_READER_VOICE_COMMANDS] ?: true
            if (!reader.isActive) prefs[KEY_READER_PACE]?.let { reader.setPace(ReaderPace.from(it)) }
            if (prefs[KEY_SESSION_RESUMPTION_ENABLED] == false) {
                val hasStoredHandle = !prefs[KEY_SESSION_RESUMPTION_HANDLE].isNullOrBlank()
                val hasStoredTimestamp = prefs[KEY_SESSION_RESUMPTION_TIMESTAMP] != null

                if (hasStoredHandle || hasStoredTimestamp) {
                    resumptionPersistenceMutex.withLock {
                        dataStore.edit {
                            it.remove(KEY_SESSION_RESUMPTION_HANDLE)
                            it.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                        }
                    }
                }
                resumptionHandle = null
            }
        }
    }

    private fun startForegroundService(): String? {
        LiveSessionForegroundService.prepareForStart()
        val intent = Intent(context, LiveSessionForegroundService::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            null
        }.getOrElse { throwable ->
            logger.e("SessionManager: Failed to request foreground service", throwable)
            throwable.localizedMessage ?: throwable.javaClass.simpleName
        }
    }

    private suspend fun ensureForegroundServiceActive(): String? {
        if (LiveSessionForegroundService.isServiceActive.value) {
            return null
        }

        startForegroundService()?.let { return it }

        val activated = withTimeoutOrNull(3000L) {
            while (!LiveSessionForegroundService.isServiceActive.value) {
                LiveSessionForegroundService.serviceError.value?.let {
                    return@withTimeoutOrNull false
                }
                delay(25L)
            }
            true
        } == true

        if (activated) return null

        return LiveSessionForegroundService.serviceError.value
            ?: "Foreground service не перешёл в активное состояние за 3000 мс"
    }

    private fun stopForegroundService() {
        runCatching {
            context.stopService(
                Intent(context, LiveSessionForegroundService::class.java)
            )
        }
    }
}

