package com.client.app.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.client.app.ui.theme.ThemeMode
import com.client.app.ui.theme.UiPrefKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Настройки оформления: тема, спокойная анимация, размер текста читалки.
 * Хранятся в общем DataStore приложения; MainActivity читает их и применяет тему сразу,
 * экраны берут текущие значения из темы (Calm.prefs).
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { dataStore.edit { it[UiPrefKeys.THEME] = mode.wire } }
    }

    fun setCalmMotion(enabled: Boolean) {
        viewModelScope.launch { dataStore.edit { it[UiPrefKeys.CALM_MOTION] = enabled } }
    }

    fun setReaderTextScale(scale: Float) {
        val clamped = scale.coerceIn(UiPrefKeys.TEXT_SCALE_MIN, UiPrefKeys.TEXT_SCALE_MAX)
        viewModelScope.launch { dataStore.edit { it[UiPrefKeys.READER_TEXT_SCALE] = clamped } }
    }
}