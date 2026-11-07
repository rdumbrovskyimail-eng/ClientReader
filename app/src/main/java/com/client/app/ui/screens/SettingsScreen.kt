package com.client.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.reader.ReaderPace
import com.client.app.ui.components.CalmDivider
import com.client.app.ui.components.CalmIconButton
import com.client.app.ui.components.CalmSegmented
import com.client.app.ui.components.CalmTopBar
import com.client.app.ui.components.ExpandableSection
import com.client.app.ui.components.HintText
import com.client.app.ui.components.QuietTextButton
import com.client.app.ui.components.RadioRow
import com.client.app.ui.components.SettingsSection
import com.client.app.ui.components.SliderRow
import com.client.app.ui.components.SwitchRow
import com.client.app.ui.components.TonalPillButton
import com.client.app.ui.components.calmTextFieldColors
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmDurations
import com.client.app.ui.theme.CalmEasing
import com.client.app.ui.theme.ThemeMode
import com.client.app.ui.theme.UiPrefKeys
import com.client.app.ui.theme.readerTextStyle
import com.client.app.viewmodel.AppearanceViewModel
import com.client.app.viewmodel.SettingsViewModel
import kotlin.math.roundToInt

private data class VoiceOption(val name: String, val male: Boolean, val trait: String)

// 30 голосов Live API. Первые — мужские дикторские (средний тембр, ровная подача)
private val Voices = listOf(
    VoiceOption("Charon", true, "информативный, дикторский"),
    VoiceOption("Schedar", true, "ровный"),
    VoiceOption("Algieba", true, "плавный"),
    VoiceOption("Iapetus", true, "чёткий"),
    VoiceOption("Sadaltager", true, "эрудированный"),
    VoiceOption("Rasalgethi", true, "информативный"),
    VoiceOption("Achird", true, "дружелюбный"),
    VoiceOption("Umbriel", true, "непринуждённый"),
    VoiceOption("Orus", true, "твёрдый"),
    VoiceOption("Alnilam", true, "твёрдый"),
    VoiceOption("Zubenelgenubi", true, "расслабленный"),
    VoiceOption("Enceladus", true, "с придыханием"),
    VoiceOption("Algenib", true, "с хрипотцой"),
    VoiceOption("Puck", true, "бодрый"),
    VoiceOption("Sadachbia", true, "живой"),
    VoiceOption("Fenrir", true, "эмоциональный"),
    VoiceOption("Sulafat", false, "тёплый"),
    VoiceOption("Achernar", false, "мягкий"),
    VoiceOption("Vindemiatrix", false, "нежный"),
    VoiceOption("Despina", false, "плавный"),
    VoiceOption("Callirrhoe", false, "непринуждённый"),
    VoiceOption("Gacrux", false, "зрелый"),
    VoiceOption("Erinome", false, "чёткий"),
    VoiceOption("Aoede", false, "лёгкий"),
    VoiceOption("Kore", false, "твёрдый"),
    VoiceOption("Pulcherrima", false, "напористый"),
    VoiceOption("Leda", false, "молодой"),
    VoiceOption("Laomedeia", false, "бодрый"),
    VoiceOption("Zephyr", false, "яркий"),
    VoiceOption("Autonoe", false, "яркий")
)
private val RecommendedVoices = setOf("Charon", "Schedar", "Algieba", "Iapetus")

private val LanguageNames = linkedMapOf(
    "ru-RU" to "Русский",
    "uk-UA" to "Українська",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "de-DE" to "Deutsch",
    "fr-FR" to "Français",
    "es-ES" to "Español",
    "it-IT" to "Italiano",
    "pl-PL" to "Polski",
    "pt-BR" to "Português (BR)",
    "tr-TR" to "Türkçe",
    "nl-NL" to "Nederlands",
    "cs-CZ" to "Čeština",
    "ja-JP" to "日本語",
    "ko-KR" to "한국어",
    "zh-CN" to "中文 (简体)"
)

