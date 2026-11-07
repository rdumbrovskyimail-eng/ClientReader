package com.client.app.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.client.app.MainActivity
import com.client.app.reader.ReaderCommand
import com.client.app.reader.ReaderStatus
import com.client.app.session.LinkState
import com.client.app.session.SessionManager
import com.client.app.session.SessionState
import com.client.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * УСТРАНЕНИЕ ДЕФЕКТА 180:
 * Мониторинг энергопотребления голосовой сессии (Power Budget Tracker).
 * Целевой бюджет платформы Snapdragon 8 Gen 2: расход < 7% емкости аккумулятора в час.
 */
class SessionPowerBudgetTracker(
    private val context: Context,
    private val logger: AppLogger
) {
    private var sessionStartTimeMs: Long = 0L
    private var startBatteryLevel: Int = -1
    private var activeDuplexDurationMs: Long = 0L
    private var lastActiveStartMs: Long = 0L
    private var wifiLockHeldDurationMs: Long = 0L
    private var lastWifiLockStartMs: Long = 0L

    fun onSessionStarted() {
        sessionStartTimeMs = SystemClock.elapsedRealtime()
        startBatteryLevel = readBatteryLevel()
        activeDuplexDurationMs = 0L
        wifiLockHeldDurationMs = 0L
        logger.d("PowerBudget: Старт мониторинга (уровень заряда: $startBatteryLevel%)")
    }

    fun onDuplexStateChanged(isLive: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isLive) {
            lastActiveStartMs = now
        } else if (lastActiveStartMs > 0L) {
            activeDuplexDurationMs += (now - lastActiveStartMs)
            lastActiveStartMs = 0L
        }
    }

    fun onWifiLockStateChanged(isHeld: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isHeld) {
            lastWifiLockStartMs = now
        } else if (lastWifiLockStartMs > 0L) {
            wifiLockHeldDurationMs += (now - lastWifiLockStartMs)
            lastWifiLockStartMs = 0L
        }
    }

    fun reportBudgetSummary() {
        val now = SystemClock.elapsedRealtime()
        val totalSessionMs = now - sessionStartTimeMs
        if (totalSessionMs <= 0L) return

        val totalMinutes = totalSessionMs / 60000.0
        val endBatteryLevel = readBatteryLevel()
        val batteryDrop = if (startBatteryLevel >= 0 && endBatteryLevel >= 0) {
            (startBatteryLevel - endBatteryLevel).coerceAtLeast(0)
        } else {
            0
        }

        val dischargeRatePerHour = if (totalMinutes >= 5.0) {
            (batteryDrop.toDouble() / totalMinutes) * 60.0
        } else {
            0.0
        }

        val wifiHeldSec = wifiLockHeldDurationMs / 1000L
        val activeSec = activeDuplexDurationMs / 1000L

        logger.d(
            "PowerBudget: Итоги сессии -> " +
            "Длительность=${"%.1f".format(totalMinutes)} мин, " +
            "Расход=$batteryDrop%, " +
            "Темп=${"%.2f".format(dischargeRatePerHour)}%/час (Лимит: 7.0%/час), " +
            "Активный дуплекс=${activeSec}с, " +
            "Low-Latency Wi-Fi=${wifiHeldSec}с"
        )
    }

    private fun readBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }
}

