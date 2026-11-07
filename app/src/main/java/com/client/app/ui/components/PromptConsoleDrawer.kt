package com.client.app.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmDurations
import com.client.app.ui.theme.CalmEasing
import kotlinx.coroutines.delay

/**
 * Капсула «Роль ассистента» в верхней панели: первая строка текущей системной роли.
 */
@Composable
fun PromptPill(
    prompt: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Calm.colors
    val shape = RoundedCornerShape(28.dp)
    val preview = remember(prompt) {
        prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.hairline, shape)
            .clickable(role = Role.Button, onClickLabel = "Изменить роль ассистента", onClick = onClick)
            .padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(c.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.EditNote, contentDescription = null, tint = c.onAccentSoft, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Роль ассистента", style = MaterialTheme.typography.labelSmall, color = c.inkFaint)
            Text(
                text = preview.ifEmpty { "Не задана — нажмите, чтобы описать" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (preview.isEmpty()) c.inkFaint else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Шторка снизу с двумя вкладками: «Читать вслух» (текст для диктора, любой объём) и «Роль ассистента».
 * Текст для чтения НЕ становится системной ролью.
 *
 * Спокойные детали: понятные подсказки, счётчик со временем чтения, «Очистить» с возможностью вернуть
 * текст (6 секунд), кнопка «Читать» неактивна, пока поле пустое — вместо всплывающих предупреждений.
 * Шторка всегда помещается над клавиатурой: сжимается только поле ввода.
 */
@Composable
fun PromptEditorSheet(
    visible: Boolean,
    currentPrompt: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
    readText: String = "",
    onReadTextChange: (String) -> Unit = {},
    onRead: ((String) -> Unit)? = null,
    startInReadMode: Boolean = true
) {
    val c = Calm.colors
    val motion = Calm.motion
    val focusManager = LocalFocusManager.current

    fun close() {
        focusManager.clearFocus(force = true)
        onDismiss()
    }

    BackHandler(enabled = visible) { close() }

    // Затемнение
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.fade(CalmDurations.MEDIUM)),
        exit = fadeOut(motion.fade())
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Закрыть",
                    onClick = { close() }
                )
        )
    }

    // Шторка
    AnimatedVisibility(
        visible = visible,
        enter = if (motion.reduced) {
            fadeIn(motion.fade())
        } else {
            slideInVertically(motion.spec(CalmDurations.LONG, CalmEasing.Enter)) { it / 3 } + fadeIn(motion.fade(CalmDurations.MEDIUM))
        },
        exit = if (motion.reduced) {
            fadeOut(motion.fade())
        } else {
            slideOutVertically(motion.spec(CalmDurations.SHORT, CalmEasing.Exit)) { it / 4 } + fadeOut(motion.fade())
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(top = 12.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            SheetContent(
                currentPrompt = currentPrompt,
                onApply = onApply,
                readText = readText,
                onReadTextChange = onReadTextChange,
                onRead = onRead,
                startInReadMode = startInReadMode,
                onClose = { close() }
            )
        }
    }
}

@Composable
private fun SheetContent(
    currentPrompt: String,
    onApply: (String) -> Unit,
    readText: String,
    onReadTextChange: (String) -> Unit,
    onRead: ((String) -> Unit)?,
    startInReadMode: Boolean,
    onClose: () -> Unit
) {
    val c = Calm.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var draft by remember { mutableStateOf(currentPrompt) }
    var readMode by remember { mutableStateOf(onRead != null && startInReadMode) }
    var undoText by remember { mutableStateOf<String?>(null) }
    val fieldInteraction = remember { MutableInteractionSource() }
    val fieldFocused by fieldInteraction.collectIsFocusedAsState()

    val text = if (readMode) readText else draft
    fun setText(value: String) {
        if (readMode) onReadTextChange(value) else draft = value
    }

    // «Вернуть» после очистки доступно 6 секунд
    LaunchedEffect(undoText) {
        if (undoText != null) {
            delay(6_000)
            undoText = null
        }
    }

    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 760.dp)
            .fillMaxHeight()
            .padding(horizontal = 8.dp)
            .clip(sheetShape)
            .background(c.raised)
            .border(1.dp, c.hairline, sheetShape)
            // Касания по пустому месту шторки поглощаются и не закрывают её (без лишней семантики для TalkBack)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)
    ) {
        // Шапка
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (readMode) "Текст для чтения" else "Роль ассистента",
                style = MaterialTheme.typography.titleLarge,
                color = c.ink,
                modifier = Modifier.weight(1f)
            )
            CalmIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Закрыть",
                onClick = onClose,
                container = c.raisedStrong,
                tint = c.inkSoft
            )
        }

        Column(Modifier.padding(end = 8.dp)) {
            if (onRead != null) {
                Spacer(Modifier.height(10.dp))
                CalmSegmented(
                    options = listOf("Читать вслух", "Роль ассистента"),
                    selectedIndex = if (readMode) 0 else 1,
                    onSelect = {
                        readMode = it == 0
                        undoText = null
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (readMode) {
                    "Вставьте любой текст — я прочитаю его целиком. Остановить, переспросить или продолжить можно голосом, кнопкой или гарнитурой."
                } else {
                    "Опишите, каким должен быть ассистент. Роль применится сразу; если разговор идёт, он мягко переподключится."
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.inkSoft
            )
            Spacer(Modifier.height(12.dp))
        }

        // Поле ввода: занимает всё свободное место и прокручивается внутри
        val fieldShape = RoundedCornerShape(20.dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(end = 8.dp)
                .clip(fieldShape)
                .background(c.sunken)
                .border(if (fieldFocused) 1.5.dp else 1.dp, if (fieldFocused) c.accent else c.hairline, fieldShape)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { setText(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = if (readMode) "Текст для чтения" else "Роль ассистента" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                interactionSource = fieldInteraction,
                cursorBrush = SolidColor(c.accent),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize()) {
                        if (text.isEmpty()) {
                            Text(
                                text = if (readMode) {
                                    "Например, статью, главу книги или заметки. Абзацы, диалоги, списки и числа прочитаю с правильной интонацией."
                                } else {
                                    "Например: «Ты — терпеливый преподаватель немецкого. Отвечай коротко и по делу.»"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.inkFaint
                            )
                        }
                        inner()
                    }
                }
            )
        }

        // Счётчик или «Вернуть»
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val undo = undoText
            if (undo != null) {
                Text("Текст очищен", style = MaterialTheme.typography.bodySmall, color = c.inkSoft, modifier = Modifier.weight(1f))
                QuietTextButton(
                    text = "Вернуть",
                    icon = Icons.AutoMirrored.Rounded.Undo,
                    onClick = {
                        setText(undo)
                        undoText = null
                    }
                )
            } else {
                Spacer(Modifier.weight(1f))
                Text(
                    text = counterLine(text, readMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkFaint
                )
            }
        }

        // Действия
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalmIconButton(icon = Icons.Rounded.ContentPaste, contentDescription = "Вставить из буфера обмена", onClick = {
                val pasted = clipboard.getText()?.text.orEmpty()
                if (pasted.isNotBlank()) {
                    undoText = null
                    setText(if (text.isBlank()) pasted else "$text\n$pasted")
                } else {
                    Toast.makeText(context, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
                }
            })
            CalmIconButton(icon = Icons.Rounded.ContentCopy, contentDescription = "Копировать", enabled = text.isNotBlank(), onClick = {
                clipboard.setText(AnnotatedString(text))
                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
            })
            CalmIconButton(icon = Icons.Rounded.DeleteOutline, contentDescription = "Очистить поле", enabled = text.isNotEmpty(), onClick = {
                undoText = text
                setText("")
            })

            if (readMode && onRead != null) {
                PrimaryPillButton(
                    text = "Читать",
                    icon = Icons.Rounded.PlayArrow,
                    enabled = readText.isNotBlank(),
                    onClick = {
                        onRead(readText.trim())
                        onClose()
                    },
                    modifier = Modifier.weight(1f)
                )
            } else {
                PrimaryPillButton(
                    text = "Сохранить роль",
                    icon = Icons.Rounded.Check,
                    onClick = {
                        val clean = draft.trim()
                        val changed = clean != currentPrompt.trim()
                        if (changed) onApply(clean)
                        onClose()
                        Toast.makeText(context, if (changed) "Роль сохранена" else "Роль не изменилась", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** «12 340 знаков · ≈ 14 мин чтения» — средний темп диктора ≈ 14,5 знака в секунду. */
private fun counterLine(text: String, readMode: Boolean): String {
    val n = text.length
    val grouped = n.toString().reversed().chunked(3).joinToString(" ").reversed()
    if (!readMode || text.isBlank()) return "$grouped ${plural(n, "знак", "знака", "знаков")}"
    val minutes = (n / 14.5f / 60f).toInt().coerceAtLeast(1)
    return "$grouped ${plural(n, "знак", "знака", "знаков")} · ≈ $minutes мин чтения"
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}