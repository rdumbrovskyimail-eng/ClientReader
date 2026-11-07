package com.client.app.session

import kotlin.math.sqrt

/**
 * Сторож известных сбоев сервера gemini-3.8-live (форум Google AI Developers, октябрь 2026):
 *
 *  • «Немой ответ»: текст ответа (outputTranscription) приходит, а аудио — нули или крошечные
 *    чанки по 2–3 байта; тишина часто начинается посреди фразы, turnComplete может не прийти вовсе.
 *    Пользователь слышит, что модель «заглохла».
 *  • «Беглое аудио»: после конца фразы сервер десятки секунд шлёт тишину и не закрывает ход.
 *
 * Сторож ничего не делает сам — он только выносит вердикт; восстановление выполняет SessionManager
 * (повтор реплики через clientContent с turnComplete=true — подтверждённый на форуме обход).
 */
class LiveTurnWatchdog(private val clock: () -> Long) {

    sealed interface Verdict {
        val turnId: Long

        /** [voicedTranscriptLength] — длина текста ответа на момент последнего реального звука. */
        data class SilentTurn(
            override val turnId: Long,
            val voicedTranscriptLength: Int,
            val transcript: String
        ) : Verdict

        data class Runaway(override val turnId: Long) : Verdict
    }

    private class Stats(val turnId: Long, val createdAtMs: Long) {
        var audioMs = 0.0
        var voicedMs = 0.0
        var silentRunAudioMs = 0.0
        val transcript = StringBuilder()
        var transcriptLenAtLastVoiced = 0
        var lastVoicedAtMs = 0L
        var lastTranscriptGrowthAtMs = 0L
        var firstTranscriptAtMs = 0L
        var lastAudioAtMs = 0L
        var closedAtMs = 0L
        var flagged = false
    }

    private val lock = Any()
    private val turns = LinkedHashMap<Long, Stats>()

    @Volatile var enabled: Boolean = true

    fun onAudio(turnId: Long, pcm: ByteArray, length: Int = pcm.size) {
        if (turnId <= 0L) return
        val now = clock()
        synchronized(lock) {
            val s = statsFor(turnId, now)
            if (s.closedAtMs > 0L) return
            val bytes = length.coerceIn(0, pcm.size)
            val durationMs = bytes / BYTES_PER_MS
            s.audioMs += durationMs
            s.lastAudioAtMs = now
            val voiced = bytes >= MIN_MEANINGFUL_BYTES && rms(pcm, bytes) >= SILENCE_RMS
            if (voiced) {
                s.voicedMs += durationMs
                s.silentRunAudioMs = 0.0
                s.lastVoicedAtMs = now
                s.transcriptLenAtLastVoiced = s.transcript.length
            } else {
                s.silentRunAudioMs += durationMs
            }
        }
    }

    fun onTranscript(turnId: Long, text: String) {
        if (turnId <= 0L || text.isEmpty()) return
        val now = clock()
        synchronized(lock) {
            val s = statsFor(turnId, now)
            if (s.closedAtMs > 0L) return
            if (s.firstTranscriptAtMs == 0L) s.firstTranscriptAtMs = now
            s.transcript.append(text)
            s.lastTranscriptGrowthAtMs = now
        }
    }

    fun onTurnClosed(turnId: Long) {
        val now = clock()
        synchronized(lock) {
            turns[turnId]?.closedAtMs = now
            prune(now)
        }
    }

    fun reset() = synchronized(lock) { turns.clear() }

    /** Вызывается периодически (каждые ~250 мс). Возвращает не более одного нового вердикта. */
    fun check(): Verdict? {
        if (!enabled) return null
        val now = clock()
        synchronized(lock) {
            prune(now)
            for (s in turns.values) {
                if (s.flagged || s.closedAtMs > 0L) continue

                val grownSinceVoiced = s.transcript.length - s.transcriptLenAtLastVoiced
                val noVoiceAtAll = s.voicedMs <= 0.0
                val sinceVoicedMs = if (s.lastVoicedAtMs > 0L) now - s.lastVoicedAtMs else now - s.createdAtMs

                // 1) Текст идёт, звука нет (нули или чанки по 2–3 байта)
                val silentWhileTalking = grownSinceVoiced >= SILENT_TRANSCRIPT_GROWTH_CHARS &&
                    (s.silentRunAudioMs >= SILENT_RUN_MS || sinceVoicedMs >= SILENT_RUN_MS)
                val transcriptButNoAudio = noVoiceAtAll &&
                    s.transcript.length >= SILENT_TRANSCRIPT_GROWTH_CHARS &&
                    s.firstTranscriptAtMs > 0L && now - s.firstTranscriptAtMs >= NO_AUDIO_GRACE_MS

                if (silentWhileTalking || transcriptButNoAudio) {
                    s.flagged = true
                    return Verdict.SilentTurn(s.turnId, s.transcriptLenAtLastVoiced, s.transcript.toString())
                }

                // 2) Фраза закончилась, а сервер продолжает слать тишину
                val transcriptIdleMs = if (s.lastTranscriptGrowthAtMs > 0L) now - s.lastTranscriptGrowthAtMs else 0L
                val audioStillFlowing = s.lastAudioAtMs > 0L && now - s.lastAudioAtMs <= 1_500L
                if (s.voicedMs > 0.0 && audioStillFlowing &&
                    s.silentRunAudioMs >= RUNAWAY_SILENCE_MS && transcriptIdleMs >= RUNAWAY_TRANSCRIPT_IDLE_MS
                ) {
                    s.flagged = true
                    return Verdict.Runaway(s.turnId)
                }
            }
        }
        return null
    }

    private fun statsFor(turnId: Long, now: Long): Stats =
        turns.getOrPut(turnId) { Stats(turnId, now) }

    private fun prune(now: Long) {
        if (turns.size <= 4) return
        val it = turns.entries.iterator()
        while (it.hasNext() && turns.size > 4) {
            val s = it.next().value
            if (s.closedAtMs > 0L && now - s.closedAtMs > 60_000L) it.remove()
            else if (now - s.createdAtMs > 15 * 60_000L) it.remove()
        }
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

    companion object {
        private const val BYTES_PER_MS = 48.0              // 24 кГц × 16 бит
        private const val MIN_MEANINGFUL_BYTES = 96        // < 2 мс — «чанки AAA=» из отчёта о сбое
        const val SILENCE_RMS = 0.001