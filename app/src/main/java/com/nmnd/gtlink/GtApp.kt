package com.nmnd.gtlink

import android.app.Application
import com.nmnd.gtlink.data.AppStore
import com.nmnd.gtlink.link.ProtocolLog
import com.nmnd.gtlink.link.WatchConnection
import com.nmnd.gtlink.weather.WeatherClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons. There is exactly one watch connection per process, shared
 * by the UI, the foreground service and the notification listener.
 */
class GtApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var store: AppStore
        private set
    val log = ProtocolLog()
    lateinit var connection: WatchConnection
        private set
    val weather by lazy { WeatherClient() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = AppStore(this)
        connection = WatchConnection(this, store, log, scope)
    }

    companion object {
        lateinit var instance: GtApp
            private set
    }
}
