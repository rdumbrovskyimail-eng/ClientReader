package com.client.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.reader.PauseReason
import com.client.app.reader.ReaderStatus
import com.client.app.session.LinkState
import com.client.app.session.SessionProfile
import com.client.app.session.SessionState
import com.client.app.ui.components.CalmNotice
import com.client.app.ui.components.CalmNoticeModel
import com.client.app.ui.components.NoticeAction
import com.client.app.ui.components.PromptEditorSheet
import com.client.app.ui.components.PromptPill
import com.client.app.ui.components.QuietTextButton
import com.client.app.ui.components.ReaderControlBar
import com.client.app.ui.components.SessionControlPill
import com.client.app.ui.components.TonalPillButton
import com.client.app.ui.components.TopBarActions
import com.client.app.ui.components.VoiceHalftone
import com.client.app.ui.components.humanizeSessionMessage
import com.client.app.ui.components.micPermissionNotice
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmDurations
import com.client.app.ui.theme.CalmEasing
import com.client.app.viewmodel.ClientViewModel
import kotlinx.coroutines.delay

/**
 * Главный экран — «тихий» и предсказуемый.
 *
 * Сверху вниз:
 * - роль ассистента и две спокойные кнопки (журнал, настройки);
 * - уведомление человеческим языком, если что-то требует внимания (вместо красных ошибок);
 * - состояние крупно и понятными словами, с подсказкой «что дальше»;
 * - полутоновое поле голоса;
 * - панель чтения с «телесуфлёром» (во время чтения) или карточка «Можно продолжить»;
 * - одна главная кнопка внизу, в зоне большого пальца.
 */
