package com.client.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.client.app.ui.theme.Calm

/**
 * Правая часть верхней панели: журнал и настройки.
 *
 * Без вращающейся шестерёнки и красного «светодиода»: если в журнале есть записи об ошибках,
 * на кнопке появляется маленькая точка тёплого песочного оттенка — заметно, но не тревожно.
 */
@Composable
fun TopBarActions(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    modifier: Modifier = Modifier,
    errorCount: Int = 0
) {
    val c = Calm.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CalmIconButton(
            icon = Icons.AutoMirrored.Rounded.Notes,
            contentDescription = if (errorCount > 0) "Журнал событий: есть записи о проблемах" else "Журнал событий",
            onClick = onOpenLogs,
            container = c.raised,
            tint = c.inkSoft,
            badgeColor = if (errorCount > 0) c.attention else null
        )
        CalmIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = "Настройки",
            onClick = onOpenSettings,
            container = c.raised,
            tint = c.inkSoft
        )
    }
}