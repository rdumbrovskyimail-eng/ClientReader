package com.client.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmColors
import com.client.app.ui.theme.CalmDurations
import com.client.app.ui.theme.CalmEasing
import com.client.app.ui.theme.CalmMono

/*
 * Спокойные уведомления вместо красных баннеров ошибок.
 *
 * Правила (NN/g, рекомендации по сообщениям об ошибках):
 * - человеческий язык без кодов: что произошло и что делать дальше;
 * - без обвинений и тревожных слов («ошибка», «сбой», «недопустимо»);
 * - вес оформления соответствует важности: мягкая плашка, а не модальное окно;
 * - смысл передают значок и текст, цвет — лишь дополнительный сигнал;
 * - технические подробности не прячутся, но открываются по желанию.
 */

enum class NoticeTone { INFO, ATTENTION, PROBLEM }

enum class NoticeIcon { INFO, KEY, NETWORK, SERVER, MIC, AUDIO, TEXT, FILE, BATTERY, WAIT }

enum class NoticeAction { NONE, RETRY, OPEN_SETTINGS, GRANT_MIC, OPEN_APP_SETTINGS }

@Immutable
data class CalmNoticeModel(
    val tone: NoticeTone,
    val icon: NoticeIcon,
    val title: String,
    val body: String,
    val action: NoticeAction = NoticeAction.NONE,
    val actionLabel: String? = null,
    /** Исходный технический текст — по кнопке «Подробнее». */
    val details: String? = null,
    /** Сообщения-подсказки исчезают сами; проблемы — только по вашему действию. */
    val autoHideMs: Long? = null
)

/**
 * Переводит технический текст ошибки сессии в спокойное сообщение.
 * Неизвестные тексты не теряются: показываются в «Подробнее».
 */
