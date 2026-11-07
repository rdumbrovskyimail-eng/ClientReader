package com.client.app.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Транспорт Live API, видимый читалке (реализует SessionManager поверх GeminiProtobufLiveClient). */
interface ReaderTransportPort {
    /** setupComplete получен, реплики можно отправлять. */
    fun isSessionReady(): Boolean
    /** Меняется при каждом переподключении (epoch). */
    fun sessionKey(): Long
    /** Реплика пользователя: clientContent с turnComplete=true (для 3.8 — с любой точки сессии). */
    suspend fun sendUserTurn(text: String): Boolean
    fun isGenerationInProgress(): Boolean
    fun activeServerTurnId(): Long
    /** Принятое из сети, но ещё не переданное в аудиодвижок аудио модели (кадры 24 кГц). */
    fun transportBacklogFrames(): Long
}

/** Аудиодвижок, видимый читалке. Кадры — 24 кГц (формат вывода Gemini). */
interface ReaderAudioPort {
    fun setReaderHold(held: Boolean)
    fun setBargeInHold(held: Boolean)
    fun playbackGeneration(): Long
    /** Кадры, записанные в движок в текущем поколении воспроизведения. */
    fun enqueuedFrames(): Long
    /** Ещё не прозвучавшие кадры в буферах движка. */
    fun pendingFrames(): Long
    fun flushPlayback(reason: String): Long
    fun adjustVolume(up: Boolean): Float
}

interface ReaderHost {
    /** Поднять/переключить Live-сессию в профиль READER и дождаться готовности. */
    suspend fun ensureReaderSession(): Boolean
    fun isGoAwayPending(): Boolean
    /** Плановое переподключение (goAway) без сброса уже принятого звука. */
    fun requestPlannedReconnect(reason: String)
    /** Новая сессия без resumption (обход повторных «немых» ответов сервера). */
    fun requestFreshSession(reason: String)
    fun onUiState(state: ReaderUiState)
    fun log(message: String)
    fun now(): Long
}

/**
 * Live-читалка поверх gemini-3.8-live.
 *
 * Принцип: документ режется на фрагменты из целых предложений; каждый фрагмент — отдельная реплика
 * `clientContent(turnComplete=true)`. Следующий фрагмент запрашивается, пока предыдущий ещё звучит
 * (модель генерирует быстрее реального времени), поэтому чтение непрерывно, а позиция известна
 * с точностью до предложения в любой момент.
 *
 * Пауза — жёсткая пауза вывода в нативном движке: очередь не читается, продолжение начинается с того же
 * сэмпла. Если буфер пришлось освободить (ответ на вопрос, перемотка, смена маршрута), чтение
 * продолжается с начала прерванного предложения.
 *
 * Голос: слушатель перебивает → вывод мгновенно удерживается → реплика уходит в ту же Live-сессию
 * (activityHandling = NO_INTERRUPTION: начатый фрагмент не обрывается) → модель вызывает функцию
 * управления или коротко отвечает на вопрос. Резерв — разбор входной транскрипции по ключевым словам.
 *
 * Самоконтроль: по выходной транскрипции и длительности звука проверяется, что фрагмент прочитан
 * целиком; недочитанный остаток запрашивается повторно с нужного предложения. Отдельно обрабатываются
 * известные сбои 3.8: «немой» ответ (текст без звука), «беглое» аудио (тишина без turnComplete).
 */
