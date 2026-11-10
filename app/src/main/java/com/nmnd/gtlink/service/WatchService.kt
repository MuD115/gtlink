package com.nmnd.gtlink.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nmnd.gtlink.GtApp
import com.nmnd.gtlink.MainActivity
import com.nmnd.gtlink.R
import com.nmnd.gtlink.link.LinkState
import com.nmnd.gtlink.link.WatchEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the watch connected while the app is in the background and carries out what
 * the watch asks of the phone: ring for "find my phone", drive the media player,
 * answer or reject calls, and serve weather.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as GtApp
    private var ringtone: Ringtone? = null
    private var weatherJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        try {
            startForegroundCompat(buildNotification("Connecting"))
        } catch (e: Exception) {
            // Android 14+ refuses a connectedDevice service without the Bluetooth grant.
            app.log.add("! can't run in background: ${e.message}")
            stopSelf()
            return
        }

        scope.launch {
            app.connection.state.map { it.link to it.battery }.distinctUntilChanged().collect { (link, battery) ->
                val text = when (link) {
                    LinkState.CONNECTED -> "Connected" + (battery?.let { " · battery $it%" } ?: "")
                    LinkState.CONNECTING, LinkState.AUTHENTICATING -> "Connecting"
                    LinkState.WAITING_TO_RETRY -> "Watch out of reach, retrying"
                    LinkState.IDLE -> "Disconnected"
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                if (link == LinkState.CONNECTED) onConnected()
            }
        }
        scope.launch { app.connection.events.collect { handle(it) } }
        app.connection.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                app.connection.disconnect()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_RINGING -> stopRinging(tellWatch = true)
            else -> app.connection.connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRinging(tellWatch = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun onConnected() {
        weatherJob?.cancel()
        weatherJob = scope.launch {
            // Weather refresh every 30 minutes while connected.
            while (isActive) {
                if (app.store.settings.value.weatherEnabled) WeatherSync.run(this@WatchService)
                delay(30 * 60 * 1000L)
            }
        }
        MediaBridge.pushCurrent(this)
        historyJob?.cancel()
        historyJob = scope.launch {
            // Pull the watch's minute-by-minute history now and every two hours.
            delay(20_000)
            while (isActive) {
                if (app.store.settings.value.autoSyncHistory) app.connection.syncHistory()
                delay(2 * 60 * 60 * 1000L)
            }
        }
    }

    private var historyJob: Job? = null

    /**
     * The watch's camera app wants the phone camera. Activities can't be started from the
     * background on Android 10+, so fall back to a notification the user taps.
     */
    private fun openCamera() {
        val intent = Intent().setClassName(this, CameraBus.ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        val inForeground = info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        if (inForeground && runCatching { startActivity(intent) }.isSuccess) return
        val pending = PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_FIND)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle("Camera remote")
            .setContentText("Tap to open the camera; the watch is the shutter")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(CAMERA_NOTIFICATION_ID, n)
    }

    private suspend fun handle(event: WatchEvent) {
        val settings = app.store.settings.value
        when (event) {
            is WatchEvent.FindPhone -> if (settings.findPhoneEnabled) {
                if (event.start) startRinging() else stopRinging(tellWatch = false)
            }
            WatchEvent.MusicOpened -> MediaBridge.pushCurrent(this)
            is WatchEvent.MusicButton -> if (settings.musicControl) {
                MediaBridge.press(this, event.button)
                delay(300)
                MediaBridge.pushCurrent(this)
            }
            is WatchEvent.MusicVolume -> if (settings.musicControl) {
                val audio = getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, event.volume.coerceIn(0, max), 0)
            }
            is WatchEvent.CallAction -> handleCall(event.accept)
            WatchEvent.WeatherRequested -> {
                if (settings.weatherEnabled) WeatherSync.run(this) else app.connection.reportWeatherUnavailable()
            }
            is WatchEvent.NotificationReply -> {
                val sent = Replies.reply(this, event.key, event.text)
                app.log.add(if (sent) "reply delivered" else "! reply had nowhere to go (notification gone)")
            }
            is WatchEvent.Camera -> if (settings.cameraRemote) when (event.action) {
                1 -> openCamera()
                2 -> CameraBus.shutter.tryEmit(Unit)
                3 -> CameraBus.close.tryEmit(Unit)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCall(accept: Boolean) {
        val telecom = getSystemService(TelecomManager::class.java) ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            app.log.add("! call ${if (accept) "accept" else "reject"} ignored: ANSWER_PHONE_CALLS not granted")
            return
        }
        try {
            if (accept) {
                telecom.acceptRingingCall()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                telecom.endCall()
            }
        } catch (e: Exception) {
            app.log.add("! call control failed: ${e.message}")
        }
    }

    private fun startRinging() {
        stopRinging(tellWatch = false)
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP_RINGING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_FIND)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle("Your watch is looking for this phone")
            .setContentText("Tap to stop ringing")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .setOngoing(true)
            .addAction(0, "Found it", stop)
            .build()
        getSystemService(NotificationManager::class.java).notify(FIND_NOTIFICATION_ID, n)
        // Give up after a minute like the official app does.
        scope.launch {
            delay(60_000)
            stopRinging(tellWatch = true)
        }
    }

    private fun stopRinging(tellWatch: Boolean) {
        val was = ringtone != null
        ringtone?.stop()
        ringtone = null
        getSystemService(NotificationManager::class.java).cancel(FIND_NOTIFICATION_ID)
        if (was && tellWatch) scope.launch { app.connection.stopFindPhone() }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_watch)
            .setContentTitle(app.store.watch.value?.name ?: "GT Link")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_FIND = "find_phone"
        private const val NOTIFICATION_ID = 1
        private const val FIND_NOTIFICATION_ID = 2
        private const val CAMERA_NOTIFICATION_ID = 3
        const val ACTION_STOP = "com.nmnd.gtlink.STOP"
        const val ACTION_STOP_RINGING = "com.nmnd.gtlink.STOP_RINGING"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "Watch connection", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while GT Link keeps your watch connected"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_FIND, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rings when your watch looks for the phone"
                },
            )
        }
    }
}

