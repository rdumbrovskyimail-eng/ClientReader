@file:Suppress("SuspiciousIndentation")

package com.client.app

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.client.app.logging.AppLogManager
import com.client.app.session.SessionManager
import com.client.app.ui.display.DisplayRateManager
import com.client.app.ui.screens.ClientScreen
import com.client.app.ui.screens.LogViewerScreen
import com.client.app.ui.screens.SettingsScreen
import com.client.app.ui.theme.Calm
import com.client.app.ui.theme.CalmDurations
import com.client.app.ui.theme.CalmEasing
import com.client.app.ui.theme.CalmTheme
import com.client.app.ui.theme.UiPrefs
import com.client.app.ui.theme.isCalmDark
import com.client.app.ui.theme.toUiPrefs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logManager: AppLogManager

    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var dataStore: DataStore<Preferences>

    private fun loadInitialPrefs(): UiPrefs = runCatching {
        runBlocking { withTimeoutOrNull(150L) { dataStore.data.first() } }
    }.getOrNull()?.toUiPrefs() ?: UiPrefs()

    @SuppressLint("SuspiciousIndentation")
    @Suppress("SuspiciousIndentation")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Прозрачные системные панели; цвет значков подстраивается под тему ниже
        enableEdgeToEdge()

        // Плавность анимаций на дисплеях 120 Гц (это пожелание системе, а не жёсткая фиксация)
        DisplayRateManager.setHighRefreshRate(window, true)

        // Тема известна до первого кадра — без «вспышки» не той темы (чтение ограничено 150 мс)
        val initialPrefs = loadInitialPrefs()

        setContent {
            // Повреждённое хранилище не должно ронять интерфейс — тогда просто оформление по умолчанию
            val uiPrefsFlow = remember { dataStore.data.map { it.toUiPrefs() }.catch { emit(UiPrefs()) } }
            val prefs by uiPrefsFlow.collectAsStateWithLifecycle(initialValue = initialPrefs)
            val dark = isCalmDark(prefs)

            // Светлые значки строки состояния на тёмной теме и тёмные — на светлой
            LaunchedEffect(dark) {
                val transparent = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
                )
            }

            CalmTheme(prefs = prefs) {
                val reduced = Calm.motion.reduced
                val canvas = Calm.colors.canvas
                LaunchedEffect(canvas) { window.decorView.setBackgroundColor(canvas.toArgb()) }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(canvas)
                ) {
                    val navController = rememberNavController()

                    // Спокойные переходы: короткое растворение и лёгкий сдвиг на 1/12 ширины.
                    // При «спокойной анимации» — только растворение.
                    val enter: EnterTransition = if (reduced) {
                        fadeIn(tween(CalmDurations.QUICK))
                    } else {
                        fadeIn(tween(CalmDurations.MEDIUM, easing = CalmEasing.Standard)) +
                            slideInHorizontally(tween(CalmDurations.LONG, easing = CalmEasing.Enter)) { it / 12 }
                    }
                    val exit: ExitTransition = if (reduced) {
                        fadeOut(tween(CalmDurations.QUICK))
                    } else {
                        fadeOut(tween(CalmDurations.SHORT, easing = CalmEasing.Standard)) +
                            slideOutHorizontally(tween(CalmDurations.MEDIUM, easing = CalmEasing.Exit)) { -it / 16 }
                    }
                    val popEnter: EnterTransition = if (reduced) {
                        fadeIn(tween(CalmDurations.QUICK))
                    } else {
                        fadeIn(tween(CalmDurations.MEDIUM, easing = CalmEasing.Standard)) +
                            slideInHorizontally(tween(CalmDurations.LONG, easing = CalmEasing.Enter)) { -it / 16 }
                    }
                    val popExit: ExitTransition = if (reduced) {
                        fadeOut(tween(CalmDurations.QUICK))
                    } else {
                        fadeOut(tween(CalmDurations.SHORT, easing = CalmEasing.Standard)) +
                            slideOutHorizontally(tween(CalmDurations.MEDIUM, easing = CalmEasing.Exit)) { it / 12 }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "client",
                        enterTransition = { enter },
                        exitTransition = { exit },
                        popEnterTransition = { popEnter },
                        popExitTransition = { popExit }
                    ) {
                        composable("client") {
                            ClientScreen(
                                onNavigateSettings = { navController.navigate("settings") },
                                onNavigateLogs = { navController.navigate("logs") }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable("logs") {
                            LogViewerScreen(
                                onBack = { navController.popBackStack() },
                                logManager = logManager
                            )
                        }
                    }
                }
            }
        }
    }

    // Видимость интерфейса: в фоне — больший запас буфера вывода (Wi-Fi power-save), без расчёта спектра,
    // с защитным окном от «шумов касания» при сворачивании/блокировке
    override fun onStart() {
        super.onStart()
        sessionManager.onUiVisibilityChanged(true)
    }

    override fun onStop() {
        super.onStop()
        sessionManager.onUiVisibilityChanged(false)
    }

    override fun onResume() {
        super.onResume()
        DisplayRateManager.setHighRefreshRate(window, true)
    }

    override fun onPause() {
        super.onPause()
        // Сброс пожелания частоты — система сама выберет энергоэкономный режим
        DisplayRateManager.setHighRefreshRate(window, false)
    }
}