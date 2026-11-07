package com.client.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.client.app.audio.NativeAudioEngine
import com.client.app.session.LinkState
import com.client.app.session.SessionState
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmColors
import com.client.app.ui.theme.CalmDurations
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

private const val COLS = 25
private const val ROWS_FULL = 7
private const val ROWS_COMPACT = 3

/** Состояние отрисовки без аллокаций в кадре; баллистика не зависит от частоты экрана. */
private class HalftoneState {
    val bands = FloatArray(5)
    val envelope = FloatArray(COLS)
    var out = 0f
    var mic = 0f
    var ai = 0f
    var user = 0f
    var wait = 0f
    var lastFrameNanos = 0L
    var timeSec = 0f
}

/**
 * «Полутоновое поле» голоса.
 *
 * Сетка точек, как в полиграфическом растре: в тишине — ровная спокойная фактура, когда звучит речь —
 * точки в центре мягко укрупняются. Цвет подсказывает, кто говорит: шалфей — вы, сумеречно-голубой — Gemini.
 * Ожидание связи — медленное «дыхание» всей сетки (0,3 Гц), без мигания.
 *
 * Чистая визуализация: для TalkBack скрыта (состояние озвучивает заголовок экрана).
 * При «спокойной анимации» нет бегущей волны, а реакция в 3–4 раза плавнее.
 */
