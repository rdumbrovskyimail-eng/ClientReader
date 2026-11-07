package com.client.app.reader

import java.security.MessageDigest

/**
 * Подготовка большого текста к чтению вслух.
 *
 * 1. Нормализация (копирование из PDF/браузера/мессенджеров): мягкие переносы, неразрывные пробелы,
 *    перенос слова по слогам «сло-\nво», переносы строк внутри абзаца.
 * 2. Разбиение на предложения с учётом русских и английских сокращений, инициалов, нумерации,
 *    многоточий, закрывающих кавычек и скобок.
 * 3. Группировка предложений во фрагменты по ~900 символов (одна реплика модели ≈ 60 с речи):
 *    фрагмент никогда не режет предложение, а короткие абзацы (заголовки) склеиваются со следующими.
 *
 * Чистый Kotlin без Android — покрыт unit-тестами.
 */
class ReadingTextSegmenter(
    private val targetChars: Int = 900,
    private val minChars: Int = 300,
    private val maxChars: Int = 1500,
    private val maxSentenceChars: Int = 700
) {

    fun build(raw: String, title: String? = null): ReaderDocument {
        val paragraphs = normalizeToParagraphs(raw)
        val text = paragraphs.joinToString(PARAGRAPH_SEPARATOR)

        val perParagraph = ArrayList<List<ReaderSentence>>(paragraphs.size)
        var offset = 0
        for (paragraph in paragraphs) {
            perParagraph += splitSentences(paragraph).map { (s, e) -> ReaderSentence(s + offset, e + offset) }
            offset += paragraph.length + PARAGRAPH_SEPARATOR.length
        }

        val segments = chunk(perParagraph)
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: deriveTitle(paragraphs)
        return ReaderDocument(
            id = sha1(text).take(16),
            title = cleanTitle,
            text = text,
            segments = segments
        )
    }

    // ------------------------------------------------------------------------------------------
    // 1. Нормализация
    // ------------------------------------------------------------------------------------------

    fun normalizeToParagraphs(raw: String): List<String> {
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        s = s.replace("­", "")
        s = INVISIBLE.replace(s, "")
        s = WIDE_SPACES.replace(s, " ")
        // «сло-\nво» → «слово» (перенос по слогам при копировании из PDF)
        s = SYLLABLE_WRAP.replace(s, "$1$2")

        val lines = s.split('\n').map { it.trim() }
        val paragraphs = ArrayList<String>()
        val current = StringBuilder()
        var previousLine = ""

        fun flush() {
            val p = MULTI_SPACE.replace(current.toString(), " ").trim()
            if (p.isNotEmpty()) paragraphs += p
            current.setLength(0)
            previousLine = ""
        }

        for (line in lines) {
            if (line.isEmpty()) {
                flush()
                continue
            }
            if (current.isEmpty()) {
                current.append(line)
            } else if (isHardLineBreak(previousLine, line)) {
                flush()
                current.append(line)
            } else {
                current.append(' ').append(line)
            }
            previousLine = line
        }
        flush()
        return paragraphs
    }

    private fun isHardLineBreak(prev: String, next: String): Boolean {
        if (LIST_MARKER.containsMatchIn(next)) return true
        val prevEndsSentence = SENTENCE_END_AT_LINE_END.containsMatchIn(prev)
        val nextStartsUpper = next.firstOrNull()?.let { it.isUpperCase() || it.isDigit() || it in OPENERS } == true
        if (prevEndsSentence && nextStartsUpper) return true
        // Короткая строка без знака препинания перед строкой с заглавной буквы — заголовок
        val prevLooksLikeHeading = prev.length <= HEADING_MAX_CHARS &&
            prev.lastOrNull()?.let { it !in ".,;:!?…-–—" } == true
        return prevLooksLikeHeading && nextStartsUpper
    }

    // ------------------------------------------------------------------------------------------
    // 2. Предложения
    // ------------------------------------------------------------------------------------------

    /** Диапазоны предложений [start, end) внутри абзаца [p] (без ведущих и хвостовых пробелов). */
    fun splitSentences(p: String): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val n = p.length
        var start = 0
        var i = 0
        while (i < n) {
            val c = p[i]
            if (c !in TERMINALS) {
                i++
                continue
            }
            var end = i + 1
            while (end < n && p[end] in TERMINALS) end++
            while (end < n && p[end] in CLOSERS) end++
            if (end >= n) break
            if (p[end] != ' ') {
                i = end
                continue
            }
            var next = end
            while (next < n && p[next] == ' ') next++
            if (next < n && isSentenceStart(p[next]) && !isDialogueContinuation(p, next) &&
                !isNonTerminalDot(p, i, next, start)
            ) {
                addTrimmed(out, p, start, end)
                start = next
                i = next
                continue
            }
            i = end
        }
        if (start < n) addTrimmed(out, p, start, n)
        return out.flatMap { splitLong(p, it.first, it.second) }
    }

    private fun addTrimmed(out: MutableList<Pair<Int, Int>>, p: String, from: Int, to: Int) {
        var s = from
        var e = to
        while (s < e && p[s].isWhitespace()) s++
        while (e > s && p[e - 1].isWhitespace()) e--
        if (e > s) out += s to e
    }

    private fun isSentenceStart(ch: Char): Boolean =
        ch.isUpperCase() || ch.isDigit() || ch in OPENERS

    /** «— Куда ты? — спросила она.» — авторская ремарка после тире с маленькой буквы продолжает фразу. */
    private fun isDialogueContinuation(p: String, index: Int): Boolean {
        if (p[index] !in DASHES) return false
        var j = index + 1
        while (j < p.length && (p[j] == ' ' || p[j] in DASHES)) j++
        return j < p.length && p[j].isLetter() && p[j].isLowerCase()
    }

    /**
     * true — точка не завершает предложение: инициалы, «г. Москва», «проф. Иванов», «см. рис. 3»,
     * номер пункта в начале абзаца («1. Введение»).
     */
    private fun isNonTerminalDot(p: String, dotIndex: Int, nextIndex: Int, sentenceStart: Int): Boolean {
        if (p[dotIndex] != '.') return false
        var k = dotIndex - 1
        while (k >= 0 && (p[k].isLetterOrDigit() || p[k] == '.' || p[k] == '-')) k--
        val tokenStart = k + 1
        if (tokenStart >= dotIndex) return false
        val token = p.substring(tokenStart, dotIndex)
        val lower = token.lowercase()
        val nextChar = p[nextIndex]

        // Инициалы: «А. С. Пушкин», «J. R. R. Tolkien»
        if (token.length == 1 && token[0].isLetter() && token[0].isUpperCase()) return true

        // Нумерация пункта в начале абзаца/предложения: «1. Введение», «12. Итоги»
        if (token.all { it.isDigit() } && tokenStart == sentenceStart) return true

        if (lower == "г" || lower == "гг") {
            // «1917 г. Началась…» — конец предложения; «в г. Москве» — сокращение
            var b = tokenStart - 1
            while (b >= 0 && p[b] == ' ') b--
            val afterNumber = b >= 0 && p[b].isDigit()
            return !afterNumber
        }

        if (lower in REFERENCE_ABBREVIATIONS) return true
        if (lower in NUMBER_ABBREVIATIONS && nextChar.isDigit()) return true
        return false
    }

    /** Слишком длинное предложение режется по «;», «:», «—» или «,» — фрагменту нужны естественные паузы. */
    private fun splitLong(p: String, from: Int, to: Int): List<Pair<Int, Int>> {
        if (to - from <= maxSentenceChars) return listOf(from to to)
        val limit = from + maxSentenceChars
        val floor = from + maxSentenceChars / 3
        var cut = -1
        for (marks in SOFT_BREAK_PRIORITY) {
            var j = limit
            while (j > floor) {
                if (p[j] in marks && j + 1 < to && p[j + 1] == ' ') {
                    cut = j + 1
                    break
                }
                j--
            }
            if (cut > 0) break
        }
        if (cut <= 0) {
            var j = limit
            while (j > floor && p[j] != ' ') j--
            cut = if (j > floor) j else limit
        }
        val head = ArrayList<Pair<Int, Int>>()
        addTrimmed(head, p, from, cut)
        val restStart = cut
        val tail = ArrayList<Pair<Int, Int>>()
        addTrimmed(tail, p, restStart, to)
        return head + tail.flatMap { splitLong(p, it.first, it.second) }
    }

    // ------------------------------------------------------------------------------------------
    // 3. Фрагменты
    // ------------------------------------------------------------------------------------------

    private fun chunk(perParagraph: List<List<ReaderSentence>>): List<ReaderSegment> {
        val segments = ArrayList<ReaderSegment>()
        var current = ArrayList<ReaderSentence>()
        var currentLength = 0
        var currentParagraphStart = true

        fun close() {
            if (current.isEmpty()) return
            segments += ReaderSegment(
                index = segments.size,
                start = current.first().start,
                end = current.last().end,
                sentences = current.toList(),
                paragraphStart = currentParagraphStart
            )
            current = ArrayList()
            currentLength = 0
        }

        for (sentences in perParagraph) {
            if (sentences.isEmpty()) continue
            // Граница абзаца — естественное место для новой реплики, если фрагмент уже не крошечный
            if (current.isNotEmpty() && currentLength >= minChars) close()
            var firstInParagraph = true
            for (sentence in sentences) {
                val length = sentence.end - sentence.start
                val overTarget = current.isNotEmpty() && currentLength + length > targetChars && currentLength >= minChars
                val overMax = current.isNotEmpty() && currentLength + length > maxChars
                if (overTarget || overMax) close()
                if (current.isEmpty()) currentParagraphStart = firstInParagraph
                current += sentence
                currentLength += length + 1
                firstInParagraph = false
            }
        }
        close()
        return segments
    }

    private fun deriveTitle(paragraphs: List<String>): String {
        val first = paragraphs.firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return "Текст"
        if (first.length <= TITLE_MAX_CHARS) return first.trimEnd('.', ',', ':', ';')
        val cut = first.lastIndexOf(' ', TITLE_MAX_CHARS).takeIf { it > 20 } ?: TITLE_MAX_CHARS
        return first.substring(0, cut).trimEnd('.', ',', ':', ';') + "…"
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PARAGRAPH_SEPARATOR = "\n\n"
        private const val HEADING_MAX_CHARS = 48
        private const val TITLE_MAX_CHARS = 60

        private val TERMINALS = setOf('.', '!', '?', '…')
        private val CLOSERS = setOf('»', '"', '”', '’', ')', ']', '\'')
        private val OPENERS = setOf('«', '"', '“', '„', '\'', '(', '[', '—', '–', '-')
        private val DASHES = setOf('—', '–', '-')
        private val SOFT_BREAK_PRIORITY = listOf(setOf(';'), setOf(':', '—', '–'), setOf(','))

        private val INVISIBLE = Regex("[​‌‍⁠﻿]")
        private val WIDE_SPACES = Regex("[     \t]")
        private val SYLLABLE_WRAP = Regex("(\\p{L})[-‐‑]\\n(\\p{Ll})")
        private val MULTI_SPACE = Regex(" {2,}")
        private val LIST_MARKER = Regex("^([-–—•*·▪●]|\\d{1,3}[.)]|[a-zа-яё][)])\\s")
        private val SENTENCE_END_AT_LINE_END = Regex("[.!?…:;][»\"”’)\\]]*$")

        /** Сокращения, после которых обычно идёт имя собственное или ссылка — точка не конец фразы. */
        private val REFERENCE_ABBREVIATIONS = setOf(
            "ул", "пр", "просп", "пр-т", "пл", "пер", "б-р", "наб", "обл", "р-н", "пос", "дер",
            "им", "проф", "акад", "доц", "д-р", "св", "см", "ср", "рис", "табл", "гл", "разд",
            "mr", "mrs", "ms", "dr", "prof", "st", "vs", "fig", "jr", "sr"
        )

        /** Сокращения перед числом: «с. 25», «т. 2», «стр. 7», «№», «no. 5», «vol. 3», «p. 12». */
        private val NUMBER_ABBREVIATIONS = setOf("с", "т", "стр", "ч", "п", "no", "vol", "p", "pp", "ст")
    }
}