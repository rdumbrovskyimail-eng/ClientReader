package com.client.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmDurations
import com.client.app.ui.theme.CalmEasing

/*
 * Общие спокойные элементы интерфейса.
 * Все интерактивные элементы — не меньше 48 dp, у каждого есть описание для TalkBack.
 */

// -------------------------------------------------------------------------------------
// Поверхности
// -------------------------------------------------------------------------------------

/** Карточка: приподнятая поверхность с волосяной рамкой, без теней. */
@Composable
fun CalmCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Calm.colors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.hairline, shape)
            .padding(contentPadding),
        content = content
    )
}

/** Верхняя панель экранов настроек и журнала. */
@Composable
fun CalmTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val c = Calm.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CalmIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Назад",
            onClick = onBack,
            container = Color.Transparent
        )
        Spacer(Modifier.width(4.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { heading() }
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, color = c.ink)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

// -------------------------------------------------------------------------------------
// Кнопки
// -------------------------------------------------------------------------------------

/** Круглая кнопка-значок 48 dp. */
@Composable
fun CalmIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Calm.colors.raisedStrong,
    tint: Color = Calm.colors.ink,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
    enabled: Boolean = true,
    badgeColor: Color? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f),
            modifier = Modifier.size(iconSize)
        )
        if (badgeColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = size * 0.2f, end = size * 0.2f)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
        }
    }
}

/** Главная кнопка-капсула (заливка акцентом). */
@Composable
fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 52.dp
) {
    val c = Calm.colors
    PillButtonBase(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        container = if (enabled) c.accent else c.raisedStrong,
        content = if (enabled) c.onAccent else c.inkFaint,
        border = null,
        enabled = enabled,
        height = height
    )
}

/** Второстепенная кнопка-капсула (тональная). */
@Composable
fun TonalPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp
) {
    val c = Calm.colors
    PillButtonBase(
        text = text,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        container = c.raisedStrong,
        content = if (enabled) c.ink else c.inkFaint,
        border = null,
        enabled = enabled,
        height = height
    )
}

/** Тихая текстовая кнопка (без фона), минимум 48 dp по высоте. */
@Composable
fun QuietTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Calm.colors.accentInk,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun PillButtonBase(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier,
    container: Color,
    content: Color,
    border: Color?,
    enabled: Boolean,
    height: Dp
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .heightIn(min = height)
            .widthIn(min = 64.dp)
            .clip(shape)
            .background(container)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// -------------------------------------------------------------------------------------
// Выбор
// -------------------------------------------------------------------------------------

/**
 * Сегментный переключатель: один выбор из 2–4 вариантов.
 * Высота 48 dp; выбранный вариант — мягкая «таблетка» акцентного полутона.
 */
@Composable
fun CalmSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = Calm.colors
    val motion = Calm.motion
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.raisedStrong)
            .padding(4.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) c.accentSoft else Color.Transparent,
                animationSpec = motion.spec(CalmDurations.SHORT),
                label = "segment_bg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(index) }),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) c.onAccentSoft else c.inkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** Строка с переключателем: нажимается целиком. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true
) {
    val c = Calm.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = calmSwitchColors()
        )
    }
}

/** Строка-вариант с радиокнопкой. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val c = Calm.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, colors = calmRadioColors())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
            }
        }
    }
}

/** Ползунок с подписью и значением. */
@Composable
fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    description: String? = null
) {
    val c = Calm.colors
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = c.ink, modifier = Modifier.weight(1f))
            Text(text = valueText, style = MaterialTheme.typography.labelLarge, color = c.accentInk)
        }
        if (description != null) {
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = c.inkSoft)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = calmSliderColors(),
            modifier = Modifier.semantics { stateDescription = valueText }
        )
    }
}

// -------------------------------------------------------------------------------------
// Разделы
// -------------------------------------------------------------------------------------

/** Заголовок раздела: значок в мягком круге и название. */
@Composable
fun SectionHeader(title: String, icon: ImageVector, modifier: Modifier = Modifier, subtitle: String? = null) {
    val c = Calm.colors
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(c.accentSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = c.onAccentSoft, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = c.inkFaint)
            }
        }
    }
}

