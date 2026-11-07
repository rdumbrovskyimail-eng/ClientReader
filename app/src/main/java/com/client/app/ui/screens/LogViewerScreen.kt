package com.client.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.logging.AppLogManager
import com.client.app.logging.LogEntry
import com.client.app.logging.LogLevel
import com.client.app.ui.components.CalmIconButton
import com.client.app.ui.components.CalmTopBar
import com.client.app.ui.components.QuietTextButton
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmColors
import com.client.app.ui.theme.CalmMono
import kotlinx.coroutines.launch

private enum class LogFilter(val label: String) {
    ALL("Все"),
    ERRORS("Проблемы"),
    WARN("Внимание"),
    NETWORK("Сеть"),
    AUDIO("Звук"),
    VAD("Речь")
}

/**
 * Журнал событий — для диагностики, но без «аварийной» эстетики:
 * уровни различаются мягкими полутоновыми метками со словами, текст записей — обычного цвета,
 * очистка спрашивает подтверждение.
 */
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    logManager: AppLogManager
) {
    val logs by logManager.logsFlow.collectAsStateWithLifecycle()
    val errorCount by logManager.errorCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val c = Calm.colors

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(LogFilter.ALL) }
    var autoScroll by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }

    val filteredLogs = remember(logs, searchQuery, selectedFilter) {
        logs.filter { entry ->
            val matchesFilter = when (selectedFilter) {
                LogFilter.ALL -> true
                LogFilter.ERRORS -> entry.level == LogLevel.ERROR
                LogFilter.WARN -> entry.level == LogLevel.WARN || entry.level == LogLevel.ERROR
                LogFilter.NETWORK -> entry.level == LogLevel.NETWORK
                LogFilter.AUDIO -> entry.level == LogLevel.AUDIO
                LogFilter.VAD -> entry.level == LogLevel.VAD
            }
            val matchesSearch = searchQuery.isBlank() ||
                entry.message.contains(searchQuery, ignoreCase = true) ||
                entry.tag.contains(searchQuery, ignoreCase = true) ||
                (entry.payload?.contains(searchQuery, ignoreCase = true) == true)
            matchesFilter && matchesSearch
        }
    }

    val listState = rememberLazyListState()

    // Автопрокрутка к свежим записям
    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }
    // Пользователь листает вверх — автопрокрутка выключается
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val atBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == filteredLogs.lastIndex
            if (!atBottom) autoScroll = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        CalmTopBar(
            title = "Журнал",
            subtitle = buildString {
                append("${filteredLogs.size} из ${logs.size}")
                if (errorCount > 0) append(" · с проблемами: $errorCount")
            },
            onBack = onBack
        ) {
            CalmIconButton(
                icon = Icons.Rounded.VerticalAlignBottom,
                contentDescription = if (autoScroll) "Автопрокрутка включена" else "Автопрокрутка выключена",
                onClick = { autoScroll = !autoScroll },
                container = if (autoScroll) c.accentSoft else Color.Transparent,
                tint = if (autoScroll) c.onAccentSoft else c.inkSoft
            )
            CalmIconButton(
                icon = Icons.Rounded.Share,
                contentDescription = "Поделиться журналом",
                onClick = {
                    scope.launch {
                        val uri = logManager.exportLogsToFile()
                        if (uri != null) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Отправить журнал"))
                        } else {
                            Toast.makeText(context, "Журнал пока пуст", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                container = Color.Transparent,
                tint = c.inkSoft
            )
            CalmIconButton(
                icon = Icons.Rounded.DeleteOutline,
                contentDescription = "Очистить журнал",
                onClick = { confirmClear = true },
                enabled = logs.isNotEmpty(),
                container = Color.Transparent,
                tint = c.inkSoft
            )
        }

        // Поиск и фильтры
        Column(Modifier.padding(horizontal = 16.dp)) {
            SearchField(query = searchQuery, onQuery = { searchQuery = it })
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilter.entries.forEach { filter ->
                    FilterPill(label = filter.label, selected = selectedFilter == filter) { selectedFilter = filter }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (filteredLogs.isEmpty()) {
                EmptyJournal(hasAny = logs.isNotEmpty())
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = filteredLogs, key = { it.id }) { entry ->
                            LogRow(
                                entry = entry,
                                onCopy = {
                                    clipboard.setText(AnnotatedString("[${entry.timeFormatted}] [${entry.tag}] ${entry.message}"))
                                    Toast.makeText(context, "Запись скопирована", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }

            // К свежим записям
            ScrollToLatest(
                visible = !autoScroll && filteredLogs.isNotEmpty(),
                onClick = {
                    autoScroll = true
                    scope.launch {
                        if (filteredLogs.isNotEmpty()) listState.animateScrollToItem(filteredLogs.lastIndex)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 16.dp)
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Записи удалятся с телефона. Если журнал нужен для разбора проблемы, сначала отправьте его.") },
            confirmButton = {
                QuietTextButton(
                    text = "Очистить",
                    onClick = {
                        confirmClear = false
                        logManager.clear()
                    },
                    color = c.problem
                )
            },
            dismissButton = {
                QuietTextButton(text = "Оставить", onClick = { confirmClear = false })
            },
            containerColor = c.raised,
            titleContentColor = c.ink,
            textContentColor = c.inkSoft,
            shape = MaterialTheme.shapes.extraLarge
        )
    }
}

@Composable
private fun ScrollToLatest(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Calm.colors
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Calm.motion.fade()),
        exit = fadeOut(Calm.motion.fade()),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(c.accentSoft)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = c.onAccentSoft, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("К новым записям", style = MaterialTheme.typography.labelLarge, color = c.onAccentSoft)
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val c = Calm.colors
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(c.sunken)
            .border(1.dp, c.hairline, shape)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = c.inkFaint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "Поиск по журналу" },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Поиск по тегу, тексту или данным", style = MaterialTheme.typography.bodyMedium, color = c.inkFaint)
                    }
                    inner()
                }
            }
        )
        if (query.isNotEmpty()) {
            CalmIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Очистить поиск",
                onClick = { onQuery("") },
                container = Color.Transparent,
                tint = c.inkSoft,
                size = 40.dp,
                iconSize = 18.dp
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Calm.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) c.accentSoft else c.raised)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.5f) else c.hairline, shape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.onAccentSoft else c.inkSoft
        )
    }
}

