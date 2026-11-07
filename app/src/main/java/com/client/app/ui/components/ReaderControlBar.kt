package com.client.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.client.app.reader.PauseReason
import com.client.app.reader.ReaderCommand
import com.client.app.reader.ReaderPace
import com.client.app.reader.ReaderStatus
import com.client.app.reader.ReaderUiState
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmDurations
import com.client.app.ui.theme.CalmEasing
import com.client.app.ui.theme.readerTextStyle
import kotlin.math.roundToInt

/**
 * Панель Live-читалки.
 *
 * - «Телесуфлёр»: предложение, которое звучит сейчас, крупно; следующее — приглушённо.
 *   Видно, где вы в тексте, даже если отвлеклись или задали вопрос.
 * - Прогресс с оценкой оставшегося времени (долгое ожидание без оценки тревожит).
 * - Три главные кнопки: повторить, пауза/продолжить, следующий абзац; темп — сегментами.
 * - Сохранённое место — карточка «Можно продолжить»; «Забыть» спрашивает подтверждение.
 */
@Composable
fun ReaderControlBar(
    reader: ReaderUiState,
    onCommand: (ReaderCommand) -> Unit,
    onResumeSaved: () -> Unit,
    onDiscardSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motion = Calm.motion
    val showActive = reader.isActive || reader.status == ReaderStatus.FINISHED
    val showSaved = !showActive && reader.hasSaved

    AnimatedVisibility(
        visible = showActive || showSaved,
        modifier = modifier,
        enter = if (motion.reduced) fadeIn(motion.fade()) else expandVertically(motion.spec(CalmDurations.MEDIUM, CalmEasing.Enter)) + fadeIn(motion.fade()),
        exit = if (motion.reduced) fadeOut(motion.fade()) else shrinkVertically(motion.spec(CalmDurations.SHORT, CalmEasing.Exit)) + fadeOut(motion.fade())
    ) {
        CalmCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
            when {
                showActive && reader.status == ReaderStatus.FINISHED -> FinishedReader(reader, onCommand)
                showActive -> ActiveReader(reader, onCommand)
                else -> SavedReader(reader, onResumeSaved, onDiscardSaved)
            }
        }
    }
}

