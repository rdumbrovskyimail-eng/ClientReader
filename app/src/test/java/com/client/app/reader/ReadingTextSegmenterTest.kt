package com.client.app.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Разбиение текста для Live-читалки: предложения не режутся на сокращениях, инициалах, датах и
 * репликах диалога; фрагменты состоят только из целых предложений и вместе покрывают весь текст.
 */
class ReadingTextSegmenterTest {

    private val segmenter = ReadingTextSegmenter()

    private fun sentences(paragraph: String): List<String> =
        segmenter.splitSentences(paragraph).map { (s, e) -> paragraph.substring(s, e) }

    @Test
    fun abbreviationsAndDatesDoNotSplitSentences() {
        val list = sentences("В 1917 г. произошла революция, т. е. сменилась власть. Это был важный год и т. д. Конец.")
        assertEquals(3, list.size, "Предложения: $list")
        assertTrue(list[0].endsWith("власть."))
    }

    @Test
    fun initialsDoNotSplitSentences() {
        val list = sentences("А. С. Пушкин родился в Москве. Он писал стихи.")
        assertEquals(listOf("А. С. Пушкин родился в Москве.", "Он писал стихи."), list)
    }

    @Test
    fun questionAndExclamationAreSentenceEnds() {
        val list = sentences("Ты придёшь? Конечно! Тогда до встречи…")
        assertEquals(3, list.size, "Предложения: $list")
    }

    @Test
    fun dialogueAttributionStaysWithReplica() {
        val list = sentences("— Привет! — сказал он. — Как дела?")
        assertEquals(2, list.size, "Предложения: $list")
        assertTrue(list[0].contains("сказал он"))
    }

    @Test
    fun pdfLineWrapsAreJoinedIntoParagraphs() {
        val paragraphs = segmenter.normalizeToParagraphs(
            "Это первая строка, которая\nпродолжается здесь, а сло-\nво перенесено.\n\nВторой абзац."
        )
        assertEquals(2, paragraphs.size, "Абзацы: $paragraphs")
        assertTrue(paragraphs[0].contains("которая продолжается"))
        assertTrue(paragraphs[0].contains("слово перенесено"))
    }

    @Test
    fun segmentsContainOnlyWholeSentencesAndCoverEverything() {
        val text = buildString {
            for (p in 1..12) {
                for (s in 1..7) append("Предложение $s абзаца $p о том, как читать длинный текст вслух без пропусков. ")
                append("\n\n")
            }
        }
        val doc = segmenter.build(text, "Тест")
        assertTrue(doc.segments.size > 1, "Ожидалось несколько фрагментов")

        val allSentences = doc.segments.flatMap { it.sentences }
        // Предложения идут подряд, без пропусков и наложений
        for (k in 1 until allSentences.size) {
            assertTrue(allSentences[k].start >= allSentences[k - 1].end, "Наложение предложений на $k")
        }
        // Каждый фрагмент не длиннее предела и начинается/заканчивается на границе предложения
        doc.segments.forEach { seg ->
            assertTrue(seg.end - seg.start <= 1500, "Фрагмент ${seg.index} длиной ${seg.end - seg.start}")
            assertEquals(seg.sentences.first().start, seg.start)
            assertEquals(seg.sentences.last().end, seg.end)
        }
        // Весь непробельный текст покрыт предложениями
        val covered = BooleanArray(doc.text.length)
        allSentences.forEach { for (i in it.start until it.end) covered[i] = true }
        val missing = doc.text.indices.count { !covered[it] && !doc.text[it].isWhitespace() }
        assertEquals(0, missing, "Непрочитанные символы")
    }

    @Test
    fun overlongSentenceIsSplitIntoReadableParts() {
        val longSentence = (1..120).joinToString(", ") { "часть $it" } + "."
        val doc = segmenter.build(longSentence)
        doc.segments.flatMap { it.sentences }.forEach {
            assertTrue(it.end - it.start <= 700, "Слишком длинная часть: ${it.end - it.start}")
        }
    }

    @Test
    fun documentIdIsStableAndPositionsResolve() {
        val text = "Первое предложение. Второе предложение.\n\nТретье предложение."
        val a = segmenter.build(text)
        val b = segmenter.build(text)
        assertEquals(a.id, b.id)
        assertFalse(a.isEmpty)
        assertEquals(0f, a.progressOf(ReaderPosition.START))
        assertTrue(a.isEnd(ReaderPosition(a.segments.size, 0)))
        assertTrue(a.segmentText(0).startsWith("Первое"))
    }
}