@Composable
fun VoiceHalftone(
    nativeEngine: NativeAudioEngine,
    state: SessionState,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val colors = Calm.colors
    val reduced = Calm.motion.reduced
    val render = remember { HalftoneState() }
    val frameNanos = remember { mutableLongStateOf(0L) }

    val sessionActive = state.link != LinkState.IDLE
    val isLive = state.link == LinkState.LIVE
    val waiting = state.link == LinkState.CONNECTING || state.link == LinkState.RECONNECTING
    val reconnecting = state.link == LinkState.RECONNECTING
    val aiSpeaking = state.isAiSpeaking
    val listening = state.isMicActive && isLive

    // Кадры идут только во время сессии (и ещё 1,5 с после — чтобы точки спокойно «осели»)
    LaunchedEffect(sessionActive, reduced) {
        val stopAt = if (sessionActive) Long.MAX_VALUE else System.nanoTime() + 1_500_000_000L
        val minStep = if (reduced) 33_000_000L else 15_000_000L // ≤ 30 / ≤ 60 кадров в секунду
        var last = 0L
        while (isActive) {
            val t = withFrameNanos { it }
            if (t - last >= minStep) {
                frameNanos.longValue = t
                last = t
            }
            if (t >= stopAt) break
        }
    }

    val shape = RoundedCornerShape(if (compact) 22.dp else 28.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (compact) 64.dp else 116.dp)
            .clip(shape)
            .background(colors.raised)
            .border(1.dp, colors.hairline, shape)
            .clearAndSetSemantics { }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = if (compact) 10.dp else 16.dp)
        ) {
            val now = frameNanos.longValue
            val dt = if (render.lastFrameNanos == 0L || now <= render.lastFrameNanos) {
                1f / 60f
            } else {
                ((now - render.lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            }
            render.lastFrameNanos = now
            render.timeSec += dt

            update(
                render = render,
                dt = dt,
                spectrum = nativeEngine.spectrumUniforms.get(),
                out = nativeEngine.outLevel.value,
                mic = nativeEngine.micLevel.value,
                aiSpeaking = aiSpeaking,
                listening = listening,
                waiting = waiting,
                reduced = reduced
            )

            val rows = if (compact) ROWS_COMPACT else ROWS_FULL
            val cellW = size.width / COLS
            val cellH = size.height / rows
            val cell = min(cellW, cellH)
            val rMax = cell * 0.40f
            val rMin = cell * 0.10f
            val center = (rows - 1) / 2f

            val neutral = colors.ink
            val voice = voiceColor(colors, render)
            val waitTint = if (reconnecting) colors.attention else colors.accent
            val breath = if (reduced) 0.5f else 0.5f + 0.5f * sin(2f * PI.toFloat() * render.timeSec / (CalmDurations.BREATH / 1000f))
            val idleAlpha = if (colors.isDark) 0.20f else 0.16f
            val presence = maxOf(render.ai, render.user)

            for (col in 0 until COLS) {
                val x = cellW * (col + 0.5f)
                val env = render.envelope[col]
                for (row in 0 until rows) {
                    val y = cellH * (row + 0.5f)
                    val rowDist = if (center == 0f) 0f else abs(row - center) / center
                    // В компактной полосе всего три ряда: мягче порог, чтобы волна читалась и на одной линии
                    val raw = if (compact) {
                        ((env - rowDist * 0.55f) / 0.45f).coerceIn(0f, 1f)
                    } else {
                        ((env * 1.15f - rowDist * 0.95f) / 0.30f).coerceIn(0f, 1f)
                    }
                    val fill = raw * raw * (3f - 2f * raw)

                    // Фактура покоя / ожидания
                    var color = neutral.copy(alpha = idleAlpha)
                    if (render.wait > 0.01f) {
                        val a = idleAlpha + render.wait * (0.10f + 0.14f * breath)
                        color = lerp(neutral, waitTint, 0.55f * render.wait).copy(alpha = a)
                    }
                    // Голос
                    if (fill > 0.001f && presence > 0.01f) {
                        val a = (idleAlpha + (0.92f - idleAlpha) * fill).coerceIn(0f, 1f)
                        color = lerp(color.copy(alpha = 1f), voice, fill).copy(alpha = a)
                    }
                    drawCircle(
                        color = color,
                        radius = rMin + (rMax - rMin) * fill,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}

private fun voiceColor(c: CalmColors, s: HalftoneState): Color {
    val sum = s.ai + s.user
    if (sum <= 0.0001f) return c.voiceAi
    return lerp(c.voiceUser, c.voiceAi, (s.ai / sum).coerceIn(0f, 1f))
}

/** Экспоненциальное сглаживание с постоянными времени: одинаково на 60 и 120 Гц. */
private fun follow(current: Float, target: Float, dt: Float, attackSec: Float, releaseSec: Float): Float {
    val tau = if (target > current) attackSec else releaseSec
    val k = 1f - exp(-dt / tau)
    return (current + (target - current) * k).coerceIn(0f, 1f)
}

private fun update(
    render: HalftoneState,
    dt: Float,
    spectrum: FloatArray,
    out: Float,
    mic: Float,
    aiSpeaking: Boolean,
    listening: Boolean,
    waiting: Boolean,
    reduced: Boolean
) {
    val slow = if (reduced) 3.5f else 1f
    for (b in 0 until 5) {
        render.bands[b] = follow(render.bands[b], spectrum.getOrElse(b) { 0f }, dt, 0.05f * slow, 0.25f * slow)
    }
    render.out = follow(render.out, out, dt, 0.06f * slow, 0.28f * slow)
    render.mic = follow(render.mic, mic, dt, 0.06f * slow, 0.28f * slow)
    render.ai = follow(render.ai, if (aiSpeaking) 1f else 0f, dt, 0.12f * slow, 0.6f * slow)
    render.user = follow(render.user, if (listening) (render.mic * 4f).coerceAtMost(1f) else 0f, dt, 0.10f * slow, 0.5f * slow)
    render.wait = follow(render.wait, if (waiting) 1f else 0f, dt, 0.4f, 0.6f)

    val level = maxOf(
        if (aiSpeaking) (render.out * 1.8f).coerceAtMost(1f) else 0f,
        if (listening) (render.mic * 2.2f).coerceAtMost(1f) else 0f
    )
    val presence = maxOf(render.ai, render.user)
    val t = render.timeSec
    for (col in 0 until COLS) {
        val x = col / (COLS - 1f)
        val dist = abs(x - 0.5f) * 2f                    // 0 — центр, 1 — края
        val bandPos = (dist * 4f).coerceIn(0f, 4f)
        val i = floor(bandPos).toInt().coerceIn(0, 4)
        val j = (i + 1).coerceAtMost(4)
        val f = bandPos - i
        val energy = render.bands[i] * (1f - f) + render.bands[j] * f
        val wave = if (reduced) 0.5f else 0.5f + 0.5f * sin(col * 0.42f - t * 1.1f)
        val target = (presence * (0.16f + 0.55f * level * (0.65f + 0.35f * wave) + 0.45f * energy - 0.22f * dist))
            .coerceIn(0f, 1f)
        render.envelope[col] = follow(render.envelope[col], target, dt, 0.09f * slow, 0.45f * slow)
    }
}