/** Карточка-раздел настроек. */
@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    CalmCard(modifier = modifier) {
        SectionHeader(title = title, icon = icon, subtitle = subtitle)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Раскрывающийся раздел: сложное скрыто, пока не понадобится. */
@Composable
fun ExpandableSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Calm.colors
    val motion = Calm.motion
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = motion.spec(CalmDurations.MEDIUM),
        label = "chevron"
    )
    CalmCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) "Свернуть" else "Развернуть",
                    onClick = { expanded = !expanded }
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(title = title, icon = icon, subtitle = subtitle, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = c.inkSoft,
                modifier = Modifier.rotate(rotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (motion.reduced) fadeIn(motion.fade()) else expandVertically(motion.spec(CalmDurations.MEDIUM, CalmEasing.Enter)) + fadeIn(motion.fade()),
            exit = if (motion.reduced) fadeOut(motion.fade()) else shrinkVertically(motion.spec(CalmDurations.SHORT, CalmEasing.Exit)) + fadeOut(motion.fade())
        ) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp), content = content)
        }
    }
}

/** Подпись-пояснение внутри раздела. */
@Composable
fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Calm.colors.inkFaint,
        modifier = modifier.padding(vertical = 4.dp)
    )
}

/** Тонкий разделитель внутри карточки. */
@Composable
fun CalmDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(Calm.colors.hairline)
    )
}

// -------------------------------------------------------------------------------------
// Индикаторы
// -------------------------------------------------------------------------------------

/**
 * «Дышащая» точка ожидания: плавно светлеет и темнеет раз в 3,4 с.
 * При уменьшенном движении — неподвижна.
 */
@Composable
fun BreathingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, active: Boolean = true) {
    val motion = Calm.motion
    val alpha = if (active && !motion.reduced) {
        val transition = rememberInfiniteTransition(label = "breath")
        val a by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = CalmDurations.BREATH / 2, easing = CalmEasing.Breath),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breath_alpha"
        )
        a
    } else {
        0.9f
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
    )
}

/** Тонкая линия прогресса без «стоп-точек» и разрывов — спокойнее стандартной. */
@Composable
fun CalmProgressLine(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp,
    color: Color = Calm.colors.accent,
    trackColor: Color = Calm.colors.raisedStrong
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, size = size, cornerRadius = r)
        val w = size.width * progress.coerceIn(0f, 1f)
        if (w > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(w.coerceAtLeast(size.height), size.height),
                cornerRadius = r
            )
        }
    }
}

// -------------------------------------------------------------------------------------
// Цвета стандартных компонентов
// -------------------------------------------------------------------------------------

@Composable
fun calmSwitchColors() = Calm.colors.let { c ->
    SwitchDefaults.colors(
        checkedThumbColor = c.onAccent,
        checkedTrackColor = c.accent,
        checkedBorderColor = c.accent,
        uncheckedThumbColor = c.outline,
        uncheckedTrackColor = c.raisedStrong,
        uncheckedBorderColor = c.outline
    )
}

@Composable
fun calmSliderColors() = Calm.colors.let { c ->
    SliderDefaults.colors(
        thumbColor = c.accent,
        activeTrackColor = c.accent,
        activeTickColor = c.onAccent,
        inactiveTrackColor = c.raisedStrong,
        inactiveTickColor = c.outline
    )
}

@Composable
fun calmRadioColors() = Calm.colors.let { c ->
    RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.outline)
}

@Composable
fun calmTextFieldColors(): TextFieldColors = Calm.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.ink,
        unfocusedTextColor = c.ink,
        focusedContainerColor = c.sunken,
        unfocusedContainerColor = c.sunken,
        cursorColor = c.accent,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.outline,
        focusedLabelColor = c.accentInk,
        unfocusedLabelColor = c.inkSoft,
        focusedTrailingIconColor = c.inkSoft,
        unfocusedTrailingIconColor = c.inkSoft,
        focusedSupportingTextColor = c.inkFaint,
        unfocusedSupportingTextColor = c.inkFaint,
        focusedPlaceholderColor = c.inkFaint,
        unfocusedPlaceholderColor = c.inkFaint
    )
}

/** Модификатор полупрозрачности для выключенных элементов. */
fun Modifier.dimmedIf(dim: Boolean): Modifier = if (dim) this.alpha(0.45f) else this