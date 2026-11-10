package com.nmnd.gtlink.service

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Connects the watch's camera-remote messages to whichever camera screen is open.
 * The screen sets [visible] while resumed, takes a picture on [shutter], and finishes
 * on [close]; it tells the watch it's ready through WatchConnection.setCameraOpen.
 */
object CameraBus {
    const val ACTIVITY = "com.nmnd.gtlink.ui.camera.CameraActivity"

    val shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val close = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile var visible: Boolean = false
}