fun humanizeSessionMessage(raw: String): CalmNoticeModel {
    val text = raw.trim()
    fun starts(prefix: String) = text.startsWith(prefix, ignoreCase = true)

    return when {
        starts("Укажите Gemini API Key") -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.KEY,
            title = "Нужен ключ Gemini API",
            body = "Добавьте ключ в настройках — это займёт минуту. Он хранится зашифрованным только на этом телефоне.",
            action = NoticeAction.OPEN_SETTINGS, actionLabel = "Открыть настройки"
        )
        text.contains("API Key повреждён", ignoreCase = true) -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.KEY,
            title = "Ключ нужно ввести ещё раз",
            body = "Сохранённый ключ не удалось прочитать. Введите его заново в настройках — всё остальное сохранено.",
            action = NoticeAction.OPEN_SETTINGS, actionLabel = "Открыть настройки"
        )
        starts("Не удалось подключиться") -> CalmNoticeModel(
            tone = NoticeTone.PROBLEM, icon = NoticeIcon.NETWORK,
            title = "Не получилось подключиться",
            body = "Проверьте интернет и попробуйте ещё раз. Настройки и текст для чтения на месте.",
            action = NoticeAction.RETRY, actionLabel = "Попробовать снова",
            details = text
        )
        starts("Нет соединения с сервером") -> CalmNoticeModel(
            tone = NoticeTone.PROBLEM, icon = NoticeIcon.NETWORK,
            title = "Нет связи с сервером",
            body = "Как только сеть станет стабильной, можно продолжить.",
            action = NoticeAction.RETRY, actionLabel = "Попробовать снова",
            details = text
        )
        starts("Соединение потеряно") -> CalmNoticeModel(
            tone = NoticeTone.PROBLEM, icon = NoticeIcon.NETWORK,
            title = "Связь прервалась",
            body = "Автоматические попытки закончились. Подключитесь снова, когда сеть будет стабильной — место в тексте сохранено.",
            action = NoticeAction.RETRY, actionLabel = "Подключиться снова",
            details = text
        )
        starts("Сервер закрыл сессию") -> CalmNoticeModel(
            tone = NoticeTone.PROBLEM, icon = NoticeIcon.SERVER,
            title = "Сервер завершил сеанс",
            body = "Так бывает при долгой работе. Можно сразу начать заново — место в тексте сохранено.",
            action = NoticeAction.RETRY, actionLabel = "Начать заново",
            details = text
        )
        starts("Подключение отменено") -> CalmNoticeModel(
            tone = NoticeTone.INFO, icon = NoticeIcon.INFO,
            title = "Подключение отменено",
            body = "Начните снова, когда будете готовы.",
            autoHideMs = 5_000
        )
        starts("Сессия ещё не готова") -> CalmNoticeModel(
            tone = NoticeTone.INFO, icon = NoticeIcon.WAIT,
            title = "Ещё подключаюсь",
            body = "Микрофон включится сам, как только связь будет готова.",
            autoHideMs = 5_000
        )
        starts("Сессия остановлена") -> CalmNoticeModel(
            tone = NoticeTone.INFO, icon = NoticeIcon.INFO,
            title = "Сеанс остановлен",
            body = "Его можно запустить снова в любой момент.",
            action = NoticeAction.RETRY, actionLabel = "Начать"
        )
        starts("Звук занят другим приложением") -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.AUDIO,
            title = "Звук у другого приложения",
            body = "Когда будете готовы, нажмите «Продолжить» в панели чтения или Play на гарнитуре."
        )
        starts("Звук сейчас занят") || starts("Аудио приостановлено") -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.AUDIO,
            title = "Звук ненадолго занят",
            body = "Идёт звонок или звук у другого приложения. Я подожду и продолжу сам."
        )
        starts("Микрофон недоступен") || text.contains("microphone foreground-service", ignoreCase = true) -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.MIC,
            title = "Микрофон недоступен",
            body = "Проверьте, что приложению разрешён доступ к микрофону.",
            action = NoticeAction.OPEN_APP_SETTINGS, actionLabel = "Разрешения",
            details = text
        )
        starts("Сбой инициализации аудиодрайвера") -> CalmNoticeModel(
            tone = NoticeTone.PROBLEM, icon = NoticeIcon.AUDIO,
            title = "Звук не запустился",
            body = "Попробуйте ещё раз. Если повторится — закройте и снова откройте приложение.",
            action = NoticeAction.RETRY, actionLabel = "Попробовать снова",
            details = text
        )
        starts("В тексте нет предложений") -> CalmNoticeModel(
            tone = NoticeTone.INFO, icon = NoticeIcon.TEXT,
            title = "Нечего прочитать",
            body = "Вставьте текст из обычных предложений и нажмите «Читать».",
            autoHideMs = 7_000
        )
        starts("Сохранённый текст не найден") -> CalmNoticeModel(
            tone = NoticeTone.INFO, icon = NoticeIcon.TEXT,
            title = "Сохранённый текст не найден",
            body = "Вставьте тот же текст снова — я продолжу с места остановки.",
            autoHideMs = 7_000
        )
        starts("Ошибка анализа") || starts("Сбой обработки файлов") -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.FILE,
            title = "Файл не удалось разобрать",
            body = "Попробуйте другой файл или отправьте текст.",
            details = text
        )
        starts("Ссылка Forvo") -> CalmNoticeModel(
            tone = NoticeTone.INFO, icon = NoticeIcon.INFO,
            title = "Произношение недоступно",
            body = "Ссылка Forvo устарела. Попробуйте позже.",
            autoHideMs = 6_000
        )
        text.contains("foreground", ignoreCase = true) -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.BATTERY,
            title = "Фоновый режим не запустился",
            body = "Android не разрешил работу в фоне. Попробуйте ещё раз, не сворачивая приложение.",
            action = NoticeAction.RETRY, actionLabel = "Попробовать снова",
            details = text
        )
        else -> CalmNoticeModel(
            tone = NoticeTone.ATTENTION, icon = NoticeIcon.INFO,
            title = "Что-то пошло не так",
            body = "Можно попробовать ещё раз. Подробности — ниже.",
            action = NoticeAction.RETRY, actionLabel = "Попробовать снова",
            details = text
        )
    }
}

