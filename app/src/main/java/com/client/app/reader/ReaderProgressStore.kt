package com.client.app.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Постоянное хранение документа и позиции чтения: «продолжить с того места, где остановился»
 * работает и после перезапуска приложения, и после выгрузки процесса системой.
 *
 * Запись атомарная (временный файл + rename), чтение терпимо к повреждённому файлу.
 */
class ReaderProgressStore(private val directory: File) {

    data class Saved(
        val documentId: String,
        val title: String,
        val text: String,
        val segment: Int,
        val sentence: Int,
        val pace: ReaderPace,
        val progress: Float,
        val finished: Boolean,
        val updatedAtMs: Long
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lock = Any()

    private val stateFile: File get() = File(directory, "reader_state.json")
    private val textFile: File get() = File(directory, "reader_document.txt")

    fun save(saved: Saved) = synchronized(lock) {
        runCatching {
            if (!directory.exists()) directory.mkdirs()
            val previousId = readStateUnlocked()?.documentId
            if (previousId != saved.documentId || !textFile.exists()) {
                writeAtomically(textFile, saved.text)
            }
            val state = buildJsonObject {
                put("documentId", saved.documentId)
                put("title", saved.title)
                put("segment", saved.segment)
                put("sentence", saved.sentence)
                put("pace", saved.pace.wire)
                put("progress", saved.progress.toDouble())
                put("finished", saved.finished)
                put("updatedAtMs", saved.updatedAtMs)
            }
            writeAtomically(stateFile, state.toString())
        }
        Unit
    }

    /** Только метаданные (без чтения текста документа) — для экрана «Продолжить чтение». */
    fun peek(): Saved? = synchronized(lock) { readStateUnlocked() }

    fun load(): Saved? = synchronized(lock) {
        val state = readStateUnlocked() ?: return@synchronized null
        val text = runCatching { textFile.readText(Charsets.UTF_8) }.getOrNull() ?: return@synchronized null
        state.copy(text = text)
    }

    fun clear() = synchronized(lock) {
        runCatching { stateFile.delete() }
        runCatching { textFile.delete() }
        Unit
    }

    private fun readStateUnlocked(): Saved? = runCatching {
        if (!stateFile.exists()) return@runCatching null
        val root = json.parseToJsonElement(stateFile.readText(Charsets.UTF_8)).jsonObject
        Saved(
            documentId = root["documentId"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null,
            title = root["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            text = "",
            segment = root["segment"]?.jsonPrimitive?.intOrNull ?: 0,
            sentence = root["sentence"]?.jsonPrimitive?.intOrNull ?: 0,
            pace = ReaderPace.from(ro