@Composable
fun ClientScreen(
    onNavigateSettings: () -> Unit,
    onNavigateLogs: () -> Unit,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errorCount by viewModel.errorCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Calm.colors
    val motion = Calm.motion

    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editorReadMode by rememberSaveable { mutableStateOf(true) }
    // Текст для чтения живёт, пока открыт экран (не в rememberSaveable: большой текст не влезет в Bundle)
    var readDraft by remember { mutableStateOf("") }
    // Действие, ожидающее выдачи разрешений (чтение вслух / продолжение сохранённого)
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Сообщение о микрофоне (после отказа в разрешении)
    var micNotice by remember { mutableStateOf<CalmNoticeModel?>(null) }

    val rawError = state.error
    val sessionNotice = remember(rawError) { rawError?.let { humanizeSessionMessage(it) } }
    val notice = micNotice ?: sessionNotice
    // Последнее сообщение нужно, чтобы карточка не опустела во время плавного скрытия
    var lastNotice by remember { mutableStateOf<CalmNoticeModel?>(null) }
    LaunchedEffect(notice) { if (notice != null) lastNotice = notice }

    // Подсказки (не проблемы) исчезают сами
    LaunchedEffect(rawError) {
        val hideAfter = sessionNotice?.autoHideMs ?: return@LaunchedEffect
        delay(hideAfter)
        if (viewModel.state.value.error == rawError) viewModel.clearError()
    }

    fun audioGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Разрешение могли выдать в системных настройках — убираем сообщение при возвращении
    LifecycleResumeEffect(Unit) {
        if (micNotice != null && audioGranted()) micNotice = null
        onPauseOrDispose { }
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val granted = audioGranted()
        val permanentlyDenied = !granted &&
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false

        val pending = pendingAction
        pendingAction = null
        if (pending != null) {
            // Чтение работает и без микрофона — тогда только без голосовых команд
            pending()
            micNotice = if (granted) null else micPermissionNotice(permanentlyDenied)
        } else if (granted) {
            micNotice = null
            if (viewModel.state.value.link == LinkState.IDLE) viewModel.toggleConnection()
        } else {
            micNotice = micPermissionNotice(permanentlyDenied)
        }
    }

    fun missingPermissions(): List<String> {
        val missing = mutableListOf<String>()
        if (!audioGranted()) missing.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return missing
    }

    fun handleSessionClick() {
        // Остановка сессии разрешений не требует
        if (viewModel.state.value.link != LinkState.IDLE) {
            viewModel.toggleConnection()
            return
        }
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            micNotice = null
            viewModel.toggleConnection()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    fun runWithPermissions(action: () -> Unit) {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    fun onNoticeAction(action: NoticeAction) {
        when (action) {
            NoticeAction.RETRY -> {
                viewModel.clearError()
                if (viewModel.state.value.link == LinkState.IDLE) handleSessionClick()
            }
            NoticeAction.OPEN_SETTINGS -> {
                viewModel.clearError()
                onNavigateSettings()
            }
            NoticeAction.GRANT_MIC -> {
                micNotice = null
                permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            NoticeAction.OPEN_APP_SETTINGS -> {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            NoticeAction.NONE -> Unit
        }
    }

    fun dismissNotice() {
        if (micNotice != null) micNotice = null else viewModel.clearError()
    }

    fun openEditor(readMode: Boolean) {
        editorReadMode = readMode
        editorOpen = true
    }

    val reader = state.reader
    val readerShown = reader.isActive || reader.status == ReaderStatus.FINISHED

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .drawBehind {
                // Едва заметный «свет сверху»: глубина без теней и контрастных пятен
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(c.raised.copy(alpha = if (c.isDark) 0.55f else 0.9f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.45f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            // 1. Верхняя панель
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PromptPill(
                    prompt = state.activePrompt,
                    onClick = { openEditor(readMode = false) },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                TopBarActions(
                    onOpenSettings = onNavigateSettings,
                    onOpenLogs = onNavigateLogs,
                    errorCount = errorCount
                )
            }

            // 2. Спокойное уведомление
            AnimatedVisibility(
                visible = notice != null,
                enter = if (motion.reduced) fadeIn(motion.fade()) else expandVertically(motion.spec(CalmDurations.MEDIUM, CalmEasing.Enter)) + fadeIn(motion.fade()),
                exit = if (motion.reduced) fadeOut(motion.fade()) else shrinkVertically(motion.spec(CalmDurations.SHORT, CalmEasing.Exit)) + fadeOut(motion.fade())
            ) {
                val shown = notice ?: lastNotice
                if (shown != null) {
                    CalmNotice(
                        model = shown,
                        onAction = { onNoticeAction(it) },
                        onDismiss = { dismissNotice() },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
                    )
                }
            }

            // 3. Середина: состояние, голос, чтение (прокручивается на небольших экранах)
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = maxHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SessionStatusHero(
                        state = state,
                        compact = readerShown,
                        modifier = Modifier.widthIn(max = 520.dp)
                    )
                    Spacer(Modifier.height(if (readerShown) 16.dp else 28.dp))
                    VoiceHalftone(
                        nativeEngine = viewModel.nativeAudioEngine,
                        state = state,
                        compact = readerShown,
                        modifier = Modifier.widthIn(max = 560.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    ReaderControlBar(
                        reader = reader,
                        onCommand = { viewModel.readerCommand(it) },
                        onResumeSaved = { runWithPermissions { viewModel.resumeSavedReading() } },
                        onDiscardSaved = { viewModel.discardSavedReading() },
                        modifier = Modifier.widthIn(max = 560.dp)
                    )
                    if (!readerShown) {
                        Spacer(Modifier.height(if (reader.hasSaved) 4.dp else 8.dp))
                        if (reader.hasSaved) {
                            QuietTextButton(
                                text = "Прочитать другой текст",
                                icon = Icons.Rounded.AutoStories,
                                onClick = { openEditor(readMode = true) }
                            )
                        } else {
                            TonalPillButton(
                                text = "Прочитать текст вслух",
                                icon = Icons.Rounded.AutoStories,
                                onClick = { openEditor(readMode = true) }
                            )
                        }
                    }
                }
            }

            // 4. Главное действие — внизу, в зоне большого пальца
            SessionControlPill(
                linkState = state.link,
                onClick = { handleSessionClick() },
                quiet = readerShown,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
            )
        }

        // 5. Шторка редактора поверх экрана
        PromptEditorSheet(
            visible = editorOpen,
            currentPrompt = state.activePrompt,
            onApply = { viewModel.applyPrompt(it) },
            onDismiss = { editorOpen = false },
            readText = readDraft,
            onReadTextChange = { readDraft = it },
            onRead = { text -> runWithPermissions { viewModel.startReading(text) } },
            startInReadMode = editorReadMode
        )
    }
}

/** Крупное состояние и подсказка «что дальше». TalkBack озвучивает смену вежливо. */
@Composable
private fun SessionStatusHero(
    state: SessionState,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val c = Calm.colors
    val motion = Calm.motion
    val status = statusCopy(state)

    Crossfade(
        targetState = status,
        modifier = modifier,
        animationSpec = motion.fade(CalmDurations.MEDIUM),
        label = "session_status"
    ) { (title, hint) ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
                color = c.ink,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = c.inkSoft,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Человеческие формулировки состояний: что происходит и что можно сделать. */
private fun statusCopy(state: SessionState): Pair<String, String> {
    val reader = state.reader
    return when {
        state.link == LinkState.RECONNECTING && reader.isActive ->
            "Связь восстанавливается" to "Чтение продолжится с того же предложения"
        reader.isActive -> when (reader.status) {
            ReaderStatus.PREPARING -> "Готовлю чтение" to "Подключаю диктора — это пара секунд"
            ReaderStatus.BUFFERING -> "Читаю вслух" to "Сеть медленная — звук догрузится сам"
            ReaderStatus.ANSWERING -> "Отвечаю на вопрос" to "Потом скажите «продолжай» — вернусь к тексту"
            ReaderStatus.PAUSED -> when (reader.pauseReason) {
                PauseReason.VOICE -> "Слушаю вас" to "Чтение на паузе, пока вы говорите"
                PauseReason.FOCUS -> "Пауза" to "Звук ненадолго занят — продолжу сам"
                PauseReason.FOCUS_PERMANENT -> "Пауза" to "Звук у другого приложения — продолжу, когда скажете"
                PauseReason.ANSWER -> "Пауза" to "Скажите «продолжай» или нажмите ▶"
                else -> "Пауза" to "Продолжим, когда будете готовы — голосом, кнопкой или гарнитурой"
            }
            else -> "Читаю вслух" to "Можно сказать «стоп», «повтори» или спросить о тексте"
        }
        state.profile == SessionProfile.READER && reader.status == ReaderStatus.FINISHED ->
            "Текст прочитан" to "Можно прослушать ещё раз или вставить новый"
        state.link == LinkState.CONNECTING ->
            "Подключаюсь" to "Обычно это занимает пару секунд"
        state.link == LinkState.RECONNECTING ->
            "Связь восстанавливается" to "Разговор сохранён — продолжим с того же места"
        state.link == LinkState.LIVE && state.isAiSpeaking ->
            "Gemini отвечает" to "Можно перебить голосом в любой момент"
        state.link == LinkState.LIVE && state.isMicActive ->
            "Слушаю вас" to "Говорите спокойно — отвечу после паузы"
        state.link == LinkState.LIVE ->
            "На связи" to "Включаю микрофон…"
        else ->
            "Готов к разговору" to "Нажмите «Начать разговор» или дайте текст — прочитаю вслух"
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}