class LiveReaderController(
    private val scope: CoroutineScope,
    private val transport: ReaderTransportPort,
    private val audio: ReaderAudioPort,
    private val host: ReaderHost,
    private val store: ReaderProgressStore?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val segmenter: ReadingTextSegmenter = ReadingTextSegmenter()
) {

    companion object {
        const val FRAMES_PER_MS = 24L
        private const val TICK_MS = 120L
        private const val LOOKAHEAD_LOW_MS = 35_000L
        private const val TURN_START_TIMEOUT_MS = 12_000L
        private const val TURN_IDLE_COMPLETE_MS = 3_500L
        private const val GENERATION_BUSY_OVERRIDE_MS = 8_000L
        private const val INTERACTION_DECISION_MS = 700L
        private const val IMPLICIT_AUTORESUME_MS = 4_500L
        private const val KEYWORD_FALLBACK_DELAY_MS = 1_300L
        private const val CHATTER_SUPPRESS_MS = 3_000L
        private const val SILENCE_CLAMP_MS = 1_500.0
        private const val MAX_RETRIES_PER_SEGMENT = 2
        private const val PERSIST_INTERVAL_MS = 3_000L
        private const val UI_INTERVAL_MS = 250L
        private const val SESSION_RETRY_MS = 3_000L
        private const val SILENCE_RMS = 0.0015
        private const val MIN_INTACT_BUFFER_FRAMES = FRAMES_PER_MS * 150
        private const val SILENT_RECOVERY_WINDOW_MS = 120_000L
        private const val ORPHAN_WAIT_MS = 4_000L
        private const val POST_STOP_DROP_MS = 6_000L
    }

    private class ReadTurn(
        val localId: Long,
        val segment: Int,
        val fromSentence: Int,
        val text: String,
        val sentenceOffsets: IntArray,
        val expectedMs: Long,
        val sentAtMs: Long,
        val sessionKey: Long
    ) {
        var serverTurnId = -1L
        var generation = -1L
        var startFrame = -1L
        var endFrame = -1L
        var receivedFrames = 0L
        var voicedMs = 0.0
        var silentRunMs = 0.0
        var lastVoicedAtMs = 0L
        val transcript = StringBuilder()
        val marks = ArrayList<LongArray>()
        var completed = false
        var suppressed = false
        var discarded = false
        var coverageReachedAtMs = 0L
    }

    private class InteractionTurn(val serverTurnId: Long, val firstSeenAtMs: Long) {
        var decision: AudioDecision = AudioDecision.HOLD
        var adopted = false
    }

    private class SendPlan(val turn: ReadTurn, val payload: String)

    private data class Playhead(val position: ReaderPosition, val fractionInSentence: Float, val absoluteChar: Int)

    private val lock = Any()

    private var document: ReaderDocument? = null
    private var status = ReaderStatus.IDLE
    private var pauseReason = PauseReason.NONE
    private var pace = ReaderPace.MEDIUM

    private var cursor = ReaderPosition.START
    private var pausedAt: ReaderPosition? = null
    private var pausedGeneration = -1L
    private var answerAdoptedSincePause = false

    private val timeline = ArrayList<ReadTurn>()
    private val turnsByServerId = LinkedHashMap<Long, ReadTurn>()
    private val interactions = LinkedHashMap<Long, InteractionTurn>()
    private var inFlight: ReadTurn? = null
    private var sendInProgress = false
    private var nextSendNotBeforeMs = 0L
    private var turnSeq = 0L
    private var lastBoundServerTurnId = -1L
    private var generationBusySinceMs = 0L
    private var startStalls = 0

    private var userSpeaking = false
    private var lastUserSpeechAtMs = 0L
    private val userUtterance = StringBuilder()
    private var keywordPending: ReaderCommand? = null
    private var keywordAtMs = 0L
    private var toolHandledAtMs = 0L
    private var suppressChatterUntilMs = 0L
    private var implicitResumeAtMs = 0L

    private val retries = HashMap<Int, Int>()
    private val silentRecoveries = ArrayDeque<Long>()
    private var goAwayReconnectRequested = false
    private var sessionRequestInFlight = false
    private var nextSessionAttemptMs = 0L
    private var lastAudioArrivalMs = 0L

    // Отменённый до начала генерации фрагмент (см. claimOrphanLocked)
    private var orphanSinceMs = 0L
    // После «стоп» хвост уже запрошенной генерации не должен прозвучать
    private var dropAllUntilMs = 0L
    // Поколение воспроизведения, известное читалке: чужой сброс буфера (смена маршрута) = потеря звука
    private var knownGeneration = -1L
    private var lastPlayhead: ReaderPosition? = null

    private var lastPersistAtMs = 0L
    private var lastUiAtMs = 0L
    private var lastUi: ReaderUiState? = null
    private var savedTitle: String? = null
    private var savedProgress = 0f

    @Volatile private var schedulerJob: Job? = null

    init {
        refreshSavedInfo()
    }

    // ==========================================================================================
    // Публичное управление
    // ==========================================================================================

    val isActive: Boolean
        get() = synchronized(lock) { isActiveLocked() }

    fun uiState(): ReaderUiState = synchronized(lock) { buildUiLocked() }

    /** Новый документ из редактора. Тот же текст, вставленный повторно, продолжается с сохранённого места. */
    fun load(rawText: String, title: String? = null): Boolean {
        val doc = segmenter.build(rawText, title)
        if (doc.isEmpty) return false
        val saved = runCatching { store?.load() }.getOrNull()
        val start = if (saved != null && saved.documentId == doc.id && !saved.finished) {
            clampPosition(doc, ReaderPosition(saved.segment, saved.sentence))
        } else {
            ReaderPosition.START
        }
        startDocument(doc, start, saved?.takeIf { it.documentId == doc.id }?.pace)
        host.log("Reader: документ «${doc.title}» — ${doc.segments.size} фрагм., ${doc.text.length} симв., старт=$start")
        return true
    }

    /** Продолжить сохранённый документ (после перезапуска приложения). */
    fun resumeSaved(): Boolean {
        val saved = runCatching { store?.load() }.getOrNull() ?: return false
        val doc = segmenter.build(saved.text, saved.title)
        if (doc.isEmpty) return false
        val start = if (saved.finished) ReaderPosition.START else clampPosition(doc, ReaderPosition(saved.segment, saved.sentence))
        startDocument(doc, start, saved.pace)
        return true
    }

    fun discardSaved() {
        runCatching { store?.clear() }
        synchronized(lock) {
            savedTitle = null
            savedProgress = 0f
            publishLocked(force = true)
        }
    }

    fun command(cmd: ReaderCommand) {
        when (cmd) {
            ReaderCommand.Pause -> pause(PauseReason.USER)
            ReaderCommand.Resume -> resume()
            ReaderCommand.Toggle -> {
                val playing = synchronized(lock) { status == ReaderStatus.READING || status == ReaderStatus.BUFFERING || status == ReaderStatus.ANSWERING }
                if (playing) pause(PauseReason.USER) else resume()
            }
            ReaderCommand.RepeatSentence -> repeat(paragraph = false)
            ReaderCommand.RepeatParagraph -> repeat(paragraph = true)
            ReaderCommand.NextParagraph -> skipParagraph()
            ReaderCommand.Stop -> stop()
            is ReaderCommand.SetPace -> {
                setPace(cmd.pace)
                resumeIfImplicitlyPaused()
            }
            is ReaderCommand.Volume -> {
                audio.adjustVolume(cmd.up)
                resumeIfImplicitlyPaused()
            }
        }
    }

    fun setPace(newPace: ReaderPace) = synchronized(lock) {
        pace = newPace
        publishLocked(force = true)
    }

    fun currentPace(): ReaderPace = synchronized(lock) { pace }

    fun pause(reason: PauseReason) {
        synchronized(lock) {
            if (document == null) return
            when (status) {
                ReaderStatus.READING, ReaderStatus.BUFFERING, ReaderStatus.ANSWERING, ReaderStatus.PREPARING -> {
                    if (status != ReaderStatus.ANSWERING) {
                        pausedAt = playheadLocked()?.position ?: pausedAt ?: cursor
                        pausedGeneration = audio.playbackGeneration()
                        answerAdoptedSincePause = false
                    }
                    audio.setReaderHold(true)
                    audio.setBargeInHold(false)
                    status = ReaderStatus.PAUSED
                    pauseReason = reason
                    implicitResumeAtMs = 0L
                    persistLocked(force = true)
                }
                ReaderStatus.PAUSED -> {
                    if (priority(reason) > priority(pauseReason)) pauseReason = reason
                    if (reason != PauseReason.VOICE) implicitResumeAtMs = 0L
                }
                else -> Unit
            }
            publishLocked(force = true)
        }
    }

    fun resume() {
        val restart: Boolean
        synchronized(lock) {
            val doc = document ?: return
            restart = status == ReaderStatus.FINISHED
            if (!restart && status != ReaderStatus.PAUSED && status != ReaderStatus.ANSWERING) return
            if (restart) {
                cursor = ReaderPosition.START
                pausedAt = null
                timeline.clear()
            } else {
                val gen = audio.playbackGeneration()
                val intact = pausedGeneration == gen && !answerAdoptedSincePause &&
                    timeline.any { it.generation == gen } && audio.pendingFrames() >= MIN_INTACT_BUFFER_FRAMES
                if (!intact) {
                    val from = pausedAt ?: cursor
                    discardInFlightLocked()
                    // Сброс всегда: в очереди подачи могут оставаться кадры ответа/старого фрагмента
                    // с текущим поколением — без смены поколения они прозвучали бы поверх чтения
                    knownGeneration = audio.flushPlayback("reader: продолжение с начала предложения")
                    timeline.clear()
                    cursor = clampPosition(doc, from)
                    host.log("Reader: продолжение с предложения $cursor (буфер не сохранился)")
                } else {
                    host.log("Reader: точное продолжение с места паузы")
                }
            }
            dropInteractionsLocked()
            audio.setBargeInHold(false)
            audio.setReaderHold(false)
            status = if (transport.isSessionReady()) ReaderStatus.READING else ReaderStatus.PREPARING
            pauseReason = PauseReason.NONE
            implicitResumeAtMs = 0L
            pausedAt = null
            suppressChatterUntilMs = host.now() + CHATTER_SUPPRESS_MS
            publishLocked(force = true)
        }
        ensureScheduler()
    }

    /** Ответ модели больше не нужен (продолжение, перемотка): его ещё не пришедший хвост не прозвучит. */
    private fun dropInteractionsLocked() {
        interactions.values.forEach {
            it.decision = AudioDecision.DROP
            it.adopted = false
        }
    }

    fun stop() {
        synchronized(lock) {
            val doc = document ?: return
            val position = pausedAt ?: playheadLocked()?.position ?: cursor
            saveSnapshotLocked(doc, position, finished = false)
            val generationPending = inFlight != null || sendInProgress || transport.isGenerationInProgress()
            discardInFlightLocked()
            audio.flushPlayback("reader: стоп")
            timeline.clear()
            audio.setBargeInHold(false)
            audio.setReaderHold(false)
            val orphan = orphanSinceMs
            resetLocked()
            orphanSinceMs = orphan
            if (generationPending) dropAllUntilMs = host.now() + POST_STOP_DROP_MS
            savedTitle = doc.title
            savedProgress = doc.progressOf(position)
            publishLocked(force = true)
        }
    }

    // ==========================================================================================
    // Аудиоконвейер (SessionManager.observeAudio)
    // ==========================================================================================

    fun isReadTurn(serverTurnId: Long): Boolean = synchronized(lock) { turnsByServerId.containsKey(serverTurnId) }

    /** Решение по кадру модели: играть, отбросить или придержать (ход-ответ ещё не классифицирован). */
    fun decideAudio(serverTurnId: Long, pcm: ByteArray, length: Int): AudioDecision {
        synchronized(lock) {
            val now = host.now()
            if (!isActiveLocked()) return if (now < dropAllUntilMs) AudioDecision.DROP else AudioDecision.PLAY
            lastAudioArrivalMs = now
            val turn = resolveReadTurnLocked(serverTurnId)
            if (turn != null) {
                val bytes = length.coerceIn(0, pcm.size)
                val frames = (bytes / 2).toLong()
                turn.receivedFrames += frames
                val ms = frames.toDouble() / FRAMES_PER_MS
                val voiced = bytes >= 96 && rms(pcm, bytes) >= SILENCE_RMS
                if (voiced) {
                    turn.voicedMs += ms
                    turn.silentRunMs = 0.0
                    turn.lastVoicedAtMs = now
                } else {
                    turn.silentRunMs += ms
                }
                if (turn.suppressed) return AudioDecision.DROP
                // «Беглая» тишина после конца фразы и длинные пустоты не воспроизводятся
                if (!voiced && turn.silentRunMs > SILENCE_CLAMP_MS) return AudioDecision.DROP
                return AudioDecision.PLAY
            }
            return interactionDecisionLocked(serverTurnId, now)
        }
    }

    fun interactionDecision(serverTurnId: Long): AudioDecision = synchronized(lock) {
        val now = host.now()
        if (!isActiveLocked()) return if (now < dropAllUntilMs) AudioDecision.DROP else AudioDecision.PLAY
        interactionDecisionLocked(serverTurnId, now)
    }

    /** true — кадры этого хода нужно перештамповать текущим поколением воспроизведения. */
    fun shouldAdopt(serverTurnId: Long): Boolean = synchronized(lock) { interactions[serverTurnId]?.adopted == true }

    fun onAudioEnqueued(serverTurnId: Long, frames: Long) {
        if (frames <= 0L) return
        synchronized(lock) {
            val turn = turnsByServerId[serverTurnId] ?: return
            val gen = audio.playbackGeneration()
            if (knownGeneration < 0L) knownGeneration = gen
            val doc = document
            if (gen != knownGeneration && doc != null) {
                handleExternalFlushLocked(doc, gen)
                return
            }
            val enqueued = audio.enqueuedFrames()
            if (turn.generation != gen || turn.startFrame < 0L) {
                turn.generation = gen
                turn.startFrame = (enqueued - frames).coerceAtLeast(0L)
                if (!timeline.contains(turn)) timeline += turn
                while (timeline.size > 16) timeline.removeAt(0)
            }
            turn.endFrame = enqueued
            if (status == ReaderStatus.BUFFERING && audio.pendingFrames() > FRAMES_PER_MS * 300) {
                status = ReaderStatus.READING
                publishLocked(force = true)
            }
        }
    }

    // ==========================================================================================
    // События сессии
    // ==========================================================================================

    fun onModelTranscript(serverTurnId: Long, text: String, turnAudioBytes: Long) {
        if (text.isEmpty()) return
        synchronized(lock) {
            if (!isActiveLocked()) return
            if (claimOrphanLocked(serverTurnId)) return
            var turn = turnsByServerId[serverTurnId]
            val f = inFlight
            if (f != null && f.serverTurnId < 0L && (turn == null || turn.completed)) {
                if (turn == null && serverTurnId > lastBoundServerTurnId && !interactions.containsKey(serverTurnId)) {
                    bindLocked(f, serverTurnId)
                    turn = f
                } else if (turn != null || !interactions.containsKey(serverTurnId)) {
                    // Транскрипт нового хода пришёл раньше первого аудио — с id прошлой генерации
                    turn = f
                }
            }
            if (turn == null || (turn.completed && turn !== f)) return
            turn.transcript.append(text)
            val framesAtMark = if (turn.serverTurnId == serverTurnId) turnAudioBytes / 2 else 0L
            turn.marks += longArrayOf(framesAtMark, turn.transcript.length.toLong())
            if (turn.coverageReachedAtMs == 0L && tailCoveredLocked(turn)) turn.coverageReachedAtMs = host.now()
        }
    }

    fun onGenerationComplete(serverTurnId: Long) = onTurnClosed(serverTurnId, "generationComplete")

    fun onTurnComplete(serverTurnId: Long) = onTurnClosed(serverTurnId, "turnComplete")

    private fun onTurnClosed(serverTurnId: Long, reason: String) {
        synchronized(lock) {
            if (!isActiveLocked()) return
            interactions[serverTurnId]?.let { st ->
                if (st.adopted && status == ReaderStatus.ANSWERING) {
                    status = ReaderStatus.PAUSED
                    pauseReason = PauseReason.ANSWER
                    publishLocked(force = true)
                }
            }
            val turn = turnsByServerId[serverTurnId] ?: return
            if (!turn.completed) completeTurnLocked(turn, reason)
        }
    }

    fun onServerInterrupted(serverTurnId: Long) {
        synchronized(lock) {
            val turn = turnsByServerId[serverTurnId] ?: inFlight?.takeIf { it.serverTurnId == serverTurnId } ?: return
            if (!turn.completed) completeTurnLocked(turn, "interrupted")
        }
    }

    fun onSessionReady() {
        synchronized(lock) {
            goAwayReconnectRequested = false
            sessionRequestInFlight = false
            if (status == ReaderStatus.PREPARING) {
                status = ReaderStatus.READING
                publishLocked(force = true)
            }
        }
        if (isActive) ensureScheduler()
    }

    fun onSessionLost() {
        synchronized(lock) {
            val f = inFlight ?: return
            if (!f.completed) lostInFlightLocked(f, "соединение потеряно")
        }
    }

    /** Можно ли сейчас переподключаться без потери фрагмента (для goAway). */
    fun canReconnectNow(): Boolean = synchronized(lock) { !isActiveLocked() || (inFlight == null && !sendInProgress) }

    fun onUserSpeechStart() {
        synchronized(lock) {
            if (!isActiveLocked()) return
            val now = host.now()
            userSpeaking = true
            lastUserSpeechAtMs = now
            userUtterance.setLength(0)
            keywordPending = null
            implicitResumeAtMs = 0L
            when (status) {
                ReaderStatus.READING, ReaderStatus.BUFFERING -> {
                    pausedAt = playheadLocked()?.position ?: cursor
                    pausedGeneration = audio.playbackGeneration()
                    answerAdoptedSincePause = false
                    audio.setReaderHold(true)
                    audio.setBargeInHold(false)
                    status = ReaderStatus.PAUSED
                    pauseReason = PauseReason.VOICE
                    persistLocked(force = true)
                }
                ReaderStatus.ANSWERING -> {
                    audio.setReaderHold(true)
                    audio.setBargeInHold(false)
                    status = ReaderStatus.PAUSED
                    pauseReason = PauseReason.ANSWER
                }
                ReaderStatus.PAUSED -> audio.setBargeInHold(false)
                else -> Unit
            }
            publishLocked(force = true)
        }
    }

    fun onUserSpeechEnd() {
        synchronized(lock) {
            if (!isActiveLocked()) return
            userSpeaking = false
            if (status == ReaderStatus.PAUSED && pauseReason == PauseReason.VOICE) {
                implicitResumeAtMs = host.now() + IMPLICIT_AUTORESUME_MS
            }
        }
    }

    fun onUserTranscript(text: String, interim: Boolean) {
        if (text.isBlank()) return
        synchronized(lock) {
            if (!isActiveLocked()) return
            val candidate = if (interim) text else {
                if (userUtterance.isNotEmpty()) userUtterance.append(' ')
                userUtterance.append(text.trim())
                userUtterance.toString()
            }
            val cmd = ReaderVoiceCommands.match(candidate)
            if (cmd != null) {
                keywordPending = cmd
                keywordAtMs = host.now()
            }
            if (status == ReaderStatus.PAUSED && pauseReason == PauseReason.VOICE && implicitResumeAtMs > 0L) {
                implicitResumeAtMs = max(implicitResumeAtMs, host.now() + IMPLICIT_AUTORESUME_MS)
            }
        }
    }

    /** Функции управления из Live-сессии. Возвращает ответ для toolResponse. */
    fun onToolCall(name: String, args: JsonObject): JsonObject {
        val now = host.now()
        synchronized(lock) {
            toolHandledAtMs = now
            keywordPending = null
            suppressChatterUntilMs = now + CHATTER_SUPPRESS_MS
            interactions.values.forEach { if (it.decision == AudioDecision.HOLD) it.decision = AudioDecision.DROP }
        }
        val arg: (String) -> String = { key -> args[key]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty() }
        when (name) {
            ReaderPrompts.TOOL_PAUSE -> pause(PauseReason.USER)
            ReaderPrompts.TOOL_RESUME -> resume()
            ReaderPrompts.TOOL_REPEAT -> repeat(paragraph = arg("scope").startsWith("para"))
            ReaderPrompts.TOOL_SKIP -> skipParagraph()
            ReaderPrompts.TOOL_STOP -> stop()
            ReaderPrompts.TOOL_PACE -> {
                setPace(ReaderPace.from(arg("pace")))
                resumeIfImplicitlyPaused()
            }
            ReaderPrompts.TOOL_VOLUME -> {
                audio.adjustVolume(up = !arg("direction").startsWith("d"))
                resumeIfImplicitlyPaused()
            }
            else -> return ReaderPrompts.okResult("unknown")
        }
        host.log("Reader: функция $name ${if (args.isEmpty()) "" else args.toString()}")
        return ReaderPrompts.okResult()
    }

    /** Вердикт сторожа: сервер прислал «немой» фрагмент (текст без звука). */
    fun onSilentReadTurn(serverTurnId: Long, voicedTranscriptLength: Int) {
        var fresh = false
        synchronized(lock) {
            val turn = turnsByServerId[serverTurnId] ?: return
            if (turn.completed) return
            val now = host.now()
            turn.suppressed = true
            turn.completed = true
            if (inFlight === turn) inFlight = null
            if (!turn.discarded) {
                val charPos = voicedTranscriptLength.coerceIn(0, max(0, turn.text.length - 1))
                cursor = ReaderPosition(turn.segment, turn.fromSentence + sentenceIndexAt(turn, charPos))
            }
            silentRecoveries.addLast(now)
            while (silentRecoveries.isNotEmpty() && now - silentRecoveries.first() > SILENT_RECOVERY_WINDOW_MS) silentRecoveries.removeFirst()
            fresh = silentRecoveries.size >= 2
            if (fresh) silentRecoveries.clear()
            host.log("Reader: «немой» фрагмент от сервера — повтор с $cursor${if (fresh) ", новая сессия" else ""}")
        }
        if (fresh) host.requestFreshSession("повторные «немые» ответы gemini-3.8-live")
    }

    fun onFocusLoss(permanent: Boolean) {
        val playing = synchronized(lock) {
            status == ReaderStatus.READING || status == ReaderStatus.BUFFERING || status == ReaderStatus.ANSWERING
        }
        if (playing) pause(if (permanent) PauseReason.FOCUS_PERMANENT else PauseReason.FOCUS)
    }

    fun onFocusGain() {
        val shouldResume = synchronized(lock) { status == ReaderStatus.PAUSED && pauseReason == PauseReason.FOCUS }
        if (shouldResume) resume()
    }

    // ==========================================================================================
    // Планировщик
    // ==========================================================================================

    private fun ensureScheduler() {
        if (schedulerJob?.isActive == true) return
        schedulerJob = scope.launch {
            while (isActive) {
                val keepRunning = try {
                    tick()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    host.log("Reader: сбой планировщика: ${t.message}")
                    true
                }
                if (!keepRunning) break
                delay(TICK_MS)
            }
        }
    }

    /** @return false — читалка неактивна, планировщик можно остановить. */
    private suspend fun tick(): Boolean {
        var needSession = false
        var plan: SendPlan? = null
        var reconnect = false
        var command: ReaderCommand? = null
        var autoResume = false

        synchronized(lock) {
            val doc = document ?: return false
            val now = host.now()
            if (status == ReaderStatus.READING || status == ReaderStatus.BUFFERING || status == ReaderStatus.PAUSED) {
                val gen = audio.playbackGeneration()
                if (knownGeneration >= 0L && gen != knownGeneration) handleExternalFlushLocked(doc, gen)
            }
            if (orphanSinceMs > 0L && now - orphanSinceMs > ORPHAN_WAIT_MS) orphanSinceMs = 0L
            when (status) {
                ReaderStatus.IDLE, ReaderStatus.FINISHED, ReaderStatus.ERROR -> {
                    publishLocked(force = false)
                    return false
                }
                ReaderStatus.PREPARING -> needSession = true
                ReaderStatus.READING, ReaderStatus.BUFFERING -> {
                    playheadLocked()?.let { lastPlayhead = it.position }
                    if (!transport.isSessionReady()) {
                        needSession = true
                    } else {
                        checkInFlightLocked(now)
                        if (inFlight == null && !sendInProgress && now >= nextSendNotBeforeMs && orphanSinceMs == 0L) {
                            if (host.isGoAwayPending()) {
                                if (!goAwayReconnectRequested && transport.transportBacklogFrames() == 0L) {
                                    goAwayReconnectRequested = true
                                    reconnect = true
                                }
                            } else if (generationAllowsSendLocked(now)) {
                                val aheadMs = bufferedAheadMsLocked()
                                if (doc.isEnd(cursor)) {
                                    if (aheadMs <= 60L && transport.transportBacklogFrames() == 0L) finishLocked(doc)
                                } else if (aheadMs < LOOKAHEAD_LOW_MS) {
                                    plan = prepareSendLocked(doc, now)
                                }
                            }
                        }
                        updateBufferingLocked(now)
                    }
                }
                ReaderStatus.PAUSED, ReaderStatus.ANSWERING -> {
                    val pending = keywordPending
                    if (pending != null && now - keywordAtMs >= KEYWORD_FALLBACK_DELAY_MS && toolHandledAtMs < keywordAtMs) {
                        keywordPending = null
                        command = pending
                    } else if (status == ReaderStatus.PAUSED && pauseReason == PauseReason.VOICE &&
                        implicitResumeAtMs > 0L && now >= implicitResumeAtMs && !userSpeaking &&
                        interactions.values.none { it.decision == AudioDecision.HOLD || (it.adopted && it.serverTurnId == transport.activeServerTurnId() && transport.isGenerationInProgress()) }
                    ) {
                        implicitResumeAtMs = 0L
                        autoResume = true
                    }
                }
            }
            persistLocked(force = false)
            publishLocked(force = false)
        }

        command?.let {
            host.log("Reader: команда по транскрипции (резерв) — $it")
            command(it)
        }
        if (autoResume) {
            host.log("Reader: посторонняя реплика — чтение продолжается")
            resume()
        }
        if (reconnect) host.requestPlannedReconnect("goAway: плановая смена соединения между фрагментами")
        if (needSession) requestSession()
        plan?.let { send(it) }
        return true
    }

    private suspend fun requestSession() {
        val now = host.now()
        synchronized(lock) {
            if (sessionRequestInFlight || now < nextSessionAttemptMs) return
            sessionRequestInFlight = true
        }
        val ready = try {
            host.ensureReaderSession()
        } catch (cancelled: CancellationException) {
            synchronized(lock) { sessionRequestInFlight = false }
            throw cancelled
        } catch (t: Throwable) {
            host.log("Reader: не удалось поднять сессию: ${t.message}")
            false
        }
        synchronized(lock) {
            sessionRequestInFlight = false
            if (!ready) {
                nextSessionAttemptMs = host.now() + SESSION_RETRY_MS
            } else if (status == ReaderStatus.PREPARING) {
                status = ReaderStatus.READING
                publishLocked(force = true)
            }
        }
    }

    private suspend fun send(plan: SendPlan) {
        val ok = try {
            transport.sendUserTurn(plan.payload)
        } catch (cancelled: CancellationException) {
            synchronized(lock) { sendInProgress = false }
            throw cancelled
        } catch (t: Throwable) {
            host.log("Reader: отправка фрагмента не удалась: ${t.message}")
            false
        }
        synchronized(lock) {
            sendInProgress = false
            if (!ok && inFlight === plan.turn) {
                inFlight = null
                nextSendNotBeforeMs = host.now() + 700L
            }
            // Запрос, отменённый командой (повтор/перемотка/стоп), так и не ушёл на сервер — «призрачной»
            // генерации не будет, ждать её (до 4 с) незачем
            if (!ok && plan.turn.discarded && plan.turn.serverTurnId < 0L) orphanSinceMs = 0L
        }
    }

    private fun prepareSendLocked(doc: ReaderDocument, now: Long): SendPlan? {
        val seg = doc.segments.getOrNull(cursor.segment) ?: return null
        val from = cursor.sentence.coerceIn(0, seg.sentences.size - 1)
        val base = seg.sentences[from].start
        val text = doc.text.substring(base, seg.end)
        val offsets = IntArray(seg.sentences.size - from) { i -> seg.sentences[from + i].start - base }
        val expectedMs = (text.length / pace.charsPerSecond * 1000f).toLong()
        val turn = ReadTurn(
            localId = ++turnSeq,
            segment = cursor.segment,
            fromSentence = from,
            text = text,
            sentenceOffsets = offsets,
            expectedMs = expectedMs,
            sentAtMs = now,
            sessionKey = transport.sessionKey()
        )
        inFlight = turn
        sendInProgress = true
        generationBusySinceMs = 0L
        val payload = ReaderPrompts.readTurn(text.trim(), cursor.segment + 1, doc.segments.size, pace)
        return SendPlan(turn, payload)
    }

    /** Следующий фрагмент можно отправлять: генерация свободна или занята «одноразовым» ходом. */
    private fun generationAllowsSendLocked(now: Long): Boolean {
        if (!transport.isGenerationInProgress()) {
            generationBusySinceMs = 0L
            return true
        }
        val activeId = transport.activeServerTurnId()
        val readTurn = turnsByServerId[activeId]
        if (readTurn != null && (readTurn.completed || readTurn.suppressed)) return true
        if (interactions[activeId]?.decision == AudioDecision.DROP) return true
        if (generationBusySinceMs == 0L) generationBusySinceMs = now
        return now - generationBusySinceMs >= GENERATION_BUSY_OVERRIDE_MS
    }

    private fun checkInFlightLocked(now: Long) {
        val f = inFlight ?: return
        if (sendInProgress) return
        if (f.sessionKey != transport.sessionKey()) {
            lostInFlightLocked(f, "сессия сменилась")
            return
        }
        if (f.serverTurnId < 0L && f.transcript.isEmpty() && now - f.sentAtMs > TURN_START_TIMEOUT_MS) {
            inFlight = null
            f.completed = true
            f.discarded = true
            startStalls++
            host.log("Reader: модель не начала фрагмент ${f.segment + 1} за ${TURN_START_TIMEOUT_MS / 1000} с — повтор ($startStalls)")
            if (startStalls >= 2) {
                startStalls = 0
                host.requestFreshSession("модель не отвечает на фрагменты")
            }
            return
        }
        // turnComplete не пришёл, хотя текст дочитан и звук давно закончился («беглое» аудио 3.8)
        val tailDone = f.coverageReachedAtMs > 0L
        val quietFor = if (f.lastVoicedAtMs > 0L) now - f.lastVoicedAtMs else 0L
        if (f.serverTurnId >= 0L && tailDone && quietFor >= TURN_IDLE_COMPLETE_MS) {
            f.suppressed = true
            completeTurnLocked(f, "тишина после конца текста")
        }
    }

    private fun completeTurnLocked(turn: ReadTurn, reason: String) {
        turn.completed = true
        if (inFlight === turn) inFlight = null
        startStalls = 0
        if (turn.discarded) return
        val doc = document ?: return
        val resumeSentence = assessIncompleteLocked(turn)
        if (resumeSentence == null) {
            retries.remove(turn.segment)
            cursor = ReaderPosition(turn.segment + 1, 0)
        } else {
            val attempt = (retries[turn.segment] ?: 0) + 1
            retries[turn.segment] = attempt
            cursor = if (attempt <= MAX_RETRIES_PER_SEGMENT) {
                host.log("Reader: фрагмент ${turn.segment + 1} прочитан не полностью ($reason) — дочитываю с предложения ${resumeSentence + 1}")
                ReaderPosition(turn.segment, resumeSentence)
            } else {
                host.log("Reader: фрагмент ${turn.segment + 1} — лимит повторов, продолжаю дальше")
                ReaderPosition(turn.segment + 1, 0)
            }
        }
        cursor = clampPosition(doc, cursor)
    }

    private fun lostInFlightLocked(f: ReadTurn, reason: String) {
        f.completed = true
        if (inFlight === f) inFlight = null
        if (f.discarded) return
        val doc = document ?: return
        val partialEnd = assessCoverageEndLocked(f)
        cursor = clampPosition(doc, ReaderPosition(f.segment, f.fromSentence + partialEnd))
        host.log("Reader: фрагмент ${f.segment + 1} прерван ($reason) — продолжу с $cursor")
    }

    private fun discardInFlightLocked() {
        inFlight?.let {
            it.discarded = true
            it.suppressed = true
            it.completed = true
            if (it.serverTurnId < 0L) orphanSinceMs = host.now()
        }
        inFlight = null
        sendInProgress = false
        turnsByServerId.values.forEach { if (!it.completed) { it.suppressed = true; it.completed = true; it.discarded = true } }
    }

    private fun finishLocked(doc: ReaderDocument) {
        status = ReaderStatus.FINISHED
        pauseReason = PauseReason.NONE
        audio.setBargeInHold(false)
        audio.setReaderHold(false)
        saveSnapshotLocked(doc, ReaderPosition(doc.segments.size, 0), finished = true)
        savedTitle = null
        savedProgress = 0f
        host.log("Reader: документ «${doc.title}» дочитан")
        publishLocked(force = true)
    }

    /** Буфер сброшен не читалкой (смена аудиомаршрута, восстановление потока): дочитываем с позиции звука. */
    private fun handleExternalFlushLocked(doc: ReaderDocument, gen: Long) {
        knownGeneration = gen
        if (timeline.isEmpty() && inFlight == null) return
        val from = pausedAt ?: lastPlayhead ?: cursor
        discardInFlightLocked()
        timeline.clear()
        cursor = clampPosition(doc, from)
        if (status == ReaderStatus.PAUSED) pausedAt = cursor
        host.log("Reader: буфер воспроизведения сброшен извне — продолжу с $cursor")
    }

    private fun updateBufferingLocked(now: Long) {
        val ahead = bufferedAheadMsLocked()
        if (status == ReaderStatus.READING && inFlight != null && ahead < 120L && now - lastAudioArrivalMs > 600L) {
            status = ReaderStatus.BUFFERING
            publishLocked(force = true)
        } else if (status == ReaderStatus.BUFFERING && ahead >= 300L) {
            status = ReaderStatus.READING
            publishLocked(force = true)
        }
    }

    // ==========================================================================================
    // Привязка ходов, ответы во время паузы
    // ==========================================================================================

    private fun resolveReadTurnLocked(serverTurnId: Long): ReadTurn? {
        turnsByServerId[serverTurnId]?.let { return it }
        if (interactions.containsKey(serverTurnId)) return null
        if (claimOrphanLocked(serverTurnId)) return turnsByServerId[serverTurnId]
        val f = inFlight ?: return null
        if (f.serverTurnId < 0L && serverTurnId > lastBoundServerTurnId && f.sessionKey == transport.sessionKey()) {
            bindLocked(f, serverTurnId)
            return f
        }
        return null
    }

    /**
     * Фрагмент был отправлен, но отменён (перемотка/ответ/стоп) раньше, чем сервер начал его генерацию.
     * Его генерация ещё придёт: первую новую генерацию помечаем «призраком» и не воспроизводим,
     * иначе она привязалась бы к следующему фрагменту и прозвучал бы не тот текст.
     */
    private fun claimOrphanLocked(serverTurnId: Long): Boolean {
        if (orphanSinceMs == 0L || serverTurnId <= lastBoundServerTurnId) return false
        if (interactions.containsKey(serverTurnId) || turnsByServerId.containsKey(serverTurnId)) return false
        val ghost = ReadTurn(
            localId = -1L,
            segment = -1,
            fromSentence = 0,
            text = "",
            sentenceOffsets = IntArray(1),
            expectedMs = 0L,
            sentAtMs = orphanSinceMs,
            sessionKey = transport.sessionKey()
        ).apply {
            suppressed = true
            completed = true
            discarded = true
        }
        bindLocked(ghost, serverTurnId)
        orphanSinceMs = 0L
        return true
    }

    private fun bindLocked(turn: ReadTurn, serverTurnId: Long) {
        turn.serverTurnId = serverTurnId
        turnsByServerId[serverTurnId] = turn
        lastBoundServerTurnId = max(lastBoundServerTurnId, serverTurnId)
        while (turnsByServerId.size > 24) {
            val oldest = turnsByServerId.keys.first()
            turnsByServerId.remove(oldest)
        }
    }

    private fun interactionDecisionLocked(serverTurnId: Long, now: Long): AudioDecision {
        var st = interactions[serverTurnId]
        if (st == null) {
            st = InteractionTurn(serverTurnId, now)
            interactions[serverTurnId] = st
            lastBoundServerTurnId = max(lastBoundServerTurnId, serverTurnId)
            while (interactions.size > 12) interactions.remove(interactions.keys.first())
        }
        if (st.decision != AudioDecision.HOLD) return st.decision
        if (now < suppressChatterUntilMs || toolHandledAtMs >= st.firstSeenAtMs - 1_500L) {
            st.decision = AudioDecision.DROP
            return st.decision
        }
        if (now - st.firstSeenAtMs < INTERACTION_DECISION_MS) return AudioDecision.HOLD
        st.decision = when (status) {
            // Посреди чтения модель «вставила» реплику сама — в текст её не смешиваем
            ReaderStatus.READING, ReaderStatus.BUFFERING, ReaderStatus.PREPARING -> AudioDecision.DROP
            ReaderStatus.PAUSED, ReaderStatus.ANSWERING -> {
                adoptAnswerLocked(st)
                AudioDecision.PLAY
            }
            else -> AudioDecision.PLAY
        }
        return st.decision
    }

    /** Ответ на вопрос слушателя во время паузы: освобождаем буфер чтения, ответ звучит сразу. */
    private fun adoptAnswerLocked(st: InteractionTurn) {
        st.adopted = true
        if (pausedAt == null) pausedAt = playheadLocked()?.position ?: cursor
        // Сброс всегда (не только при непустом буфере): кадры фрагмента, ещё стоящие в очереди подачи,
        // иначе прозвучали бы вперемешку с ответом
        discardInFlightLocked()
        knownGeneration = audio.flushPlayback("reader: ответ на вопрос во время паузы")
        timeline.clear()
        answerAdoptedSincePause = true
        audio.setBargeInHold(false)
        audio.setReaderHold(false)
        status = ReaderStatus.ANSWERING
        publishLocked(force = true)
    }

    private fun resumeIfImplicitlyPaused() {
        val implicit = synchronized(lock) { status == ReaderStatus.PAUSED && pauseReason == PauseReason.VOICE }
        if (implicit) resume()
    }

    private fun repeat(paragraph: Boolean) {
        synchronized(lock) {
            val doc = document ?: return
            val head = playheadLocked()
            val base = pausedAt ?: head?.position ?: cursor
            val target = if (paragraph) {
                doc.paragraphStartOf(base)
            } else if (head != null && pausedAt == null && head.fractionInSentence < 0.3f) {
                doc.previousSentence(head.position)
            } else if (pausedAt != null && head != null && head.position == pausedAt && head.fractionInSentence < 0.3f) {
                doc.previousSentence(base)
            } else {
                base
            }
            seekLocked(doc, target)
        }
        ensureScheduler()
    }

    private fun skipParagraph() {
        synchronized(lock) {
            val doc = document ?: return
            val base = pausedAt ?: playheadLocked()?.position ?: cursor
            seekLocked(doc, doc.nextParagraphAfter(base))
        }
        ensureScheduler()
    }

    private fun seekLocked(doc: ReaderDocument, target: ReaderPosition) {
        val clamped = clampPosition(doc, target)
        discardInFlightLocked()
        dropInteractionsLocked()
        knownGeneration = audio.flushPlayback("reader: переход к $clamped")
        timeline.clear()
        cursor = clamped
        pausedAt = null
        answerAdoptedSincePause = false
        audio.setBargeInHold(false)
        audio.setReaderHold(false)
        pauseReason = PauseReason.NONE
        implicitResumeAtMs = 0L
        suppressChatterUntilMs = host.now() + CHATTER_SUPPRESS_MS
        status = if (doc.isEnd(clamped)) ReaderStatus.FINISHED else if (transport.isSessionReady()) ReaderStatus.READING else ReaderStatus.PREPARING
        persistLocked(force = true)
        publishLocked(force = true)
    }

    // ==========================================================================================
    // Позиция, проверка полноты, сохранение
    // ==========================================================================================

    private fun playheadLocked(): Playhead? {
        val doc = document ?: return null
        val gen = audio.playbackGeneration()
        var turn: ReadTurn? = null
        val played = (audio.enqueuedFrames() - audio.pendingFrames()).coerceAtLeast(0L)
        for (t in timeline) {
            if (t.generation != gen || t.startFrame < 0L) continue
            if (turn == null || played >= t.startFrame) turn = t
        }
        val current = turn ?: return null
        val span = (current.endFrame - current.startFrame).coerceAtLeast(1L)
        val rel = (played - current.startFrame).coerceIn(0L, span)
        val textLength = current.text.length
        val charPos = if (current.marks.size >= 2 && current.transcript.isNotEmpty()) {
            val spoken = interpolateMarks(current.marks, rel)
            if (current.completed) {
                (spoken.toDouble() / current.transcript.length.coerceAtLeast(1) * textLength).toInt()
            } else {
                spoken.toInt()
            }
        } else {
            (rel.toDouble() / span * textLength).toInt()
        }
        val pos = charPos.coerceIn(0, max(0, textLength - 1))
        val k = sentenceIndexAt(current, pos)
        val sentenceStart = current.sentenceOffsets[k]
        val sentenceEnd = if (k + 1 < current.sentenceOffsets.size) current.sentenceOffsets[k + 1] else textLength
        val fraction = (pos - sentenceStart).toFloat() / max(1, sentenceEnd - sentenceStart).toFloat()
        val seg = doc.segments.getOrNull(current.segment) ?: return null
        val absolute = (seg.sentences.getOrNull(current.fromSentence)?.start ?: seg.start) + pos
        return Playhead(ReaderPosition(current.segment, current.fromSentence + k), fraction.coerceIn(0f, 1f), absolute)
    }

    private fun interpolateMarks(marks: List<LongArray>, rel: Long): Long {
        var prevFrames = 0L
        var prevLen = 0L
        for (m in marks) {
            val frames = m[0]
            val len = m[1]
            if (frames <= rel) {
                prevFrames = frames
                prevLen = len
                continue
            }
            val spanFrames = (frames - prevFrames).coerceAtLeast(1L)
            return prevLen + (len - prevLen) * (rel - prevFrames) / spanFrames
        }
        return prevLen
    }

    private fun sentenceIndexAt(turn: ReadTurn, charPos: Int): Int {
        val offsets = turn.sentenceOffsets
        var lo = 0
        var hi = offsets.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= charPos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun bufferedAheadMsLocked(): Long =
        (audio.pendingFrames() + transport.transportBacklogFrames()).coerceAtLeast(0L) / FRAMES_PER_MS

    /** null — фрагмент прочитан полностью; иначе индекс предложения в сегменте, с которого дочитать. */
    private fun assessIncompleteLocked(turn: ReadTurn): Int? {
        val textWords = stems(turn.text, withOffsets = true)
        val spoken = stems(turn.transcript.toString(), withOffsets = false).map { it.first }
        if (spoken.isEmpty()) {
            // Транскрипция выключена или не пришла — судим по длительности реального звука
            return if (turn.voicedMs >= turn.expectedMs * 0.45) null else turn.fromSentence
        }
        if (textWords.isEmpty()) return null
        val match = alignLocked(textWords, spoken)
        val ratio = match.first.toFloat() / textWords.size.toFloat()
        val lastMatchedOffset = match.second
        val tailOk = lastMatchedOffset >= (turn.text.length * 0.85f).toInt() || turn.coverageReachedAtMs > 0L
        val lengthRatio = turn.transcript.length.toFloat() / turn.text.length.coerceAtLeast(1).toFloat()
        if (tailOk && (ratio >= 0.55f || lengthRatio >= 0.7f)) return null
        if (ratio >= 0.85f) return null
        if (turn.voicedMs >= turn.expectedMs * 0.8 && lengthRatio >= 0.75f) return null
        val rel = lastMatchedOffset.coerceAtLeast(0)
        var k = sentenceIndexAt(turn, rel)
        val sentenceEnd = if (k + 1 < turn.sentenceOffsets.size) turn.sentenceOffsets[k + 1] else turn.text.length
        val sentenceStart = turn.sentenceOffsets[k]
        if (lastMatchedOffset >= 0 && rel >= sentenceStart + (sentenceEnd - sentenceStart) * 0.9f) k += 1
        if (k >= turn.sentenceOffsets.size) return null
        return turn.fromSentence + k
    }

    /** Индекс (относительно turn) предложения, до которого фрагмент успел прозвучать. */
    private fun assessCoverageEndLocked(turn: ReadTurn): Int {
        val textWords = stems(turn.text, withOffsets = true)
        val spoken = stems(turn.transcript.toString(), withOffsets = false).map { it.first }
        if (textWords.isEmpty() || spoken.isEmpty()) return 0
        val lastOffset = alignLocked(textWords, spoken).second
        if (lastOffset < 0) return 0
        return sentenceIndexAt(turn, lastOffset)
    }

    /** Последовательное сопоставление основ слов текста и транскрипта (окно ±8 слов). */
    private fun alignLocked(textWords: List<Pair<String, Int>>, spoken: List<String>): Pair<Int, Int> {
        var j = 0
        var matched = 0
        var lastOffset = -1
        for ((stem, offset) in textWords) {
            val limit = min(spoken.size, j + 8)
            var k = j
            while (k < limit && spoken[k] != stem) k++
            if (k < limit) {
                matched++
                j = k + 1
                lastOffset = offset
            }
        }
        return matched to lastOffset
    }

    private fun tailCoveredLocked(turn: ReadTurn): Boolean {
        val textWords = stems(turn.text, withOffsets = true)
        if (textWords.isEmpty()) return false
        val tail = textWords.takeLast(2).map { it.first }
        val spoken = stems(turn.transcript.toString(), withOffsets = false).map { it.first }
        if (spoken.isEmpty()) return false
        val window = spoken.takeLast(max(6, spoken.size / 2))
        return tail.all { it in window }
    }

    private fun stems(text: String, withOffsets: Boolean): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        val n = text.length
        while (i < n) {
            if (!text[i].isLetter()) {
                i++
                continue
            }
            val start = i
            while (i < n && (text[i].isLetter() || text[i] == '\'' || text[i] == '’')) i++
            val word = text.substring(start, i).lowercase().replace('ё', 'е')
            if (word.length >= 3) out += word.take(5) to (if (withOffsets) start else 0)
        }
        return out
    }

    private fun startDocument(doc: ReaderDocument, start: ReaderPosition, savedPace: ReaderPace?) {
        synchronized(lock) {
            if (document != null) {
                discardInFlightLocked()
                audio.flushPlayback("reader: новый документ")
            }
            // Запрошенная, но ещё не начатая генерация прежнего документа должна стать «призраком»,
            // а не первым фрагментом нового (см. claimOrphanLocked)
            val orphan = orphanSinceMs
            resetLocked()
            orphanSinceMs = orphan
            knownGeneration = audio.playbackGeneration()
            document = doc
            cursor = start
            if (savedPace != null) pace = savedPace
            status = if (transport.isSessionReady()) ReaderStatus.READING else ReaderStatus.PREPARING
            audio.setBargeInHold(false)
            audio.setReaderHold(false)
            persistLocked(force = true)
            publishLocked(force = true)
        }
        ensureScheduler()
    }

    private fun resetLocked() {
        document = null
        status = ReaderStatus.IDLE
        pauseReason = PauseReason.NONE
        cursor = ReaderPosition.START
        pausedAt = null
        pausedGeneration = -1L
        answerAdoptedSincePause = false
        timeline.clear()
        turnsByServerId.clear()
        interactions.clear()
        inFlight = null
        sendInProgress = false
        nextSendNotBeforeMs = 0L
        generationBusySinceMs = 0L
        startStalls = 0
        userSpeaking = false
        userUtterance.setLength(0)
        keywordPending = null
        toolHandledAtMs = 0L
        suppressChatterUntilMs = 0L
        implicitResumeAtMs = 0L
        retries.clear()
        silentRecoveries.clear()
        goAwayReconnectRequested = false
        sessionRequestInFlight = false
        nextSessionAttemptMs = 0L
        orphanSinceMs = 0L
        knownGeneration = -1L
        lastPlayhead = null
    }

    private fun persistLocked(force: Boolean) {
        val doc = document ?: return
        val now = host.now()
        if (!force && now - lastPersistAtMs < PERSIST_INTERVAL_MS) return
        if (status == ReaderStatus.FINISHED) return
        lastPersistAtMs = now
        val position = pausedAt ?: playheadLocked()?.position ?: cursor
        saveSnapshotLocked(doc, position, finished = false)
    }

    private fun saveSnapshotLocked(doc: ReaderDocument, position: ReaderPosition, finished: Boolean) {
        val s = store ?: return
        val snapshot = ReaderProgressStore.Saved(
            documentId = doc.id,
            title = doc.title,
            text = doc.text,
            segment = position.segment,
            sentence = position.sentence,
            pace = pace,
            progress = if (finished) 1f else doc.progressOf(position),
            finished = finished,
            updatedAtMs = System.currentTimeMillis()
        )
        scope.launch(ioDispatcher) { runCatching { s.save(snapshot) } }
    }

    private fun refreshSavedInfo() {
        val saved = runCatching { store?.peek() }.getOrNull()
        synchronized(lock) {
            if (saved != null && !saved.finished) {
                savedTitle = saved.title.ifBlank { "Текст" }
                savedProgress = saved.progress
            } else {
                savedTitle = null
                savedProgress = 0f
            }
        }
    }

    private fun buildUiLocked(): ReaderUiState {
        val doc = document
        val position = pausedAt ?: playheadLocked()?.position ?: cursor
        val head = playheadLocked()
        val progress = when {
            doc == null -> 0f
            status == ReaderStatus.FINISHED -> 1f
            head != null && pausedAt == null -> (head.absoluteChar.toFloat() / doc.text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
            else -> doc.progressOf(position)
        }
        return ReaderUiState(
            status = status,
            title = doc?.title.orEmpty(),
            progress = progress,
            segment = position.segment.coerceAtMost(max(0, (doc?.segments?.size ?: 1) - 1)),
            totalSegments = doc?.segments?.size ?: 0,
            pace = pace,
            pauseReason = pauseReason,
            savedTitle = if (doc == null) savedTitle else null,
            savedProgress = savedProgress
        )
    }

    private fun publishLocked(force: Boolean) {
        val now = host.now()
        if (!force && now - lastUiAtMs < UI_INTERVAL_MS) return
        lastUiAtMs = now
        val ui = buildUiLocked()
        if (ui != lastUi) {
            lastUi = ui
            host.onUiState(ui)
        }
    }

    private fun isActiveLocked(): Boolean =
        document != null && status != ReaderStatus.IDLE && status != ReaderStatus.FINISHED && status != ReaderStatus.ERROR

    private fun priority(reason: PauseReason): Int = when (reason) {
        PauseReason.NONE -> 0
        PauseReason.VOICE -> 1
        PauseReason.FOCUS -> 2
        PauseReason.ANSWER -> 3
        PauseReason.FOCUS_PERMANENT -> 4
        PauseReason.USER -> 5
    }

    private fun clampPosition(doc: ReaderDocument, position: ReaderPosition): ReaderPosition {
        if (doc.segments.isEmpty()) return ReaderPosition.START
        if (position.segment >= doc.segments.size) return ReaderPosition(doc.segments.size, 0)
        val seg = doc.segments[position.segment.coerceAtLeast(0)]
        return ReaderPosition(seg.index, position.sentence.coerceIn(0, seg.sentences.size - 1))
    }

    private fun rms(pcm: ByteArray, bytes: Int): Double {
        val samples = bytes / 2
        if (samples <= 0) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < bytes) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768.0
            sum += s * s
            i += 2
        }
        return sqrt(sum / samples)
    }
}