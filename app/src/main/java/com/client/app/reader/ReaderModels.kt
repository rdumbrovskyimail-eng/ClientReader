package com.client.app.reader

/**
 * Модель Live-читалки: документ, разбитый на фрагменты (segments) из целых предложений,
 * позиция чтения с точностью до предложения и состояние для UI.
 *
 * Все смещения (start/end) — индексы в нормализованном тексте документа [ReaderDocument.text].
 */

enum class ReaderStatus {
    IDLE,        // читалка не активна
    PREPARING,   // документ загружен, поднимается Live-сессия профиля READER
    READING,     // идёт чтение
    BUFFERING,   // чтение идёт, но звук ещё не пришёл (сеть)
    PAUSED,      // пауза (голосом, кнопкой, гарнитурой, аудиофокусом)
    ANSWERING,   // модель отвечает на вопрос слушателя во время паузы
    FINISHED,    // документ дочитан
    ERROR
}

enum class PauseReason {
    NONE,
    USER,            // явная пауза: кнопка, гарнитура, «стоп/пауза»
    VOICE,           // слушатель заговорил поверх чтения (неявная пауза, может автопродолжиться)
    FOCUS,           // временная потеря аудиофокуса (звонок, навигатор) — продолжится сам
    FOCUS_PERMANENT, // другое приложение забрало звук — продолжение только по команде
    ANSWER           // модель ответила на вопрос — ждём «продолжай»
}

/**
 * Темп чтения. [charsPerSecond] — ожидаемая скорость речи диктора (символы текста в секунду),
 * используется для оценки длительности фрагмента и сторожей «обрезанного» чтения.
 */
enum class ReaderPace(val wire: String, val charsPerSecond: Float, val label: String) {
    SLOW("slow", 11.5f, "медленный"),
    MEDIUM("medium", 14.5f, "средний"),
    FAST("fast", 17.5f, "быстрый");

    companion object {
        /** Терпимо к формулировкам модели: «slower», «медленнее», «faster», «быстрее». */
        fun from(value: String?): ReaderPace {
            val clean = value?.trim()?.lowercase().orEmpty()
            entries.firstOrNull { it.wire == clean }?.let { return it }
            return when {
                clean.startsWith("slow") || clean.startsWith("медл") || clean.startsWith("помедл") -> SLOW
                clean.startsWith("fast") || clean.startsWith("quick") || clean.startsWith("быстр") || clean.startsWith("побыстр") -> FAST
                else -> MEDIUM
            }
        }
    }
}

/** Предложение документа: [start, end) в нормализованном тексте. */
data class ReaderSentence(val start: Int, val end: Int)

/**
 * Фрагмент чтения — одна реплика модели. Состоит только из целых предложений.
 * [paragraphStart] — фрагмент начинается с нового абзаца.
 */
data class ReaderSegment(
    val index: Int,
    val start: Int,
    val end: Int,
    val sentences: List<ReaderSentence>,
    val paragraphStart: Boolean
)

class ReaderDocument(
    val id: String,
    val title: String,
    val text: String,
    val segments: List<ReaderSegment>
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /** Текст фрагмента начиная с предложения [fromSentence] (для продолжения с середины). */
    fun segmentText(segmentIndex: Int, fromSentence: Int = 0): String {
        val seg = segments.getOrNull(segmentIndex) ?: return ""
        val from = fromSentence.coerceIn(0, (seg.sentences.size - 1).coerceAtLeast(0))
        val start = seg.sentences.getOrNull(from)?.start ?: seg.start
        return text.substring(start.coerceIn(0, text.length), seg.end.coerceIn(0, text.length)).trim()
    }

    /** Абсолютное смещение начала предложения — для прогресса и сохранения позиции. */
    fun charOffsetOf(position: ReaderPosition): Int {
        val seg = segments.getOrNull(position.segment) ?: return text.length
        return seg.sentences.getOrNull(position.sentence)?.start ?: seg.start
    }

    fun progressOf(position: ReaderPosition): Float =
        if (text.isEmpty()) 0f else (charOffsetOf(position).toFloat() / text.length.toFloat()).coerceIn(0f, 1f)

    /** Позиция начала абзаца, в котором находится [position]. */
    fun paragraphStartOf(position: ReaderPosition): ReaderPosition {
        var idx = position.segment.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
        while (idx > 0 && segments.getOrNull(idx)?.paragraphStart != true) idx--
        return ReaderPosition(idx, 0)
    }

    /** Начало следующего абзаца после [position] (или конец документа). */
    fun nextParagraphAfter(position: ReaderPosition): ReaderPosition {
        var idx = position.segment + 1
        while (idx < segments.size && !segments[idx].paragraphStart) idx++
        return ReaderPosition(idx.coerceAtMost(segments.size), 0)
    }

    /** Предыдущее предложение (через границу фрагмента). */
    fun previousSentence(position: ReaderPosition): ReaderPosition {
        if (position.sentence > 0) return ReaderPosition(position.segment, position.sentence - 1)
        val prevSeg = position.segment - 1
        if (prevSeg < 0) return ReaderPosition(0, 0)
        val count = segments[prevSeg].sentences.size
        return ReaderPosition(prevSeg, (count - 1).coerceAtLeast(0))
    }

    fun isEnd(position: ReaderPosition): Boolean = position.segment >= segments.size
}

data class ReaderPosition(val segment: Int, val sentence: Int) : Comparable<ReaderPosition> {
    override fun compareTo(other: ReaderPosition): Int =
        if (segment != other.segment) segment.compareTo(other.segment) else sentence.compareTo(other.sentence)

    companion object {
        val START = ReaderPosition(0, 0)
    }
}

/** Состояние читалки для экрана, уведомления и медиасессии. */
data class ReaderUiState(
    val status: ReaderStatus = ReaderStatus.IDLE,
    val title: String = "",
    val progress: Float = 0f,
    val segment: Int = 0,
    val totalSegments: Int = 0,
    val pace: ReaderPace = ReaderPace.MEDIUM,
    val pauseReason: PauseReason = PauseReason.NONE,
    val savedTitle: String? = null,
    val savedProgress: Float = 0f
) {
    val isActive: Boolean
        get() = status != ReaderStatus.IDLE && status != ReaderStatus.FINISHED && status != ReaderStatus.ERROR

    val isPlaying: Boolean
        get() = status == ReaderStatus.READING || status == ReaderStatus.BUFFERING

    val hasSaved: Boolean get() = savedTitle != null
}

sealed interface ReaderCommand {
    data object Pause : ReaderCommand
    data object Resume : ReaderCommand
    data object Toggle : ReaderCommand
    data object RepeatSentence : ReaderCommand
    data object RepeatParagraph : ReaderCommand
    data object NextParagraph : ReaderCommand
    data object Stop : ReaderCommand
    data class SetPace(val pace: ReaderPace) : ReaderCommand
    data class Volume(val up: Boolean) : ReaderCommand
}

/** Решение по аудиокадру модели в режиме читалки. */
enum class AudioDecision { PLAY, DROP, HOLD }