@Composable
private fun EmptyJournal(hasAny: Boolean) {
    val c = Calm.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(c.raisedStrong),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null, tint = c.inkFaint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (hasAny) "Ничего не найдено" else "Журнал пуст",
            style = MaterialTheme.typography.titleMedium,
            color = c.ink
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (hasAny) "Попробуйте другой фильтр или запрос." else "Записи появятся, когда приложение начнёт работу.",
            style = MaterialTheme.typography.bodySmall,
            color = c.inkSoft
        )
    }
}

private data class LevelStyle(val label: String, val container: Color, val content: Color)

private fun CalmColors.levelStyle(level: LogLevel): LevelStyle = when (level) {
    LogLevel.ERROR -> LevelStyle("Проблема", problemSoft, onProblemSoft)
    LogLevel.WARN -> LevelStyle("Внимание", attentionSoft, onAttentionSoft)
    LogLevel.NETWORK -> LevelStyle("Сеть", raisedStrong, inkSoft)
    LogLevel.AUDIO -> LevelStyle("Звук", accentSoft, onAccentSoft)
    LogLevel.VAD -> LevelStyle("Речь", accentSoft, onAccentSoft)
    LogLevel.INFO -> LevelStyle("Инфо", raisedStrong, inkSoft)
    else -> LevelStyle("Отладка", raisedStrong, inkFaint)
}

@Composable
private fun LogRow(entry: LogEntry, onCopy: () -> Unit) {
    val c = Calm.colors
    var expanded by remember { mutableStateOf(false) }
    val style = c.levelStyle(entry.level)
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.hairline, shape)
            .clickable(
                onClickLabel = if (entry.payload != null) "Показать данные" else "Скопировать запись",
                onClick = { if (entry.payload != null) expanded = !expanded else onCopy() }
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = entry.timeFormatted, style = CalmMono, color = c.inkFaint)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(style.container)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(text = style.label, style = MaterialTheme.typography.labelSmall, color = style.content)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.tag,
                style = MaterialTheme.typography.labelMedium,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.threadName,
                style = MaterialTheme.typography.labelSmall,
                color = c.inkFaint,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(text = entry.message, style = CalmMono, color = c.ink)

        val payload = entry.payload
        if (payload != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = c.accentInk,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(if (expanded) 180f else 0f)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (expanded) "Скрыть данные" else "Показать данные · ${payload.length} байт",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.accentInk
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = payload,
                    style = CalmMono,
                    color = c.inkSoft,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.sunken)
                        .padding(10.dp)
                )
            }
        }
    }
}