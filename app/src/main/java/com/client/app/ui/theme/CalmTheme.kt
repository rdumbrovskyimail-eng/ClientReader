package com.client.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.compose.LifecycleResumeEffect

// =====================================================================================
// Настройки оформления (хранятся в том же DataStore, что и остальные параметры)
// =====================================================================================

enum class ThemeMode(val wire: String, val label: String) {
    SYSTEM("system", "Как в системе"),
    LIGHT("light", "Светлая"),
    DARK("dark", "Тёмная");

    companion object {
        fun from(value: String?): ThemeMode = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

@Immutable
data class UiPrefs(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Спокойная анимация: без пульсации и бегущих волн, переходы почти мгновенные. */
    val calmMotion: Boolean = false,
    /** Масштаб текста «телесуфлёра» читалки. */
    val readerTextScale: Float = 1f
)

object UiPrefKeys {
    val THEME = stringPreferencesKey("ui_theme_mode")
    val CALM_MOTION = booleanPreferencesKey("ui_calm_motion")
    val READER_TEXT_SCALE = floatPreferencesKey("ui_reader_text_scale")

    const val TEXT_SCALE_MIN = 0.85f
    const val TEXT_SCALE_MAX = 1.5f
}

fun Preferences.toUiPrefs(): UiPrefs = UiPrefs(
    theme = ThemeMode.from(this[UiPrefKeys.THEME]),
    calmMotion = this[UiPrefKeys.CALM_MOTION] ?: false,
    readerTextScale = (this[UiPrefKeys.READER_TEXT_SCALE] ?: 1f)
        .coerceIn(UiPrefKeys.TEXT_SCALE_MIN, UiPrefKeys.TEXT_SCALE_MAX)
)

// =====================================================================================
// Движение: мягкие кривые Material 3, без «пружинок» с перелётом
// =====================================================================================

object CalmEasing {
    /** Стандартная кривая M3: спокойное начало и долгое мягкое торможение. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Появление (emphasized decelerate). */
    val Enter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Уход (emphasized accelerate). */
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Дыхание: синусоидальная, без рывков на разворотах. */
    val Breath: Easing = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)
}

object CalmDurations {
    const val QUICK = 150
    const val SHORT = 220
    const val MEDIUM = 320
    const val LONG = 450
    /** Полный цикл «вдох–выдох» индикатора ожидания: 0,3 Гц, далеко от порога мерцания (3 Гц). */
    const val BREATH = 3400
}

@Immutable
data class CalmMotion(val reduced: Boolean) {
    /** Переход заданной длительности; при «спокойной анимации» — мгновенно. */
    fun <T> spec(durationMs: Int, easing: Easing = CalmEasing.Standard): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMillis = durationMs, easing = easing)

    /** Короткое растворение остаётся даже при уменьшенном движении: оно не создаёт ощущения движения. */
    fun <T> fade(durationMs: Int = CalmDurations.SHORT): FiniteAnimationSpec<T> =
        tween(durationMillis = if (reduced) CalmDurations.QUICK else durationMs, easing = CalmEasing.Standard)
}

// =====================================================================================
// Типографика: системный шрифт, без КАПСА и моноширинного «терминала» в основном интерфейсе
// =====================================================================================

val CalmTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
)

/**
 * Текст читалки: межстрочный 1,55 (WCAG 1.4.8 — не меньше 1,5), без выключки по ширине.
 * Размер настраивается пользователем.
 */
fun readerTextStyle(scale: Float): TextStyle = TextStyle(
    fontSize = (20f * scale).sp,
    lineHeight = (31f * scale).sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.1.sp
)

/** Технический текст (журнал) — моноширинный, но не мельче 12 sp. */
val CalmMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

val CalmShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

// =====================================================================================
// Тема
// =====================================================================================

val LocalCalmColors = staticCompositionLocalOf { DayColors }
val LocalCalmMotion = staticCompositionLocalOf { CalmMotion(reduced = false) }
val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

/** Короткий доступ к токенам: `Calm.colors.ink`, `Calm.motion.reduced`. */
object Calm {
    val colors: CalmColors
        @Composable @ReadOnlyComposable get() = LocalCalmColors.current
    val motion: CalmMotion
        @Composable @ReadOnlyComposable get() = LocalCalmMotion.current
    val prefs: UiPrefs
        @Composable @ReadOnlyComposable get() = LocalUiPrefs.current
}

/**
 * Системная настройка «Убрать анимацию» (Специальные возможности): масштаб длительности анимаций = 0.
 * Перечитывается при каждом возвращении в приложение.
 */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    fun read(): Boolean = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    var reduced by remember { mutableStateOf(read()) }
    LifecycleResumeEffect(Unit) {
        reduced = read()
        onPauseOrDispose { }
    }
    return reduced
}

@Composable
fun isCalmDark(prefs: UiPrefs): Boolean = when (prefs.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun CalmTheme(
    prefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit
) {
    val colors = if (isCalmDark(prefs)) DuskColors else DayColors
    val reduced = prefs.calmMotion || rememberSystemReducedMotion()
    val scheme = remember(colors) { colors.toColorScheme() }

    CompositionLocalProvider(
        LocalCalmColors provides colors,
        LocalCalmMotion provides CalmMotion(reduced),
        LocalUiPrefs provides prefs
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = CalmTypography,
            shapes = CalmShapes,
            content = content
        )
    }
}