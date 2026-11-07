package com.client.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * «Тихий свет» — полутоновая палитра приложения.
 *
 * Принципы (из исследований цвета и рекомендаций по доступности):
 * - Низкая насыщенность и средняя яркость: насыщенность сильнее всего повышает возбуждение,
 *   серо-зелёные и серо-голубые полутона — наименее тревожные (Wilms & Oberfeld, 2018).
 * - Нет чистого чёрного и чистого белого: мягкий «графит» и «бумага» не режут глаз,
 *   тёмная тема — глубокий сланец, а не #000 (рекомендация Material для тёмной темы).
 * - Нет насыщенного красного: проблема обозначается приглушённой «глиной» и всегда
 *   дублируется значком и словами — цвет никогда не единственный носитель смысла.
 * - Контраст текста проверен по WCAG 2.2: основной ≥ 11:1, вторичный ≥ 6:1,
 *   подписи ≥ 4,5:1, значки и рамки элементов ≥ 3:1 — на всех поверхностях обеих тем.
 */
@Immutable
data class CalmColors(
    val isDark: Boolean,
    // Поверхности: холст → карточка → элемент на карточке; «утопленная» — поля ввода
    val canvas: Color,
    val raised: Color,
    val raisedStrong: Color,
    val sunken: Color,
    // Линии: декоративная волосяная и функциональная рамка (≥ 3:1)
    val hairline: Color,
    val outline: Color,
    // Текст: основной, вторичный, подписи
    val ink: Color,
    val inkSoft: Color,
    val inkFaint: Color,
    // Акцент «эвкалипт»: главное действие, выбранное состояние, активные значки
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val accentInk: Color,
    // Голоса в визуализации: вы — шалфей, Gemini — сумеречно-голубой
    val voiceUser: Color,
    val voiceAi: Color,
    // «Обратите внимание» — тёплый песок (вместо жёлтого/оранжевого)
    val attention: Color,
    val attentionSoft: Color,
    val onAttentionSoft: Color,
    // «Что-то не так» — приглушённая глина (вместо красного)
    val problem: Color,
    val problemSoft: Color,
    val onProblemSoft: Color,
    // Затемнение под шторками
    val scrim: Color,
    // Инверсные поверхности (всплывающие подсказки)
    val inverseSurface: Color,
    val inverseInk: Color
)

/** Дневная тема: тёплая «каменная бумага» и мягкий графит. */
val DayColors = CalmColors(
    isDark = false,
    canvas = Color(0xFFF1F0EB),
    raised = Color(0xFFF8F7F3),
    raisedStrong = Color(0xFFE8E7E1),
    sunken = Color(0xFFEAE9E3),
    hairline = Color(0xFFDCDAD2),
    outline = Color(0xFF7B817E),
    ink = Color(0xFF262B2A),
    inkSoft = Color(0xFF4F5654),
    inkFaint = Color(0xFF5D6462),
    accent = Color(0xFF466F68),
    onAccent = Color(0xFFF6F8F6),
    accentSoft = Color(0xFFD5E4DF),
    onAccentSoft = Color(0xFF1D3833),
    accentInk = Color(0xFF3E6760),
    voiceUser = Color(0xFF5B8A80),
    voiceAi = Color(0xFF5F7898),
    attention = Color(0xFF8C6D33),
    attentionSoft = Color(0xFFF0E6D2),
    onAttentionSoft = Color(0xFF463613),
    problem = Color(0xFF9A5A4C),
    problemSoft = Color(0xFFF2E2DC),
    onProblemSoft = Color(0xFF4E281F),
    scrim = Color(0x66262B2A),
    inverseSurface = Color(0xFF2E3332),
    inverseInk = Color(0xFFEEF0EE)
)

/** Сумеречная тема: глубокий сланец (не чёрный) и мягкий «лунный» текст. */
val DuskColors = CalmColors(
    isDark = true,
    canvas = Color(0xFF151A1A),
    raised = Color(0xFF1C2222),
    raisedStrong = Color(0xFF252C2C),
    sunken = Color(0xFF111515),
    hairline = Color(0xFF2B3333),
    outline = Color(0xFF6E7876),
    ink = Color(0xFFE1E5E2),
    inkSoft = Color(0xFFB3BAB7),
    inkFaint = Color(0xFF959D9A),
    accent = Color(0xFF8FBAB0),
    onAccent = Color(0xFF10302A),
    accentSoft = Color(0xFF2A4540),
    onAccentSoft = Color(0xFFD2E8E1),
    accentInk = Color(0xFF9CC5BB),
    voiceUser = Color(0xFF88B6AB),
    voiceAi = Color(0xFF9DB2D0),
    attention = Color(0xFFD0B47E),
    attentionSoft = Color(0xFF3A3224),
    onAttentionSoft = Color(0xFFF0E2C2),
    problem = Color(0xFFD99E8F),
    problemSoft = Color(0xFF3C2A26),
    onProblemSoft = Color(0xFFF4D9D0),
    scrim = Color(0x99090C0C),
    inverseSurface = Color(0xFFDDE2DF),
    inverseInk = Color(0xFF2A302F)
)

/** Те же токены в роли схемы Material 3 — чтобы стандартные компоненты выглядели согласованно. */
fun CalmColors.toColorScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        inversePrimary = DayColors.accent,
        secondary = voiceAi,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = onAccentSoft,
        tertiary = attention,
        onTertiary = attentionSoft,
        tertiaryContainer = attentionSoft,
        onTertiaryContainer = onAttentionSoft,
        background = canvas,
        onBackground = ink,
        surface = canvas,
        onSurface = ink,
        surfaceVariant = raisedStrong,
        onSurfaceVariant = inkSoft,
        surfaceTint = accent,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseInk,
        error = problem,
        onError = problemSoft,
        errorContainer = problemSoft,
        onErrorContainer = onProblemSoft,
        outline = outline,
        outlineVariant = hairline,
        scrim = scrim,
        surfaceBright = raisedStrong,
        surfaceDim = sunken,
        surfaceContainerLowest = sunken,
        surfaceContainerLow = raised,
        surfaceContainer = raised,
        surfaceContainerHigh = raisedStrong,
        surfaceContainerHighest = raisedStrong
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        inversePrimary = DuskColors.accent,
        secondary = voiceAi,
        onSecondary = onAccent,
        secondaryContainer = accentSoft,
        onSecondaryContainer = onAccentSoft,
        tertiary = attention,
        onTertiary = Color(0xFFFFFDF7),
        tertiaryContainer = attentionSoft,
        onTertiaryContainer = onAttentionSoft,
        background = canvas,
        onBackground = ink,
        surface = canvas,
        onSurface = ink,
        surfaceVariant = raisedStrong,
        onSurfaceVariant = inkSoft,
        surfaceTint = accent,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseInk,
        error = problem,
        onError = onAccent,
        errorContainer = problemSoft,
        onErrorContainer = onProblemSoft,
        outline = outline,
        outlineVariant = hairline,
        scrim = scrim,
        surfaceBright = raised,
        surfaceDim = raisedStrong,
        surfaceContainerLowest = raised,
        surfaceContainerLow = raised,
        surfaceContainer = canvas,
        surfaceContainerHigh = raisedStrong,
        surfaceContainerHighest = raisedStrong
    )
}