/** Reads and controls whatever media session is playing, through the notification-listener grant. */
object MediaBridge {
    private fun activeController(context: Context): MediaController? = try {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val sessions = msm.getActiveSessions(ComponentName(context, NotificationRelay::class.java))
        sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    } catch (e: SecurityException) {
        null
    }

    fun pushCurrent(context: Context) {
        val app = context.applicationContext as GtApp
        if (!app.connection.state.value.connected) return
        val controller = activeController(context)
        val meta = controller?.metadata
        val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val state = controller?.playbackState?.state ?: PlaybackState.STATE_NONE
        val audio = context.getSystemService(AudioManager::class.java)
        app.scope.launch {
            app.connection.sendMusicInfo(
                artist, title, state,
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            )
        }
    }

    fun press(context: Context, button: Int) {
        val controller = activeController(context)
        val audio = context.getSystemService(AudioManager::class.java)
        when (button) {
            1 -> controller?.transportControls?.play() ?: mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PLAY)
            2 -> controller?.transportControls?.pause() ?: mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PAUSE)
            3 -> controller?.transportControls?.skipToPrevious() ?: mediaKey(audio, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            4 -> controller?.transportControls?.skipToNext() ?: mediaKey(audio, KeyEvent.KEYCODE_MEDIA_NEXT)
            5 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            6 -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        }
    }

    private fun mediaKey(audio: AudioManager, code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}

/** Fetches weather for the configured place and pushes it to the watch. */
object WeatherSync {
    @SuppressLint("MissingPermission")
    suspend fun run(context: Context): Result<Unit> {
        val app = context.applicationContext as GtApp
        val s = app.store.settings.value
        return runCatching {
            var lat = s.weatherLat
            var lon = s.weatherLon
            var name = s.weatherCityName ?: "Here"
            if (s.weatherUseDeviceLocation &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                val lm = context.getSystemService(LocationManager::class.java)
                val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
                if (loc != null) {
                    lat = loc.latitude
                    lon = loc.longitude
                    name = placeName(context, loc.latitude, loc.longitude) ?: "Current location"
                }
            }
            if (lat == null || lon == null) error("No location for weather: pick a city in Weather settings")
            val report = app.weather.fetch(lat, lon, name)
            app.connection.sendWeather(report, s.fahrenheit).getOrThrow()
        }.onFailure { app.log.add("! weather: ${it.message}") }
    }

    /** The town the watch shows above the temperature. Blocking geocoder call, so off the main thread. */
    private suspend fun placeName(context: Context, lat: Double, lon: Double): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                if (!android.location.Geocoder.isPresent()) return@runCatching null
                @Suppress("DEPRECATION")
                android.location.Geocoder(context).getFromLocation(lat, lon, 1)?.firstOrNull()
                    ?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }.getOrNull()
        }
}
