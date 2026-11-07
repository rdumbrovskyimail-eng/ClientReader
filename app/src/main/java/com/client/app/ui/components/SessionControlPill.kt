package com.client.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.client.app.session.LinkState
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmDurations

/**
 * Главная кнопка разговора — единственное основное действие экрана.
 *
 * Состояния без тревожных цветов:
 * - «Начать разговор» — заливка акцентом;
 * - «Подключаюсь…» — мягкий акцентный полутон и «дышащая» точка, нажатие отменяет;
 * - «Восстанавливаю связь…» — тёплый песок, нажатие останавливает;
 * - «Завершить разговор» — нейтральная поверхность (не красная: завершение — обычное действие).
 *
 * [quiet] — компактный вариант, когда главным становится чтение (кнопки читалки важнее).
 */
@Composable
fun SessionControlPill(
    linkState: LinkState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    quiet: Boolean = false
) {
    val c = Calm.colors
    val motion = Calm.motion
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val container = when (linkState) {
        LinkState.IDLE -> if (quiet) c.raisedStrong else c.accent
        LinkState.CONNECTING -> c.accentSoft
        LinkState.RECONNECTING -> c.attentionSoft
        LinkState.LIVE -> c.raisedStrong
    }
    val content = when (linkState) {
        LinkState.IDLE -> if (quiet) c.ink else c.onAccent
        LinkState.CONNECTING -> c.onAccentSoft
        LinkState.RECONNECTING -> c.onAttentionSoft
        LinkState.LIVE -> c.ink
    }
    val animatedContainer by animateColorAsState(container, motion.spec(CalmDurations.MEDIUM), label = "pill_bg")
    val animatedContent by animateColorAsState(content, motion.spec(CalmDurations.MEDIUM), label = "pill_fg")
    val scale by animateFloatAsState(
        targetValue = if (pressed && !motion.reduced) 0.98f else 1f,
        animationSpec = motion.spec(CalmDurations.QUICK),
        label = "pill_press"
    )

    val (title, caption) = when (linkState) {
        LinkState.IDLE -> "Начать разговор" to null
        LinkState.CONNECTING -> "Подключаюсь…" to "нажмите, чтобы отменить"
        LinkState.RECONNECTING -> "Восстанавливаю связь…" to "нажмите, чтобы остановить"
        LinkState.LIVE -> (if (quiet) "Завершить сеанс" else "Завершить разговор") to null
    }
    val shape = RoundedCornerShape(50)
    val height = if (quiet) 52.dp else 60.dp

    Row(
        modifier = modifier
            .heightIn(min = height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(animatedContainer)
            .then(if (linkState == LinkState.LIVE) Modifier.border(1.dp, c.hairline, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button) {
                CalmHaptics.confirm(context)
                onClick()
            }
            .semantics {
                contentDescription = title
                stateDescription = when (linkState) {
                    LinkState.IDLE -> "Разговор не начат"
                    LinkState.CONNECTING -> "Идёт подключение"
                    LinkState.RECONNECTING -> "Связь восстанавливается"
                    LinkState.LIVE -> "Разговор идёт"
                }
            }
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        when (linkState) {
            LinkState.IDLE -> Icon(Icons.Rounded.Mic, contentDescription = null, tint = animatedContent, modifier = Modifier.size(22.dp))
            LinkState.LIVE -> Icon(Icons.Rounded.Stop, contentDescription = null, tint = animatedContent, modifier = Modifier.size(22.dp))
            LinkState.CONNECTING -> BreathingDot(color = c.accent, size = 10.dp)
            LinkState.RECONNECTING -> BreathingDot(color = c.attention, size = 10.dp)
        }
        Spacer(Modifier.width(12.dp))
        AnimatedContent(
            targetState = title to caption,
            transitionSpec = { fadeIn(motion.fade()) togetherWith fadeOut(motion.fade()) },
            label = "pill_label"
        ) { (t, cap) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = t, style = MaterialTheme.typography.labelLarge, color = animatedContent)
                if (cap != null) {
                    Text(
                        text = cap,
                        style = MaterialTheme.typography.labelSmall,
                        color = animatedContent.copy(alpha = 0.78f),
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
        }
    }
}