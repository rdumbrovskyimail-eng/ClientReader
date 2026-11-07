package com.client.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.client.app.reader.PauseReason
import com.client.app.reader.ReaderCommand
import com.client.app.reader.ReaderPace
import com.client.app.reader.ReaderStatus
import com.client.app.reader.ReaderUiState

// Чёрная палитра главного экрана
private val ColorCard = Color(0xFF111113)
private val ColorButton = Color(0xFF1A1A1D)
private val ColorHairline = Color(0xFF26262B)
private val ColorTextPrimary = Color(0xFFF4F4F5)
private val ColorTextSecondary = Color(0xFFA1A1AA)
private val ColorTextMuted = Color(0xFF71717A)
private val ColorAccent = Color(0xFFF4F4F5)
private val ColorOnAccent = Color(0xFF09090B)
private val ColorTrack = Color(0xFF27272A)

private val CardShape = RoundedCornerShape(20.dp)
private val ChipShape = RoundedCornerShape(12.dp)

/**
 * Панель Live-читалки: что читается, прогресс, пауза/продолжение, повтор, следующий абзац, стоп и темп.
 * Если чтение не идёт, но есть сохранённое место — карточка «Продолжить».
 */
@Composable
fun ReaderControlBar(
    reader: ReaderUiState,
    onCommand: (ReaderCommand) -> Unit,
    onResumeSaved: () -> Unit,
    onDiscardSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showActive = reader.isActive || reader.status == ReaderStatus.FINISHED
    val showSaved = !showActive && reader.hasSaved

    AnimatedVisibility(
        visible = showActive || showSaved,
        modifier = modifier,
        enter = expandVertically(animationSpec = tween(240)) + fadeIn(animationSpec = tween(240)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(animationSpec = tween(160))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .background(ColorCard)
                .border(1.dp, ColorHairline, CardShape)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (showActive) {
                ActiveReader(reader, onCommand)
            } else {
                SavedReader(reader, onResumeSaved, onDiscardSaved)
            }
        }
    }
}

@Composable
private fun ActiveReader(reader: ReaderUiState, onCommand: (ReaderCommand) -> Unit) {
    val progress by animateFloatAsState(
        targetValue = reader.progress.coerceIn(0f, 1f),
        animationSpec = tween(400),
        label = "reader_progress"
    )
    val finished = reader.status == ReaderStatus.FINISHED
    val paused = reader.status == ReaderStatus.PAUSED || finished

    Text(
        text = statusLabel(reader),
        color = ColorTextMuted,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = reader.title.ifBlank { "Текст" },
        color = ColorTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = ColorAccent,
        trackColor = ColorTrack,
        strokeCap = StrokeCap.Round
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = if (reader.totalSegments > 0) {
            "${(reader.progress * 100).toInt()}% · фрагмент ${(reader.segment + 1).coerceAtMost(reader.totalSegments)} из ${reader.totalSegments}"
        } else {
            "${(reader.progress * 100).toInt()}%"
        },
        color = ColorTextSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundButton(Icons.Filled.Replay, "Повторить предложение", enabled = !finished) {
            onCommand(ReaderCommand.RepeatSentence)
        }
        RoundButton(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            description = if (finished) "Читать сначала" else if (paused) "Продолжить" else "Пауза",
            primary = true,
            size = 56
        ) {
            onCommand(if (paused) ReaderCommand.Resume else ReaderCommand.Pause)
        }
        RoundButton(Icons.Filled.SkipNext, "Следующий абзац", enabled = !finished) {
            onCommand(ReaderCommand.NextParagraph)
        }
        RoundButton(Icons.Filled.Stop, "Завершить чтение") {
            onCommand(ReaderCommand.Stop)
        }
    }

    if (!finished) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            ReaderPace.entries.forEach { pace ->
                val selected = reader.pace == pace
                Box(
                    modifier = Modifier
                        .clip(ChipShape)
                        .background(if (selected) ColorAccent else ColorButton)
                        .border(1.dp, if (selected) ColorAccent else ColorHairline, ChipShape)
                        .clickable { onCommand(ReaderCommand.SetPace(pace)) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = pace.label,
                        color = if (selected) ColorOnAccent else ColorTextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedReader(reader: ReaderUiState, onResumeSaved: () -> Unit, onDiscardSaved: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "ПРОДОЛЖИТЬ ЧТЕНИЕ",
                color = ColorTextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = reader.savedTitle.orEmpty().ifBlank { "Текст" },
                color = ColorTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Прочитано ${(reader.savedProgress * 100).toInt()}% — продолжу с прерванного предложения",
                color = ColorTextSecondary,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        RoundButton(Icons.Filled.PlayArrow, "Продолжить", primary = true, size = 48, onClick = onResumeSaved)
        Spacer(Modifier.width(8.dp))
        RoundButton(Icons.Filled.Close, "Забыть сохранённое место", size = 36, onClick = onDiscardSaved)
    }
}

private fun statusLabel(reader: ReaderUiState): String = when (reader.status) {
    ReaderStatus.PREPARING -> "ПОДГОТОВКА…"
    ReaderStatus.READING -> "ЧИТАЮ · ${reader.pace.label.uppercase()} ТЕМП"
    ReaderStatus.BUFFERING -> "ЗАГРУЗКА ЗВУКА…"
    ReaderStatus.ANSWERING -> "ОТВЕЧАЮ НА ВОПРОС"
    ReaderStatus.FINISHED -> "ДОЧИТАНО"
    ReaderStatus.ERROR -> "ОШИБКА"
    ReaderStatus.IDLE -> ""
    ReaderStatus.PAUSED -> when (reader.pauseReason) {
        PauseReason.VOICE -> "СЛУШАЮ ВАС…"
        PauseReason.FOCUS -> "ПАУЗА · ЗВУК ВРЕМЕННО ЗАНЯТ"
        PauseReason.FOCUS_PERMANENT -> "ПАУЗА · ЗВУК ЗАНЯТ ДРУГИМ ПРИЛОЖЕНИЕМ"
        PauseReason.ANSWER -> "ПАУЗА · СКАЖИТЕ «ПРОДОЛЖАЙ»"
        else -> "ПАУЗА"
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    size: Int = 44,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (primary) ColorAccent else ColorButton)
            .border(1.dp, if (primary) ColorAccent else ColorHairline, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = when {
                primary -> ColorOnAccent
                enabled -> ColorTextPrimary
                else -> ColorTextMuted
            },
            modifier = Modifier.size((size * 0.45f).dp)
        )
    }
}