/** Сообщение о недоступном микрофоне (после отказа в разрешении). */
fun micPermissionNotice(permanentlyDenied: Boolean): CalmNoticeModel = CalmNoticeModel(
    tone = NoticeTone.ATTENTION,
    icon = NoticeIcon.MIC,
    title = "Нужен доступ к микрофону",
    body = if (permanentlyDenied) {
        "Без него я не услышу вас. Разрешение выдаётся в настройках телефона: Разрешения → Микрофон."
    } else {
        "Без него я не услышу вас. Чтение вслух работает и без микрофона — только без голосовых команд."
    },
    action = if (permanentlyDenied) NoticeAction.OPEN_APP_SETTINGS else NoticeAction.GRANT_MIC,
    actionLabel = if (permanentlyDenied) "Открыть настройки" else "Разрешить"
)

private fun NoticeIcon.vector(): ImageVector = when (this) {
    NoticeIcon.INFO -> Icons.Rounded.Info
    NoticeIcon.KEY -> Icons.Rounded.Key
    NoticeIcon.NETWORK -> Icons.Rounded.WifiOff
    NoticeIcon.SERVER -> Icons.Rounded.CloudOff
    NoticeIcon.MIC -> Icons.Rounded.MicOff
    NoticeIcon.AUDIO -> Icons.AutoMirrored.Rounded.VolumeOff
    NoticeIcon.TEXT -> Icons.Rounded.AutoStories
    NoticeIcon.FILE -> Icons.Rounded.Description
    NoticeIcon.BATTERY -> Icons.Rounded.BatterySaver
    NoticeIcon.WAIT -> Icons.Rounded.Schedule
}

private data class ToneColors(val container: Color, val icon: Color, val onContainer: Color)

private fun CalmColors.forTone(tone: NoticeTone): ToneColors = when (tone) {
    NoticeTone.INFO -> ToneColors(accentSoft, accentInk, onAccentSoft)
    NoticeTone.ATTENTION -> ToneColors(attentionSoft, attention, onAttentionSoft)
    NoticeTone.PROBLEM -> ToneColors(problemSoft, problem, onProblemSoft)
}

/**
 * Карточка уведомления. Появляется мягко, объявляется TalkBack вежливо (без перебивания),
 * закрывается крестиком 48 dp.
 */
@Composable
fun CalmNotice(
    model: CalmNoticeModel,
    onAction: (NoticeAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Calm.colors
    val tone = c.forTone(model.tone)
    var showDetails by remember(model) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(tone.container)
            .semantics(mergeDescendants = false) { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(c.raised.copy(alpha = if (c.isDark) 0.10f else 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(model.icon.vector(), contentDescription = null, tint = tone.icon, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 10.dp)
            ) {
                Text(text = model.title, style = MaterialTheme.typography.titleSmall, color = tone.onContainer)
                Spacer(Modifier.height(2.dp))
                Text(text = model.body, style = MaterialTheme.typography.bodySmall, color = tone.onContainer.copy(alpha = 0.86f))
            }
            CalmIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "Скрыть сообщение",
                onClick = onDismiss,
                container = Color.Transparent,
                tint = tone.onContainer.copy(alpha = 0.8f),
                iconSize = 20.dp
            )
        }

        val hasAction = model.action != NoticeAction.NONE && model.actionLabel != null
        if (hasAction || model.details != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (model.details != null) {
                    QuietTextButton(
                        text = if (showDetails) "Скрыть подробности" else "Подробнее",
                        onClick = { showDetails = !showDetails },
                        color = tone.onContainer.copy(alpha = 0.75f)
                    )
                }
                if (hasAction) {
                    QuietTextButton(
                        text = model.actionLabel!!,
                        onClick = { onAction(model.action) },
                        color = tone.onContainer
                    )
                }
            }
        }

        val motion = Calm.motion
        AnimatedVisibility(
            visible = showDetails && model.details != null,
            enter = if (motion.reduced) fadeIn(motion.fade()) else expandVertically(motion.spec(CalmDurations.MEDIUM, CalmEasing.Enter)) + fadeIn(motion.fade()),
            exit = if (motion.reduced) fadeOut(motion.fade()) else shrinkVertically(motion.spec(CalmDurations.SHORT, CalmEasing.Exit)) + fadeOut(motion.fade())
        ) {
            SelectionContainer {
                Text(
                    text = model.details.orEmpty(),
                    style = CalmMono,
                    color = tone.onContainer.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(start = 48.dp, end = 12.dp, bottom = 6.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}