private val ModelDescriptions = mapOf(
    "gemini-3.8-live" to "Самый быстрый отклик. Рекомендуется для разговора.",
    "gemini-3.8-live-extended-thinking" to "Обдумывает сложные вопросы — отвечает с короткой паузой.",
    "gemini-3.1-flash-live-preview" to "Предыдущее поколение, для сравнения."
)

/**
 * Настройки: простое — сверху, сложное — в разделе «Для опытных» (свёрнут).
 * Обычный язык вместо технических имён параметров; значения применяются сразу и сохраняются.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    appearance: AppearanceViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Текущее оформление уже применено темой (MainActivity читает его из DataStore) — берём оттуда
    val ui = Calm.prefs
    val context = LocalContext.current
    val c = Calm.colors

    var showGeminiKey by remember { mutableStateOf(false) }
    var showForvoKey by remember { mutableStateOf(false) }

    var volumeDraft by remember(settings.volume) { mutableFloatStateOf(settings.volume) }
    var micGainDraft by remember(settings.micGain) { mutableFloatStateOf(settings.micGain) }
    var tempDraft by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var prefixPaddingDraft by remember(settings.prefixPaddingMs) { mutableIntStateOf(settings.prefixPaddingMs) }
    var silenceDurationDraft by remember(settings.silenceDurationMs) { mutableIntStateOf(settings.silenceDurationMs) }
    var historyTurnsDraft by remember(settings.initialHistoryTurns) { mutableIntStateOf(settings.initialHistoryTurns) }
    var textScaleDraft by remember(ui.readerTextScale) { mutableFloatStateOf(ui.readerTextScale) }

    // Исключение из оптимизации батареи: перепроверяется при каждом возврате на экран
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var batteryUnrestricted by remember {
        mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true)
    }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.canvas)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        CalmTopBar(
            title = "Настройки",
            subtitle = "Применяются при следующем подключении",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---------------------------------------------------------------- Оформление
            SettingsSection(title = "Оформление", icon = Icons.Rounded.Palette) {
                SubLabel("Тема")
                val modes = ThemeMode.entries
                CalmSegmented(
                    options = modes.map { it.label },
                    selectedIndex = modes.indexOf(ui.theme).coerceAtLeast(0),
                    onSelect = { appearance.setTheme(modes[it]) }
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    title = "Спокойная анимация",
                    description = "Без пульсации и бегущей волны, переходы почти мгновенные. " +
                        "Системная настройка «Убрать анимацию» учитывается автоматически.",
                    checked = ui.calmMotion,
                    onCheckedChange = { appearance.setCalmMotion(it) }
                )
                CalmDivider()
                SliderRow(
                    title = "Размер текста при чтении",
                    valueText = "${(textScaleDraft * 100).roundToInt()} %",
                    value = textScaleDraft,
                    onValueChange = { textScaleDraft = it },
                    onValueChangeFinished = { appearance.setReaderTextScale(textScaleDraft) },
                    valueRange = UiPrefKeys.TEXT_SCALE_MIN..UiPrefKeys.TEXT_SCALE_MAX
                )
                Text(
                    text = "Так будет выглядеть текст во время чтения.",
                    style = readerTextStyle(textScaleDraft),
                    color = c.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(c.sunken)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }

            // ---------------------------------------------------------------- Подключение
            SettingsSection(title = "Подключение", icon = Icons.Rounded.Key) {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = viewModel::setApiKey,
                    label = { Text("Ключ Gemini API") },
                    supportingText = { Text("Хранится зашифрованным только на этом телефоне") },
                    singleLine = true,
                    visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        CalmIconButton(
                            icon = if (showGeminiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (showGeminiKey) "Скрыть ключ" else "Показать ключ",
                            onClick = { showGeminiKey = !showGeminiKey },
                            container = Color.Transparent,
                            tint = c.inkSoft
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = calmTextFieldColors()
                )
                Spacer(Modifier.height(8.dp))
                SubLabel("Модель для разговора")
                SettingsViewModel.LIVE_MODEL_OPTIONS.forEach { model ->
                    RadioRow(
                        title = model,
                        subtitle = ModelDescriptions[model],
                        selected = settings.liveModel == model,
                        onClick = { viewModel.setLiveModel(model) }
                    )
                }
                HintText("Чтение вслух всегда идёт на gemini-3.8-live: минимальная задержка и тихие голосовые команды. " +
                    "Анализ файлов: ${settings.analyzerModel}.")
            }

            // ---------------------------------------------------------------- Голос
            SettingsSection(title = "Голос", icon = Icons.Rounded.RecordVoiceOver) {
                var showAllVoices by rememberSaveable { mutableStateOf(false) }
                SubLabel("Рекомендуем для чтения")
                Voices.filter { it.name in RecommendedVoices || (!showAllVoices && it.name == settings.voice) }
                    .forEach { v -> VoiceRow(v, settings.voice == v.name) { viewModel.setVoice(v.name) } }

                AnimatedVisibility(
                    visible = showAllVoices,
                    enter = expandVertically(Calm.motion.spec(CalmDurations.MEDIUM, CalmEasing.Enter)) + fadeIn(Calm.motion.fade()),
                    exit = shrinkVertically(Calm.motion.spec(CalmDurations.SHORT, CalmEasing.Exit)) + fadeOut(Calm.motion.fade())
                ) {
                    Column {
                        SubLabel("Мужские голоса")
                        Voices.filter { it.male && it.name !in RecommendedVoices }
                            .forEach { v -> VoiceRow(v, settings.voice == v.name) { viewModel.setVoice(v.name) } }
                        SubLabel("Женские голоса")
                        Voices.filter { !it.male }
                            .forEach { v -> VoiceRow(v, settings.voice == v.name) { viewModel.setVoice(v.name) } }
                    }
                }
                QuietTextButton(
                    text = if (showAllVoices) "Свернуть список" else "Все голоса (${Voices.size})",
                    onClick = { showAllVoices = !showAllVoices }
                )

                CalmDivider()
                SubLabel("Язык речи")
                LanguageChips(
                    // Пустое значение приложение трактует как ru-RU
                    selected = settings.speechLanguage.ifBlank { "ru-RU" },
                    onSelect = { viewModel.setSpeechLanguage(it) }
                )
                HintText("Язык, на котором Gemini говорит и распознаёт вашу речь.")
            }

            // ---------------------------------------------------------------- Чтение вслух
            SettingsSection(title = "Чтение вслух", icon = Icons.Rounded.AutoStories) {
                SubLabel("Темп диктора")
                val paces = ReaderPace.entries
                CalmSegmented(
                    options = listOf("Медленно", "Обычно", "Быстро"),
                    selectedIndex = paces.indexOfFirst { it.wire == settings.readerPace }.coerceAtLeast(0),
                    onSelect = { viewModel.setReaderPace(paces[it].wire) }
                )
                Spacer(Modifier.height(6.dp))
                SwitchRow(
                    title = "Голосовые команды",
                    description = "«Стоп», «продолжай», «повтори», «повтори абзац», «дальше», «медленнее / быстрее», " +
                        "«громче / тише», «закончи чтение». Вопрос по тексту — короткий ответ и пауза.",
                    checked = settings.readerVoiceCommands,
                    onCheckedChange = { viewModel.setReaderVoiceCommands(it) }
                )
                HintText("Место остановки сохраняется: тот же текст продолжится с прерванного предложения даже после перезапуска.")
            }

            // ---------------------------------------------------------------- Звук
            SettingsSection(title = "Звук", icon = Icons.AutoMirrored.Rounded.VolumeUp) {
                SliderRow(
                    title = "Громкость",
                    valueText = "${(volumeDraft * 100).roundToInt()} %",
                    value = volumeDraft,
                    onValueChange = { volumeDraft = it },
                    onValueChangeFinished = { viewModel.setVolume(volumeDraft) },
                    valueRange = 0.3f..1.0f
                )
                SliderRow(
                    title = "Чувствительность микрофона",
                    valueText = "${(micGainDraft * 100).roundToInt()} %",
                    value = micGainDraft,
                    onValueChange = { micGainDraft = it },
                    onValueChangeFinished = { viewModel.setMicGain(micGainDraft) },
                    valueRange = 0.5f..2.0f
                )
                CalmDivider()
                SwitchRow(
                    title = "Качественный звук в Bluetooth-наушниках",
                    description = if (settings.btHiFiMode) {
                        "Стерео 48 кГц, полный тёплый голос. Слушает микрофон телефона — держите его рядом."
                    } else {
                        "Режим гарнитуры: микрофон наушников, звук проще (моно, полоса 8 кГц)."
                    },
                    checked = settings.btHiFiMode,
                    onCheckedChange = { viewModel.setBtHiFiMode(it) }
                )
            }

            // ---------------------------------------------------------------- Работа в фоне
            SettingsSection(title = "Работа в фоне", icon = Icons.Rounded.BatterySaver) {
                if (batteryUnrestricted) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                        Icon(Icons.Rounded.TaskAlt, contentDescription = null, tint = c.accentInk, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Всё в порядке: приложение работает без ограничений, чтение не прервётся при выключенном экране.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.inkSoft
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(c.attentionSoft)
                            .padding(16.dp)
                    ) {
                        Text("Можно сделать надёжнее", style = MaterialTheme.typography.titleSmall, color = c.onAttentionSoft)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "При выключенном экране система может приостанавливать сеть и микрофон — " +
                                "чтение и ответы будут прерываться. Разрешите работу без ограничений.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onAttentionSoft.copy(alpha = 0.88f)
                        )
                        Spacer(Modifier.height(12.dp))
                        TonalPillButton(
                            text = "Разрешить работу в фоне",
                            icon = Icons.Rounded.BatterySaver,
                            onClick = { openBatterySettings(context) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Samsung: также уберите приложение из «Спящих» (Батарея → Ограничения фоновой работы).",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onAttentionSoft.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // ---------------------------------------------------------------- Для опытных
            ExpandableSection(
                title = "Для опытных",
                subtitle = "Модель, распознавание речи, детекция и инструменты",
                icon = Icons.Rounded.Tune
            ) {
                SliderRow(
                    title = "Температура",
                    valueText = "%.2f".format(tempDraft),
                    description = "Для Gemini 3 рекомендуется 1,00",
                    value = tempDraft,
                    onValueChange = { tempDraft = it },
                    onValueChangeFinished = { viewModel.setTemperature(tempDraft) },
                    valueRange = 0.0f..1.5f
                )
                SubLabel("Разрешение изображений")
                val resolutions = listOf("MEDIA_RESOLUTION_HIGH", "MEDIA_RESOLUTION_MEDIUM", "MEDIA_RESOLUTION_LOW")
                CalmSegmented(
                    options = listOf("Высокое", "Среднее", "Низкое"),
                    selectedIndex = resolutions.indexOf(settings.mediaResolution).coerceAtLeast(0),
                    onSelect = { viewModel.setMediaResolution(resolutions[it]) }
                )

                AdvancedGroup("Распознавание вашей речи") {
                    SwitchRow(
                        title = "Расшифровывать вашу речь",
                        checked = settings.inputTxEnabled,
                        onCheckedChange = { viewModel.setInputTxEnabled(it) }
                    )
                    if (settings.inputTxEnabled) {
                        TranscriptionOptions(
                            mode = settings.inputTxMode,
                            onMode = { viewModel.setInputTxMode(it) },
                            languages = settings.inputTxLanguages,
                            onLanguages = viewModel::setInputTxLanguages,
                            languagesLabel = "Языки (BCP-47 через запятую)",
                            vocab = settings.inputTxVocab,
                            onVocab = viewModel::setInputTxVocab,
                            vocabLabel = "Словарь терминов (до 1000 слов)"
                        )
                    }
                }

                AdvancedGroup("Субтитры ответа") {
                    SwitchRow(
                        title = "Расшифровывать речь Gemini",
                        checked = settings.outputTxEnabled,
                        onCheckedChange = { viewModel.setOutputTxEnabled(it) }
                    )
                    if (settings.outputTxEnabled) {
                        TranscriptionOptions(
                            mode = settings.outputTxMode,
                            onMode = { viewModel.setOutputTxMode(it) },
                            languages = settings.outputTxLanguages,
                            onLanguages = viewModel::setOutputTxLanguages,
                            languagesLabel = "Языки субтитров (пусто — авто)",
                            vocab = settings.outputTxVocab,
                            onVocab = viewModel::setOutputTxVocab,
                            vocabLabel = "Словарь вывода (через запятую)"
                        )
                    }
                }

                AdvancedGroup("Детекция речи") {
                    SwitchRow(
                        title = "Детекция на сервере (AAD)",
                        description = "Выключено — ручной режим: начало и конец фразы определяет телефон.",
                        checked = settings.aadEnabled,
                        onCheckedChange = { viewModel.setAadEnabled(it) }
                    )
                    if (settings.aadEnabled) {
                        SubLabel("Чувствительность к началу речи")
                        val starts = listOf("START_SENSITIVITY_HIGH", "START_SENSITIVITY_LOW")
                        CalmSegmented(
                            options = listOf("Высокая", "Низкая"),
                            selectedIndex = starts.indexOf(settings.aadStartSensitivity).coerceAtLeast(0),
                            onSelect = { viewModel.setAadStartSensitivity(starts[it]) }
                        )
                        SubLabel("Чувствительность к окончанию речи")
                        val ends = listOf("END_SENSITIVITY_HIGH", "END_SENSITIVITY_LOW")
                        CalmSegmented(
                            options = listOf("Высокая", "Низкая"),
                            selectedIndex = ends.indexOf(settings.aadEndSensitivity).coerceAtLeast(0),
                            onSelect = { viewModel.setAadEndSensitivity(ends[it]) }
                        )
                        SliderRow(
                            title = "Запас перед началом речи",
                            valueText = "$prefixPaddingDraft мс",
                            value = prefixPaddingDraft.toFloat(),
                            onValueChange = { prefixPaddingDraft = it.roundToInt() },
                            onValueChangeFinished = { viewModel.setPrefixPaddingMs(prefixPaddingDraft) },
                            valueRange = 0f..300f
                        )
                    }
                    // Действует и в ручном режиме: «хвост» локального детектора перед концом фразы
                    SliderRow(
                        title = "Пауза конца фразы",
                        valueText = "$silenceDurationDraft мс",
                        description = "По умолчанию 700 мс. Больше — реже перебивает, меньше — отвечает быстрее.",
                        value = silenceDurationDraft.toFloat(),
                        onValueChange = { silenceDurationDraft = it.roundToInt() },
                        onValueChangeFinished = { viewModel.setSilenceDurationMs(silenceDurationDraft) },
                        valueRange = 300f..1500f
                    )
                    SubLabel("Когда вы заговорили во время ответа")
                    val handlings = listOf("START_OF_ACTIVITY_INTERRUPTS", "NO_INTERRUPTION")
                    CalmSegmented(
                        options = listOf("Gemini замолкает", "Gemini договаривает"),
                        selectedIndex = handlings.indexOf(settings.activityHandling).coerceAtLeast(0),
                        onSelect = { viewModel.setActivityHandling(handlings[it]) }
                    )
                }

                AdvancedGroup("Контекст и сессия") {
                    SwitchRow(
                        title = "Сжатие контекста",
                        description = "Старые реплики сворачиваются, чтобы длинный разговор не упирался в лимит.",
                        checked = settings.compressionEnabled,
                        onCheckedChange = { viewModel.setCompressionEnabled(it) }
                    )
                    SwitchRow(
                        title = "Восстановление сессии",
                        description = "При сбое сети разговор продолжается с того же места.",
                        checked = settings.sessionResumptionEnabled,
                        onCheckedChange = { viewModel.setSessionResumptionEnabled(it) }
                    )
                    SliderRow(
                        title = "История диалога при подключении",
                        valueText = "$historyTurnsDraft ${turnsWord(historyTurnsDraft)}",
                        value = historyTurnsDraft.toFloat(),
                        onValueChange = { historyTurnsDraft = it.roundToInt() },
                        onValueChangeFinished = { viewModel.setInitialHistoryTurns(historyTurnsDraft) },
                        valueRange = 5f..50f
                    )
                }

                AdvancedGroup("Инструменты") {
                    SwitchRow(
                        title = "Поиск Google",
                        description = "Свежие факты из интернета во время ответа.",
                        checked = settings.enableGoogleSearch,
                        onCheckedChange = { viewModel.setEnableGoogleSearch(it) }
                    )
                    SwitchRow(
                        title = "Произношение Forvo",
                        description = "Записи носителей языка для отдельных слов.",
                        checked = settings.enableForvo,
                        onCheckedChange = { viewModel.setEnableForvo(it) }
                    )
                    if (settings.enableForvo) {
                        OutlinedTextField(
                            value = settings.forvoApiKey,
                            onValueChange = viewModel::setForvoApiKey,
                            label = { Text("Ключ Forvo API") },
                            singleLine = true,
                            visualTransformation = if (showForvoKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                CalmIconButton(
                                    icon = if (showForvoKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (showForvoKey) "Скрыть ключ" else "Показать ключ",
                                    onClick = { showForvoKey = !showForvoKey },
                                    container = Color.Transparent,
                                    tint = c.inkSoft
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            colors = calmTextFieldColors()
                        )
                    }
                }

                CalmDivider()
                HintText("Звук: AAudio (MMAP / общий режим) · рассуждения модели — встроенные.")
            }
        }
    }
}

@Composable
private fun SubLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Calm.colors.inkSoft,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
    )
}

@Composable
private fun AdvancedGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    CalmDivider(Modifier.padding(top = 8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = Calm.colors.ink,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
    Column(content = content)
}

@Composable
private fun VoiceRow(voice: VoiceOption, selected: Boolean, onClick: () -> Unit) {
    RadioRow(
        title = voice.name,
        subtitle = (if (voice.male) "Мужской · " else "Женский · ") + voice.trait,
        selected = selected,
        onClick = onClick
    )
}

/** Выбор языка речи «чипами»: печатать коды вручную не нужно. */
@Composable
private fun LanguageChips(selected: String, onSelect: (String) -> Unit) {
    val all = buildList {
        LanguageNames.forEach { (code, name) -> add(code to name) }
        SettingsViewModel.VALID_SPEECH_LANGUAGES.sorted()
            .filter { it !in LanguageNames.keys }
            .forEach { add(it to it) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        all.forEach { (code, label) ->
            CalmChip(label = label, selected = selected == code, onClick = { onSelect(code) })
        }
    }
}

@Composable
private fun CalmChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Calm.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) c.accentSoft else c.raisedStrong)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.5f) else c.hairline, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.onAccentSoft else c.inkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TranscriptionOptions(
    mode: String,
    onMode: (String) -> Unit,
    languages: String,
    onLanguages: (String) -> Unit,
    languagesLabel: String,
    vocab: String,
    onVocab: (String) -> Unit,
    vocabLabel: String
) {
    SubLabel("Режим")
    val modes = listOf("VERBATIM", "SMART")
    CalmSegmented(
        options = listOf("Дословно", "Умно"),
        selectedIndex = modes.indexOf(mode).coerceAtLeast(0),
        onSelect = { onMode(modes[it]) }
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = languages,
        onValueChange = onLanguages,
        label = { Text(languagesLabel) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = calmTextFieldColors()
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = vocab,
        onValueChange = onVocab,
        label = { Text(vocabLabel) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = calmTextFieldColors()
    )
}

private fun turnsWord(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> "реплик"
        mod10 == 1 -> "реплика"
        mod10 in 2..4 -> "реплики"
        else -> "реплик"
    }
}

private fun openBatterySettings(context: Context) {
    runCatching {
        // Прямой системный диалог «Разрешить работу в фоне?» (разрешение REQUEST_IGNORE_BATTERY_OPTIMIZATIONS в манифесте)
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }.recoverCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.onFailure {
        Toast.makeText(context, "Настройки → Приложения → Батарея → Без ограничений", Toast.LENGTH_LONG).show()
    }
}