@AndroidEntryPoint
class LiveSessionForegroundService : Service() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var logger: AppLogger

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    // Android 10–13: LOW_LATENCY действует только на экране; при погашенном экране нужен HIGH_PERF,
    // иначе Wi-Fi уходит в power-save, пакеты приходят пачками и звук рвётся/«глохнет»
    private var wifiLockBackground: WifiManager.WifiLock? = null
    private var mediaSession: MediaSessionCompat? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastNotificationKey: String? = null
    private var lastMetadataTitle: String? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaObserverJob: Job? = null
    private var powerTracker: SessionPowerBudgetTracker? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCurrentNetworkWifi: Boolean = false

    companion object {
        const val ACTION_STOP = "com.client.app.action.STOP"
        const val ACTION_PLAY_PAUSE = "com.client.app.action.PLAY_PAUSE"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "live_client_voice_channel"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

        @Volatile
        private var instanceRef: WeakReference<LiveSessionForegroundService>? = null

        fun prepareForStart() {
            _serviceError.value = null
        }

        fun ensureMicrophoneForegroundType(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            val service = instanceRef?.get() ?: return false
            val permissionGranted = ContextCompat.checkSelfPermission(
                service,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!permissionGranted) return false
            // Тип microphone уже активен — повторный startForeground() не нужен
            if ((service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0) return true
            return service.promoteToForeground()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        prepareForStart()
        createNotificationChannel()
        initMediaSession()
        initNetworkMonitoring()
        registerScreenReceiver()

        powerTracker = SessionPowerBudgetTracker(this, logger).apply {
            onSessionStarted()
        }

        promoteToForeground()
        if (_isServiceActive.value) {
            acquireCpuWakeLock()
        }

        mediaObserverJob = serviceScope.launch {
            sessionManager.state.collect { state ->
                val isLive = state.link == LinkState.LIVE
                powerTracker?.onDuplexStateChanged(isLive)

                // УСТРАНЕНИЕ ДЕФЕКТА 179: Динамическое управление блокировкой Wi-Fi
                updateWifiLockState(isLive)

                updateMediaSession(state)
                updateNotificationIfNeeded(state)
            }
        }
    }

    /**
     * Кнопки гарнитуры, экрана блокировки и часов: Play/Pause — пауза/продолжение чтения (или ответа),
     * «вперёд» — следующий абзац, «назад» — повтор предложения. Состояние PLAYING/PAUSED нужно системе,
     * чтобы медиакнопки приходили именно в эту сессию.
     */
    private fun updateMediaSession(state: SessionState) {
        val session = mediaSession ?: return
        val reader = state.reader
        val mediaState = when {
            reader.isActive && reader.status == ReaderStatus.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            reader.isActive -> PlaybackStateCompat.STATE_PLAYING
            state.link != LinkState.IDLE -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        var actions = PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        if (reader.isActive) {
            actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(mediaState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (mediaState == PlaybackStateCompat.STATE_PLAYING) 1.0f else 0.0f)
                .build()
        )
        val title = if (reader.isActive) reader.title.ifBlank { "Чтение" } else "Gemini Live"
        if (title != lastMetadataTitle) {
            lastMetadataTitle = title
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Gemini Live")
                    .build()
            )
        }
    }

    /** Уведомление перестраивается только при смене статуса/заголовка/каждых 5 % прогресса. */
    private fun updateNotificationIfNeeded(state: SessionState) {
        val reader = state.reader
        val key = "${state.link}|${reader.status}|${reader.title}|${(reader.progress * 20).toInt()}"
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        if (!_isServiceActive.value) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    /** Экран включён/выключен: SessionManager меняет запас буфера и включает защитное окно от касаний. */
    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> sessionManager.onScreenInteractiveChanged(true)
                    Intent.ACTION_SCREEN_OFF -> sessionManager.onScreenInteractiveChanged(false)
                }
                updateWifiLockState(sessionManager.state.value.link == LinkState.LIVE)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = receiver
        }.onFailure { logger.w("LiveSessionForegroundService: приёмник экрана не зарегистрирован: ${it.message}") }
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        sessionManager.onScreenInteractiveChanged(pm?.isInteractive ?: true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sessionManager.stopSession()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PLAY_PAUSE) {
            sessionManager.mediaPlayPause()
            return START_NOT_STICKY
        }

        promoteToForeground()
        if (_isServiceActive.value) {
            acquireCpuWakeLock()
        }
        return START_NOT_STICKY
    }

    private fun initNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        checkCurrentNetworkTransport()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (isCurrentNetworkWifi != isWifi) {
                    isCurrentNetworkWifi = isWifi
                    val isLive = sessionManager.state.value.link == LinkState.LIVE
                    updateWifiLockState(isLive)
                }
            }

            override fun onLost(network: Network) {
                isCurrentNetworkWifi = false
                updateWifiLockState(false)
            }
        }
        networkCallback = callback
        // Главный поток: wifiLock меняется там же, где его трогает mediaObserverJob.
        // Колбэк обязательно снимается в onDestroy().
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        }.onFailure {
            networkCallback = null
            logger.w("LiveSessionForegroundService: NetworkCallback не зарегистрирован: ${it.message}")
        }
    }

    private fun checkCurrentNetworkTransport() {
        val cm = connectivityManager ?: return
        val activeNetwork = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return
        isCurrentNetworkWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * УСТРАНЕНИЕ ДЕФЕКТА 179:
     * Блокировка Low-Latency Wi-Fi удерживается строго при наличии Wi-Fi соединения и активного дуплекса.
     * При переходе на сотовую сеть или паузе в диалоге радиомодуль переходит в энергосбережение.
     */
    private fun updateWifiLockState(isLive: Boolean) {
        val shouldHoldWifi = isLive && isCurrentNetworkWifi

        if (shouldHoldWifi) {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "client:live_session_wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                powerTracker?.onWifiLockStateChanged(true)
            }
            // LOW_LATENCY работает только при включённом экране и приложении на переднем плане.
            // Android 10–13: для погашенного экрана дополнительно HIGH_PERF (на 14+ он не действует —
            // там непрерывность обеспечивает увеличенный буфер вывода, см. SessionManager)
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q until Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                wifiLockBackground?.isHeld != true
            ) {
                runCatching {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    @Suppress("DEPRECATION")
                    wifiLockBackground = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "client:live_session_wifi_bg").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }
        } else {
            if (wifiLock?.isHeld == true) {
                runCatching { wifiLock?.release() }
                wifiLock = null
                powerTracker?.onWifiLockStateChanged(false)
            }
            if (wifiLockBackground?.isHeld == true) runCatching { wifiLockBackground?.release() }
            wifiLockBackground = null
        }
    }

    private fun acquireCpuWakeLock() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "client:live_session_cpu"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun initMediaSession() {
        runCatching {
            mediaSession = MediaSessionCompat(this, "GeminiLiveMediaSession").apply {
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() = sessionManager.mediaPlay()
                    override fun onPause() = sessionManager.mediaPause()
                    override fun onSkipToNext() {
                        sessionManager.readerCommand(ReaderCommand.NextParagraph)
                    }
                    override fun onSkipToPrevious() {
                        sessionManager.readerCommand(ReaderCommand.RepeatSentence)
                    }
                    override fun onStop() {
                        sessionManager.stopSession()
                    }
                })
                val state = PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PLAY_PAUSE)
                    .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }
        }
    }

    private fun promoteToForeground(): Boolean {
        val notification = buildNotification(if (::sessionManager.isInitialized) sessionManager.state.value else null)
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (hasMic) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    type
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }
            _serviceError.value = null
            _isServiceActive.value = true
            true
        }.getOrElse { throwable ->
            _serviceError.value =
                throwable.localizedMessage ?: throwable.javaClass.simpleName
            _isServiceActive.value = false
            logger.e(
                "LiveSessionForegroundService: startForeground failed",
                throwable
            )
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            false
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gemini Live Ultra Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Активный фоновый дуплекс-диалог"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: SessionState?): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, LiveSessionForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPausePendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LiveSessionForegroundService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val reader = state?.reader
        val reading = reader?.isActive == true
        val title = if (reading) "Чтение: ${reader!!.title.ifBlank { "текст" }}" else "Gemini Live активен"
        val text = when {
            reading && reader!!.status == ReaderStatus.PAUSED -> "Пауза · ${(reader.progress * 100).toInt()}% · «Продолжить» — кнопкой или голосом"
            reading -> "${(reader!!.progress * 100).toInt()}% · фрагмент ${reader.segment + 1} из ${reader.totalSegments}"
            else -> "Фоновая Gemini Live-сессия активна"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val paused = reading && reader!!.status == ReaderStatus.PAUSED
        builder.addAction(
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
            if (paused) "Продолжить" else "Пауза",
            playPausePendingIntent
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Завершить",
            stopPendingIntent
        )

        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
        }

        return builder.build()
    }

    override fun onDestroy() {
        _isServiceActive.value = false
        mediaObserverJob?.cancel()
        serviceScope.cancel()

        networkCallback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        networkCallback = null

        powerTracker?.reportBudgetSummary()
        powerTracker = null

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
        runCatching {
            if (wifiLockBackground?.isHeld == true) wifiLockBackground?.release()
        }
        wifiLockBackground = null

        screenReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        screenReceiver = null

        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null

        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