@Composable
private fun ColumnScope.ActiveReader(reader: ReaderUiState, onCommand: (ReaderCommand) -> Unit) {
    val c = Calm.colors
    val motion = Calm.motion
    val context = LocalContext.current
    val progress by animateFloatAsState(
        targetValue = reader.progress.coerceIn(0f, 1f),
        animationSpec = motion.spec(CalmDurations.LONG),
        label = "reader_progress"
    )
    val paused = reader.status == ReaderStatus.PAUSED
    val waiting = reader.status == ReaderStatus.PREPARING || reader.status == ReaderStatus.BUFFERING

    // Шапка: состояние и процент
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (waiting) {
            BreathingDot(color = c.accent, size = 8.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = statusLabel(reader),
            style = MaterialTheme.typography.labelMedium,
            color = c.inkSoft,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${(reader.progress * 100).roundToInt()} %",
            style = MaterialTheme.typography.labelMedium,
            color = c.inkFaint
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = reader.title.ifBlank { "Текст" },
        style = MaterialTheme.typography.titleMedium,
        color = c.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )

    // Телесуфлёр
    if (reader.sentence.isNotBlank()) {
        Spacer(Modifier.height(14.dp))
        AnimatedContent(
            targetState = reader.sentence to reader.nextSentence,
            transitionSpec = { fadeIn(motion.fade(CalmDurations.MEDIUM)) togetherWith fadeOut(motion.fade(CalmDurations.SHORT)) },
            label = "teleprompter"
        ) { (now, next) ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = now,
                    style = readerTextStyle(Calm.prefs.readerTextScale),
                    color = c.ink,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
                if (next.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = next,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.inkFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    CalmProgressLine(progress = progress)
    Spacer(Modifier.height(8.dp))
    Text(
        text = metaLine(reader),
        style = MaterialTheme.typography.bodySmall,
        color = c.inkFaint
    )

    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundAction(Icons.Rounded.Replay, "Повторить предложение") {
            onCommand(ReaderCommand.RepeatSentence)
        }
        RoundAction(
            icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            description = if (paused) "Продолжить чтение" else "Пауза",
            primary = true,
            size = 68.dp
        ) {
            CalmHaptics.soft(context)
            onCommand(if (paused) ReaderCommand.Resume else ReaderCommand.Pause)
        }
        RoundAction(Icons.Rounded.SkipNext, "Следующий абзац") {
            onCommand(ReaderCommand.NextParagraph)
        }
    }

    Spacer(Modifier.height(14.dp))
    val paces = ReaderPace.entries
    CalmSegmented(
        options = paces.map { paceLabel(it) },
        selectedIndex = paces.indexOf(reader.pace).coerceAtLeast(0),
        onSelect = { onCommand(ReaderCommand.SetPace(paces[it])) }
    )
    Spacer(Modifier.height(6.dp))
    QuietTextButton(
        text = "Завершить чтение",
        onClick = { onCommand(ReaderCommand.Stop) },
        icon = Icons.Rounded.Stop,
        color = c.inkSoft,
        modifier = Modifier.align(Alignment.CenterHorizontally)
    )
}

@Composable
private fun ColumnScope.FinishedReader(reader: ReaderUiState, onCommand: (ReaderCommand) -> Unit) {
    val c = Calm.colors
    Text(text = "Прочитано", style = MaterialTheme.typography.labelMedium, color = c.accentInk)
    Spacer(Modifier.height(4.dp))
    Text(
        text = reader.title.ifBlank { "Текст" },
        style = MaterialTheme.typography.titleMedium,
        color = c.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Текст прочитан до конца. Можно прослушать его ещё раз или вставить новый.",
        style = MaterialTheme.typography.bodyMedium,
        color = c.inkSoft
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryPillButton(
            text = "Прослушать снова",
            icon = Icons.Rounded.Replay,
            onClick = { onCommand(ReaderCommand.Resume) },
            modifier = Modifier.weight(1f)
        )
        TonalPillButton(text = "Закрыть", onClick = { onCommand(ReaderCommand.Stop) }, height = 52.dp)
    }
}

@Composable
private fun ColumnScope.SavedReader(reader: ReaderUiState, onResumeSaved: () -> Unit, onDiscardSaved: () -> Unit) {
    val c = Calm.colors
    var confirmForget by remember { mutableStateOf(false) }

    Text(text = "Можно продолжить", style = MaterialTheme.typography.labelMedium, color = c.accentInk)
    Spacer(Modifier.height(4.dp))
    Text(
        text = reader.savedTitle.orEmpty().ifBlank { "Текст" },
        style = MaterialTheme.typography.titleMedium,
        color = c.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(10.dp))
    CalmProgressLine(progress = reader.savedProgress)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Прочитано ${(reader.savedProgress * 100).roundToInt()} % — продолжу с того же предложения",
        style = MaterialTheme.typography.bodySmall,
        color = c.inkFaint
    )
    Spacer(Modifier.height(14.dp))

    AnimatedContent(
        targetState = confirmForget,
        transitionSpec = { fadeIn(Calm.motion.fade()) togetherWith fadeOut(Calm.motion.fade()) },
        label = "saved_actions"
    ) { confirming ->
        if (!confirming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryPillButton(
                    text = "Продолжить",
                    icon = Icons.Rounded.PlayArrow,
                    onClick = onResumeSaved,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                QuietTextButton(text = "Забыть", onClick = { confirmForget = true }, color = c.inkSoft)
            }
        } else {
            Column {
                Text(
                    text = "Забыть место чтения? Чтобы продолжить потом, текст придётся вставить заново.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkSoft
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    QuietTextButton(text = "Оставить", onClick = { confirmForget = false }, color = c.accentInk)
                    QuietTextButton(
                        text = "Забыть",
                        onClick = {
                            confirmForget = false
                            onDiscardSaved()
                        },
                        color = c.problem
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundAction(
    icon: ImageVector,
    description: String,
    primary: Boolean = false,
    size: Dp = 52.dp,
    onClick: () -> Unit
) {
    val c = Calm.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (primary) c.accent else c.raisedStrong)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (primary) c.onAccent else c.ink,
            modifier = Modifier.size(size * 0.44f)
        )
    }
}

private fun paceLabel(pace: ReaderPace): String = when (pace) {
    ReaderPace.SLOW -> "Медленно"
    ReaderPace.MEDIUM -> "Обычно"
    ReaderPace.FAST -> "Быстро"
}

private fun statusLabel(reader: ReaderUiState): String = when (reader.status) {
    ReaderStatus.PREPARING -> "Готовлю чтение"
    ReaderStatus.READING -> "Читаю"
    ReaderStatus.BUFFERING -> "Догружаю звук — сеть медленная"
    ReaderStatus.ANSWERING -> "Отвечаю на вопрос"
    ReaderStatus.FINISHED -> "Прочитано"
    ReaderStatus.ERROR -> "Чтение остановлено"
    ReaderStatus.IDLE -> ""
    ReaderStatus.PAUSED -> when (reader.pauseReason) {
        PauseReason.VOICE -> "Слушаю вас"
        PauseReason.FOCUS -> "Пауза — звук ненадолго занят"
        PauseReason.FOCUS_PERMANENT -> "Пауза — звук у другого приложения"
        PauseReason.ANSWER -> "Пауза — скажите «продолжай»"
        else -> "Пауза"
    }
}

/** «Фрагмент 12 из 340 · осталось ≈ 25 мин». */
private fun metaLine(reader: ReaderUiState): String {
    val parts = mutableListOf<String>()
    if (reader.totalSegments > 0) {
        parts += "Фрагмент ${(reader.segment + 1).coerceAtMost(reader.totalSegments)} из ${reader.totalSegments}"
    }
    if (reader.totalChars > 0) {
        val remainingSec = reader.totalChars * (1f - reader.progress.coerceIn(0f, 1f)) / reader.pace.charsPerSecond
        parts += "осталось ${formatDuration(remainingSec)}"
    }
    return parts.joinToString(" · ")
}

private fun formatDuration(seconds: Float): String {
    val min = (seconds / 60f).roundToInt()
    return when {
        seconds < 50f -> "меньше минуты"
        min < 60 -> "≈ $min мин"
        else -> "≈ ${min / 60} ч ${min % 60} мин"
    }
}